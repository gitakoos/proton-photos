/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import java.io.File
import java.io.FileOutputStream

/**
 * Central helper that advances the widget to its next photo, loads + caches the
 * bitmap, persists updated state, and triggers a Glance redraw.
 */
object PhotoWidgetUpdater {

    /** Max long edge for cached widget bitmaps (RGB_565 → ~450 KB at 480 px, safe under 1 MB Binder limit). */
    private const val MAX_BITMAP_PX = 480

    /**
     * Hilt EntryPoint for the dependencies needed by [WidgetMode.CLOUD_SELECTED].
     * Lets this top-level object pull singletons out of the SingletonComponent
     * without converting the Worker into a HiltWorker (kept the existing class
     * untouched per "additive only" rule).
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CloudDeps {
        fun thumbnailScheduler(): ThumbnailDecryptScheduler
        fun photoListingDao(): PhotoListingDao
        fun accountManager(): AccountManager
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
            val currentIndex    = prefs[PhotoWidgetKeys.CURRENT_INDEX] ?: 0

            // 2. Resolve the photo list for this mode
            val photos = resolvePhotos(context, mode, selectedUris, albumName, selectedLinkIds)
            if (photos.isEmpty()) {
                clearBitmapState(context, glanceId, appWidgetId)
                return@withContext
            }

            // 3. Advance index (wrap around)
            val nextIndex = if (mode == WidgetMode.ALL_PHOTOS) {
                // Pure random for ALL_PHOTOS — ignore stored index
                (0 until photos.size).random()
            } else {
                (currentIndex + 1) % photos.size
            }
            val photoRef = photos[nextIndex]

            // 4. Load, scale, save bitmap to private cache
            val bitmap = when (mode) {
                WidgetMode.CLOUD_SELECTED -> loadScaledBitmapFromCloudCache(context, photoRef)
                else                       -> loadScaledBitmap(context, photoRef)
            }
            val cachePath = if (bitmap != null) {
                saveBitmapToCache(context, appWidgetId, bitmap)
            } else null
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

    private suspend fun resolvePhotos(
        context: Context,
        mode: WidgetMode,
        selectedUris: List<String>,
        albumName: String?,
        selectedLinkIds: List<String>,
    ): List<String> = when (mode) {
        WidgetMode.SELECTED        -> selectedUris.ifEmpty { queryAllImages(context) }
        WidgetMode.ALBUM           -> albumName?.let { queryAlbumImages(context, it) } ?: queryAllImages(context)
        WidgetMode.ALL_PHOTOS      -> queryAllImages(context)
        // Exclude the cloud copies of photos the user has hidden on this device. A hidden
        // photo's Drive copy stays in the account, and its linkId can still be in the
        // widget's selection list — surfacing it here would leak the hidden image onto the
        // home screen. The hidden→cloudId map persists each hidden item as a
        // "hiddenUri|cloudLinkId" token; we strip any selected linkId that appears there.
        WidgetMode.CLOUD_SELECTED  -> {
            val hiddenCloudIds = hiddenCloudLinkIds(context)
            selectedLinkIds.filterNot { it in hiddenCloudIds }
        }
    }

    /**
     * Cloud linkIds belonging to photos the user has hidden on this device, read from the
     * hidden→cloudId map (each entry encoded as "hiddenUri|cloudLinkId"). Used to keep
     * hidden items' Drive copies out of every widget mode that renders by cloud linkId.
     */
    private suspend fun hiddenCloudLinkIds(context: Context): Set<String> = runCatching {
        context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP]
            ?.map { it.substringAfter('|') }
            ?.toSet()
            ?: emptySet()
    }.getOrDefault(emptySet())

    private fun queryAllImages(context: Context): List<String> {
        val result = mutableListOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
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

    private fun cacheFileName(id: Int) = "widget_$id.jpg"

    // ── CLOUD_SELECTED helpers ────────────────────────────────────────────────

    /**
     * Load a decrypted thumbnail from the app-private encrypted cache by linkId,
     * scaled to fit within [MAX_BITMAP_PX]. When the thumbnail has not been
     * materialised yet (under the lazy decrypt architecture), we fire a
     * one-shot decrypt request through [ThumbnailDecryptScheduler] and wait up to
     * five seconds for the JPEG to appear before giving up. The widget worker is
     * fine taking a few seconds — it runs in the background, not on a UI thread —
     * and a miss just means we keep the previously-rendered bitmap until the next
     * cycle. The decoded pixel data never leaves the app process until Glance
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
