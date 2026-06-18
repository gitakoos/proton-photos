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

package eu.akoos.photos.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.StorageFullException
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val UPLOAD_TAG = "UploadUseCase"

/**
 * Per-batch upload parallelism. Three concurrent uploads matches the inner CDN-block
 * semaphore in [eu.akoos.photos.data.repository.drive.PhotoUploadService] (2 blocks × ~1.5
 * outer queue depth keeps the pipe saturated without starving the shared Proton networkSemaphore).
 * Bumped from 1 — serial uploads were the dominant cost in long sync batches and contributed
 * to the Android 14+ foreground-service daily-budget exhaustion.
 */
private const val UPLOAD_PARALLELISM = 3

/**
 * Per-file upload event surfaced to UI (Settings sync progress).
 *
 * Status transitions per file: `Queued` → `Encrypting` → `Uploading` → (`Done` | `Failed`).
 * `done` and `total` are batch-level counters so the UI can render a single linear bar without
 * keeping its own state.
 *
 * `Encrypting` was split out from `Uploading` so the user sees the pre-network phase distinctly
 * — multi-hundred-MB videos can spend minutes in encrypt/sign/spill before the first byte hits
 * the CDN, and folding that into "Uploading" was the most common cause of "stuck at 0 %" reports.
 *
 * `Idle` is a synthetic frame the use-case emits when it finishes (or finds nothing to upload)
 * so observers can clear any in-flight view without watching a separate signal.
 */
enum class UploadStatus { Queued, Encrypting, Uploading, Done, Failed, Idle }

data class UploadProgress(
    val uri: String,
    val displayName: String,
    val status: UploadStatus,
    val doneIdx: Int,
    val totalCount: Int,
    /** Plaintext byte count of the file this event is about. Used by the Sync card
     *  to compute a running bytes/sec speed. 0 for non-per-file events (Idle frames). */
    val sizeBytes: Long = 0L,
    /**
     * Live byte counter for the current file's phase (Encrypting or Uploading). 0 outside
     * those phases. SettingsViewModel reads this for the live MB/s speed read-out so the
     * meter updates DURING a single large upload instead of only ticking on file completion.
     */
    val doneBytes: Long = 0L,
)

