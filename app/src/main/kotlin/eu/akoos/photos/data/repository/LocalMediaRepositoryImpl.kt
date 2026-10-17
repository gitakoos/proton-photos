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
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.entity.LocalTagEntity
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DeviceMediaScan
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.CaptureDateOverride
import eu.akoos.photos.util.HiddenCaptureTime
import eu.akoos.photos.util.LocalTagPrune
import eu.akoos.photos.util.FolderCoverMap
import eu.akoos.photos.util.MediaScanCoverage
import eu.akoos.photos.util.SharedQuerySource
import eu.akoos.photos.util.forEachSqlChunk
import eu.akoos.photos.util.mimeFromPath
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalMediaRepository"

/** Quiet time after a MediaStore change before the shared list re-scans, so the several
 *  notifications one capture or one batch delete fires cost a single scan. */
private const val CHANGE_QUIET_MS = 250L

/** The order a device scan reads MediaStore in: newest capture first, the file's added time breaking ties. */
private const val SCAN_SORT_ORDER =
    "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"

/** How old a complete device scan may be and still answer a one-shot reader when nothing has changed. */
private const val RECENT_SCAN_MAX_AGE_MS = 90_000L

/** Longest a steady stream of changes (an import landing file by file) holds the re-scan back. */
private const val CHANGE_MAX_WAIT_MS = 2_000L

/** How long the shared list outlives its last collector, so moving between screens reuses it
 *  instead of re-scanning. After that it is dropped. The gallery feed keeps the same window. */
private const val SHARE_STOP_TIMEOUT_MS = 5_000L

/** Ids bound per lookup when a prune re-checks its candidates, well under SQLite's 999 host variables. */
private const val MEDIA_ID_CHUNK = 500

/**
 * Keeps the first item of each uri and drops the rest, in place, and says whether it dropped any. A
 * cursor over a very large collection is read in windows, and a row inserted or re-dated while it is
 * read shifts the later windows, so the same row can come back twice; every reader keys the list by
 * uri, and a grid throws on a repeated key. The shift that repeats a row also pushes as many rows past
 * the end of the cursor, unread, so a read that repeated anything did not see the whole collection.
 */
internal fun MutableList<LocalMediaItem>.dropRepeatedUris(): Boolean {
    if (size < 2) return false
    val seen = HashSet<String>(size * 2)
    return retainAll { seen.add(it.uri) }
}

/**
 * Whether a device scan saw every file, so that a file it did not return may be taken for gone.
 *
 * Each condition is a way a scan comes back short while looking whole: without [fullAccess] (both
 * media grants) a collection answers with the app's own files or nothing; a collection whose cursor
 * failed or never opened ([rootsRead] short of [rootsQueried]) gives only what it read before;
 * [repeatsDropped] marks a read a concurrent change tore (see [dropRepeatedUris]); and a read of no
 * [rows] at all is also what a storage volume not yet mounted answers.
 */
internal fun isCompleteScan(
    fullAccess: Boolean,
    rootsQueried: Int,
    rootsRead: Int,
    repeatsDropped: Boolean,
    rows: Int,
): Boolean = fullAccess && rootsRead == rootsQueried && !repeatsDropped && rows > 0

/**
 * Which of [candidates] a direct look by id confirms are gone, for a prune that must not act on a
 * scan's word alone: a trashed file is missing from every default query yet still restorable, and a
 * torn read can skip a row without repeating one.
 *
 * [presentIds] answers for one collection root of [roots] and at most [chunkSize] of its ids: the ids
 * the root still holds, its trash included, or null when it cannot tell, which keeps every candidate
 * of that chunk. A candidate that is not an id under one of [roots] is kept too, since nothing here
 * can look it up.
 */
