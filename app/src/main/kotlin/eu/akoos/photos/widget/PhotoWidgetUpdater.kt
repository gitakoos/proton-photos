/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.util.SQL_CHUNK_SIZE
import java.io.File
import java.io.FileOutputStream

/**
 * Central helper that advances the widget to its next photo, loads + caches the
 * bitmap, persists updated state, and triggers a Glance redraw.
 */
object PhotoWidgetUpdater {

    /** Max long edge for cached widget bitmaps (RGB_565 → ~450 KB at 480 px, safe under 1 MB Binder limit). */
    private const val MAX_BITMAP_PX = 480

    /** How many device photos from the one due next an update looks through for one whose Drive copy
     *  is not hidden, all of them answered by a single read of their sync rows. */
    private const val HIDDEN_SKIP_WINDOW = 32

    /** Start of every widget's cached bitmap name in the cache dir, one file per widget id. */
    internal const val CACHE_FILE_PREFIX = "widget_"

    /**
     * Hilt EntryPoint for the singletons the updater reads: the account, the cloud thumbnail cache,
     * and the records that say what is hidden. Lets this top-level object pull them out of the
     * SingletonComponent without converting the Worker into a HiltWorker.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CloudDeps {
        fun thumbnailScheduler(): ThumbnailDecryptScheduler
        fun photoListingDao(): PhotoListingDao
        fun accountManager(): AccountManager
        fun albumPhotoMembershipDao(): AlbumPhotoMembershipDao
        fun syncStateDao(): SyncStateDao
    }

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun update(context: Context, appWidgetId: Int) = withContext(Dispatchers.IO) {
        try {
            val manager = GlanceAppWidgetManager(context)
            val glanceId = manager.getGlanceIdBy(appWidgetId)

            // 1. Read current Glance state
            val prefs = androidx.glance.appwidget.state.getAppWidgetState(
                context, PreferencesGlanceStateDefinition, glanceId,
            )

            val mode = WidgetMode.valueOf(
                prefs[PhotoWidgetKeys.MODE] ?: WidgetMode.ALL_PHOTOS.name,
            )
            val selectedUrisRaw = prefs[PhotoWidgetKeys.SELECTED_URIS] ?: ""
            val selectedUris    = if (selectedUrisRaw.isBlank()) emptyList()
                                  else selectedUrisRaw.split(PhotoWidgetKeys.URI_SEPARATOR)
            val selectedLinkIdsRaw = prefs[PhotoWidgetKeys.SELECTED_LINK_IDS] ?: ""
            val selectedLinkIds = if (selectedLinkIdsRaw.isBlank()) emptyList()
                                  else selectedLinkIdsRaw.split(PhotoWidgetKeys.URI_SEPARATOR)
            val albumName       = prefs[PhotoWidgetKeys.ALBUM_NAME]?.takeIf { it.isNotBlank() }
            val cloudAlbumLinkId = prefs[PhotoWidgetKeys.CLOUD_ALBUM_LINK_ID]?.takeIf { it.isNotBlank() }
            val currentIndex    = prefs[PhotoWidgetKeys.CURRENT_INDEX] ?: 0

            val deps = EntryPointAccessors.fromApplication(context.applicationContext, CloudDeps::class.java)
            val userId = deps.accountManager().getPrimaryUserId().first()
            // A cloud mode draws from the signed-in account's decrypted cache. With no account there
            // is nothing it may draw, whatever a cache left behind still holds.
            if (mode.isCloud && userId == null) {
                clearBitmapState(context, glanceId, appWidgetId)
                return@withContext
            }
            val account = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            // Deliberately not caught on its own: an update that cannot tell what is hidden draws
            // nothing new and leaves it to the next cycle.
            val hidden = readHiddenSets(context, deps, account)

            // 2. Resolve the photo list for this mode, hidden photos left out
            val photos = resolvePhotos(
                context, deps, mode, selectedUris, albumName, selectedLinkIds, cloudAlbumLinkId, hidden,
            )
            if (photos.isEmpty()) {
                clearBitmapState(context, glanceId, appWidgetId)
                return@withContext
            }

            // 3. Advance index (wrap around). ALL_PHOTOS starts from a random photo each cycle.
            val start = if (mode == WidgetMode.ALL_PHOTOS) {
                (0 until photos.size).random()
            } else {
                (currentIndex + 1) % photos.size
            }
            // A device photo whose Drive copy is hidden (a member of a hidden album keeps its device
            // file) is passed over for the next one along, the same photo the gallery leaves out.
            val twinUris = if (!mode.isCloud && hidden.cloudIds.isNotEmpty()) {
                val window = windowIndices(photos.size, start, HIDDEN_SKIP_WINDOW).map { photos[it] }
                hiddenTwinUris(
                    deps.syncStateDao().cloudPairs(account, window).asSequence()
                        .map { it.localUri to it.cloudFileId },
                    hidden.cloudIds,
                )
            } else {
                emptySet()
            }
            val nextIndex = firstVisibleIndex(photos.size, start, HIDDEN_SKIP_WINDOW) { photos[it] !in twinUris }
            if (nextIndex == null) {
                clearBitmapState(context, glanceId, appWidgetId)
                return@withContext
            }
            val photoRef = photos[nextIndex]

            // 4. Load, scale, save bitmap to private cache
            val bitmap = if (mode.isCloud) {
                loadScaledBitmapFromCloudCache(context, photoRef)
            } else {
                loadScaledBitmap(context, photoRef)
            }
            // The account read above may have signed out while the photo loaded, and a sign-out sweeps the
            // widgets' bitmaps before it ends: one saved after that sweep would put the departed account's
            // photo back on the launcher. So the account is read once more right before the save, and a
            // changed one draws nothing. A cancelled update stops here too, as the read checks for it.
            if (deps.accountManager().getPrimaryUserId().first() != userId) {
                clearBitmapState(context, glanceId, appWidgetId)
                return@withContext
            }
            // With no bitmap this cycle the previous one is deleted rather than left on disk, since
            // the state written below stops pointing at it.
            val cachePath = if (bitmap != null) {
                saveBitmapToCache(context, appWidgetId, bitmap)
            } else {
                File(context.cacheDir, cacheFileName(appWidgetId)).delete()
                null
            }
            // For CLOUD_SELECTED the "currentUri" we persist is the linkId — there is no
            // device URI for these photos. Other modes keep the previous behaviour.
            val photoUri = photoRef

            // 5. Persist state changes
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().also { mp ->
                    mp[PhotoWidgetKeys.CURRENT_INDEX] = nextIndex
                    mp[PhotoWidgetKeys.CURRENT_URI]   = photoUri
                    if (cachePath != null) mp[PhotoWidgetKeys.CACHED_BITMAP_PATH] = cachePath
                    else mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                }
            }

            // 6. Ask Glance to redraw
            PhotoWidget().update(context, glanceId)

        } catch (_: Exception) {
            // Widget update failure is non-fatal — will retry next cycle
        }
    }

    /** Clean up cached file and state when widget is removed. */
    suspend fun cleanup(context: Context, appWidgetId: Int) {
        try {
            File(context.cacheDir, cacheFileName(appWidgetId)).delete()
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().also { mp ->
                    mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                    mp.remove(PhotoWidgetKeys.CURRENT_URI)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun clearBitmapState(context: Context, glanceId: GlanceId, id: Int) {
        File(context.cacheDir, cacheFileName(id)).delete()
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().also { mp ->
                mp.remove(PhotoWidgetKeys.CACHED_BITMAP_PATH)
                mp.remove(PhotoWidgetKeys.CURRENT_URI)
            }
        }
        PhotoWidget().update(context, glanceId)
    }

    /**
     * What the widget may not show, read fresh each cycle from the same records the gallery's hide
     * filters read. [account] keys the vault's sync rows, the local partition when no one is signed
     * in, so a guest's device widget keeps working.
     */
    private suspend fun readHiddenSets(context: Context, deps: CloudDeps, account: String): WidgetHiddenSets {
        val prefs = context.settingsDataStore.data.first()
        val albumMembers = HashSet<String>()
        for (albumLinkId in prefs[SettingsKeys.HIDDEN_ALBUM_IDS].orEmpty()) {
            albumMembers += deps.albumPhotoMembershipDao().getPhotoLinkIds(albumLinkId)
        }
        val vaultedRowCloudIds = deps.syncStateDao().getVaulted(account).mapNotNull { it.cloudFileId }
        return widgetHiddenSets(prefs, albumMembers, vaultedRowCloudIds)
    }

    private suspend fun resolvePhotos(
        context: Context,
        deps: CloudDeps,
        mode: WidgetMode,
        selectedUris: List<String>,
        albumName: String?,
        selectedLinkIds: List<String>,
        cloudAlbumLinkId: String?,
        hidden: WidgetHiddenSets,
    ): List<String> = when (mode) {
        WidgetMode.SELECTED        -> if (selectedUris.isEmpty()) {
            queryAllImages(context, hidden.folderNames)
        } else {
            visibleSelectedUris(selectedUris, queryImageBuckets(context, selectedUris), hidden.folderNames)
        }
        // A folder the user hid draws nothing, the same way its card leaves the Albums grid.
        WidgetMode.ALBUM           -> when {
            albumName == null -> queryAllImages(context, hidden.folderNames)
            isInHiddenFolder(albumName, hidden.folderNames) -> emptyList()
            else -> queryAlbumImages(context, albumName)
        }
        WidgetMode.ALL_PHOTOS      -> queryAllImages(context, hidden.folderNames)
        // Every hidden cloud photo is left out: one hidden on its own, a member of a hidden album,
        // and the Drive copy of a vaulted photo. Each stays in the account and its linkId can still
        // be in the widget's selection, so drawing it would put a hidden photo on the home screen.
        WidgetMode.CLOUD_SELECTED  -> visibleCloudLinkIds(selectedLinkIds, hidden)
        // Follow-the-album: read the chosen album's CURRENT member linkIds live from the local
        // membership rows every cycle, so photos added to the album after setup appear. A hidden
        // album draws nothing, and a hidden member is left out as in CLOUD_SELECTED.
        WidgetMode.CLOUD_ALBUM     -> {
            val memberLinkIds = cloudAlbumLinkId
                ?.takeUnless { it in hidden.albumIds }
                ?.let { albumId ->
                    runCatching { deps.albumPhotoMembershipDao().getPhotoLinkIds(albumId) }.getOrNull()
                }
                .orEmpty()
            followedAlbumLinkIds(cloudAlbumLinkId, memberLinkIds, hidden)
        }
    }

    /** Every device image outside a hidden folder, newest first. */
    private fun queryAllImages(context: Context, hiddenFolderNames: Set<String>): List<String> {
        val result = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (isInHiddenFolder(cursor.getString(bucketCol), hiddenFolderNames)) continue
                result += Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(col).toString(),
                ).toString()
            }
        }
        return result
    }

    /**
     * Each of [uris] MediaStore still holds, mapped to its folder. One whose photo is gone is absent,
     * so no folder vouches for it. The image collection's own uris are read in batches by id; any
     * other form is asked for its folder directly.
     */
    private fun queryImageBuckets(context: Context, uris: List<String>): Map<String, String?> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() + "/"
        val urisById = HashMap<Long, MutableList<String>>()
        val bucketByUri = HashMap<String, String?>()
        for (uri in uris) {
            val id = uri.takeIf { it.startsWith(collection) }?.substring(collection.length)?.toLongOrNull()
            if (id != null) {
                urisById.getOrPut(id) { mutableListOf() } += uri
                continue
            }
            runCatching {
                context.contentResolver.query(
                    Uri.parse(uri), arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null, null,
                )?.use { cursor -> if (cursor.moveToFirst()) bucketByUri[uri] = cursor.getString(0) }
            }
        }
        for (chunk in urisById.keys.chunked(SQL_CHUNK_SIZE)) {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",") { "?" }})",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucket = cursor.getString(bucketCol)
                    urisById[cursor.getLong(col)]?.forEach { bucketByUri[it] = bucket }
                }
            }
        }
        return bucketByUri
    }

    private fun queryAlbumImages(context: Context, album: String): List<String> {
        val result = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
            arrayOf(album),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                result += Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(col).toString(),
                ).toString()
            }
        }
        return result
    }

    fun loadScaledBitmap(context: Context, uriString: String): Bitmap? = try {
        val uri  = Uri.parse(uriString)
        // First pass: read dimensions only
        val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts1)
        }
        val longest   = maxOf(opts1.outWidth, opts1.outHeight).coerceAtLeast(1)
        val sample    = Integer.highestOneBit((longest / MAX_BITMAP_PX).coerceAtLeast(1))
        // Second pass: decode at reduced size, using memory-efficient RGB_565
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize       = sample
            inPreferredConfig  = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts2)
        }
    } catch (_: Exception) { null }

    private fun saveBitmapToCache(context: Context, id: Int, bitmap: Bitmap): String {
        val file = File(context.cacheDir, cacheFileName(id))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    private fun cacheFileName(id: Int) = "$CACHE_FILE_PREFIX$id.jpg"

    // ── CLOUD_SELECTED helpers ────────────────────────────────────────────────

    /**
     * Load a decrypted thumbnail from the app-private encrypted cache by linkId,
     * scaled to fit within [MAX_BITMAP_PX]. When the thumbnail has not been
     * materialised yet (under the lazy decrypt architecture), we fire a
     * one-shot decrypt request through [ThumbnailDecryptScheduler] and wait up to
     * five seconds for the JPEG to appear before giving up. The widget worker is
     * fine taking a few seconds — it runs in the background, not on a UI thread —
     * and a miss just leaves the widget on its placeholder until the next cycle.
     * The decoded pixel data never leaves the app process until Glance
     * hands the scaled-down bitmap to the launcher for rendering; the underlying
     * encrypted material stays in the Room cache, and the decrypted thumbnail
     * file stays under `cacheDir/thumbnails/` which is 0700 to our UID only.
     */
    private suspend fun loadScaledBitmapFromCloudCache(context: Context, linkId: String): Bitmap? {
        if (linkId.isBlank()) return null
        val deps = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, CloudDeps::class.java)
        }.getOrNull() ?: return null

        val thumbnailFile = File(context.cacheDir, "thumbnails/thumb_$linkId.jpg")

        if (!thumbnailFile.exists()) {
            // Kick off a lazy decrypt and poll for the file. The scheduler dedups
            // concurrent requests for the same linkId, so calling on every widget
            // cycle when the entry is missing is harmless.
            val userId = runCatching { deps.accountManager().getPrimaryUserId().first() }.getOrNull()
                ?: return null
            val photo = runCatching { deps.photoListingDao().getByLinkId(linkId) }.getOrNull()
                ?: return null
            deps.thumbnailScheduler().request(userId, photo)
            withTimeoutOrNull(5_000L) {
                while (!thumbnailFile.exists()) {
                    delay(100L)
                }
            }
            if (!thumbnailFile.exists()) return null
        }

        // First pass: read dimensions only — the cached thumbnail's native size
        // varies (Drive picks ~500-1000 px depending on aspect ratio).
        val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(thumbnailFile.absolutePath, opts1)
        val longest = maxOf(opts1.outWidth, opts1.outHeight).coerceAtLeast(1)
        val sample  = Integer.highestOneBit((longest / MAX_BITMAP_PX).coerceAtLeast(1))
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize      = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(thumbnailFile.absolutePath, opts2) }.getOrNull()
    }
}