@Singleton
class UploadPendingUseCase @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val pendingDeleteNotif: PendingDeleteNotificationUseCase,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    /** Serialises read-modify-write on the PENDING_ALBUM_ADDS set so two concurrent uploads
     *  removing different entries don't clobber each other's removals. */
    private val pendingAddMutex = Mutex()

    /**
     * Hot stream of per-file upload events. Buffered so a slow collector never throttles the
     * upload loop. Replay = 1 so a UI that connects mid-batch sees the latest event immediately.
     */
    private val _progress = MutableSharedFlow<UploadProgress>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val progress: SharedFlow<UploadProgress> = _progress.asSharedFlow()

    /**
     * Returned to the worker so it can distinguish "0 to upload" (success) from "tried N,
     * 0 succeeded" (the latter must not silently mark sync as done when every item failed).
     * [SyncWorker] downgrades the verdict when [successCount] is 0 but [attempted] is
     * non-zero so WorkManager retries instead of writing LAST_SYNC_MS.
     */
    data class Result(val attempted: Int, val successCount: Int) {
        val allFailed: Boolean get() = attempted > 0 && successCount == 0
    }

    /**
     * Run the pending-upload batch. Items inside a single batch run in parallel bounded by
     * [UPLOAD_PARALLELISM]; the [mutex] still serialises concurrent batch *invocations* so
     * we never queue the same SyncState row twice.
     *
     * [isStoppedSignal] lets the caller (typically [eu.akoos.photos.worker.SyncWorker]) signal
     * "the OS pulled the foreground budget — abort". When it returns `true`, in-flight tasks
     * are cancelled and the function returns whatever succeeded so far. Default `{ false }`
     * keeps the existing UI callers (SettingsViewModel / GalleryViewModel) source-compatible.
     */
    suspend operator fun invoke(
        userId: UserId,
        isStoppedSignal: () -> Boolean = { false },
    ): Result = mutex.withLock {
        // Read folder filter — same source of truth as ReconcileSyncStateUseCase.
        // null = first-run (backup nothing); empty set = all disabled; non-empty = selected folders.
        // BACKUP_EVERYTHING bypasses the folder filter entirely (mirrors reconcile).
        val prefs = context.settingsDataStore.data.first()
        val backupEverything = prefs[SettingsKeys.BACKUP_EVERYTHING] ?: false
        val selectedFolders: Set<String>? = prefs[SettingsKeys.SYNC_FOLDER_NAMES]
        // Backup-everything carve-outs (mirrors ReconcileSyncStateUseCase). Defending in
        // depth here matters: a user can toggle excludes AFTER reconcile created the
        // LOCAL_ONLY rows, or during an in-flight upload batch. Without the filter we'd
        // ship the excluded bucket's photos to Drive on the way out.
        val excludedFolders: Set<String> = prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet()
        // Per-folder cloud-album mirror opt-in. Photos always upload to the photostream
        // regardless; this set decides whether the upload path also adds the photo to
        // an auto-named album matching its source bucket (Camera, Screenshots, …).
        val albumOptInFolders: Set<String> = prefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] ?: emptySet()

        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: true
        val renameToCaptureDate = prefs[SettingsKeys.RENAME_TO_CAPTURE_DATE] ?: false
        val deleteLocalAfterBackup = prefs[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] ?: false
        val stripConfig = MetadataStripConfig(
            stripGps = prefs[SettingsKeys.STRIP_GPS] ?: true,
            stripCameraInfo = prefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
            stripTimestamp = prefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
            stripSoftwareInfo = prefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false,
        )

        // Drain pending album-adds for photos that already finished uploading (their row is
        // SYNCED, so uploadOne below won't run for them). This covers the gap where a photo
        // backed up before its album-add succeeded — or a prior add failed — and guarantees the
        // add is eventually applied across restarts and partial failures. Runs BEFORE the
        // no-folders early-out so a queued add still lands even when backup is otherwise idle.
        val pendingAdds = decodePendingAlbumAdds(prefs[SettingsKeys.PENDING_ALBUM_ADDS] ?: emptySet())
        for ((localUri, albumLinkId) in pendingAdds) {
            val cloudId = syncStateRepo.getByUri(localUri)?.cloudFileId ?: continue
            // No-album sentinel: the entry only forced the upload, there is no album to join.
            // Drop it now that the photo is backed up.
            if (albumLinkId == SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM) {
                removePendingAlbumAdd(localUri, albumLinkId)
                continue
            }
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
                .onSuccess {
                    removePendingAlbumAdd(localUri, albumLinkId)
                    Log.d(UPLOAD_TAG, "Drained pending add: $cloudId → album $albumLinkId")
                }
                .onFailure { e -> Log.w(UPLOAD_TAG, "Pending-add drain failed for $localUri: ${e.message}") }
        }
        // URIs that still have a queued album-add need their upload forced even when their folder
        // is not in the backup selection — bypass the folder guards below for these.
        val forcedUploadUris: Set<String> = pendingAdds.keys

        if (!backupEverything && (selectedFolders == null || selectedFolders.isEmpty()) && forcedUploadUris.isEmpty()) {
            Log.d(UPLOAD_TAG, "No backup folders configured — skipping upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }

        var pending = syncStateRepo.observeAll(userId).first()
            .filter { it.status == SyncStatus.LOCAL_ONLY }

        // Apply the backup-everything exclusion at upload-time as well as reconcile-time.
        // Reconcile already filters BEFORE creating LOCAL_ONLY rows, but a toggle between
        // reconcile and upload (or a long-running batch the user wants to redirect) can
        // leave excluded rows here. Lookup needs bucketName, which lives on LocalMediaItem
        // not SyncState — one MediaStore query, mapped by URI.
        // Re-apply the folder filter at upload time, not only at reconcile time. The user can
        // deselect a folder (allow-list mode) or add an exclusion (backup-everything mode) AFTER
        // reconcile created the LOCAL_ONLY rows, or mid-batch. Without this, photos already queued
        // from a folder the user just turned off keep uploading until the queue drains, so the
        // backup looks like it ignores the toggle. bucketName lives on LocalMediaItem, not
        // SyncState, so one MediaStore query mapped by URI resolves each pending row's folder.
        if (pending.isNotEmpty()) {
            val bucketByUri: Map<String, String?> = localRepo.observeLocalMedia().first()
                .associate { it.uri to it.bucketName }
            val before = pending.size
            pending = pending.filter { state ->
                // A photo explicitly queued for an album always uploads — it must back up to join it.
                if (state.localUri in forcedUploadUris) return@filter true
                val bucket = bucketByUri[state.localUri]
                if (backupEverything) {
                    // Everything except the buckets the user carved out.
                    bucket == null || bucket !in excludedFolders
                } else {
                    // Allow-list: only the selected buckets. A null bucket matches reconcile, which
                    // treats bucket-less media as backup-able while any folder is selected.
                    !selectedFolders.isNullOrEmpty() && (bucket == null || bucket in selectedFolders)
                }
            }
            if (pending.size != before) {
                Log.d(UPLOAD_TAG, "Folder filter dropped ${before - pending.size}/$before pending item(s)")
            }
        }

        if (pending.isEmpty()) {
            Log.d(UPLOAD_TAG, "No pending items to upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }
        Log.d(UPLOAD_TAG, "Starting upload of ${pending.size} pending item(s)")

        // Build name→linkId map from existing Drive albums (source of truth from cloud).
        // MUTABLE: virtual-album-driven cloud creates below need to merge into this so a
        // second upload to the same virtual album doesn't try to recreate the cloud counterpart.
        val existingAlbumsByName: MutableMap<String, String> = try {
            cloudRepo.loadAlbums(userId).associate { it.name.lowercase() to it.linkId }
                .toMutableMap()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Could not load existing albums for name matching: ${e.message}")
            mutableMapOf()
        }

        // Rebuild the in-memory bucket→albumLinkId cache from DataStore, but validate each entry
        // against the cloud state. Stale entries (from deleted albums) are dropped so we re-create
        // instead of endlessly trying to add photos to a non-existent album.
        val albumCache = mutableMapOf<String, String>().apply {
            (prefs[SettingsKeys.ALBUM_BUCKET_MAP] ?: emptySet()).forEach { entry ->
                val idx = entry.indexOf('=')
                if (idx > 0) {
                    val bucket = entry.substring(0, idx)
                    val storedLinkId = entry.substring(idx + 1)
                    // Only keep if the cloud still has an album with this name AND its linkId matches
                    val cloudLinkId = existingAlbumsByName[bucket.lowercase()]
                    if (cloudLinkId != null) {
                        put(bucket, cloudLinkId)  // use cloud linkId (authoritative)
                    } else {
                        Log.d(UPLOAD_TAG, "albumCache: dropping stale entry '$bucket'=$storedLinkId (album deleted)")
                    }
                }
            }
        }
        // Persist the cleaned cache immediately so stale entries don't survive app restarts
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
        }

        val successCount = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)
        val totalCount = pending.size

        // Bounded-parallel upload: at most UPLOAD_PARALLELISM photos in flight at once. Matches
        // the CDN-block parallelism inside PhotoUploadService so a single batch fully uses the
        // pipe without over-subscribing it.
        val uploadSemaphore = Semaphore(UPLOAD_PARALLELISM)
        // Album-cache mutations (read-check-create-publish) race when multiple uploads target
        // the same new album. Guard cache reads/writes + the DataStore persist with this mutex
        // so two parallel uploads of "Camera" photos don't both call createDriveAlbum.
        val albumCacheMutex = Mutex()

        // Storage-full is a fatal-for-batch signal: the OS won't accept more bytes for *anyone*.
        // Set this flag from inside an async task; remaining tasks check it before doing CPU
        // work so we don't pointlessly compute hashes for files that can't upload. AtomicBoolean
        // (not @Volatile var — @Volatile is a field-only annotation, not allowed on locals)
        // gives us safe cross-coroutine writes.
        val storageFullHit = AtomicBoolean(false)

        try {
            coroutineScope {
                val jobs = pending.map { state ->
                    async {
                        // Cheapest possible early-out: cooperative cancellation, OS stop-signal,
                        // and storage-full flag. Done BEFORE acquiring the permit so a stopped
                        // worker doesn't tie up a slot while it spins down.
                        coroutineContext.ensureActive()
                        if (isStoppedSignal() || storageFullHit.get()) return@async

                        uploadSemaphore.withPermit {
                            // Re-check after acquiring the permit — earlier tasks may have set
                            // storageFullHit / isStoppedSignal while we were queued.
                            coroutineContext.ensureActive()
                            if (isStoppedSignal() || storageFullHit.get()) return@withPermit

                            uploadOne(
                                userId = userId,
                                state = state,
                                totalCount = totalCount,
                                backupEverything = backupEverything,
                                selectedFolders = selectedFolders,
                                forcedUploadUris = forcedUploadUris,
                                albumOptInFolders = albumOptInFolders,
                                stripOnUpload = stripOnUpload,
                                renameToCaptureDate = renameToCaptureDate,
                                deleteLocalAfterBackup = deleteLocalAfterBackup,
                                stripConfig = stripConfig,
                                existingAlbumsByName = existingAlbumsByName,
                                albumCache = albumCache,
                                albumCacheMutex = albumCacheMutex,
                                successCount = successCount,
                                finishedCount = finishedCount,
                                onStorageFull = { storageFullHit.set(true) },
                            )
                        }
                    }
                }
                // awaitAll surfaces the first thrown exception (and cancels the siblings).
                // We swallow per-photo errors inside uploadOne so this only escapes for hard
                // failures (e.g. coroutine cancellation), which we handle in the catch below.
                jobs.awaitAll()
            }
        } catch (e: CancellationException) {
            Log.d(UPLOAD_TAG, "Upload batch cancelled — ${successCount.get()}/$totalCount succeeded before stop")
            // Propagate cancellation so structured concurrency tears down the caller properly.
            throw e
        }

        val finalSuccess = successCount.get()
        Log.d(UPLOAD_TAG, "Upload complete: $finalSuccess/${pending.size} succeeded")
        // Refresh the consent notification with the latest pending queue. Same
        // call MainActivity.onResume fires so an externally deleted file (file
        // manager, OS trash flush) gets reconciled the moment the user opens the
        // app even without a worker run.
        pendingDeleteNotif()

        // Final "Idle" frame so the UI clears any in-flight panel.
        _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, totalCount, totalCount))
        Result(attempted = pending.size, successCount = finalSuccess)
    }

    /**
     * Upload a single pending photo. Extracted from [invoke] so the parallel loop can call it
     * from inside `async { semaphore.withPermit { ... } }` blocks. Per-photo errors are caught
     * here so one failure doesn't tear down siblings via structured concurrency.
     *
     * Progress emissions use [finishedCount] (atomic running tally) rather than the photo's
     * position in the source list — with parallel uploads, photo at index 5 may finish before
     * the photo at index 2, and the UI bar should render real completion progress.
     */
    @Suppress("LongParameterList")
    private suspend fun uploadOne(
        userId: UserId,
        state: SyncState,
        totalCount: Int,
        backupEverything: Boolean,
        selectedFolders: Set<String>?,
        forcedUploadUris: Set<String>,
        albumOptInFolders: Set<String>,
        stripOnUpload: Boolean,
        renameToCaptureDate: Boolean,
        deleteLocalAfterBackup: Boolean,
        stripConfig: MetadataStripConfig,
        existingAlbumsByName: MutableMap<String, String>,
        albumCache: MutableMap<String, String>,
        albumCacheMutex: Mutex,
        successCount: AtomicInteger,
        finishedCount: AtomicInteger,
        onStorageFull: () -> Unit,
    ) {
        var strippedFile: File? = null
        try {
            val rawLocalItem = localRepo.queryByUri(state.localUri)
            if (rawLocalItem == null) {
                Log.w(UPLOAD_TAG, "Local item not found for URI: ${state.localUri}")
                return
            }
            // Guard: skip items from folders that are no longer selected.
            // ReconcileSyncStateUseCase normally cleans these up, but this is a safety net
            // in case a stale LOCAL_ONLY entry survives (e.g. after import / app restart).
            // backupEverything skips this defense-in-depth folder check too —
            // there's no folder filter to compare against. A photo the user explicitly added to
            // an album (forcedUploadUris) also bypasses the check: it must upload so it can join
            // the album even when its source folder isn't in the backup set.
            if (!backupEverything && state.localUri !in forcedUploadUris &&
                rawLocalItem.bucketName != null && selectedFolders != null && rawLocalItem.bucketName !in selectedFolders) {
                Log.d(UPLOAD_TAG, "Skipping ${rawLocalItem.displayName}: folder '${rawLocalItem.bucketName}' not selected for backup")
                return
            }
            // Optional rename: derive cloud displayName from the source's capture timestamp
            // (MediaStore DATE_TAKEN). The on-device file is NOT touched — only the name we
            // send to Drive changes. Strip-on-upload runs against the bytes of the original
            // (or stripped temp) file independently, so a stripped + renamed photo still gets
            // its EXIF erased before the cloud name lands.
            val renamedItem = if (renameToCaptureDate) {
                val ext = rawLocalItem.displayName.substringAfterLast('.', "")
                val captureMs = rawLocalItem.dateTaken.takeIf { it > 0L } ?: System.currentTimeMillis()
                val newBase = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(captureMs))
                val newName = if (ext.isNotEmpty()) "$newBase.$ext" else newBase
                Log.d(UPLOAD_TAG, "Rename-on-upload: '${rawLocalItem.displayName}' → '$newName'")
                rawLocalItem.copy(displayName = newName)
            } else {
                rawLocalItem
            }
            // Strip-timestamp promises capture-time removal, but dateTaken also feeds the
            // Drive captureTime and the xAttr ModificationTime (PhotoUploadService). Floor
            // it to upload time here so the cloud metadata can't reconstruct when the shot
            // was actually taken. The on-device MediaStore row is untouched.
            val localItem = if (stripOnUpload && stripConfig.stripTimestamp) {
                renamedItem.copy(dateTaken = System.currentTimeMillis())
            } else {
                renamedItem
            }
            Log.d(UPLOAD_TAG, "Uploading: ${localItem.displayName} (${localItem.mimeType}, ${localItem.sizeBytes} bytes)")
            // First UI signal: this file has entered the pipeline at the Encrypting phase.
            // The bar's per-file colour shifts to the dimmer "pre-network" tint until the
            // first onProgress(Uploading) callback fires. doneIdx uses the running-finished
            // tally so the bar reflects completions, not phase transitions of any one file.
            _progress.tryEmit(
                UploadProgress(
                    uri = state.localUri,
                    displayName = localItem.displayName,
                    status = UploadStatus.Encrypting,
                    doneIdx = finishedCount.get(),
                    totalCount = totalCount,
                    sizeBytes = localItem.sizeBytes,
                )
            )

            // Strip metadata into a temp file if configured. Images go through EXIF tag
            // wiping; videos through a stream-copy re-mux that drops the container's GPS
            // location atom (EXIF wiping can't reach an MP4/MOV moov/udta). Both paths
            // fall back to the original URI when the temp couldn't be produced — a strip
            // failure must never block the backup of the file itself.
            val uploadUri: String = if (stripOnUpload && localItem.mimeType.startsWith("image/")) {
                // Motion Photos carry an MP4 appended after the primary still. A plain
                // ExifInterface rewrite-strip drops that trailer (and the motion is lost), so
                // detect first and, for a motion photo, strip only the primary's EXIF while
                // re-attaching the original trailer byte-for-byte.
                val motionTemp = stripImagePreservingMotion(state.localUri, stripConfig)
                if (motionTemp != null) {
                    strippedFile = motionTemp
                    Log.d(UPLOAD_TAG, "Motion Photo primary stripped, trailer preserved for ${localItem.displayName}")
                    android.net.Uri.fromFile(motionTemp).toString()
                } else {
                    // Not a motion photo — take the ordinary EXIF tag wipe. (A confirmed motion
                    // photo never reaches here: the helper returns a byte-exact temp instead of
                    // null when its split can't complete.) stripToTempFile returning null then
                    // means nothing to strip or a strip error, so the original URI uploads untouched.
                    strippedFile = ExifHelper.stripToTempFile(context, state.localUri, stripConfig)
                    if (strippedFile != null) {
                        Log.d(UPLOAD_TAG, "Metadata stripped for ${localItem.displayName}")
                        android.net.Uri.fromFile(strippedFile).toString()
                    } else {
                        state.localUri
                    }
                }
            } else if (stripOnUpload && stripConfig.stripGps && localItem.mimeType.startsWith("video/")) {
                val tmp = File.createTempFile("stripped_", ".mp4", context.cacheDir)
                if (eu.akoos.photos.presentation.editor.VideoMetadataStripper
                        .remuxWithoutLocation(context, state.localUri, tmp)) {
                    strippedFile = tmp
                    Log.d(UPLOAD_TAG, "Video location atom stripped for ${localItem.displayName}")
                    android.net.Uri.fromFile(tmp).toString()
                } else {
                    // Re-mux failed — the temp is already cleaned up by the stripper; upload
                    // the untouched original so the file still reaches Drive.
                    Log.w(UPLOAD_TAG, "Video location strip failed for ${localItem.displayName}; uploading original")
                    state.localUri
                }
            } else {
                state.localUri
            }

            val hash = computeSha1(uploadUri)
            val uploadItem = if (strippedFile != null)
                localItem.copy(sizeBytes = strippedFile.length())
            else
                localItem

            // Throttled progress relay. PhotoUploadService already debounces to ~250 ms per
            // phase, but a single batch can have multiple uploads in flight (UPLOAD_PARALLELISM
            // permits), each with its own meter. We re-throttle per-uri here so the SharedFlow
            // can't be flooded by ~30 callbacks/s × N concurrent uploads. Phase TRANSITIONS
            // always emit (even if inside the throttle window) so the UI sees the Encrypting →
            // Uploading switch the moment the first CDN PUT lands.
            val lastEmitMsByPhase = AtomicLong(0L)
            // Phase tracked via AtomicReference because PhotoUploadService invokes onProgress
            // from multiple coroutines: parallel encrypt tasks during the encrypt phase, then
            // parallel CDN PUTs during the upload phase. The phase boundary itself is sequential
            // (encrypt awaitAll → 100 % tick → uploads start), but within a phase the lambda
            // sees concurrent calls and the captured var would otherwise need explicit syncing.
            val lastPhase = java.util.concurrent.atomic.AtomicReference<
                eu.akoos.photos.data.repository.drive.UploadPhase?>(null)
            val onProgressForFile: (
                eu.akoos.photos.data.repository.drive.UploadPhase,
                Long,
                Long,
            ) -> Unit = { phase, doneBytes, _ ->
                val nowMs = System.currentTimeMillis()
                val prev = lastEmitMsByPhase.get()
                val phaseChanged = lastPhase.getAndSet(phase) != phase
                if (phaseChanged || nowMs - prev >= 250L) {
                    lastEmitMsByPhase.set(nowMs)
                    val statusForUi = when (phase) {
                        eu.akoos.photos.data.repository.drive.UploadPhase.Encrypting ->
                            UploadStatus.Encrypting
                        eu.akoos.photos.data.repository.drive.UploadPhase.Uploading ->
                            UploadStatus.Uploading
                    }
                    _progress.tryEmit(
                        UploadProgress(
                            uri = state.localUri,
                            displayName = localItem.displayName,
                            status = statusForUi,
                            doneIdx = finishedCount.get(),
                            totalCount = totalCount,
                            doneBytes = doneBytes,
                        )
                    )
                }
            }
            // Resolve Camera/Location + rotation-corrected dimensions from the ORIGINAL source
            // (pre-strip), gated against the strip config so the xAttr never re-leaks a field the
            // file had erased. uploadItem.dateTaken is already floored when timestamps are stripped.
            val xAttrMetadata = buildXAttrMetadata(
                sourceUri = state.localUri,
                item = uploadItem,
                stripOnUpload = stripOnUpload,
                stripConfig = stripConfig,
            )
            val cloudId = cloudRepo.uploadFile(userId, uploadItem, hash, uploadUri, xAttrMetadata, onProgressForFile)

            strippedFile?.delete()
            strippedFile = null

            syncStateRepo.upsert(
                state.copy(
                    cloudFileId = cloudId,
                    localHash = hash,
                    status = SyncStatus.SYNCED,
                    lastSyncSuccessMs = System.currentTimeMillis(),
                    backedUpAtMs = System.currentTimeMillis(),
                ),
                userId,
            )

            // Delete-after-backup: only the ORIGINAL MediaStore URI, never the strip
            // temp. Runs AFTER the SYNCED upsert so a crash here cannot leave the
            // user with a deleted local file and no Drive record. The direct call
            // throws RecoverableSecurityException on Samsung Gallery owned items
            // even with Manage Media granted — the OS still wants an explicit
            // consent gesture. We queue refused URIs into PENDING_DELETE_URIS and
            // the batched foreground sweep at the end of the upload run sends a
            // single createDeleteRequest IntentSender that lets the user accept N
            // files at once instead of one dialog per file.
            if (deleteLocalAfterBackup) {
                val uri = runCatching { Uri.parse(state.localUri) }.getOrNull()
                if (uri == null) {
                    Log.w(UPLOAD_TAG, "Delete after backup: invalid URI ${state.localUri}")
                } else {
                    val rows = runCatching { context.contentResolver.delete(uri, null, null) }
                        .getOrElse { e ->
                            Log.w(UPLOAD_TAG, "Delete after backup threw for ${localItem.displayName}: ${e::class.simpleName} ${e.message}")
                            0
                        }
                    if (rows > 0) {
                        Log.d(UPLOAD_TAG, "Delete after backup: removed ${localItem.displayName} from MediaStore")
                    } else {
                        context.settingsDataStore.edit { p ->
                            val existing = p[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()
                            p[SettingsKeys.PENDING_DELETE_URIS] = existing + state.localUri
                        }
                        Log.d(UPLOAD_TAG, "Delete after backup: queued ${localItem.displayName} for batched consent dialog")
                    }
                }
            }

            successCount.incrementAndGet()
            val doneNow = finishedCount.incrementAndGet()
            Log.d(UPLOAD_TAG, "Upload OK: ${localItem.displayName} → cloudId=$cloudId")
            _progress.tryEmit(
                UploadProgress(
                    uri = state.localUri,
                    displayName = localItem.displayName,
                    status = UploadStatus.Done,
                    // doneIdx reflects how many photos have actually finished — not the
                    // current photo's slot. With parallelism, "3 of 5 done" is the right
                    // signal when photo #5 finishes before photo #3.
                    doneIdx = doneNow,
                    totalCount = totalCount,
                    sizeBytes = uploadItem.sizeBytes,
                )
            )

            // Auto-sync to the bucket-name Drive album (Camera, Screenshots, …) for folders
            // the user opted into mirroring. Bucket-name-album mirror is opt-in per folder;
            // photos still upload either way — only the album mirror step is gated.
            val targetAlbumNames = buildSet {
                localItem.bucketName?.takeIf { it in albumOptInFolders }?.let { add(it) }
            }
            for (albumName in targetAlbumNames) {
                try {
                    // Resolve-or-create the album linkId under a mutex so two parallel
                    // uploads to the same new album don't both call createDriveAlbum.
                    val albumLinkId = albumCacheMutex.withLock {
                        albumCache[albumName]
                            ?: existingAlbumsByName[albumName.lowercase()]?.also { linkId ->
                                // Found an existing album — add to cache so we skip the lookup next time
                                albumCache[albumName] = linkId
                                context.settingsDataStore.edit { p ->
                                    p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
                                }
                                Log.d(UPLOAD_TAG, "Matched existing album '$albumName' → $linkId")
                            }
                            ?: run {
                                val newAlbum = cloudRepo.createDriveAlbum(userId, albumName)
                                albumCache[albumName] = newAlbum.linkId
                                // Keep the in-memory name→linkId index in sync so later iterations
                                // in this same batch don't try to re-create the album.
                                existingAlbumsByName[albumName.lowercase()] = newAlbum.linkId
                                context.settingsDataStore.edit { p ->
                                    p[SettingsKeys.ALBUM_BUCKET_MAP] = albumCache.entries.map { "${it.key}=${it.value}" }.toSet()
                                }
                                Log.d(UPLOAD_TAG, "Created new album '$albumName' → ${newAlbum.linkId}")
                                newAlbum.linkId
                            }
                    }
                    runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
                        .onSuccess { Log.d(UPLOAD_TAG, "Added $cloudId to album '$albumName' ($albumLinkId)") }
                        .onFailure { e -> Log.w(UPLOAD_TAG, "addPhotosToAlbum failed for '$albumName': ${e.message}") }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(UPLOAD_TAG, "Album sync failed for '$albumName': ${e.message}")
                    // Non-fatal: photo is uploaded, album sync is best-effort
                }
            }

            // User-queued album-adds for this freshly-uploaded photo. These are album linkIds
            // directly (the user picked an existing album for a local-only photo), so skip the
            // bucket-name resolve/create the mirror loop above does. Best-effort like the mirror:
            // on failure the photo stays uploaded and the entry stays for the next-pass drain.
            val queuedAlbumLinkIds = decodePendingAlbumAdds(
                context.settingsDataStore.data.first()[SettingsKeys.PENDING_ALBUM_ADDS] ?: emptySet()
            ).filterKeys { it == state.localUri }.values.toSet()
            for (albumLinkId in queuedAlbumLinkIds) {
                // No-album sentinel: this URI was forced to upload with no album to join. The
                // upload just succeeded, so drop the marker without an album call.
                if (albumLinkId == SettingsKeys.PENDING_ALBUM_ADD_NO_ALBUM) {
                    removePendingAlbumAdd(state.localUri, albumLinkId)
                    continue
                }
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
                    .onSuccess {
                        removePendingAlbumAdd(state.localUri, albumLinkId)
                        Log.d(UPLOAD_TAG, "Added $cloudId to queued album $albumLinkId")
                    }
                    .onFailure { e -> Log.w(UPLOAD_TAG, "Queued album-add failed for $albumLinkId: ${e.message}") }
            }
        } catch (e: CancellationException) {
            // Structured cancellation — re-throw so awaitAll() tears down siblings cleanly.
            throw e
        } catch (e: StorageFullException) {
            Log.w(UPLOAD_TAG, "Storage full — flagging batch abort after current task")
            onStorageFull()
            _progress.tryEmit(
                UploadProgress(
                    uri = state.localUri,
                    displayName = state.localUri.substringAfterLast('/'),
                    status = UploadStatus.Failed,
                    doneIdx = finishedCount.get(),
                    totalCount = totalCount,
                )
            )
        } catch (e: Exception) {
            Log.e(UPLOAD_TAG, "Upload failed for ${state.localUri}: ${e.message}", e)
            _progress.tryEmit(
                UploadProgress(
                    uri = state.localUri,
                    // localItem may be null at this point; degrade to the URI tail rather
                    // than crash. UI rows truncate ellipsis-end so this still looks ok.
                    displayName = state.localUri.substringAfterLast('/'),
                    status = UploadStatus.Failed,
                    doneIdx = finishedCount.get(),
                    totalCount = totalCount,
                )
            )
            // swallowed — caller batch continues with siblings
        } finally {
            strippedFile?.delete()
        }
    }

    /**
     * Hex-encodes the SHA-1 of the file's plaintext content. Drive's `ContentHash`
     * wire field is `HMAC-SHA256(rootNodeHashKey, sha1Hex.utf8Bytes())` and Drive
     * web's `photosTransferPayloadBuilder` rejects any payload whose photo content
     * hash was derived from a SHA-256 input with the misleading error
     * "Cannot build photo payload without a content hash". Drive Android picks SHA-1
     * via `ConfigurationProvider.contentDigestAlgorithm = "SHA1"` (tempandroid-drive
     * `drive/base/domain/.../ConfigurationProvider.kt:73`). The xAttr Common.Digests
     * map carries the same SHA-1 hex so a later cross-client xAttr verify lines up.
     */
    /**
     * Decodes the PENDING_ALBUM_ADDS set ("localUri=albumLinkId" entries) into a localUri→linkId
     * map. The split is on the LAST '=' because a content:// URI can contain '=' in a query
     * string while a Drive album linkId never does. A localUri with multiple queued albums keeps
     * only the last one in the map, but every raw entry is still removed individually on success.
     */
    private fun decodePendingAlbumAdds(raw: Set<String>): Map<String, String> =
        raw.mapNotNull { entry ->
            val idx = entry.lastIndexOf('=')
            if (idx <= 0 || idx == entry.length - 1) null
            else entry.substring(0, idx) to entry.substring(idx + 1)
        }.toMap()

    /** Removes a single "localUri=albumLinkId" entry from PENDING_ALBUM_ADDS after its add lands. */
    private suspend fun removePendingAlbumAdd(localUri: String, albumLinkId: String) {
        pendingAddMutex.withLock {
            context.settingsDataStore.edit { p ->
                val existing = p[SettingsKeys.PENDING_ALBUM_ADDS] ?: emptySet()
                p[SettingsKeys.PENDING_ALBUM_ADDS] = existing - "$localUri=$albumLinkId"
            }
        }
    }

    /**
     * Strip path for Motion Photos. Returns a temp upload file when [localUri] is a motion photo,
     * or null when it is not (so the caller runs the ordinary EXIF strip instead).
     *
     * For a motion photo the bytes split into primary = `[0, videoOffset)` and trailer =
     * `[videoOffset, EOF)`. The primary is written to a temp, GPS/EXIF-stripped via [ExifHelper],
     * then the original trailer is appended byte-for-byte. The trailer length is unchanged, so a
     * recipient's `fileSize - videoLength` math still resolves and the motion (plus the motion XMP
     * the primary still carries) survives.
     *
     * Safety: if the file is a confirmed motion photo but the split or primary strip can't complete
     * cleanly, the byte-exact materialized copy is returned so the upload preserves the motion
     * rather than risk a corrupt primary. Returns null only when detection finds no motion photo.
     */
    private fun stripImagePreservingMotion(localUri: String, stripConfig: MetadataStripConfig): File? {
        // Materialize the source so the tail scan and the split read real bytes, not a stream.
        val source = try {
            val tmp = File.createTempFile("motion_src_", ".bin", context.cacheDir)
            context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: run { tmp.delete(); return null }
            tmp
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Motion-photo materialize failed for $localUri: ${e.message}")
            return null
        }

        val info = MotionPhotoUtil.detect(source)
        if (info == null) {
            // Not a motion photo — let the caller take the ordinary strip path.
            source.delete()
            return null
        }

        // From here the file IS a motion photo: never return null (that would invite the
        // destructive plain strip). On any failure fall back to the byte-exact source copy.
        var primary: File? = null
        try {
            primary = File.createTempFile("motion_primary_", ".jpg", context.cacheDir)
            RandomAccessFile(source, "r").use { raf ->
                primary!!.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = info.videoOffset
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            // Strip GPS/EXIF from the primary only. Feed it through the existing temp-file strip
            // via a file:// URI so the same tag set + behaviour applies.
            val strippedPrimary = ExifHelper.stripToTempFile(
                context, Uri.fromFile(primary).toString(), stripConfig,
            )
            // stripToTempFile returns null on no-op or error; in either case keep the primary bytes
            // we already split so the concatenation still yields an intact motion photo.
            val primaryForJoin = strippedPrimary ?: primary!!

            val joined = File.createTempFile("motion_out_", ".jpg", context.cacheDir)
            joined.outputStream().use { out ->
                primaryForJoin.inputStream().use { it.copyTo(out) }
                RandomAccessFile(source, "r").use { raf ->
                    raf.seek(info.videoOffset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            strippedPrimary?.delete()
            primary?.delete()
            source.delete()
            return joined
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Motion-photo split/strip failed for $localUri; uploading byte-exact: ${e.message}")
            primary?.delete()
            // Byte-exact fallback: the untouched materialized copy keeps the motion intact.
            return source
        }
    }

    /**
     * Builds the photo xAttr Camera/Location + display-dimension metadata for [item] from its
     * ORIGINAL [sourceUri] (read before any strip pass). Every field is gated against the strip
     * config so the encrypted xAttr can never carry data the on-file strip removed:
     *
     *  - Camera.Orientation: always emitted when known (never stripped; the web needs it to display).
     *  - Camera.Device: only when camera info is NOT stripped.
     *  - Camera.CaptureTime: [item].dateTaken — already floored to upload time when timestamps are
     *    stripped (see [uploadOne]), else the real capture time.
     *  - Location: only when GPS is NOT stripped.
     *  - displayWidth/Height: W/H swapped when EXIF orientation / video rotation is 90/270.
     */
    private fun buildXAttrMetadata(
        sourceUri: String,
        item: eu.akoos.photos.domain.entity.LocalMediaItem,
        stripOnUpload: Boolean,
        stripConfig: MetadataStripConfig,
    ): eu.akoos.photos.data.repository.drive.UploadXAttrMetadata {
        val stripGps = stripOnUpload && stripConfig.stripGps
        val stripCamera = stripOnUpload && stripConfig.stripCameraInfo
        // ISO_INSTANT (e.g. 2023-01-15T10:30:00Z), matching Drive Android's DateTimeFormatter.
        val captureTimeIso = item.dateTaken.takeIf { it > 0L }?.let { ms ->
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(ms))
        }
        return when {
            item.mimeType.startsWith("video/") -> {
                var rotation = 0
                runCatching {
                    android.media.MediaMetadataRetriever().use { r ->
                        r.setDataSource(context, Uri.parse(sourceUri))
                        rotation = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                        )?.toIntOrNull() ?: 0
                    }
                }
                val swap = rotation == 90 || rotation == 270
                eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(
                    // Video carries no EXIF GPS/camera tags here; the container location atom is
                    // stripped separately. Map rotation→EXIF orientation so Camera.Orientation
                    // still describes the displayed frame.
                    cameraOrientation = rotationToExifOrientation(rotation),
                    cameraCaptureTimeIso = captureTimeIso,
                    displayWidth = if (swap) item.height.takeIf { it > 0 } else item.width.takeIf { it > 0 },
                    displayHeight = if (swap) item.width.takeIf { it > 0 } else item.height.takeIf { it > 0 },
                )
            }
            item.mimeType.startsWith("image/") -> {
                val meta = ExifHelper.readMetadata(context, sourceUri)
                val orientation = meta.orientation
                    ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                val swap = orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE
                eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(
                    latitude = if (!stripGps) meta.gpsLatitude else null,
                    longitude = if (!stripGps) meta.gpsLongitude else null,
                    cameraOrientation = orientation,
                    cameraCaptureTimeIso = captureTimeIso,
                    cameraDevice = if (!stripCamera) meta.model else null,
                    subjectCoordinates = if (!stripCamera) readSubjectCoordinates(sourceUri) else null,
                    displayWidth = if (swap) item.height.takeIf { it > 0 } else item.width.takeIf { it > 0 },
                    displayHeight = if (swap) item.width.takeIf { it > 0 } else item.height.takeIf { it > 0 },
                )
            }
            else -> eu.akoos.photos.data.repository.drive.UploadXAttrMetadata()
        }
    }

    /** Maps a video container rotation (degrees) to the equivalent EXIF orientation tag so the
     *  xAttr Camera.Orientation field uses the same 1..8 vocabulary as photos. */
    private fun rotationToExifOrientation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }

    /** Reads EXIF SubjectArea (3 or 4 comma-separated ints) and converts it to the
     *  [Top,Left,Bottom,Right] rectangle Drive's xAttr SubjectCoordinates expects, matching
     *  Drive Android's Rectangle.fromCenter. Returns null when absent or malformed. */
    private fun readSubjectCoordinates(uri: String): IntArray? = runCatching {
        val raw = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            androidx.exifinterface.media.ExifInterface(it)
                .getAttribute(androidx.exifinterface.media.ExifInterface.TAG_SUBJECT_AREA)
        }?.takeUnless { it.isEmpty() } ?: return null
        val a = raw.split(",").map { it.trim().toInt() }
        val (cx, cy, w, h) = when (a.size) {
            3 -> listOf(a[0], a[1], a[2], a[2])
            4 -> listOf(a[0], a[1], a[2], a[3])
            else -> return null
        }
        // Rectangle.fromCenter: top, left, bottom, right.
        intArrayOf(cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2)
    }.getOrNull()

    private fun computeSha1(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ""
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