internal fun confirmedGone(
    candidates: Collection<String>,
    roots: Collection<String>,
    chunkSize: Int = MEDIA_ID_CHUNK,
    presentIds: (root: String, ids: List<Long>) -> Set<Long>?,
): List<String> {
    if (candidates.isEmpty()) return emptyList()
    val gone = ArrayList<String>()
    for (root in roots) {
        // Item uris are the root with the MediaStore id appended, so the separator is what makes this
        // a containment test rather than a name-prefix one.
        val prefix = "$root/"
        val urisById = LinkedHashMap<Long, MutableList<String>>()
        for (uri in candidates) {
            if (!uri.startsWith(prefix)) continue
            val id = uri.substring(prefix.length).toLongOrNull() ?: continue
            urisById.getOrPut(id) { ArrayList(1) } += uri
        }
        for (chunk in urisById.keys.chunked(chunkSize)) {
            val present = presentIds(root, chunk) ?: continue
            for (id in chunk) if (id !in present) gone += urisById.getValue(id)
        }
    }
    return gone
}

/**
 * The columns the whole-device scan reads from one collection, on a device running [sdkInt].
 *
 * Two of them are not there on every version: the pending flag arrives with Android 10, and before
 * that only the video collection has a duration. The provider refuses a projection that names a
 * column its collection lacks, and the refusal costs the whole collection, not the one column. Every
 * other column here is on both collections from Android 8.
 */
internal fun scanProjection(sdkInt: Int, isVideo: Boolean): Array<String> = listOfNotNull(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DATE_TAKEN,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.DATE_MODIFIED,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.MediaColumns.SIZE,
    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    MediaStore.MediaColumns.IS_PENDING.takeIf { sdkInt >= Build.VERSION_CODES.Q },
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    MediaStore.MediaColumns.DURATION.takeIf { hasDurationColumn(sdkInt, isVideo) },
).toTypedArray()

/**
 * The scan's row filter on a device running [sdkInt], or null to read every row. From Android 10 it
 * is the clause on the pending flag explained at the scan; below that the flag does not exist, a file
 * is written in place, and nothing is filtered. Trashed rows need no clause: the trash arrives with
 * Android 11, where a query leaves trashed rows out unless it asks for them.
 */
internal fun scanSelection(sdkInt: Int): String? =
    if (sdkInt >= Build.VERSION_CODES.Q) "${MediaStore.MediaColumns.IS_PENDING} <= 1" else null

/**
 * The columns a read of one item or of one folder takes from a collection, on a device running
 * [sdkInt]: the scan's without the modified time and the pending flag, with the duration kept to the
 * collections that have one (see [scanProjection]).
 */
internal fun itemProjection(sdkInt: Int, isVideo: Boolean): Array<String> = listOfNotNull(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DATE_TAKEN,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.MediaColumns.SIZE,
    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    MediaStore.MediaColumns.DURATION.takeIf { hasDurationColumn(sdkInt, isVideo) },
).toTypedArray()

/**
 * The row filter of a one-folder read on a device running [sdkInt]. The folder is its one argument,
 * bound rather than spliced in, so a folder named with a quote is matched rather than breaking the
 * statement. From Android 10 it also holds to finished rows, since a file still mid-write has no bytes
 * to act on yet; below that no row is ever pending.
 */
internal fun bucketSelection(sdkInt: Int): String {
    val bucket = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
    return if (sdkInt >= Build.VERSION_CODES.Q) "$bucket AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else bucket
}

/** Both collections have a duration column from Android 10; before that, only the video one. */
private fun hasDurationColumn(sdkInt: Int, isVideo: Boolean): Boolean =
    isVideo || sdkInt >= Build.VERSION_CODES.Q

/**
 * The cursor's current row as the item at [uri], which already carries the row's id.
 *
 * Every other column is read only where the cursor has it, and one it lacks reads as empty: zero, no
 * name, no folder. The queries leave out a column that a collection or an Android version does not
 * have rather than fail on it (see [scanProjection]), and only the trash's own read asks for the
 * expiry time.
 */
