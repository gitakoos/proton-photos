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

package eu.akoos.photos.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.upload.MirrorOverwriteJournal
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.StorageFullException
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import eu.akoos.photos.util.Mp4CreationTime
import eu.akoos.photos.util.UltraHdrUtil
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val UPLOAD_TAG = "UploadUseCase"

/** cacheDir entries with one of these name prefixes are upload-path temps. Age-swept once per pass
 *  so a permanently-failing file's temp (or one stranded by a process death) can't linger forever.
 *  `upload_` resume dirs are covered here too, backstopping the periodic CachePruneWorker sweep. */
private val UPLOAD_TEMP_PREFIXES = listOf(
    // `mirror_bak_` sweeps any legacy mirror-overwrite backup left in cacheDir by an older build. The
    // crash-consistent backups now live in filesDir/<MirrorOverwriteJournal.DIR_NAME>, outside cacheDir,
    // so this cacheDir sweep never reaches the journal and can never delete the only intact copy.
    "upload_", "videocompress_", "revision_", "stripped_", "compressed_", "mirror_bak_",
)

/** Only upload temps older than this are swept, so an in-flight upload or a recently-preserved
 *  resume dir (both freshly created) is never removed; the age gate is the whole safety. */
private val STALE_UPLOAD_TEMP_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(3)

/**
 * Process-wide gate serialising video transcodes to one at a time. A device has a single
 * hardware video encoder, so two concurrent transcodes (possible with [UPLOAD_PARALLELISM]
 * in-flight uploads) would fail or thrash. File-level so the limit holds across every upload
 * coroutine and batch regardless of how many use-case instances exist.
 */
private val videoTranscodeGate = Semaphore(1)

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
 *
 * `WaitingForWifi` and `PreparingBackup` are synthetic frames emitted when the auto-sync drain is
 * deferred (Wi-Fi-only on but off Wi-Fi / cloud listing not settled after a reinstall), so the
 * queued-but-idle state reads as "waiting", not "broken". They carry no per-file payload.
 *
 * `StorageFull` is the same kind of frame for the one deferral the user has to act on: the Drive is
 * out of space, so the batch stopped and no later trigger can get past it either. It replaces the
 * closing `Idle` frame rather than preceding it, because `Idle` is what observers clear the panel
 * on, and a reason wiped in the same breath would leave the backup looking merely finished.
 */
