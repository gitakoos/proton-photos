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

package eu.akoos.photos.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.CaptureDateOverride
import eu.akoos.photos.util.HiddenCaptureTime
import eu.akoos.photos.util.LocalTagPrune
import eu.akoos.photos.util.FolderCoverMap
import eu.akoos.photos.util.MediaScanCoverage
import eu.akoos.photos.util.forEachSqlChunk
import eu.akoos.photos.util.mimeFromPath
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localTagDao: LocalTagDao,
    private val localTagScanScheduler: LocalTagScanScheduler,
) : LocalMediaRepository {

    // Manual refresh trigger merged with the MediaStore ContentObserver. Permission grants do
    // not fire onChange, so without this the gallery would stay empty after first grant until
    // the user restarts the app. A change to a side store the scan reads rides the same trigger,
    // the provider having no event for it either.
    private val refreshTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun notifyMediaChanged() {
        refreshTrigger.tryEmit(Unit)
    }

    /**
     * Settle-and-kick scheduler for the auto-sync trigger. OneDrive-style: when a new photo
     * arrives, wait for a short quiet window (no more MediaStore changes), then start the
     * SyncWorker. This batches camera bursts (10 photos in 2 seconds) into a single sync
     * run instead of pinging the worker once per file.
     *
     * Each onChange resets the pending kick — so the timer only fires after the user stops
     * adding files for [syncSettleMs] milliseconds. The kick itself is a OneTime enqueue,
     * which KEEP-coalesces with any in-flight periodic / oneshot worker.
     */
    private val syncKickHandler = Handler(Looper.getMainLooper())
    private val syncSettleMs: Long = 2_000L
    /** IO scope for the DataStore read inside [performSyncKick]. The Handler post stays on
     *  the main looper for the settle/debounce, but the actual work hops off so the cold
     *  cache read can't stall the UI thread. */
    private val syncKickScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncKickRunnable = Runnable { syncKickScope.launch { performSyncKick() } }

    private fun maybeKickSync() {
        syncKickHandler.removeCallbacks(syncKickRunnable)
        syncKickHandler.postDelayed(syncKickRunnable, syncSettleMs)
    }

    private suspend fun performSyncKick() {
        try {
            val prefs: Preferences = context.settingsDataStore.data.first()
            val autoSync = prefs[SettingsKeys.AUTO_SYNC] != false
            val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
            val backupEverything = prefs[SettingsKeys.BACKUP_EVERYTHING] ?: false
            val selectedFolders = prefs[SettingsKeys.SYNC_FOLDER_NAMES]
            if (!autoSync) return
            // Kick the SyncWorker when EITHER backup-everything is on OR explicit folders
            // are selected. Without the backupEverything branch, users who toggled the
            // new mode (without picking folders) would never see auto-upload because this
            // gate would early-return on a null selectedFolders set.
            if (!backupEverything && selectedFolders.isNullOrEmpty()) return
            SyncWorker.runNow(context, wifiOnly)
        } catch (_: Throwable) {
            // Losing one kick is harmless — the next MediaStore change or the periodic
            // fallback will pick the photos up.
        }
    }

    override fun observeLocalMedia(): Flow<List<LocalMediaItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
                // New camera photos otherwise sit until the next periodic SyncWorker fire
                // (up to 15 minutes by default). Kicking here closes the gap to "a few
                // seconds after the picture is saved", subject to the upload-pipeline's
                // own folder-selection guards.
                maybeKickSync()
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        trySend(Unit) // initial query

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.let { contentChanges ->
        merge(contentChanges, refreshTrigger.asSharedFlow())
            .let { allTriggers ->
                flow {
                    allTriggers.collect {
                        emit(queryAllMedia())
                    }
                }
            }
    }

    override fun observeTrashedMedia(): Flow<List<LocalMediaItem>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return flowOf(emptyList())
        return callbackFlow {
            suspend fun emit() { trySend(queryTrashedMedia()) }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            emit()
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }.let { changes ->
            flow { changes.collect { emit(queryTrashedMedia()) } }
        }
    }

    override fun hasMediaPermission(): Flow<Boolean> = flowOf(checkMediaPermission())

    override suspend fun sha1(uri: String): String? = withContext(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            } ?: return@withContext null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@withContext null
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun queryByUri(uri: String): LocalMediaItem? = withContext(Dispatchers.IO) {
        val parsedUri = Uri.parse(uri)
        // App-private hidden file (file://...) — synthesize the LocalMediaItem from the file.
        if (parsedUri.scheme == "file") {
            val file = parsedUri.path?.let { java.io.File(it) } ?: return@withContext null
            if (!file.exists()) return@withContext null
            // The same extension-to-type map every write against an app-private file reads, so what a
            // screen offers for a vaulted photo and what the write then does with it cannot disagree.
            val mime = mimeFromPath(file.name)
            return@withContext LocalMediaItem(
                uri         = uri,
                // The capture time the vault recorded in the name, which is the only place it
                // survives (see [HiddenCaptureTime]). The file's modified time is the moment the
                // copy was written, so it stands in only for an entry whose name records nothing.
                dateTaken   = HiddenCaptureTime.parse(file.name) ?: file.lastModified(),
                // Without the capture time the vault keeps in the name: that suffix is the vault's own
                // bookkeeping, and a rename field prefilled with it puts it back into the name the user
                // ends up with.
                displayName = HiddenCaptureTime.strip(file.name),
                mimeType    = mime,
                sizeBytes   = file.length(),
                bucketName  = "Hidden",
                width       = 0,
                height      = 0,
                duration    = 0L,
            )
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
        try {
            // parsedUri is already an item URI (e.g. content://media/external/images/media/12345).
            // We must pass the COLLECTION URI as baseUri — NOT the item URI — so that
            // toLocalMediaItem can reconstruct the correct content URI from the cursor's _ID.
            // Using the item URI directly causes Uri.withAppendedPath(itemUri, id) to produce
            // a double-ID path (".../12345/12345") which openInputStream cannot resolve.
            val collectionUri: Uri? = when {
                parsedUri.toString().contains("/video/") ->
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                parsedUri.toString().contains("/images/") ->
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            context.contentResolver.query(parsedUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.toLocalMediaItem(baseUri = collectionUri) else null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    override suspend fun queryByBucket(bucketName: String): List<LocalMediaItem> =
        withContext(Dispatchers.IO) {
            if (bucketName.isBlank()) return@withContext emptyList()
            if (!checkMediaPermission()) return@withContext emptyList()

            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DURATION,
            )
            // The bucket is bound as an argument rather than spliced into the selection, so a folder
            // named with a quote is matched rather than breaking the statement. IS_PENDING is held to
            // finished rows: a file still mid-write has no bytes to act on yet.
            val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
            val args = arrayOf(bucketName)
            // Capture-date overrides, the same map the full scan applies, so a folder read here dates
            // its photos exactly as the timeline does. Only read, never pruned: proving a row deleted
            // needs the whole device, which is precisely what this query does not look at.
            val dateOverrides: Map<String, CaptureDateOverride.Entry> = CaptureDateOverride.parse(
                runCatching { context.settingsDataStore.data.first() }.getOrNull()
                    ?.get(SettingsKeys.DOWNLOAD_DATE_OVERRIDES),
            )

            val result = mutableListOf<LocalMediaItem>()
            for (uri in listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )) {
                try {
                    context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            result += cursor.toLocalMediaItem(baseUri = uri, dateOverrides = dateOverrides)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            result.sortedByDescending { it.dateTaken }
        }

    private suspend fun queryAllMedia(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        if (!checkMediaPermission()) return@withContext emptyList()

        val result = mutableListOf<LocalMediaItem>()

        // Load the category-tag cache once for this scan. Each item picks up its tags from the
        // entry that still MATCHES on dateModified + sizeBytes; a stale or missing entry leaves
        // tags empty (the categorizer falls back to its cheap heuristics, and the tag scheduler
        // re-detects out of band). A read failure degrades to "no cache" — correctness intact.
        val tagCache: Map<String, LocalTagEntity> =
            runCatching { localTagDao.getAll().associateBy { it.uri } }.getOrDefault(emptyMap())

        // One snapshot of the store for the two uri-keyed preferences this scan prunes, so a large
        // library pays for a single read. A read failure degrades to "nothing stored", which prunes
        // nothing rather than pruning wrongly.
        val scanPrefs = runCatching { context.settingsDataStore.data.first() }.getOrNull()

        // Capture-date overrides for files whose MediaStore DATE_TAKEN is missing or wrong (a PNG/WebP
        // the provider refuses; see SettingsKeys.DOWNLOAD_DATE_OVERRIDES). Applied per row ahead of
        // the column; pruned below once the live uris are known.
        val dateOverrides: Map<String, CaptureDateOverride.Entry> =
            CaptureDateOverride.parse(scanPrefs?.get(SettingsKeys.DOWNLOAD_DATE_OVERRIDES))

        // Device-folder covers the user pinned, keyed by bucket name. Read here only to be pruned:
        // the folder card and the folder hero resolve their own cover from the same preference.
        val pinnedFolderCovers: Map<String, String> =
            FolderCoverMap.parse(scanPrefs?.get(SettingsKeys.FOLDER_COVER_URI_MAP))

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )

        // IS_PENDING <= 1 (not == 0) so freshly-captured camera photos still mid-finalization
        // appear in the query result. The MediaStore observer fires once when the file is
        // created (IS_PENDING=1) and again when the camera app commits (IS_PENDING=0); the
        // prior appearance lets the sync pipeline have the SyncState row ready by the time
        // the commit lands, instead of waiting a full periodic cycle after.
        val selection = "${MediaStore.MediaColumns.IS_PENDING} <= 1"
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"

        // The collection roots this scan actually enumerated. A root joins the list only once its
        // cursor has been drained, so a denied permission (images and videos are separate grants from
        // Android 13) or a provider failure leaves it out, so the tag prune below knows those rows
        // are unproven rather than deleted.
        val scannedRoots = mutableListOf<String>()

        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            try {
                context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        result += cursor.toLocalMediaItem(
                            baseUri = uri, tagCache = tagCache, dateOverrides = dateOverrides,
                        )
                    }
                    scannedRoots += uri.toString()
                }
            } catch (e: Exception) {
                // Skip inaccessible URIs
            }
        }

        // Every uri-keyed side store below needs this scan's live uris to spot a row whose file is
        // gone. Built once and shared, and skipped entirely when no store holds anything, so a
        // large library does not pay for a set nothing reads.
        val liveUris: Set<String> =
            if (dateOverrides.isEmpty() && tagCache.isEmpty() && pinnedFolderCovers.isEmpty()) emptySet()
            else result.mapTo(HashSet(result.size)) { it.uri }

        // Prune override entries whose file is gone, so the map can't grow without bound — but only
        // where THIS scan proves the file is gone, which is the rule in [MediaScanCoverage]. An entry
        // is the only place a downloaded PNG or WebP keeps its capture date (#34), so a scan that
        // failed outright, or that never got past the images collection on a videos-denied device,
        // must leave what it could not see alone. Only open the DataStore edit when something
        // qualifies.
        val staleOverrideUris = MediaScanCoverage.provenDeleted(dateOverrides.keys, liveUris, scannedRoots)
        if (staleOverrideUris.isNotEmpty()) {
            runCatching {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] ?: emptySet()
                    CaptureDateOverride.dropUris(current, staleOverrideUris.toSet())?.let {
                        prefs[SettingsKeys.DOWNLOAD_DATE_OVERRIDES] = it
                    }
                }
            }
        }

        // Same rule for the pinned device-folder covers: a cover whose photo this scan proves is gone
        // names nothing, so the entry goes and the folder falls back to its newest photo. A photo
        // merely moved out of its folder is still live, so its entry stays and only the fallback at
        // the read sites hides it — which is what keeps a move from discarding a deliberate choice.
        val stalePinnedCovers = MediaScanCoverage.provenDeleted(pinnedFolderCovers.values, liveUris, scannedRoots)
        if (stalePinnedCovers.isNotEmpty()) {
            runCatching {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.FOLDER_COVER_URI_MAP] ?: emptySet()
                    FolderCoverMap.dropUris(current, stalePinnedCovers.toSet())?.let {
                        prefs[SettingsKeys.FOLDER_COVER_URI_MAP] = it
                    }
                }
            }
        }

        // Same for the category-tag cache, which otherwise keeps a row per file the device ever held.
        // It adds a guard of its own on top of the shared rule; see [LocalTagPrune]. The row set is the
        // one already read above, so this adds no query. The delete is chunked because a long-lived
        // library can strand more uris than SQLite will bind in one statement. A failure only means the
        // table stays large for now, and the next scan retries.
        if (tagCache.isNotEmpty()) {
            val staleTagUris = LocalTagPrune.staleUris(
                cachedUris = tagCache.keys,
                liveUris = liveUris,
                scannedRoots = scannedRoots,
            ) { tagCache[it]?.userTagsCsv.orEmpty() }
            if (staleTagUris.isNotEmpty()) {
                runCatching { staleTagUris.forEachSqlChunk { localTagDao.deleteByUris(it) } }
            }
        }

        val sorted = result.sortedByDescending { it.dateTaken }
        // Fill/refresh the photo-category tag cache for new or changed files in the background
        // (cheap pre-filter; only large images get an XMP read), keyed on the real DATE_MODIFIED
        // this query reads — so the next scan surfaces accurate type badges instead of caching a
        // dateModified of 0 that never matches the gallery's freshness check.
        localTagScanScheduler.schedule(sorted)
        sorted
    }

    private suspend fun queryTrashedMedia(): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        if (!checkMediaPermission()) return@withContext emptyList()

        val result = mutableListOf<LocalMediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            // Exact OS auto-purge time for a trashed row (epoch seconds). API 30+, which this
            // query already requires; the shared cursor mapper reads it defensively.
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val queryArgs = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }

        for (baseUri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            try {
                context.contentResolver.query(baseUri, projection, queryArgs, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        result += cursor.toLocalMediaItem(baseUri = baseUri)
                    }
                }
            } catch (_: Exception) {}
        }
        result.sortedByDescending { it.dateTaken }
    }

    private fun checkMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // On Android 13+, images and videos are separate permissions; grant if at least one is.
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun android.database.Cursor.toLocalMediaItem(
        baseUri: Uri? = null,
        tagCache: Map<String, LocalTagEntity> = emptyMap(),
        dateOverrides: Map<String, CaptureDateOverride.Entry> = emptyMap(),
    ): LocalMediaItem {
        val idCol        = getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val dateTakenCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModCol   = getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val nameCol      = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeCol      = getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val sizeCol      = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val bucketCol    = getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val widthCol     = getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightCol    = getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val durationCol  = getColumnIndex(MediaStore.MediaColumns.DURATION)
        // -1 for every non-trashed query (the column isn't in that projection); present only for
        // the trashed-media query. Read as absent when missing or non-positive.
        val dateExpiresCol = getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)

        val id = getLong(idCol)
        val root         = baseUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val contentUri   = Uri.withAppendedPath(root, id.toString())
        val uriString    = contentUri.toString()
        val rawDateTaken = getLong(dateTakenCol)
        val dateAdded    = getLong(dateAddedCol)
        // A recorded override is read first: it exists only where the stored date was established to be
        // missing or wrong (a downloaded PNG whose DATE_TAKEN write MediaStore refuses, or a file whose
        // column disagrees with its own EXIF), so it is the better value wherever it is present. The
        // column comes next, and the file's added time is the last resort.
        val dateTaken    = CaptureDateOverride.resolveDateTaken(
            rawDateTaken, dateOverrides[uriString]?.captureMs, dateAdded,
        )
        val dateModified = if (dateModCol >= 0) getLong(dateModCol) else 0L
        val sizeBytes    = getLong(sizeCol)

        val displayName  = getString(nameCol) ?: ""
        val rawMimeType  = getString(mimeCol) ?: ""
        val mimeType     = if (rawMimeType.isNotEmpty()) rawMimeType else {
            when (displayName.substringAfterLast('.').lowercase()) {
                "mp4", "m4v", "3gp", "ts"  -> "video/mp4"
                "mov"                        -> "video/quicktime"
                "avi"                        -> "video/x-msvideo"
                "mkv"                        -> "video/x-matroska"
                "webm"                       -> "video/webm"
                "jpg", "jpeg"               -> "image/jpeg"
                "png"                        -> "image/png"
                "gif"                        -> "image/gif"
                "webp"                       -> "image/webp"
                "heic", "heif"               -> "image/heic"
                else                         -> ""
            }
        }

        // A cache entry counts only while the file is unchanged: same DATE_MODIFIED AND same
        // size. Any drift means the file was replaced (edited, re-saved) so its old tags are
        // discarded and re-detection happens out of band via the tag scheduler.
        val cacheEntry = tagCache[uriString]
        val cachedTags = cacheEntry
            ?.takeIf { it.dateModified == dateModified && it.sizeBytes == sizeBytes }
            ?.tags()
            ?: emptySet()
        // The user's own choice rides the same row, so it costs no extra read. It deliberately
        // skips the freshness gate above: that key belongs to the detection, and a row written for
        // a file the scanner has not reached yet carries zeros there, which match no live
        // MediaStore row. Gating the choice on it would hide every category picked ahead of a scan,
        // and re-hide it each time the file changes, though the choice is still the user's.
        val userTags = cacheEntry?.userTags() ?: emptySet()

        return LocalMediaItem(
            uri         = uriString,
            dateTaken   = dateTaken,
            displayName = displayName,
            mimeType    = mimeType,
            sizeBytes   = sizeBytes,
            bucketName  = if (bucketCol >= 0) getString(bucketCol) else null,
            width       = if (widthCol >= 0) getInt(widthCol) else 0,
            height      = if (heightCol >= 0) getInt(heightCol) else 0,
            duration    = if (durationCol >= 0) getLong(durationCol) else 0L,
            dateModified = dateModified,
            tags        = cachedTags,
            userTags    = userTags,
            dateTakenIsExplicit = rawDateTaken > 0 || uriString in dateOverrides,
            dateExpiresSec = if (dateExpiresCol >= 0) getLong(dateExpiresCol).takeIf { it > 0 } else null,
        )
    }
}