internal fun Cursor.readMediaRow(
    uri: String,
    tagCache: Map<String, LocalTagEntity> = emptyMap(),
    dateOverrides: Map<String, CaptureDateOverride.Entry> = emptyMap(),
    columns: MediaRowColumns = MediaRowColumns(this),
): LocalMediaItem {
    val rawDateTaken = longOrZero(columns.dateTaken)
    val dateAdded    = longOrZero(columns.dateAdded)
    // A recorded override is read first: it exists only where the stored date was established to be
    // missing or wrong (a downloaded PNG whose DATE_TAKEN write MediaStore refuses, or a file whose
    // column disagrees with its own EXIF), so it is the better value wherever it is present. The
    // column comes next, and the file's added time is the last resort.
    val dateTaken    = CaptureDateOverride.resolveDateTaken(
        rawDateTaken, dateOverrides[uri]?.captureMs, dateAdded,
    )
    val dateModified = longOrZero(columns.dateModified)
    val sizeBytes    = longOrZero(columns.size)

    val displayName  = stringOrNull(columns.displayName) ?: ""
    val rawMimeType  = stringOrNull(columns.mimeType) ?: ""
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
    val cacheEntry = tagCache[uri]
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
        uri         = uri,
        dateTaken   = dateTaken,
        displayName = displayName,
        mimeType    = mimeType,
        sizeBytes   = sizeBytes,
        bucketName  = stringOrNull(columns.bucket),
        width       = intOrZero(columns.width),
        height      = intOrZero(columns.height),
        duration    = longOrZero(columns.duration),
        dateModified = dateModified,
        tags        = cachedTags,
        userTags    = userTags,
        dateTakenIsExplicit = rawDateTaken > 0 || uri in dateOverrides,
        // The OS purge time of a trashed row; no other row has one, and a non-positive value means none.
        dateExpiresSec = longOrZero(columns.dateExpires).takeIf { it > 0 },
    )
}

/** [item] with the tags a cache entry holds for it: the detected ones only while the file is unchanged, the
 *  user's own always, exactly as [readMediaRow] applies a cache. */
internal fun LocalMediaItem.withCachedTags(entry: LocalTagEntity): LocalMediaItem = copy(
    tags = entry.takeIf { it.dateModified == dateModified && it.sizeBytes == sizeBytes }?.tags() ?: emptySet(),
    userTags = entry.userTags(),
)

private fun Cursor.longOrZero(index: Int): Long = if (index >= 0) getLong(index) else 0L

private fun Cursor.intOrZero(index: Int): Int = if (index >= 0) getInt(index) else 0

private fun Cursor.stringOrNull(index: Int): String? = if (index >= 0) getString(index) else null

/**
 * The positions of the columns a media row is read from, looked up once for a cursor. A column the
 * query did not ask for is -1 and reads as missing. Looking each name up for every row cost more than
 * reading the values on a library of a hundred thousand photos.
 */
internal class MediaRowColumns(cursor: Cursor) {
    val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
    val dateTaken = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
    val dateAdded = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
    val dateModified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
    val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
    val displayName = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
    val mimeType = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
    val bucket = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    val width = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
    val height = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
    val duration = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
    val dateExpires = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
}