enum class UploadStatus { Queued, Encrypting, Uploading, Done, Failed, Idle, WaitingForWifi, PreparingBackup, StorageFull }

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
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
    private val uploadAlbumTargetDao: eu.akoos.photos.data.db.dao.UploadAlbumTargetDao,
    @ApplicationContext private val context: Context,
    @eu.akoos.photos.di.AppScope private val appScope: kotlinx.coroutines.CoroutineScope,
) {
    private val mutex = Mutex()

    /** Crash-consistent backup of the bytes a mirror overwrite is about to replace. Lives in
     *  filesDir (never cacheDir), so a process killed mid-truncate is recovered on the next launch
     *  by [eu.akoos.photos.data.upload.MirrorOverwriteJournal]. Lazy so nothing reads filesDir until
     *  a mirror overwrite actually runs. */
    private val mirrorOverwriteJournal by lazy {
        eu.akoos.photos.data.upload.MirrorOverwriteJournal(
            File(context.filesDir, eu.akoos.photos.data.upload.MirrorOverwriteJournal.DIR_NAME),
        )
    }

    /**
     * Guards the one-time stale-claim recovery. A row left UPLOADING by a killed process is
     * invisible to both the LOCAL_ONLY selector and reconcile, so it would never retry. On the
     * first upload pass of this process (inside [mutex] and before any row is selected or claimed),
     * every UPLOADING row with no cloud copy is reset to LOCAL_ONLY, since nothing is genuinely
     * in flight in a fresh process. Running once and before the first claim means a live claim made
     * later in the same process is never clobbered.
     */
    private val staleClaimsRecovered = AtomicBoolean(false)

    /**
     * Cooperative "stop" flag for the current in-flight batch. Set by [requestStop] when the user
     * taps a cancel button (Activity monitor / avatar pill / device-folder back-up). The batch loop
     * in [invoke] checks it at the item boundary, BEFORE claiming/starting the next pending row,
     * and breaks, so remaining queued items are never started. Deliberately NOT wired into any
     * cancellation: an already-running [uploadOne] runs its encrypt + CDN work to completion, because
     * interrupting an in-flight native PGP call (libgojni) crashes the process with a native SIGSEGV.
     * Cleared at the start and end of every [invoke] so a stop affects only the batch that was
     * running when the user tapped stop, never a later auto-backup trigger.
     */
    private val stopRequested = AtomicBoolean(false)

    /**
     * One-shot "the user asked for this run" flag, set by [requestManualRun] when Sync now is tapped.
     * The next [invoke] consumes it and lets the folder sweep's own rows through even with auto-backup
     * off, which is the only thing it changes: the folder selection, the Wi-Fi guard and the listing
     * guards all still apply exactly as they do to an automatic pass.
     *
     * A flag rather than a queue-source rewrite because "back up now" is a fact about this RUN, not
     * about any photo. Stamping the rows MANUAL instead would also make them bypass the folder filter
     * from then on, so one tap would permanently pull every out-of-scope photo into the backup.
     */
    private val manualRunRequested = AtomicBoolean(false)

    /**
     * Mark the next batch as user-requested. Called by Sync now before it hands the run to the
     * worker, so a one-off backup still works while auto-backup is switched off.
     */
    fun requestManualRun() {
        manualRunRequested.set(true)
    }

    /**
     * Request a graceful stop of the current upload batch. Prevents any not-yet-started queued item
     * from beginning; the item currently in transit finishes and is backed up. Safe to call when no
     * batch is running (the flag is cleared at the start of the next [invoke]).
     *
     * This is the single user-cancel chokepoint (every cancel button and the notification Stop route
     * here), so it is also where a cancelled manual "back up now" is de-queued: the not-yet-started
     * MANUAL rows have their queued intent cleared so they do NOT resurface on the next natural
     * trigger. ALBUM_ADD rows stay queued (an album-add must still upload to join its album) and
     * AUTO_FOLDER rows stay queued (continuous folder backup resumes on the next pass, so cancel is a
     * pause for auto but a removal for manual). The clear is guarded to never touch an UPLOADING claim
     * or a SYNCED row, so the in-flight item (which finishes) is untouched. Fired on [appScope] because
     * this entry point is a plain synchronous call from receivers / view models; a de-queue failure is
     * logged, not propagated (a lingering MANUAL flag at worst re-uploads a file the user cancelled).
     */
    fun requestStop() {
        stopRequested.set(true)
        appScope.launch {
            runCatching { syncStateRepo.clearManualQueue() }
                .onSuccess { cleared ->
                    if (cleared > 0) {
                        Log.d(UPLOAD_TAG, "User cancel: de-queued $cleared not-yet-started manual upload(s)")
                    }
                }
                .onFailure { e -> Log.w(UPLOAD_TAG, "clearManualQueue on cancel failed: ${e.message}") }
        }
    }

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
     * A cooperative stop (see [requestStop]) can prevent not-yet-started items from beginning, but
     * never interrupts an item already in flight; its encrypt/CDN work always runs to completion so
     * a mid-encrypt cancel can't tear down the native PGP runtime. The stop flag is cleared here at
     * the start of every run so a stop requested during an earlier batch does not carry over.
     */
    suspend operator fun invoke(
        userId: UserId,
    ): Result = mutex.withLock {
        // Fresh batch: clear any stop left over from a previous run so a later natural trigger
        // (new photo / periodic / app-open) resumes normally. The flag only affects the batch that
        // was in flight when the user tapped stop.
        stopRequested.set(false)
        // Recover claims stranded by a process death before selecting anything. Runs once per
        // process, inside the lock and ahead of every row claim below, so it resets only rows a
        // prior (now-dead) process left UPLOADING with no cloud copy, never a live claim this
        // process makes later. Fresh process = nothing is genuinely uploading, so such a row is
        // safe to return to LOCAL_ONLY for a retry. An editor upload sets UPLOADING via a separate
        // path and only after the user actively saves, which cannot precede this first pass.
        if (staleClaimsRecovered.compareAndSet(false, true)) {
            runCatching { syncStateRepo.resetStaleUploadingClaims() }
                .onFailure { e -> Log.w(UPLOAD_TAG, "Stale-claim recovery failed: ${e.message}") }
        }

        // Retry any uncommitted-node cleanup deletes stranded by an earlier upload failure whose
        // own delete also failed. Runs before the Wi-Fi / listing / folder early-returns below so a
        // queued orphan is swept even when there is nothing to upload; it is a cheap no-op when the
        // queue is empty (one DataStore read, no network) and never blocks the actual upload work.
        runCatching { cloudRepo.retryPendingOrphanDeletes(userId) }
            .onFailure { e -> Log.w(UPLOAD_TAG, "Pending orphan-delete sweep failed: ${e.message}") }

        // Age-sweep abandoned upload temps. A retryable failure deliberately preserves its resume
        // dir, and a process death can strand any other upload temp; none are otherwise age-swept, so
        // a permanently-failing file's temp would linger until the OS clears the cache. Only entries
        // older than STALE_UPLOAD_TEMP_MS are removed, so a live in-flight upload (or a recent
        // preserved resume dir) is never touched. Cheap listFiles() no-op when nothing is stale.
        sweepStaleUploadTemps()

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

        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false
        val compressOnUpload = prefs[SettingsKeys.COMPRESS_ON_UPLOAD] ?: false
        val compressTier = UploadCompressionTier
            .fromOrdinalOrDefault(prefs[SettingsKeys.COMPRESS_UPLOAD_TIER] ?: -1)
        val mirrorStripToLocal = prefs[SettingsKeys.MIRROR_STRIP_TO_LOCAL] ?: false
        val mirrorCompressToLocal = prefs[SettingsKeys.MIRROR_COMPRESS_TO_LOCAL] ?: false
        val compressVideosOnUpload = prefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] ?: false
        val renameToCaptureDate = prefs[SettingsKeys.RENAME_TO_CAPTURE_DATE] ?: false
        val deleteLocalAfterBackup = prefs[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] ?: false
        // Authorship has no upload preference of its own, so it rides on the software one: the
        // backup keeps removing the software, artist and copyright tags as a single choice, and the
        // artist/copyright split stays confined to the manual picker where the user ticks it.
        val stripSoftwareInfo = prefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false
        val stripConfig = MetadataStripConfig(
            stripGps = prefs[SettingsKeys.STRIP_GPS] ?: false,
            stripCameraInfo = prefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
            stripTimestamp = prefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
            stripSoftwareInfo = stripSoftwareInfo,
            stripAuthorship = stripSoftwareInfo,
        )

        // Drain queued album targets for photos that already finished uploading (their row is
        // SYNCED, so uploadOne below won't run for them). This covers the gap where a photo
        // backed up before its album-add succeeded — or a prior add failed — and guarantees the
        // add is eventually applied across restarts and partial failures. The target table is the
        // single source of truth: one row per (localUri, albumLinkId). A row with no cloudFileId yet
        // is skipped (uploadOne joins it once uploaded); a successful join drops just that pair, a
        // failed one leaves it for the next pass. Runs BEFORE the no-folders early-out so a queued
        // add still lands even when backup is otherwise idle.
        for (target in uploadAlbumTargetDao.getAll()) {
            val cloudId = syncStateRepo.getByUri(target.localUri)?.cloudFileId ?: continue
            runCatching { cloudRepo.addPhotosToAlbum(userId, target.albumLinkId, listOf(cloudId)) }
                .onSuccess {
                    uploadAlbumTargetDao.deleteTarget(target.localUri, target.albumLinkId)
                    Log.d(UPLOAD_TAG, "Drained pending add: $cloudId → album ${target.albumLinkId}")
                }
                .onFailure { e -> Log.w(UPLOAD_TAG, "Pending-add drain failed for ${target.localUri}: ${e.message}") }
        }

        // One snapshot of every SyncState row, read once up front so the explicit-action bypass
        // below and the pending selection further down share it (no second Flow.first()).
        val allStates = syncStateRepo.observeAll(userId).first()

        // Explicit user-action bypass. A row queued as MANUAL ("back up now") or ALBUM_ADD (added to
        // a cloud album) is something the user asked for regardless of network or folder selection, so
        // its presence bypasses the Wi-Fi-only / listing / no-folders early-returns below. A queued
        // AUTO_FOLDER row does NOT bypass (it is an ordinary folder backup, subject to the guards).
        // The DB queue is the single source of truth here: the row's queueSource is what marks it.
        val explicitBypassUris: Set<String> = allStates.asSequence()
            .filter {
                it.queued && (
                    it.queueSource == eu.akoos.photos.domain.entity.QueueSource.MANUAL ||
                        it.queueSource == eu.akoos.photos.domain.entity.QueueSource.ALBUM_ADD
                    )
            }
            .map { it.localUri }
            .toSet()
        val hasExplicitBypass = explicitBypassUris.isNotEmpty()

        // Wi-Fi-only enforcement for the auto-sync drain (absent key = ON, matching the rest of the
        // app). ANY-Wi-Fi semantics: a metered Wi-Fi (hotspot, some routers) is still allowed, but
        // mobile data is not. Centralised here so the inline callers (pull-to-refresh, Settings sync,
        // editor) get the same guard the worker relies on. An explicit forced upload (a queued
        // album-add / "back up now") bypasses it — the user asked for that one regardless of network.
        val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
        if (wifiOnly && !hasExplicitBypass && !networkObserver.currentlyOnWifi()) {
            Log.d(UPLOAD_TAG, "Wi-Fi-only on and not on Wi-Fi — skipping auto-sync upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.WaitingForWifi, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }

        // After a reinstall the cloud listing repopulates page by page; until it has been walked
        // end-to-end at least once, a LOCAL_ONLY row may just be a photo already on Drive whose
        // listing entry hasn't been re-fetched yet — uploading it now would duplicate it. Defer the
        // bulk upload until the listing is known-complete; explicit forced uploads still go through.
        val initialListingComplete = prefs.asMap().any { (k, v) ->
            k.name.startsWith("photo_listing_ever_complete_${userId.id}_") && v == true
        }
        // Second half of the same guard: even once the listing is complete, a content-hash reconcile
        // must have actually RUN against that complete listing before the LOCAL_ONLY rows are safe to
        // drain. The moment the listing finishes, the ever-complete flag opens; if the upload drained
        // here before the post-completion reconcile paired the rows, photos already on Drive would
        // re-upload as duplicates. The sync worker runs reconcile (which sets this flag) before the
        // upload, so it settles within the same pass — no infinite defer.
        val pairingSettled = prefs[SettingsKeys.pairingSettledKey(userId.id)] ?: false
        if ((!initialListingComplete || !pairingSettled) && !hasExplicitBypass) {
            Log.d(UPLOAD_TAG, "Cloud listing/pairing not settled yet — deferring bulk upload to avoid duplicates")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.PreparingBackup, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }

        if (!backupEverything && (selectedFolders == null || selectedFolders.isEmpty()) && !hasExplicitBypass) {
            Log.d(UPLOAD_TAG, "No backup folders configured — skipping upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, 0, 0))
            return@withLock Result(attempted = 0, successCount = 0)
        }

        // Recovery for force-queued album-adds. A photo the user added to an album has a row in the
        // upload_album_target table. An interrupted prior pass can leave its sync_state row stranded in
        // a non-LOCAL_ONLY status while it still has no cloud copy: that row is invisible to BOTH the
        // LOCAL_ONLY filter below and the cloudFileId-keyed album drain above, so the photo shows
        // "uploading" forever and redoing the album-add never re-queues it. Reset such rows to a clean
        // LOCAL_ONLY so this pass uploads them and then joins the album. HIDDEN is left untouched (the
        // user moved it out of backup on purpose), matching ForceUploadLocalUrisUseCase. Manual/stuck
        // UPLOADING rows are covered by resetStaleUploadingClaims + the stranded-intent recovery, so no
        // separate manual set is needed here.
        val albumTargetUris: Set<String> = uploadAlbumTargetDao.getAll().map { it.localUri }.toSet()
        val strandedForced = allStates.filter {
            it.localUri in albumTargetUris &&
                it.cloudFileId == null &&
                it.status != SyncStatus.LOCAL_ONLY &&
                it.status != SyncStatus.HIDDEN
        }
        if (strandedForced.isNotEmpty()) {
            strandedForced.forEach {
                syncStateRepo.upsert(
                    it.copy(status = SyncStatus.LOCAL_ONLY, backedUpAtMs = null, lastSyncSuccessMs = null),
                    userId,
                )
            }
            eu.akoos.photos.util.SyncDiagnostics.log(
                "re-queued ${strandedForced.size} album-add upload(s) stranded by an interrupted pass",
            )
        }
        val strandedForcedUris = strandedForced.map { it.localUri }.toSet()

        // Queue-gated selection: a LOCAL_ONLY row is uploaded only when it also carries an explicit
        // queued intent (manual / album / auto-folder / editor; reconcile stamps AUTO_FOLDER for a
        // folder-selected backup, so an ordinary auto-backup is queued too). This replaces the old
        // "every LOCAL_ONLY (+ forced) row" rule; the previous forcedUploadUris / strandedForced
        // inclusion is now subsumed because manual and album rows are queued. The stranded-forced
        // recovery is still ORed in explicitly: those rows were just reset to LOCAL_ONLY from the
        // stale pre-upsert snapshot and must upload to join their album regardless of the snapshot's
        // queue view.
        var pending = allStates.filter {
            (it.status == SyncStatus.LOCAL_ONLY && it.queued) || it.localUri in strandedForcedUris
        }.map {
            if (it.localUri in strandedForcedUris) {
                it.copy(status = SyncStatus.LOCAL_ONLY, backedUpAtMs = null, lastSyncSuccessMs = null)
            } else {
                it
            }
        }

        // Consumed HERE, past every early return above, not where the other prefs are read. A Sync now
        // tap can land on a batch that defers for Wi-Fi or for an unsettled listing, and consuming the
        // flag on the way into one of those would spend the user's request on a run that uploaded
        // nothing: the deferred rows would then be held by the switch on every later trigger, which is
        // the same silent nothing this flag exists to prevent. Surviving a deferral means the next run
        // that actually gets this far honours the tap.
        //
        // Absent AUTO_SYNC = ON, matching every other reader of this key.
        val manualRun = manualRunRequested.getAndSet(false)
        val autoSync = manualRun || prefs[SettingsKeys.AUTO_SYNC] != false

        // The auto-backup switch is enforced here, on the queue, not only on the background triggers.
        // Turning it off leaves the folder selection intact so re-enabling restores it, so the folder
        // filter below cannot express "off" at all; without this step a foreground refresh, which
        // kicks a run of its own, uploads the whole selection with the switch showing off. Only the
        // rows the folder sweep queued are dropped. An explicit intent, and a photo owed to an album,
        // still upload: each is an instruction about one photo, while the switch is a statement about
        // the sweep. The rows keep their queued flag, so flipping the switch back resumes them.
        if (!autoSync) {
            val before = pending.size
            pending = pending.filter { state ->
                !QueueSource.isAutomatic(state.queueSource) || state.localUri in albumTargetUris
            }
            if (pending.size != before) {
                Log.d(UPLOAD_TAG, "Auto-backup off: held ${before - pending.size}/$before queued item(s)")
            }
        }

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
                // A photo queued by an explicit user action (manual "back up now" or album-add) always
                // uploads regardless of the folder selection: it must back up to satisfy that action /
                // join its album. An AUTO_FOLDER row is still folder-filtered. albumTargetUris covers a
                // stranded album row whose snapshot queueSource may not read ALBUM_ADD yet.
                if (isExplicitAction(state.queueSource) || state.localUri in albumTargetUris) return@filter true
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

        // Source URIs of the photos that upload cleanly, for the History thumbnails. Thread-safe
        // because the parallel tasks add to it concurrently.
        val successUris = java.util.concurrent.ConcurrentLinkedQueue<String>()

        try {
            coroutineScope {
                val jobs = pending.map { state ->
                    async {
                        // Item boundary. Check the cooperative stop flag and the storage-full flag
                        // BEFORE claiming/starting this item, so a stop keeps the remaining queued
                        // items from ever beginning while an already-running upload finishes. This is
                        // a plain boolean, never a cancellation; an in-flight uploadOne is untouched.
                        // ensureActive() only guards genuine scope teardown (process death); a normal
                        // stop leaves the scope alive so it never fires here.
                        coroutineContext.ensureActive()
                        if (stopRequested.get() || storageFullHit.get()) return@async

                        uploadSemaphore.withPermit {
                            // Re-check after acquiring the permit — earlier tasks may have set
                            // storageFullHit / stopRequested while we were queued.
                            coroutineContext.ensureActive()
                            if (stopRequested.get() || storageFullHit.get()) return@withPermit

                            uploadOne(
                                userId = userId,
                                state = state,
                                totalCount = totalCount,
                                manualRun = manualRun,
                                albumTargetUris = albumTargetUris,
                                albumOptInFolders = albumOptInFolders,
                                stripOnUpload = stripOnUpload,
                                compressOnUpload = compressOnUpload,
                                compressTier = compressTier,
                                mirrorStripToLocal = mirrorStripToLocal,
                                mirrorCompressToLocal = mirrorCompressToLocal,
                                compressVideosOnUpload = compressVideosOnUpload,
                                renameToCaptureDate = renameToCaptureDate,
                                deleteLocalAfterBackup = deleteLocalAfterBackup,
                                stripConfig = stripConfig,
                                existingAlbumsByName = existingAlbumsByName,
                                albumCache = albumCache,
                                albumCacheMutex = albumCacheMutex,
                                successCount = successCount,
                                successUris = successUris,
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

        // Clear the cooperative stop so it only affected this batch. A later natural trigger
        // (new photo / periodic / app-open) then resumes the still-pending items normally.
        val wasStopped = stopRequested.getAndSet(false)
        if (wasStopped) {
            Log.d(UPLOAD_TAG, "Upload batch stopped by user; remaining queued items left pending for a later trigger")
        }

        val finalSuccess = successCount.get()
        Log.d(UPLOAD_TAG, "Upload complete: $finalSuccess/${pending.size} succeeded")
        transferCenter.log(
            eu.akoos.photos.data.transfer.TransferCenter.Kind.UPLOAD, finalSuccess,
            uris = successUris.toList(),
        )
        // Refresh the consent notification with the latest pending queue. Same
        // call MainActivity.onResume fires so an externally deleted file (file
        // manager, OS trash flush) gets reconciled the moment the user opens the
        // app even without a worker run. Guarded like the other two call sites: its
        // DataStore read and write can throw, and a throw here reaches SyncWorker as
        // a failed run, turning a batch that uploaded everything into a retry.
        runCatching { pendingDeleteNotif() }

        // Final frame so the UI clears any in-flight panel. A batch the Drive stopped closes on
        // StorageFull instead: the rows stay queued and every later trigger will hit the same wall,
        // so this is the only moment anything can say why the backup went quiet.
        val closingStatus = if (storageFullHit.get()) UploadStatus.StorageFull else UploadStatus.Idle
        _progress.tryEmit(UploadProgress("", "", closingStatus, totalCount, totalCount))
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
        /** This batch was asked for by the user, so the live auto-backup re-check does not apply. */
        manualRun: Boolean,
        albumTargetUris: Set<String>,
        albumOptInFolders: Set<String>,
        stripOnUpload: Boolean,
        compressOnUpload: Boolean,
        compressTier: UploadCompressionTier,
        mirrorStripToLocal: Boolean,
        mirrorCompressToLocal: Boolean,
        compressVideosOnUpload: Boolean,
        renameToCaptureDate: Boolean,
        deleteLocalAfterBackup: Boolean,
        stripConfig: MetadataStripConfig,
        existingAlbumsByName: MutableMap<String, String>,
        albumCache: MutableMap<String, String>,
        albumCacheMutex: Mutex,
        successCount: AtomicInteger,
        successUris: MutableCollection<String>,
        finishedCount: AtomicInteger,
        onStorageFull: () -> Unit,
    ) {
        var strippedFile: File? = null
        var compressedFile: File? = null
        // A temp holding a timestamp-floored copy of an uncompressed video (Fix: backup video mvhd
        // floor). Tracked alongside the other temps so it is cleaned up on both success and failure.
        var tsFloorFile: File? = null
        try {
            val rawLocalItem = localRepo.queryByUri(state.localUri)
            if (rawLocalItem == null) {
                Log.w(UPLOAD_TAG, "Local item not found for URI: ${state.localUri}")
                return
            }
            // Skip items whose folder is no longer in the backup selection. Read the selection LIVE
            // (not the snapshot captured when the batch began) so unchecking a folder mid-sync stops
            // its not-yet-started uploads, not just the next batch. Items already past this point
            // finish; an explicit user action (queueSource MANUAL / ALBUM_ADD, or a photo with a queued
            // album target) always proceeds so a "back up now" runs and an album-add can join its
            // album. An AUTO_FOLDER row is still folder-filtered here as a backstop against a mid-batch
            // toggle.
            if (!isExplicitAction(state.queueSource) && state.localUri !in albumTargetUris) {
                val livePrefs = context.settingsDataStore.data.first()
                // Same live read for the auto-backup switch, so turning it off part-way through a
                // long batch stops the items that have not started rather than only the next run.
                // A user-requested run is exempt: the switch was already off when they asked.
                if (!manualRun && QueueSource.isAutomatic(state.queueSource) && livePrefs[SettingsKeys.AUTO_SYNC] == false) {
                    Log.d(UPLOAD_TAG, "Skipping ${rawLocalItem.displayName}: auto-backup was switched off")
                    return
                }
                val liveBackupEverything = livePrefs[SettingsKeys.BACKUP_EVERYTHING] ?: false
                val liveSelected = livePrefs[SettingsKeys.SYNC_FOLDER_NAMES]
                val liveExcluded = livePrefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet()
                val bucket = rawLocalItem.bucketName
                val stillEligible = if (liveBackupEverything) {
                    bucket == null || bucket !in liveExcluded
                } else {
                    !liveSelected.isNullOrEmpty() && (bucket == null || bucket in liveSelected)
                }
                if (!stillEligible) {
                    Log.d(UPLOAD_TAG, "Skipping ${rawLocalItem.displayName}: folder '${rawLocalItem.bucketName}' no longer in the backup selection")
                    return
                }
            }
            // Atomically claim this row before any hashing or network work. Two upload passes (the
            // one-shot and the content-observer WorkManager names) run in parallel, guarded only by
            // an in-process mutex that a process restart loses, so both could otherwise select and
            // upload the same LOCAL_ONLY row, producing a real Drive duplicate. The claim flips it to
            // UPLOADING only while it is still LOCAL_ONLY: exactly one pass gets the 1, the other gets
            // 0 and skips. A claimed (UPLOADING) row is invisible to both the selector and reconcile.
            // On upload failure the catch below resets it to LOCAL_ONLY so it retries; on success the
            // SYNCED upsert transitions it.
            if (syncStateRepo.claimForUpload(state.localUri) != 1) {
                Log.d(UPLOAD_TAG, "Skipping ${rawLocalItem.displayName}: already claimed by another pass or no longer local-only")
                return
            }
            // Once claimed, this item runs to completion. No stop/cancel check is placed between the
            // claim and the encrypt/upload below: interrupting an in-flight upload would tear down a
            // native PGP call and crash the process. A user stop is honoured only at the item boundary
            // in invoke() (before the claim), so a claimed item is never abandoned mid-pipeline. A row
            // stranded UPLOADING by a genuine process death is recovered on the next pass.
            // Optional rename: derive cloud displayName from the source's capture timestamp
            // (MediaStore DATE_TAKEN). The on-device file keeps its own name unless "mirror to
            // local" is on, which renames it to match — deferred until AFTER the upload succeeds
            // (below) so a failed upload never renames a file that has no Drive copy. Strip-on-upload
            // runs against the bytes independently, so a stripped + renamed photo still gets erased.
            var mirrorRenameTarget: String? = null
            val renamedItem = if (renameToCaptureDate) {
                val ext = rawLocalItem.displayName.substringAfterLast('.', "")
                val captureMs = rawLocalItem.dateTaken.takeIf { it > 0L } ?: System.currentTimeMillis()
                val newBase = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(captureMs))
                val newName = if (ext.isNotEmpty()) "$newBase.$ext" else newBase
                Log.d(UPLOAD_TAG, "Rename-on-upload: '${rawLocalItem.displayName}' → '$newName'")
                if (mirrorStripToLocal) mirrorRenameTarget = newName
                rawLocalItem.copy(displayName = newName)
            } else {
                rawLocalItem
            }
            // Strip-timestamp promises capture-time removal, but dateTaken also feeds the
            // Drive captureTime and the xAttr ModificationTime (PhotoUploadService). Floor
            // it to upload time so the cloud metadata can't reconstruct when the shot was
            // actually taken. The on-device MediaStore row is untouched.
            val stripCaptureTime = stripOnUpload && stripConfig.stripTimestamp
            // Read the file's own EXIF DateTimeOriginal only when it can actually change the choice:
            // a non-stripped image whose MediaStore DATE_TAKEN was absent (0), so dateTaken fell back
            // to the import time (DATE_ADDED). For an image received from another app the file's own
            // EXIF often still carries the real capture date. Best-effort: any read/parse failure
            // yields 0 and the existing fallback date is kept. This I/O stays here; the choice below
            // is a pure function.
            val exifDateTimeOriginalMs: Long? = if (
                !stripCaptureTime &&
                renamedItem.mimeType.startsWith("image/") &&
                !renamedItem.dateTakenIsExplicit
            ) {
                runCatching {
                    val raw = ExifHelper.readMetadata(context, state.localUri).dateTimeOriginal
                    if (raw != null) {
                        ExifDateFormat.fromExifLocal(raw, ZoneId.systemDefault()) ?: 0L
                    } else {
                        0L
                    }
                }.getOrDefault(0L)
            } else {
                null
            }
            val resolvedCaptureTime = resolveUploadCaptureTimeMs(
                mediaStoreDateTakenMs = renamedItem.dateTaken,
                dateTakenIsExplicit = renamedItem.dateTakenIsExplicit,
                exifDateTimeOriginalMs = exifDateTimeOriginalMs,
                stripTimestamp = stripCaptureTime,
                nowMs = System.currentTimeMillis(),
            )
            if (exifDateTimeOriginalMs != null && exifDateTimeOriginalMs > 0L) {
                Log.d(UPLOAD_TAG, "Corrected capture date from EXIF for ${renamedItem.displayName}")
            }
            val localItem = if (resolvedCaptureTime != renamedItem.dateTaken) {
                renamedItem.copy(dateTaken = resolvedCaptureTime)
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
            val strippedUploadUri: String = if (stripOnUpload && localItem.mimeType.startsWith("image/")) {
                // Motion Photos carry an MP4 appended after the primary still. A plain ExifInterface
                // rewrite-strip drops that trailer (and the motion is lost), so detect first and, for
                // a motion photo, strip only the primary's EXIF into a temp while re-attaching the
                // original trailer byte-for-byte. This runs FIRST and authoritatively (MotionPhotoUtil
                // .detect) so a motion photo can never reach the in-place mirror wipe below.
                val motionTemp = stripImagePreservingMotion(state.localUri, stripConfig)
                // An Ultra HDR still appends its gain map as a second image after the primary, the same
                // shape as a motion trailer, so every ordinary strip route below drops it. Decided HERE,
                // ahead of the in-place mirror wipe, because that wipe overwrites the on-device original
                // and cannot be undone. The route is taken only when the platform can prove the rebuilt
                // file still decodes WITH its gain map; on any doubt it yields null and the ordinary strip
                // runs, because a lost HDR rendition is recoverable and a shipped GPS tag is not.
                val gainMapTemp = if (motionTemp == null &&
                    eu.akoos.photos.data.upload.UploadImageCompressor.attemptsGainMapPreservingStrip(
                        localItem.mimeType,
                        stripOnUpload,
                        Build.VERSION.SDK_INT,
                        hasGainMap = { hasGainMapUpload(state.localUri) },
                    )
                ) {
                    stripImagePreservingGainMap(state.localUri, stripConfig)
                } else {
                    null
                }
                if (motionTemp != null) {
                    strippedFile = motionTemp
                    Log.d(UPLOAD_TAG, "Motion Photo primary stripped, trailer preserved for ${localItem.displayName}")
                    // Mirror: a motion photo can't be wiped in place (an EXIF rewrite drops its
                    // trailer), so overwrite the on-device original with the whole motion-preserving
                    // stripped file instead — the local loses its metadata too and byte-matches the
                    // cloud. Silent with all-files; a refused write leaves the original intact.
                    if (mirrorStripToLocal && overwriteLocalInPlace(state.localUri, motionTemp)) {
                        Log.d(UPLOAD_TAG, "Mirror strip: on-device motion photo wiped for ${localItem.displayName}")
                    }
                    android.net.Uri.fromFile(motionTemp).toString()
                } else if (gainMapTemp != null) {
                    strippedFile = gainMapTemp
                    Log.d(UPLOAD_TAG, "Ultra HDR primary stripped, gain map preserved for ${localItem.displayName}")
                    // Mirror: same reasoning as the motion photo. An in-place EXIF rewrite would drop the
                    // appended gain map, so the on-device original is replaced with the whole verified
                    // file instead. Silent with all-files; a refused write leaves the original intact.
                    if (mirrorStripToLocal && overwriteLocalInPlace(state.localUri, gainMapTemp)) {
                        Log.d(UPLOAD_TAG, "Mirror strip: on-device Ultra HDR photo wiped for ${localItem.displayName}")
                    }
                    android.net.Uri.fromFile(gainMapTemp).toString()
                } else if (mirrorStripToLocal &&
                    ExifHelper.stripFieldsInPlace(context, state.localUri, stripConfig)
                        is eu.akoos.photos.util.StripResult.Stripped
                ) {
                    // Confirmed neither a motion photo nor a verifiably preservable Ultra HDR (both
                    // helpers above returned null) → safe to wipe the on-device original in place and
                    // upload it as-is, so the local and the backed-up copy stay byte-identical and pair
                    // by content hash. The wipe is the last step before the upload and is idempotent,
                    // so a failed upload simply retries it.
                    Log.d(UPLOAD_TAG, "Mirror strip: on-device original wiped for ${localItem.displayName}")
                    state.localUri
                } else {
                    // Mirror off, or the OS refused the in-place write (no MANAGE_MEDIA) → ordinary
                    // temp-copy EXIF wipe, which leaves the original untouched. stripToTempFile
                    // returning null means nothing to strip or a strip error → the original uploads.
                    strippedFile = ExifHelper.stripToTempFile(context, state.localUri, stripConfig)
                    if (strippedFile != null) {
                        Log.d(UPLOAD_TAG, "Metadata stripped for ${localItem.displayName}")
                        android.net.Uri.fromFile(strippedFile).toString()
                    } else if (eu.akoos.photos.data.upload.UploadImageCompressor
                            .needsStripTranscode(
                                localItem.mimeType,
                                stripOnUpload,
                                compressOnUpload,
                                isMotionPhoto = { isMotionPhotoUpload(state.localUri) },
                            )
                    ) {
                        // The container cannot be EXIF-rewritten in place (HEIC / HEIF / AVIF) and no
                        // compression pass will run to rebuild a gated JPEG, so stripToTempFile no-oped
                        // and the untouched original would ship carrying the very GPS / camera EXIF the
                        // user asked to strip. Transcode a stripped JPEG and upload that instead. A
                        // motion photo is excluded by the probe: re-encoding its primary frame would
                        // drop the appended clip, so its motion outweighs the unwritable container.
                        val transcoded = eu.akoos.photos.data.upload.UploadImageCompressor
                            .transcodeStrippedJpeg(context, state.localUri, stripConfig)
                        if (transcoded != null) {
                            strippedFile = transcoded
                            Log.d(UPLOAD_TAG, "Stripped JPEG transcoded for ${localItem.displayName} (unwritable container)")
                            android.net.Uri.fromFile(transcoded).toString()
                        } else {
                            state.localUri
                        }
                    } else {
                        state.localUri
                    }
                }
            } else if (stripOnUpload && stripConfig.stripGps && localItem.mimeType.startsWith("video/")) {
                val tmp = File.createTempFile("stripped_", ".mp4", context.cacheDir)
                if (eu.akoos.photos.util.VideoMetadataStripper
                        .remuxWithoutLocation(context, state.localUri, tmp)) {
                    strippedFile = tmp
                    Log.d(UPLOAD_TAG, "Video location atom stripped for ${localItem.displayName}")
                    // Mirror: overwrite the on-device video with the location-stripped remux so the
                    // local loses its GPS too and matches the cloud. Silent with all-files; a refused
                    // write leaves the original intact.
                    if (mirrorStripToLocal && overwriteLocalInPlace(state.localUri, tmp)) {
                        Log.d(UPLOAD_TAG, "Mirror strip: on-device video wiped for ${localItem.displayName}")
                    }
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

            // Opt-in image compression. Runs AFTER the strip fork so it composes: it recompresses
            // whatever [strippedUploadUri] resolved to (the stripped temp when stripping, else the
            // original), so a stripped + compressed photo keeps the strip and ends up as the lighter
            // JPEG. This branch is images only; video is handled by the video-compress branch below,
            // not uploaded here. On any failure (or when the
            // recompressed copy would not be smaller) the recompressor returns null and the upload
            // proceeds with the pre-compression bytes, so a compression failure never blocks a backup.
            // The bytes sent, hashed, and sized are the compressed copy, mirroring the strip handling.
            // A Motion Photo is a still with an MP4 appended after it; the image compressor decodes
            // only the primary frame and re-encodes a plain JPEG, which would silently drop the motion
            // trailer. Skip compression for one (the stripped/original bytes upload as-is, keeping the
            // motion). Detection reuses MotionPhotoUtil against the exact bytes about to be compressed.
            val compressIsMotionPhoto = compressOnUpload &&
                localItem.mimeType.startsWith("image/") &&
                isMotionPhotoUpload(strippedUploadUri)
            if (compressIsMotionPhoto) {
                Log.d(UPLOAD_TAG, "Skipping image compression for motion photo ${localItem.displayName}; motion preserved")
            }
            // An Ultra HDR still is the same story with a gain map in place of a clip: the compressor
            // decodes only the primary frame, so re-encoding drops the appended gain map and the photo
            // loses its HDR rendition. Skip compression for one so the stripped/original bytes upload
            // whole. Detection reuses UltraHdrUtil against the exact bytes about to be compressed.
            val compressHasGainMap = !compressIsMotionPhoto &&
                eu.akoos.photos.data.upload.UploadImageCompressor.skipsCompressionForGainMap(
                    localItem.mimeType,
                    compressOnUpload,
                    hasGainMap = { hasGainMapUpload(strippedUploadUri) },
                )
            if (compressHasGainMap) {
                Log.d(UPLOAD_TAG, "Skipping image compression for Ultra HDR ${localItem.displayName}; gain map preserved")
            }
            val uploadUri: String = if (compressOnUpload && !compressIsMotionPhoto && !compressHasGainMap &&
                localItem.mimeType.startsWith("image/")
            ) {
                // The compressor rebuilds the output JPEG's EXIF from the source it recompresses, so it
                // must honour the strip directly: when the source is a format the strip step could not
                // rewrite (HEIC), strippedUploadUri fell back to the untouched original and copying its
                // EXIF unfiltered would re-inject the GPS / camera tags the user asked to remove. Pass
                // the effective config so those groups are dropped; a no-op when strip-on-upload is off,
                // so a non-stripping upload still carries the full EXIF byte-for-byte as before.
                val compressStripConfig = if (stripOnUpload) stripConfig else MetadataStripConfig()
                val compressed = eu.akoos.photos.data.upload.UploadImageCompressor
                    .compressToTemp(context, strippedUploadUri, compressTier, compressStripConfig)
                if (compressed != null) {
                    compressedFile = compressed
                    // The stripped temp (if any) is now superseded by the compressed copy, so delete
                    // it so a strip+compress pass doesn't leak the intermediate. The compressed file is
                    // the only temp we still need, tracked for cleanup in the finally below.
                    strippedFile?.delete()
                    strippedFile = null
                    Log.d(UPLOAD_TAG, "Compressed ${localItem.displayName} for upload (tier=${compressTier.name})")
                    android.net.Uri.fromFile(compressed).toString()
                } else {
                    strippedUploadUri
                }
            } else if (compressVideosOnUpload && localItem.mimeType.startsWith("video/")) {
                // Transcode behind the process-wide gate so only one hardware encode runs at a time.
                // The transcode is a suspend call inside this upload coroutine, so a stopped worker
                // cancels it. On ANY failure / unsupported / not-smaller / over-ceiling the
                // compressor returns null and the upload proceeds with the original bytes.
                val compressed = videoTranscodeGate.withPermit {
                    eu.akoos.photos.data.upload.VideoUploadCompressor.compressToTemp(
                        context,
                        android.net.Uri.parse(strippedUploadUri),
                        eu.akoos.photos.data.upload.VideoUploadCompressor.VideoCompressionParams(
                            compressTier.videoMaxShortEdgePx,
                            compressTier.videoBitrateBps,
                        ),
                        localItem.dateTaken,
                    )
                }
                if (compressed != null) {
                    compressedFile = compressed
                    strippedFile?.delete()
                    strippedFile = null
                    Log.d(UPLOAD_TAG, "Compressed video ${localItem.displayName} for upload (tier=${compressTier.name})")
                    android.net.Uri.fromFile(compressed).toString()
                } else {
                    strippedUploadUri
                }
            } else {
                strippedUploadUri
            }

            // Strip-timestamp floors the cloud DISPLAY date (localItem.dateTaken is now), and the
            // compress path already stamps that floored time into the transcoded mvhd. The gap is an
            // UNCOMPRESSED video: its container's own mvhd creation time can still reveal the real
            // capture moment. When the bytes about to be uploaded are the original video (no compress),
            // floor the mvhd to localItem.dateTaken too so the file itself can't reconstruct it. When a
            // GPS-strip temp already exists, stamp that in place; otherwise copy the original URI's
            // bytes to a fresh temp, stamp that, and upload the temp. Best-effort: any failure falls
            // back to uploading the original bytes.
            var finalUploadUri = uploadUri
            if (localItem.mimeType.startsWith("video/") &&
                stripOnUpload && stripConfig.stripTimestamp && compressedFile == null
            ) {
                if (strippedFile != null) {
                    Mp4CreationTime.stamp(strippedFile!!, localItem.dateTaken)
                } else {
                    runCatching {
                        val tmp = File(context.cacheDir, "upl_ts_" + System.nanoTime() + ".mp4")
                        context.contentResolver.openInputStream(Uri.parse(uploadUri))!!.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        Mp4CreationTime.stamp(tmp, localItem.dateTaken)
                        tsFloorFile = tmp
                        finalUploadUri = Uri.fromFile(tmp).toString()
                    }.onFailure {
                        Log.w(UPLOAD_TAG, "Video mvhd floor copy failed for ${localItem.displayName}; uploading original")
                    }
                }
            }

            val sha1StartMs = System.currentTimeMillis()
            val hash = computeSha1(finalUploadUri)
            // Privacy: never log the file name (mirror PhotoUploadService.logRef) — a non-reversible
            // URI-derived ref keeps the per-file lines correlatable without revealing the file.
            val logRef = eu.akoos.photos.util.uploadLogRef(localItem.uri)
            eu.akoos.photos.util.SyncDiagnostics.log(
                "upload $logRef: sha1 in ${System.currentTimeMillis() - sha1StartMs}ms"
            )
            if (hash == null) {
                // The content could not be read this pass. Never upload with a bogus/empty hash (it
                // would land a Drive duplicate that never content-pairs on reconcile): release the
                // claim back to LOCAL_ONLY so the row stays claimable and retries next pass, and skip
                // uploadFile + the hash upsert entirely. NonCancellable so the reset still lands if the
                // batch is torn down around this. A plain return here bypasses the catch blocks below,
                // so the claim release is done explicitly.
                Log.w(UPLOAD_TAG, "Skipping ${localItem.displayName}: content hash unavailable (unreadable source), will retry")
                withContext(NonCancellable) { releaseUploadClaim(state.localUri, userId) }
                _progress.tryEmit(
                    UploadProgress(
                        uri = state.localUri,
                        displayName = localItem.displayName,
                        status = UploadStatus.Failed,
                        doneIdx = finishedCount.get(),
                        totalCount = totalCount,
                    )
                )
                return
            }
            // Persist the content hash BEFORE the upload, keeping the UPLOADING claim intact (the
            // in-memory `state` still reads LOCAL_ONLY from selection time, so it must NOT be written
            // back verbatim or it would clear the claim mid-upload and let a second pass re-grab the
            // row). If the process is killed between uploadFile() returning (the file is already on
            // Drive) and the SYNCED upsert below, the row stays UPLOADING with a null cloudFileId
            // carrying this hash; the next process's one-time stale recovery demotes it to LOCAL_ONLY
            // and reconcile then pairs it BY CONTENT HASH to the file already on Drive instead of
            // re-uploading a duplicate. Cheap: the SYNCED upsert rewrites the same hash on success.
            if (state.localHash != hash) {
                syncStateRepo.upsert(state.copy(localHash = hash, status = SyncStatus.UPLOADING), userId)
            }
            // Size the item from whatever temp holds the bytes we actually send: the compressed copy
            // when compression produced one (strippedFile was cleared to it above), the stripped temp
            // otherwise, else the original. Keeps sizeBytes / progress / xAttr aligned with the wire.
            val uploadTempFile = compressedFile ?: strippedFile
            val uploadItem = if (uploadTempFile != null)
                localItem.copy(sizeBytes = uploadTempFile.length())
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
            // A downscaled video must report the OUTPUT dimensions in the xAttr, not the original
            // source's. Probe the compressed temp once (a video compressedFile is set only on the
            // video-compress path) so a 4K source transcoded to 1080p carries 1920x1080, matching the
            // bytes actually uploaded. Rotation is unchanged (Drive reads it from the container).
            val videoDimsOverride: Pair<Int, Int>? =
                if (compressedFile != null && localItem.mimeType.startsWith("video/")) {
                    probeVideoDimensions(compressedFile!!)
                } else {
                    null
                }
            // Resolve Camera/Location + rotation-corrected dimensions from the ORIGINAL source
            // (pre-strip), gated against the strip config so the xAttr never re-leaks a field the
            // file had erased. uploadItem.dateTaken is already floored when timestamps are stripped.
            val xAttrMetadata = buildXAttrMetadata(
                sourceUri = state.localUri,
                item = uploadItem,
                stripOnUpload = stripOnUpload,
                stripConfig = stripConfig,
                videoDimsOverride = videoDimsOverride,
            )
            val cloudId = cloudRepo.uploadFile(
                userId, uploadItem, hash, finalUploadUri, xAttrMetadata, onProgressForFile,
            )

            // Mirror-compress the on-device original, now that the upload is committed and never
            // before: like the rename below, a failed upload must not leave the local shrunk with no
            // Drive copy. Reuses the compressed temp before it is deleted just below; the overwrite is
            // crash-safe (stage a backup, truncate-write, restore on failure), so the original is never
            // left partial. Independent of the strip mirror: the local ends up COMPRESSED, and stripped
            // only when mirrorStripToLocal is also on.
            val mirrorCompressImage = compressOnUpload && mirrorCompressToLocal &&
                localItem.mimeType.startsWith("image/")
            if (
                mirrorCompressImage &&
                !eu.akoos.photos.data.upload.UploadImageCompressor
                    .canOverwriteLocalWithCompressedJpeg(localItem.mimeType)
            ) {
                // The compressor only ever encodes JPEG, so replacing a PNG / WebP / HEIC original with
                // its output would leave a file whose bytes contradict its name and its MediaStore mime.
                // Leave the on-device file untouched; the uploaded copy is compressed either way.
                Log.d(
                    UPLOAD_TAG,
                    "Mirror compress skipped for ${localItem.displayName}: ${localItem.mimeType} cannot hold a JPEG",
                )
            } else if (mirrorCompressImage) {
                if (mirrorStripToLocal || !stripOnUpload) {
                    // compressedFile already holds the right local content: compressed from the stripped
                    // temp when strip is mirrored, or from the original when strip-upload is off. A null
                    // (compression failed or not smaller) skips silently.
                    compressedFile?.let { local ->
                        if (overwriteLocalInPlace(state.localUri, local)) {
                            Log.d(UPLOAD_TAG, "Mirror compress: on-device original compressed for ${localItem.displayName}")
                        }
                    }
                } else if (!compressIsMotionPhoto &&
                    !eu.akoos.photos.data.upload.UploadImageCompressor.skipsCompressionForGainMap(
                        localItem.mimeType,
                        compressOnUpload,
                        hasGainMap = { hasGainMapUpload(state.localUri) },
                    )
                ) {
                    // stripOnUpload && !mirrorStripToLocal: the upload's compressed temp is stripped, but
                    // the user did not opt to strip the local. Compress a FRESH strip-free copy from the
                    // untouched original and overwrite with that, then delete the fresh temp. Skipped for
                    // a motion photo so the on-device motion is never re-encoded away, and for an Ultra
                    // HDR still so its gain map is never flattened out of the local copy. That probe reads
                    // the untouched original, which is exactly what this branch would overwrite, and not
                    // the stripped temp the upload-side skip looks at.
                    // A no-op strip config keeps this local copy's EXIF intact: the user opted to
                    // compress the on-device original but not to strip it, so it must retain the full
                    // metadata the untouched original carries.
                    val localCompressed = eu.akoos.photos.data.upload.UploadImageCompressor
                        .compressToTemp(context, state.localUri, compressTier, MetadataStripConfig())
                    if (localCompressed != null) {
                        if (overwriteLocalInPlace(state.localUri, localCompressed)) {
                            Log.d(UPLOAD_TAG, "Mirror compress: on-device original compressed (strip-free) for ${localItem.displayName}")
                        }
                        localCompressed.delete()
                    }
                }
            }
            // Mirror-compress the on-device video: reuse the already-transcoded temp, never re-transcode.
            // Safe to mirror when the local content matches that temp: strip mirrored (both stripped) or
            // strip-upload off (neither stripped).
            if (compressVideosOnUpload && mirrorCompressToLocal && localItem.mimeType.startsWith("video/")) {
                if (mirrorStripToLocal || !stripOnUpload) {
                    compressedFile?.let { local ->
                        if (overwriteLocalInPlace(state.localUri, local)) {
                            Log.d(UPLOAD_TAG, "Mirror compress: on-device video compressed for ${localItem.displayName}")
                        }
                    }
                }
                // else (stripOnUpload && !mirrorStripToLocal): skip; a strip-free re-transcode is too costly.
            }

            strippedFile?.delete()
            strippedFile = null
            compressedFile?.delete()
            compressedFile = null
            tsFloorFile?.delete()
            tsFloorFile = null

            // The file is on Drive once uploadFile returns a real cloudId. Record that fact
            // non-cancellably so an interrupt in this window can't leave the row LOCAL_ONLY with
            // a null cloudFileId — which would re-select and re-upload the same file (a Drive
            // duplicate) on the next pass.
            val syncedState = state.copy(
                cloudFileId = cloudId,
                localHash = hash,
                status = SyncStatus.SYNCED,
                lastSyncSuccessMs = System.currentTimeMillis(),
                backedUpAtMs = System.currentTimeMillis(),
            )
            withContext(NonCancellable) {
                syncStateRepo.upsert(syncedState, userId)
                // RULE 1: clear the queued flag now the row is SYNCED, so a later grace-window demotion
                // can't leave it flagged and re-queued for a duplicate. Unguarded because the row is
                // known backed up. Same non-cancellable window as the SYNCED upsert so an interrupt
                // can't split the two. The upsert round-trips a domain SyncState, which never carries
                // the queue columns, so this separate DAO write is what actually clears them.
                syncStateRepo.clearQueuedForSynced(state.localUri)
            }

            // Mirror the cloud rename onto the on-device file now that the upload is committed (and
            // never before — a failed upload must not rename a file with no Drive copy). Silent with
            // MANAGE_MEDIA; the URI is stable across a DISPLAY_NAME change, so the SYNCED row above
            // still tracks it, and renaming never touches bytes, so localHash stays valid. Two
            // same-second burst shots resolve to one name — MediaStore refuses the second rename and
            // renameLocalInPlace skips it (the content hash still pairs that copy by its bytes).
            mirrorRenameTarget?.let { renameLocalInPlace(state.localUri, it) }

            // Delete-after-backup: only the ORIGINAL MediaStore URI, never the strip
            // temp. Runs AFTER the SYNCED upsert so a crash here cannot leave the
            // user with a deleted local file and no Drive record. The direct call
            // is refused on Samsung Gallery owned items even with Manage Media
            // granted — the OS still wants an explicit consent gesture. We queue
            // refused URIs into PENDING_DELETE_URIS and the batched foreground sweep
            // at the end of the upload run sends a single IntentSender that lets the
            // user accept N files at once instead of one dialog per file.
            // The removal and the CLOUD_ONLY flip share one non-cancellable window so
            // an interrupt between them cannot leave the row claiming a device copy
            // that is already gone.
            val removal = resolveDeleteAfterBackupRemoval(
                sdkInt = Build.VERSION.SDK_INT,
                deleteLocalAfterBackup = deleteLocalAfterBackup,
                uploadSucceeded = cloudId.isNotBlank(),
            )
            if (removal != Removal.NONE) {
                withContext(NonCancellable) {
                    val uri = runCatching { Uri.parse(state.localUri) }.getOrNull()
                    if (uri == null) {
                        Log.w(UPLOAD_TAG, "Delete after backup: invalid URI ${state.localUri}")
                    } else {
                        val rows = runCatching {
                            if (removal == Removal.TRASH) {
                                context.contentResolver.update(
                                    uri,
                                    ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) },
                                    null,
                                    null,
                                )
                            } else {
                                // No system trash exists before R, so the device copy can only be
                                // removed outright; the recoverable window starts at R.
                                context.contentResolver.delete(uri, null, null)
                            }
                        }.getOrElse { e ->
                            if (e is CancellationException) throw e
                            Log.w(UPLOAD_TAG, "Delete after backup threw for ${localItem.displayName}: ${e::class.simpleName} ${e.message}")
                            0
                        }
                        if (rows > 0) {
                            // The device copy is gone, so the row must stop claiming one: a SYNCED
                            // row here would let a later free-up-space pass re-select the same photo
                            // and raise a consent dialog for a file that is no longer on the device.
                            syncStateRepo.upsert(syncedState.copy(status = SyncStatus.CLOUD_ONLY), userId)
                            val verb = if (removal == Removal.TRASH) "trashed" else "removed"
                            Log.d(UPLOAD_TAG, "Delete after backup: $verb ${localItem.displayName}")
                        } else {
                            context.settingsDataStore.edit { p ->
                                val existing = p[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()
                                p[SettingsKeys.PENDING_DELETE_URIS] = existing + state.localUri
                            }
                            Log.d(UPLOAD_TAG, "Delete after backup: queued ${localItem.displayName} for batched consent dialog")
                        }
                    }
                }
            }

            successCount.incrementAndGet()
            successUris.add(state.localUri)
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

            // User-queued album-adds for this freshly-uploaded photo, read from the target table (the
            // single source of truth). These are album linkIds directly (the user picked an existing
            // album for a local-only photo), so skip the bucket-name resolve/create the mirror loop
            // above does. Best-effort like the mirror: each pair is dropped only on a successful add,
            // and a failure leaves that pair for the next-pass drain so an album membership is never
            // lost. A manual "back up now" has no target rows, so this loop is simply empty for it.
            for (albumLinkId in uploadAlbumTargetDao.getTargetsFor(state.localUri)) {
                runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(cloudId)) }
                    .onSuccess {
                        uploadAlbumTargetDao.deleteTarget(state.localUri, albumLinkId)
                        Log.d(UPLOAD_TAG, "Added $cloudId to queued album $albumLinkId")
                    }
                    .onFailure { e -> Log.w(UPLOAD_TAG, "Queued album-add failed for $albumLinkId: ${e.message}") }
            }
        } catch (e: CancellationException) {
            // A user cancel (or an OS stop) can land mid-upload while this row is claimed UPLOADING
            // by the #69 fix. Release the claim back to LOCAL_ONLY first, otherwise the row stays
            // stranded at UPLOADING until the next process restart's stale-claim recovery, so a
            // cancelled upload could never retry on the next genuine trigger. NonCancellable so the
            // reset still lands while the batch is being torn down around this cancellation. Only a
            // row still UPLOADING with no cloud copy is reset (see releaseUploadClaim), so a
            // concurrently-committed success is never trampled. Then re-throw so awaitAll() tears
            // down siblings cleanly and structured concurrency stays intact.
            withContext(NonCancellable) { releaseUploadClaim(state.localUri, userId) }
            throw e
        } catch (e: StorageFullException) {
            Log.w(UPLOAD_TAG, "Storage full — flagging batch abort after current task")
            onStorageFull()
            // Release the claim so a later pass retries this row once space frees up. The upload
            // never committed, so it must not stay stuck at UPLOADING. NonCancellable so the reset
            // still lands even if the batch is being torn down around this failure.
            withContext(NonCancellable) { releaseUploadClaim(state.localUri, userId) }
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
            // Release builds minify every android.util.Log call away, so the line above vanishes from
            // exactly the builds a tester runs and an upload failure leaves no trace anywhere. Record
            // the reason in the diagnostics buffer instead: the exception types plus a message run
            // through the sanitizer, which strips names, paths, urls and ids.
            eu.akoos.photos.util.SyncDiagnostics.log(
                "upload ${eu.akoos.photos.util.uploadLogRef(state.localUri)} FAILED: " +
                    eu.akoos.photos.util.describeUploadFailure(e)
            )
            // Reset the claim: the upload failed before the SYNCED upsert, so return the row to
            // LOCAL_ONLY for the next pass instead of leaving it stranded at UPLOADING (which the
            // selector and reconcile both skip). NonCancellable so it lands during a batch teardown.
            withContext(NonCancellable) { releaseUploadClaim(state.localUri, userId) }
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
            compressedFile?.delete()
            tsFloorFile?.delete()
        }
    }

    /**
     * True when a row's [queueSource] marks it as an explicit user action: a manual "back up now"
     * ([QueueSource.MANUAL]) or an album-add ([QueueSource.ALBUM_ADD]), which the user asked for
     * regardless of the current Wi-Fi / folder state. Such a row bypasses the folder re-filters and
     * the network/listing early-returns; an [QueueSource.AUTO_FOLDER] (or [QueueSource.EDITOR]) row
     * does not, so it stays subject to the folder guards. Replaces the old forcedUploadUris check.
     */
    private fun isExplicitAction(queueSource: String?): Boolean =
        queueSource == eu.akoos.photos.domain.entity.QueueSource.MANUAL ||
            queueSource == eu.akoos.photos.domain.entity.QueueSource.ALBUM_ADD

    /**
     * Returns a failed upload's claimed row from UPLOADING to LOCAL_ONLY so the next pass retries
     * it. Only a row this pass left UPLOADING with no cloud copy is reset, so a concurrent success
     * (which would have flipped it to SYNCED with a cloudFileId) is never trampled. Preserves the
     * row's other fields (notably the localHash persisted before the upload) so reconcile can
     * still content-hash-pair it if the file did reach Drive.
     */
    private suspend fun releaseUploadClaim(localUri: String, userId: UserId) {
        runCatching {
            val current = syncStateRepo.getByUri(localUri)
            if (current?.status == SyncStatus.UPLOADING && current.cloudFileId == null) {
                // RULE 2: re-queue the demoted row so it stays eligible under the future queue selector,
                // otherwise a failed upload / edit would silently drop off the queue. Read the original
                // source (why it was up for backup) BEFORE the upsert below: upsert round-trips a domain
                // SyncState, whose mapper drops the queue columns and so clears them, so reading after
                // would always see null. Default AUTO_FOLDER when the row carried no source.
                val source = syncStateRepo.getQueueSource(localUri)
                    ?: eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER
                syncStateRepo.upsert(current.copy(status = SyncStatus.LOCAL_ONLY), userId)
                // markQueued is a per-row UPDATE, so it re-stamps the queue columns the upsert just
                // cleared; running it after the upsert lands the flag on the now-LOCAL_ONLY row.
                syncStateRepo.markQueued(localUri, source, System.currentTimeMillis())
            }
        }.onFailure { e -> Log.w(UPLOAD_TAG, "Failed to release upload claim for $localUri: ${e.message}") }
    }

    /**
     * Deletes upload-path temps in cacheDir whose name starts with a known prefix and whose
     * lastModified is older than [STALE_UPLOAD_TEMP_MS]. Dirs (e.g. an `upload_` resume dir) are
     * removed recursively. The age gate is the safety: an in-flight upload only reads freshly
     * created temps, and a retryable failure's preserved resume dir is only ever >3 days old once
     * abandoned, so a recent one is never swept and a resume can still find it. Best-effort and
     * cheap: one listFiles() with nothing to do when no temp is stale; never blocks upload work.
     */
    private fun sweepStaleUploadTemps() {
        runCatching {
            val cutoff = System.currentTimeMillis() - STALE_UPLOAD_TEMP_MS
            var removed = 0
            context.cacheDir.listFiles()?.forEach { entry ->
                if (UPLOAD_TEMP_PREFIXES.none { entry.name.startsWith(it) }) return@forEach
                val mtime = entry.lastModified()
                if (mtime in 1..cutoff) {
                    val ok = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                    if (ok) removed++
                }
            }
            if (removed > 0) Log.d(UPLOAD_TAG, "Swept $removed stale upload temp(s) from cache")
        }.onFailure { e -> Log.w(UPLOAD_TAG, "Stale upload-temp sweep failed: ${e.message}") }
    }

    /** Renames the on-device MediaStore file in place (silent with MANAGE_MEDIA), so a mirrored
     *  upload's local copy shares the cloud name. A write the OS refuses is logged and skipped. */
    private fun renameLocalInPlace(uri: String, newName: String): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        val rows = context.contentResolver.update(Uri.parse(uri), values, null, null)
        if (rows > 0) Log.d(UPLOAD_TAG, "Mirror rename: on-device file renamed to '$newName'")
        rows > 0
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(UPLOAD_TAG, "Mirror rename skipped for $uri: ${e.message}")
        false
    }

    /** Overwrites the on-device MediaStore file with [src]'s bytes (silent with MANAGE_MEDIA /
     *  all-files). Used to mirror a strip onto the original: a motion photo's in-place EXIF rewrite
     *  would drop the trailer, and a video can't be EXIF-edited at all, so the whole stripped file is
     *  written instead. The current bytes are first staged into [mirrorOverwriteJournal], then "wt"
     *  truncates and rewrites the file. A copy that throws (I/O error, eject) is rolled back in place
     *  from the staged backup, and a process killed mid-truncate is rolled back from the same backup
     *  on the next launch, so the original is never left partial. A write the OS refuses is logged and
     *  skipped (the original stays intact); a staging failure returns false without touching the file. */
    private fun overwriteLocalInPlace(uri: String, src: File): Boolean {
        val parsed = Uri.parse(uri)
        var backup: File? = null
        val outcome = eu.akoos.photos.data.upload.MirrorOverwriteJournal.guardedOverwrite(
            stage = {
                // Staging publishes its marker only once the backup copy is complete, so a kill during
                // the truncate-write below always leaves a journal pair the next launch can replay.
                mirrorOverwriteJournal.stage(uri) { file ->
                    context.contentResolver.openInputStream(parsed)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                        true
                    } ?: false
                }?.also { backup = it.backup }
            },
            openAndWrite = {
                // A null stream means the OS refused the write, so nothing was truncated. Once the
                // stream opens, "wt" has already emptied the file and any failure must be rolled back.
                val out = context.contentResolver.openOutputStream(parsed, "wt")
                if (out == null) {
                    false
                } else {
                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                    true
                }
            },
            restore = {
                val bak = backup
                bak != null && context.contentResolver.openOutputStream(parsed, "wt")?.use { o ->
                    bak.inputStream().use { it.copyTo(o) }
                } != null
            },
            commit = { entry -> mirrorOverwriteJournal.commit(entry) },
        )
        when (outcome) {
            MirrorOverwriteJournal.Outcome.WRITTEN -> return true
            MirrorOverwriteJournal.Outcome.SKIPPED ->
                Log.w(UPLOAD_TAG, "Mirror overwrite skipped for $uri (backup or write refused)")
            MirrorOverwriteJournal.Outcome.ROLLED_BACK ->
                Log.w(UPLOAD_TAG, "Mirror overwrite failed for $uri; original restored")
            MirrorOverwriteJournal.Outcome.STRANDED ->
                Log.e(
                    UPLOAD_TAG,
                    "Mirror overwrite failed for $uri and the in-place restore also failed; the original " +
                        "stays staged in the journal and is replayed on the next launch",
                )
        }
        return false
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
     * Strip path for Ultra HDR stills. Returns a temp upload file ONLY when the rebuilt bytes are proven
     * to still decode with their gain map, else null so the caller runs the ordinary strip untouched.
     *
     * An Ultra HDR still is an SDR JPEG with the gain map appended as a second image after the primary,
     * so the split is primary = `[0, end of the first EOI after the scan)` and tail = the rest. The
     * primary's EXIF goes through the same helper the motion path uses and the tail is re-attached
     * byte-for-byte, so the appended image and the header that points at it both survive intact.
     *
     * Safety runs the OPPOSITE way to the motion path: there, a corrupt primary is worse than a lost
     * strip; here, a lost gain map only costs the HDR rendition while an unstripped upload leaks GPS. So
     * every uncertainty (a split that does not resolve, a strip that no-ops, a result the decoder refuses
     * or that decodes without a gain map) deletes the temps and returns null, handing the file back to
     * the ordinary strip with no behaviour change at all.
     */
    private fun stripImagePreservingGainMap(localUri: String, stripConfig: MetadataStripConfig): File? {
        // Materialize the source so the marker walk and the split read real bytes, not a stream.
        val source = try {
            val tmp = File.createTempFile("gainmap_src_", ".bin", context.cacheDir)
            context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: run { tmp.delete(); return null }
            tmp
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Ultra HDR materialize failed for $localUri: ${e.message}")
            return null
        }

        var primary: File? = null
        var strippedPrimary: File? = null
        var joined: File? = null
        try {
            // A split that lands at or past EOF means there is no appended image to protect, so the
            // ordinary strip loses nothing and is the safer route.
            val splitAt = primaryImageEndOffset(source)
            if (splitAt <= 0L || splitAt >= source.length()) return null

            primary = File.createTempFile("gainmap_primary_", ".jpg", context.cacheDir)
            RandomAccessFile(source, "r").use { raf ->
                primary!!.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = splitAt
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            // Strip the primary through the existing temp-file strip via a file:// URI so the same tag
            // set and behaviour apply. A null is a no-op or an error: either way nothing here is proven
            // stripped, so the ordinary path takes over rather than this one shipping unstripped bytes.
            strippedPrimary = ExifHelper.stripToTempFile(
                context, Uri.fromFile(primary).toString(), stripConfig,
            ) ?: return null

            joined = File.createTempFile("gainmap_out_", ".jpg", context.cacheDir)
            joined!!.outputStream().use { out ->
                strippedPrimary!!.inputStream().use { it.copyTo(out) }
                RandomAccessFile(source, "r").use { raf ->
                    raf.seek(splitAt)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }

            if (!verifyGainMapPreserved(joined!!)) {
                Log.d(UPLOAD_TAG, "Ultra HDR rebuild unverified for $localUri; ordinary strip applies")
                return null
            }
            // Verified, so hand it to the caller and keep the finally below from deleting it.
            val verified = joined!!
            joined = null
            return verified
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Ultra HDR split/strip failed for $localUri: ${e.message}")
            return null
        } finally {
            // Every intermediate goes, on success and on failure alike; only the verified result escapes.
            source.delete()
            primary?.delete()
            strippedPrimary?.delete()
            joined?.delete()
        }
    }

    /**
     * Offset one byte past the primary image's EOI in [source], or -1 when it cannot be resolved.
     *
     * The marker chain is walked by its length fields up to SOS, so a thumbnail's own SOI/SOS/EOI inside
     * an APPn payload is stepped over instead of taken for the primary's. From the scan onwards the bytes
     * are entropy-coded, where every literal 0xFF is stuffed as `0xFF 0x00`, so the first `0xFF 0xD9`
     * pair there is genuinely the end of the primary and whatever follows it is the appended image.
     */
    private fun primaryImageEndOffset(source: File): Long {
        RandomAccessFile(source, "r").use { raf ->
            val length = raf.length()
            if (length < 4L) return -1L
            if (raf.read() != 0xFF || raf.read() != 0xD8) return -1L
            while (raf.filePointer < length) {
                // Anything other than 0xFF here means the walk has lost sync with the segment chain.
                if (raf.read() != 0xFF) return -1L
                // A marker may be padded with any number of extra 0xFF fill bytes.
                var marker = raf.read()
                while (marker == 0xFF) marker = raf.read()
                when {
                    marker < 0 -> return -1L
                    // Standalone markers carry no length field.
                    marker == 0xD8 || marker == 0x01 -> continue
                    marker in 0xD0..0xD7 -> continue
                    // EOI before any scan: there is no primary image to split off.
                    marker == 0xD9 -> return -1L
                }
                val high = raf.read()
                val low = raf.read()
                if (high < 0 || low < 0) return -1L
                // The big-endian length counts its own two bytes, so anything below 2 is corrupt.
                val segment = (high shl 8) or low
                if (segment < 2) return -1L
                val payloadEnd = raf.filePointer + (segment - 2)
                if (payloadEnd > length) return -1L
                if (marker == 0xDA) return firstEoiFrom(raf, payloadEnd, length)
                raf.seek(payloadEnd)
            }
        }
        return -1L
    }

    /** Offset one byte past the first `0xFF 0xD9` at or after [from] in [raf], or -1 when there is none
     *  before [length]. */
    private fun firstEoiFrom(raf: RandomAccessFile, from: Long, length: Long): Long {
        raf.seek(from)
        val buffer = ByteArray(64 * 1024)
        var position = from
        var markerPrefixPending = false
        while (position < length) {
            val read = raf.read(buffer)
            if (read <= 0) break
            for (i in 0 until read) {
                val value = buffer[i].toInt() and 0xFF
                if (markerPrefixPending && value == 0xD9) return position + i + 1
                markerPrefixPending = value == 0xFF
            }
            position += read
        }
        return -1L
    }

    /**
     * True only when the platform decoder can actually decode [candidate] AND reports a gain map on the
     * result. This is what makes a rebuilt file safe to ship: bytes that fail to decode, or that decode
     * without the gain map, are not a preserved Ultra HDR and must never replace the ordinary strip.
     * Decoded at a small target size so the check costs a fraction of the full bitmap. Below API 34 there
     * is no gain-map query at all, so the answer is false and the caller falls back.
     */
    private fun verifyGainMapPreserved(candidate: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        // Enough pixels for the decoder to do real work, small enough that the verification costs about
        // a megabyte of heap rather than the full-resolution bitmap.
        val targetPx = 512
        var bitmap: android.graphics.Bitmap? = null
        return try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(candidate)) { decoder, info, _ ->
                var sample = 1
                val longest = maxOf(info.size.width, info.size.height)
                while (longest / (sample * 2) >= targetPx) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
            bitmap.hasGainmap()
        } catch (e: Throwable) {
            // Throwable so a decoder OutOfMemoryError also reads as "not preserved" instead of failing
            // the upload.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Gain-map verification failed for ${candidate.name}: ${e.message}")
            false
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * True when [uploadUri] points at an Android Motion Photo (a still with an appended MP4 trailer),
     * so the caller can skip image compression that would re-encode only the primary and lose the
     * motion. Reuses [MotionPhotoUtil.detect], which needs a [File]: a file:// URI (a strip temp that
     * already preserved the motion) is read in place; a content:// original is materialized to a temp
     * first, then deleted. Defensive: any failure returns false so an unreadable file just compresses
     * as an ordinary still.
     */
    private fun isMotionPhotoUpload(uploadUri: String): Boolean {
        val uri = runCatching { Uri.parse(uploadUri) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val path = uri.path ?: return false
            return runCatching { MotionPhotoUtil.detect(File(path)) != null }.getOrDefault(false)
        }
        var temp: File? = null
        return try {
            temp = File.createTempFile("motion_check_", ".bin", context.cacheDir)
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                temp!!.outputStream().use { input.copyTo(it) }
                true
            } ?: false
            copied && MotionPhotoUtil.detect(temp!!) != null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Motion-photo check failed for $uploadUri: ${e.message}")
            false
        } finally {
            temp?.delete()
        }
    }

    /**
     * True when [uploadUri] points at a JPEG advertising an Ultra HDR gain map, so the caller can skip a
     * pass that would drop it. [UltraHdrUtil] reads only the marker segments ahead of the scan, so no
     * temp copy and no bitmap is needed whatever the scheme, unlike the motion probe. Defensive: any
     * failure returns false and the file is treated as an ordinary still.
     */
    private fun hasGainMapUpload(uploadUri: String): Boolean {
        val uri = runCatching { Uri.parse(uploadUri) }.getOrNull() ?: return false
        return try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use {
                UltraHdrUtil.hasGainMap(it)
            } ?: false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(UPLOAD_TAG, "Gain-map check failed for $uploadUri: ${e.message}")
            false
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
    /** RAW formats whose embedded preview (what the thumbnail decodes from) is already display-
     *  oriented, so their xAttr must report a NORMAL camera orientation — see [buildXAttrMetadata]. */
    private val rawImageMimeTypes = setOf(
        "image/x-adobe-dng", "image/dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-canon-crw",
        "image/x-nikon-nef", "image/x-nikon-nrw", "image/x-sony-arw", "image/x-sony-sr2", "image/x-sony-srf",
        "image/x-fuji-raf", "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-pentax-pef",
        "image/x-samsung-srw", "image/x-minolta-mrw", "image/x-kodak-dcr", "image/x-sigma-x3f",
        "image/x-epson-erf", "image/x-hasselblad-3fr", "image/x-raw",
    )
    private val rawImageExtensions = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "srf", "raf", "rw2", "orf", "pef",
        "srw", "mrw", "dcr", "x3f", "erf", "3fr", "raw",
    )

    private fun buildXAttrMetadata(
        sourceUri: String,
        item: eu.akoos.photos.domain.entity.LocalMediaItem,
        stripOnUpload: Boolean,
        stripConfig: MetadataStripConfig,
        videoDimsOverride: Pair<Int, Int>? = null,
    ): eu.akoos.photos.data.repository.drive.UploadXAttrMetadata {
        val stripGps = stripOnUpload && stripConfig.stripGps
        val stripCamera = stripOnUpload && stripConfig.stripCameraInfo
        // ISO_INSTANT (e.g. 2023-01-15T10:30:00Z), matching Drive Android's DateTimeFormatter.
        val captureTimeIso = item.dateTaken.takeIf { it > 0L }?.let { ms ->
            java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(ms))
        }
        return when {
            item.mimeType.startsWith("video/") -> {
                var rawW = 0
                var rawH = 0
                runCatching {
                    // Released explicitly: MediaMetadataRetriever implements AutoCloseable only from
                    // API 29, so a `use` block throws at close time on every older device and leaks
                    // the native retriever there.
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, Uri.parse(sourceUri))
                        rawW = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )?.toIntOrNull() ?: 0
                        rawH = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )?.toIntOrNull() ?: 0
                    } finally {
                        runCatching { r.release() }
                    }
                }
                // Send the RAW encoded stream dimensions UNSWAPPED and NO Camera block at all — what
                // the official client sends for a video. Drive web reads the display rotation from the
                // MP4 container itself, so swapping the dimensions or adding an orientation makes web
                // double-count the rotation and show the video sideways. The capture date still reaches
                // Drive through the separate photo.captureTime field (PhotoMetaDto), so the video keeps
                // its timeline position without an xAttr Camera block.
                // A compressed (downscaled) upload overrides the source dimensions with the OUTPUT
                // stream's, so the xAttr matches the bytes on the wire; rotation stays with the container.
                val baseW = videoDimsOverride?.first ?: rawW.takeIf { it > 0 } ?: item.width
                val baseH = videoDimsOverride?.second ?: rawH.takeIf { it > 0 } ?: item.height
                eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(
                    displayWidth = baseW.takeIf { it > 0 },
                    displayHeight = baseH.takeIf { it > 0 },
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
                // RAW: the embedded preview the thumbnail decodes from is already display-oriented, so
                // report a NORMAL camera orientation. Sending the source's sensor orientation makes
                // Drive web rotate the already-upright RAW thumbnail and show it sideways (our own grid
                // draws the thumbnail as-is, so it looks right there — the mismatch only shows on the
                // web). Dimensions still use the upright (swapped) values so the aspect ratio matches.
                val ext = item.displayName.substringAfterLast('.', "").lowercase()
                val isRaw = item.mimeType in rawImageMimeTypes || ext in rawImageExtensions
                eu.akoos.photos.data.repository.drive.UploadXAttrMetadata(
                    latitude = if (!stripGps) meta.gpsLatitude else null,
                    longitude = if (!stripGps) meta.gpsLongitude else null,
                    cameraOrientation = if (isRaw)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL else orientation,
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

    /** Probes a transcoded video temp for its OUTPUT width/height (same retriever keys the video
     *  xAttr branch reads for the source), so a downscaled upload reports the real stream size.
     *  Returns null on any failure or a non-positive dimension, so the caller keeps the source dims. */
    private fun probeVideoDimensions(file: File): Pair<Int, Int>? = runCatching {
        // Released explicitly: MediaMetadataRetriever implements AutoCloseable only from API 29, so a
        // `use` block throws at close time on every older device and turns each probe into a null.
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val h = r.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) w to h else null
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

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

    /**
     * Hex-encodes the SHA-1 of the file's plaintext content. Drive's `ContentHash`
     * wire field is `HMAC-SHA256(rootNodeHashKey, sha1Hex.utf8Bytes())` and Drive
     * web's `photosTransferPayloadBuilder` rejects any payload whose photo content
     * hash was derived from a SHA-256 input with the misleading error
     * "Cannot build photo payload without a content hash". Drive Android picks SHA-1
     * via `ConfigurationProvider.contentDigestAlgorithm = "SHA1"` (Drive Android's
     * `ConfigurationProvider`). The xAttr Common.Digests
     * map carries the same SHA-1 hex so a later cross-client xAttr verify lines up.
     */
    private fun computeSha1(uri: String): String? {
        val digest = MessageDigest.getInstance("SHA-1")
        try {
            // A null stream (or a read error below) means the content is unreadable this pass. Return
            // null rather than the SHA-1 of empty/partial input: a bogus hash persisted as localHash
            // and sent as the content hash never content-pairs on reconcile, so the file uploads as a
            // silent Drive duplicate (and multiple failures would even share one hash). The caller
            // treats null as a hard per-file failure and retries the row next pass.
            val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            stream.use { s ->
                val buffer = ByteArray(8192)
                var read: Int
                while (s.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** What the delete-after-backup step does with the device copy, decided by [resolveDeleteAfterBackupRemoval]. */
    enum class Removal {
        /** Move the device copy to the system trash, recoverable for the OS retention window. */
        TRASH,
        /** Remove the device copy outright, with no recovery. */
        PERMANENT,
        /** Leave the device copy alone. */
        NONE,
    }

    companion object {
        /**
         * Whether an upload's device copy is trashed, permanently removed, or kept, the single
         * safety decision of the delete-after-backup path. Pure and side-effect-free so it can be
         * pinned by a plain JVM test.
         *
         * The device copy is touched only when the user opted into [deleteLocalAfterBackup] AND the
         * Drive copy is committed ([uploadSucceeded]) — a failed upload must never cost the user the
         * only copy of a photo. From R the removal goes through the system trash so it stays
         * recoverable; below R no system trash exists, so the copy can only be removed outright.
         *
         * Free-up-space and Hide deliberately do NOT trash and do not route through here:
         * free-up-space would reclaim zero bytes until the trash retention expires, and a hidden
         * photo is already preserved in the app-private Hidden vault, so trashing it would surface
         * it in the device gallery's Recently Deleted.
         */
        fun resolveDeleteAfterBackupRemoval(
            sdkInt: Int,
            deleteLocalAfterBackup: Boolean,
            uploadSucceeded: Boolean,
        ): Removal = when {
            !deleteLocalAfterBackup || !uploadSucceeded -> Removal.NONE
            sdkInt >= Build.VERSION_CODES.R -> Removal.TRASH
            else -> Removal.PERMANENT
        }

        /**
         * The capture time an upload stamps onto its Drive copy (PhotoMetaDto.captureTime and the
         * xAttr ModificationTime), chosen from the sources the caller already holds. Pure and
         * side-effect-free so the choice can be pinned by a plain JVM test; the EXIF read that
         * produces [exifDateTimeOriginalMs] stays in the caller because it is I/O.
         *
         * Precedence: strip-timestamp floors the time to [nowMs] so the cloud metadata cannot
         * reconstruct when the shot was taken; otherwise an explicit MediaStore DATE_TAKEN
         * ([dateTakenIsExplicit]) wins; otherwise a non-explicit MediaStore date falls back to a
         * positive [exifDateTimeOriginalMs]; with none of those the MediaStore date is kept.
         */
        fun resolveUploadCaptureTimeMs(
            mediaStoreDateTakenMs: Long,
            dateTakenIsExplicit: Boolean,
            exifDateTimeOriginalMs: Long?,
            stripTimestamp: Boolean,
            nowMs: Long,
        ): Long {
            if (stripTimestamp) return nowMs
            if (!dateTakenIsExplicit && exifDateTimeOriginalMs != null && exifDateTimeOriginalMs > 0L) {
                return exifDateTimeOriginalMs
            }
            return mediaStoreDateTakenMs
        }
    }
}