@Singleton
class LocalMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localTagDao: LocalTagDao,
    private val localTagScanScheduler: LocalTagScanScheduler,
    @AppScope appScope: CoroutineScope,
) : LocalMediaRepository {

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

    /** The grants and mounted volumes the latest device scan ran under; see [mediaEnvironment]. */
    @Volatile private var lastScanEnvironment: String? = null

    /**
     * The device's photos and videos as one list the whole app shares.
     *
     * Every [observeLocalMedia] collector reads the same copy from the same scan: one MediaStore
     * observer drives it while anything collects, a burst of changes settles into one re-scan, and
     * the list is dropped a few seconds after the last collector leaves. [freshScan] reads through
     * the same gate, so two scans of the whole library never run at once, and the side effects in
     * [queryAllMedia] (the prunes, the tag scheduling) run once per scan rather than once per reader.
     */
    private val deviceMedia = SharedQuerySource<DeviceMediaScan>(
        scope = appScope,
        listen = ::listenForMediaChanges,
        quietMs = CHANGE_QUIET_MS,
        maxWaitMs = CHANGE_MAX_WAIT_MS,
        stopTimeoutMs = SHARE_STOP_TIMEOUT_MS,
        isStale = { lastScanEnvironment.let { it != null && it != mediaEnvironment() } },
        onReadFailure = { Log.w(TAG, "device media scan failed: ${it.message}") },
    ) {
        lastScanEnvironment = mediaEnvironment()
        queryAllMedia()
    }

    // A permission grant fires no MediaStore notification, so without this the gallery would stay
    // empty after the first grant until the app restarts. A change to a side store the scan reads
    // (the capture-date overrides) rides the same request, the provider having no event for it either.
    override fun notifyMediaChanged() {
        deviceMedia.requestRefresh()
    }

    // A re-scan that found the same files is not handed on again, whether or not it saw all of them.
    override fun observeLocalMedia(): Flow<List<LocalMediaItem>> =
        deviceMedia.shared.map { it.items }.distinctUntilChanged()

    // A scan that saw the whole device, finished within [RECENT_SCAN_MAX_AGE_MS] with no change signalled since
    // it began, answers a one-shot reader as a new one would: every reconcile, backfill walk and upload pass
    // asked for its own whole-library read, and with a hundred thousand photos those back-to-back reads kept
    // the app busy and its heap full while a back-up ran. A scan that could not see everything is never reused.
    override suspend fun freshScan(): DeviceMediaScan =
        deviceMedia.recent(RECENT_SCAN_MAX_AGE_MS) { it.complete }

    override suspend fun freshScanStrict(): DeviceMediaScan = deviceMedia.fresh()

    override suspend fun newestMedia(limit: Int): List<LocalMediaItem> = withContext(Dispatchers.IO) {
        if (!checkMediaPermission()) return@withContext emptyList()
        val dateOverrides = CaptureDateOverride.parse(
            runCatching { context.settingsDataStore.data.first() }.getOrNull()
                ?.get(SettingsKeys.DOWNLOAD_DATE_OVERRIDES),
        )
        val args = Bundle().apply {
            scanSelection(Build.VERSION.SDK_INT)?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SCAN_SORT_ORDER)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val result = ArrayList<LocalMediaItem>(limit * 2)
        for (root in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            val projection = scanProjection(Build.VERSION.SDK_INT, isVideo = root == MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            try {
                context.contentResolver.query(root, projection, args, null)?.use { cursor ->
                    val columns = MediaRowColumns(cursor)
                    val rootPrefix = "$root/"
                    // A provider that does not honour the limit still answers in order; stop after [limit] rows.
                    var read = 0
                    while (read < limit && cursor.moveToNext()) {
                        result += cursor.toLocalMediaItem(
                            baseUri = root, dateOverrides = dateOverrides, columns = columns, rootPrefix = rootPrefix,
                        )
                        read++
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "newest media query failed for $root: ${e.message}")
            }
        }
        result.sortedByDescending { it.dateTaken }.take(limit)
    }

    /** Registers the one MediaStore observer the shared list runs on and returns its removal. */
    private fun listenForMediaChanges(signal: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                signal()
                // New camera photos otherwise sit until the next periodic SyncWorker fire
                // (up to 15 minutes by default). Kicking here closes the gap to "a few
                // seconds after the picture is saved", subject to the upload-pipeline's
                // own folder-selection guards.
                maybeKickSync()
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        return { resolver.unregisterContentObserver(observer) }
    }

    /**
     * What a device scan depends on besides MediaStore's own rows: the media grants and the mounted
     * storage volumes. A change to either reaches no ContentObserver, so a reader subscribing to the
     * shared list compares this with the value the latest scan ran under and asks for a re-scan when
     * they differ, as a scan of its own would have picked the change up.
     */
    private fun mediaEnvironment(): String {
        val grants = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val granted = grants.joinToString(",") {
            (ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED).toString()
        }
        val volumes = runCatching {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.map { "${it.uuid}:${it.state}" }
                ?.sorted()
                ?.joinToString(",")
        }.getOrNull().orEmpty()
        return "$granted|$volumes"
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

    /**
     * [queryTrashedMedia]'s row count, read off an `_ID`-only cursor per collection rather than built
     * into items: the same collections, trash selection and permission gate, re-counted on the same
     * MediaStore changes, with a burst of changes folding into one count.
     */
    override fun countTrashedMedia(): Flow<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return flowOf(0)
        suspend fun countTrashed(): Int = withContext(Dispatchers.IO) {
            if (!checkMediaPermission()) return@withContext 0
            val queryArgs = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            var total = 0
            for (baseUri in listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )) {
                try {
                    context.contentResolver.query(baseUri, arrayOf(MediaStore.MediaColumns._ID), queryArgs, null)
                        ?.use { cursor -> total += cursor.count }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "trashed media count failed: ${e.message}")
                }
            }
            total
        }
        return callbackFlow<Unit> {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            trySend(Unit)
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }.conflate().map { countTrashed() }
    }

    override fun hasMediaPermission(): Flow<Boolean> = flowOf(checkMediaPermission())

    override suspend fun confirmedGone(uris: Collection<String>): Set<String> =
        if (uris.isEmpty()) emptySet()
        else withContext(Dispatchers.IO) { confirmGone(uris.toList()).toHashSet() }

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
            val projection = itemProjection(
                Build.VERSION.SDK_INT,
                isVideo = collectionUri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
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

            val selection = bucketSelection(Build.VERSION.SDK_INT)
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
                val projection = itemProjection(
                    Build.VERSION.SDK_INT,
                    isVideo = uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )
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

    private suspend fun queryAllMedia(): DeviceMediaScan = withContext(Dispatchers.IO) {
        coroutineScope {
            val startedAtMs = System.currentTimeMillis()
            if (!checkMediaPermission()) {
                return@coroutineScope DeviceMediaScan(emptyList(), complete = false).also { it.startedAtMs = startedAtMs }
            }
            // Read before the queries: a scan run without both grants cannot vouch for what it returned.
            val fullAccess = hasFullMediaAccess()
            eu.akoos.photos.util.PerfDiagnostics.mark("devicescan:start", 0)

            val result = mutableListOf<LocalMediaItem>()

            // Load the category-tag cache once for this scan. Each item picks up its tags from the
            // entry that still MATCHES on dateModified + sizeBytes; a stale or missing entry leaves
            // tags empty (the categorizer falls back to its cheap heuristics, and the tag scheduler
            // re-detects out of band). A read failure degrades to "no cache" — correctness intact.
            // Read while MediaStore is being queried, not before it: the two are independent, and the tag cache of
            // a large library took seconds on its own. Each row is read without tags and takes them afterwards.
            val tagCacheLoad = async {
                runCatching { localTagDao.getAll().associateBy { it.uri } }.getOrDefault(emptyMap())
            }

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

            eu.akoos.photos.util.PerfDiagnostics.mark("devicescan:prelude", 0)

            // IS_PENDING <= 1 (not == 0) so freshly-captured camera photos still mid-finalization
            // appear in the query result. The MediaStore observer fires once when the file is
            // created (IS_PENDING=1) and again when the camera app commits (IS_PENDING=0); the
            // prior appearance lets the sync pipeline have the SyncState row ready by the time
            // the commit lands, instead of waiting a full periodic cycle after. Below Android 10
            // there is no pending flag, and every row is read.
            val selection = scanSelection(Build.VERSION.SDK_INT)
            val sortOrder = SCAN_SORT_ORDER

            // The collection roots this scan actually enumerated. A root joins the list only once its
            // cursor has been drained, so a denied permission (images and videos are separate grants from
            // Android 13) or a provider failure leaves it out, and the scan counts as incomplete.
            val scannedRoots = mutableListOf<String>()
            val roots = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )

            for (uri in roots) {
                // Per collection, as the two do not carry the same columns on every Android version.
                val projection = scanProjection(
                    Build.VERSION.SDK_INT,
                    isVideo = uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )
                try {
                    context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                        val columns = MediaRowColumns(cursor)
                        val rootPrefix = "$uri/"
                        while (cursor.moveToNext()) {
                            result += cursor.toLocalMediaItem(
                                baseUri = uri, dateOverrides = dateOverrides,
                                columns = columns, rootPrefix = rootPrefix,
                            )
                        }
                        scannedRoots += uri.toString()
                    }
                } catch (e: Exception) {
                    // The rows read before the failure stay for display; the root is left out of scannedRoots.
                    Log.w(TAG, "device media query failed for $uri: ${e.message}")
                }
            }
            val tagCache = tagCacheLoad.await()
            if (tagCache.isNotEmpty()) {
                for (i in result.indices) {
                    val entry = tagCache[result[i].uri] ?: continue
                    result[i] = result[i].withCachedTags(entry)
                }
            }
            eu.akoos.photos.util.PerfDiagnostics.mark("devicescan:queried", result.size)
            val repeatsDropped = result.dropRepeatedUris()
            val complete = isCompleteScan(
                fullAccess = fullAccess,
                rootsQueried = roots.size,
                rootsRead = scannedRoots.size,
                repeatsDropped = repeatsDropped,
                rows = result.size,
            )
            if (!complete) {
                Log.w(
                    TAG,
                    "device scan incomplete: ${result.size} rows, ${scannedRoots.size}/${roots.size} collections, " +
                        "fullAccess=$fullAccess, repeats=$repeatsDropped",
                )
            }

            // Every uri-keyed side store prunes a row only once the file behind it is proven gone, and a
            // scan that missed part of the device proves nothing about the part it missed: an incomplete
            // one leaves all three stores as they are, and the next complete scan catches up.
            if (complete) pruneSideStores(result, scannedRoots, tagCache, dateOverrides, pinnedFolderCovers)

            eu.akoos.photos.util.PerfDiagnostics.mark("devicescan:pruned", result.size)
            val sorted = result.sortedByDescending { it.dateTaken }
            eu.akoos.photos.util.PerfDiagnostics.mark("devicescan:end", sorted.size)
            // Fill/refresh the photo-category tag cache for new or changed files in the background
            // (cheap pre-filter; only large images get an XMP read), keyed on the real DATE_MODIFIED
            // this query reads, so the next scan surfaces accurate type badges instead of caching a
            // dateModified of 0 that never matches the gallery's freshness check.
            localTagScanScheduler.schedule(sorted, tagCache)
            DeviceMediaScan(sorted, complete).also { it.startedAtMs = startedAtMs }
        }
    }

    /**
     * Drops the side-store rows whose file a complete scan proves gone: the recorded capture dates,
     * the pinned folder covers and the category-tag cache, each of which keeps a row per file and would
     * otherwise grow for as long as the app is installed. [items] and [scannedRoots] are the scan's.
     */
    private suspend fun pruneSideStores(
        items: List<LocalMediaItem>,
        scannedRoots: List<String>,
        tagCache: Map<String, LocalTagEntity>,
        dateOverrides: Map<String, CaptureDateOverride.Entry>,
        pinnedFolderCovers: Map<String, String>,
    ) {
        // Skipped entirely when no store holds anything, so a large library does not pay for a set of
        // live uris nothing reads.
        if (dateOverrides.isEmpty() && tagCache.isEmpty() && pinnedFolderCovers.isEmpty()) return
        val liveUris: Set<String> = items.mapTo(HashSet(items.size)) { it.uri }

        // Prune override entries whose file is gone, so the map can't grow without bound, but only
        // where the file is proven gone: absent from this scan by the rule in [MediaScanCoverage], and
        // then not found by id either, trash included. An entry is the only place a downloaded PNG or
        // WebP keeps its capture date (#34), and trashing such a file hides it from every default
        // query while it stays restorable; dropping its entry then would date it to the download day
        // for good once restored. Only open the DataStore edit when something qualifies.
        val staleOverrideUris = confirmGone(MediaScanCoverage.provenDeleted(dateOverrides.keys, liveUris, scannedRoots))
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

        // Same rule for the pinned device-folder covers: a cover whose photo is proven gone names
        // nothing, so the entry goes and the folder falls back to its newest photo. A photo merely
        // moved out of its folder is still live, and a trashed one still restorable, so their entries
        // stay and only the fallback at the read sites hides them, which is what keeps a move or a
        // trip through the trash from discarding a deliberate choice.
        val stalePinnedCovers = confirmGone(MediaScanCoverage.provenDeleted(pinnedFolderCovers.values, liveUris, scannedRoots))
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
        // one already read by the scan, so this adds no query. The delete is chunked because a long-lived
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
    }

    /** The subset of [candidates] a lookup by id no longer finds; see [confirmedGone]. */
    private fun confirmGone(candidates: List<String>): List<String> =
        confirmedGone(
            candidates = candidates,
            roots = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString(),
            ),
        ) { root, ids -> presentMediaIds(Uri.parse(root), ids) }

    /**
     * The subset of [ids] the collection at [root] still holds, trashed and pending rows included, or
     * null when the provider could not answer. [ids] is one bounded chunk, bound as arguments rather
     * than spliced into the statement.
     */
    private fun presentMediaIds(root: Uri, ids: List<Long>): Set<Long>? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",") { "?" }})"
        val args = Array(ids.size) { ids[it].toString() }
        return try {
            val cursor = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.contentResolver.query(
                    root,
                    projection,
                    android.os.Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        // A trashed file is still on the device and restorable, and one being written
                        // is still arriving: both count as present.
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    },
                    null,
                )
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    @Suppress("DEPRECATION")
                    val withPending = MediaStore.setIncludePending(root)
                    context.contentResolver.query(withPending, projection, selection, args, null)
                }
                else -> context.contentResolver.query(root, projection, selection, args, null)
            }
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                HashSet<Long>().apply { while (c.moveToNext()) add(c.getLong(idCol)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "media id lookup failed for $root: ${e.message}")
            null
        }
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "trashed media query failed: ${e.message}")
            }
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

    /**
     * Both media grants rather than either: a collection queried without its own grant answers with
     * the app's own files or nothing, which a whole-device scan must not be mistaken for.
     */
    private fun hasFullMediaAccess(): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * The cursor's current row as the item under the collection [baseUri] (the images one when
     * null); see [readMediaRow]. The id is the one column a row cannot do without, being the item's
     * address, so a cursor without it fails the read.
     */
    private fun Cursor.toLocalMediaItem(
        baseUri: Uri? = null,
        tagCache: Map<String, LocalTagEntity> = emptyMap(),
        dateOverrides: Map<String, CaptureDateOverride.Entry> = emptyMap(),
        columns: MediaRowColumns = MediaRowColumns(this),
        rootPrefix: String? = null,
    ): LocalMediaItem {
        if (columns.id < 0) throw IllegalArgumentException("column '_id' does not exist")
        val id = getLong(columns.id)
        val uri = if (rootPrefix != null) {
            rootPrefix + id
        } else {
            Uri.withAppendedPath(baseUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString()
        }
        return readMediaRow(uri, tagCache, dateOverrides, columns)
    }
}
