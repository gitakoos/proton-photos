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
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.preferences.putAlbumMirrorFolders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
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
import eu.akoos.photos.data.preferences.albumMirrorFolders
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.preferences.CompressionPreferences
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.StorageFullException
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.ExifDateFormat
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PerfDiagnostics
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Compressible videos per batch allowed past the video lane at once, which bounds the temp copies on
 *  disk. Taken before the upload slot, so a video waiting here holds nothing a photo needs. */
private const val VIDEO_PIPELINE_DEPTH = 2

/**
 * Per-batch upload parallelism. Three concurrent uploads matches the inner CDN-block
 * semaphore in [eu.akoos.photos.data.repository.drive.PhotoUploadService] (2 blocks × ~1.5
 * outer queue depth keeps the pipe saturated without starving the shared Proton networkSemaphore).
 * Bumped from 1 — serial uploads were the dominant cost in long sync batches and contributed
 * to the Android 14+ foreground-service daily-budget exhaustion.
 */
private const val UPLOAD_PARALLELISM = 3

/** Passes a queued add to an album shared with this user may miss before the pair is let go. */
internal const val FOREIGN_ALBUM_JOIN_ATTEMPTS = 5

@Singleton
class UploadPendingUseCase @Inject constructor(
    private val syncStateRepo: SyncStateRepository,
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val pendingDeleteNotif: PendingDeleteNotificationUseCase,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
    private val uploadAlbumTargetDao: eu.akoos.photos.data.db.dao.UploadAlbumTargetDao,
    private val pendingMetadataEditDao: eu.akoos.photos.data.db.dao.PendingMetadataEditDao,
    private val photoLocationDao: eu.akoos.photos.data.db.dao.PhotoLocationDao,
    private val structuralStripper: UploadStructuralStripper,
    private val compressionStep: UploadCompressionStep,
    @ApplicationContext private val context: Context,
    @eu.akoos.photos.di.AppScope private val appScope: kotlinx.coroutines.CoroutineScope,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) {
    private val mutex = Mutex()

    private val xAttrBuilder = eu.akoos.photos.data.upload.UploadXAttrBuilder(context)
    private val deviceFiles = eu.akoos.photos.data.upload.UploadDeviceFiles(context)
    private val bytePreparer = UploadBytePreparer(context, structuralStripper, compressionStep, deviceFiles, photoLocationDao)
    private val bucketAlbumMirror = BucketAlbumMirror(cloudRepo, context)
    private val albumJoiner = QueuedAlbumJoiner(
        cloudRepo, syncStateRepo, uploadAlbumTargetDao, albumListEvents, appScope, context,
    )

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
     * Joins the photos [bucketName] already backed up ([onDrive], device uri to Drive link, see
     * [DrivePairLookup]) to the folder's Drive album, for when its album mirror is turned on (see
     * [BucketAlbumMirror.backfill]). Runs under the batch lock, so this and an upload pass can never both
     * create the folder's album. Returns how many photos were queued.
     */
    suspend fun backfillFolderAlbum(userId: UserId, bucketName: String, onDrive: Map<String, String>): Int = mutex.withLock {
        val queued = bucketAlbumMirror.backfill(userId, bucketName, onDrive, uploadAlbumTargetDao)
        if (queued > 0) albumListEvents.notifyChanged()
        queued
    }

    /**
     * Brings the Drive albums of mirrored folders in step after photos moved between device folders on the
     * phone ([sourceBucketByUri], device uri to the folder it left, into [targetBucket]; [onDrive] maps the moved
     * photos already on Drive to their links); the rule is [folderMoveAlbumPlan]. Those photos leave each mirrored
     * source folder's album (and any
     * join still queued for it), the target folder starts mirroring when it did not, and the photos join its
     * album through [BucketAlbumMirror.backfill], which creates the album when missing and queues what does not
     * land. Runs under the batch lock, like an upload pass. Returns the plan carried out, or null for none.
     */
    internal suspend fun followFolderMove(
        userId: UserId,
        sourceBucketByUri: Map<String, String?>,
        onDrive: Map<String, String>,
        targetBucket: String,
        emptiedSources: Set<String> = emptySet(),
    ): FolderMoveAlbumPlan? = mutex.withLock {
        val rule = context.settingsDataStore.data.first().albumMirrorFolders()
        // The album tick follows a folder renamed, or moved whole into a new one, also when none of its photos is on
        // Drive yet. Read and carried here, after the plan below reads the rule as it was before the move.
        val tickFrom = albumTickToCarry(rule, sourceBucketByUri, emptiedSources, targetBucket)
        val carryTick: suspend () -> Unit = {
            if (tickFrom != null) {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs.albumMirrorFolders()
                    prefs.putAlbumMirrorFolders(
                        current.copy(optedIn = current.optedIn - tickFrom + targetBucket, optedOut = current.optedOut - targetBucket),
                    )
                }
            }
        }
        val plan = folderMoveAlbumPlan(rule, sourceBucketByUri, onDrive, targetBucket)
        if (plan == null) {
            carryTick()
            return@withLock null
        }
        if (plan.removals.isNotEmpty()) {
            val albumsByName = cloudRepo.loadAlbums(userId).associate { it.name.lowercase() to it.linkId }
            // A folder renamed, or moved whole into a new one: its album is renamed along and keeps everything.
            val renamed = folderAlbumToRename(plan, sourceBucketByUri, emptiedSources, albumsByName.keys, targetBucket)
            if (renamed != null) {
                cloudRepo.renameAlbum(userId, albumsByName.getValue(renamed.lowercase()), targetBucket)
                carryTick()
                context.settingsDataStore.edit { prefs ->
                    val current = prefs.albumMirrorFolders()
                    if (targetBucket !in current) prefs.putAlbumMirrorFolders(current.flipped(targetBucket))
                }
                albumListEvents.notifyChanged()
                eu.akoos.photos.util.SyncDiagnostics.log("folder move albums: the folder's album was renamed along")
                return@withLock plan
            }
            for ((bucket, pairs) in plan.removals) {
                val albumLinkId = albumsByName[bucket.lowercase()] ?: continue
                pairs.keys.forEach { uploadAlbumTargetDao.deleteTarget(it, albumLinkId) }
                cloudRepo.removePhotosFromAlbum(userId, albumLinkId, pairs.values.distinct())
            }
        }
        carryTick()
        if (plan.optInTarget) {
            context.settingsDataStore.edit { prefs ->
                val current = prefs.albumMirrorFolders()
                if (targetBucket !in current) prefs.putAlbumMirrorFolders(current.flipped(targetBucket))
            }
        }
        bucketAlbumMirror.backfill(userId, targetBucket, plan.joins, uploadAlbumTargetDao)
        albumListEvents.notifyChanged()
        eu.akoos.photos.util.SyncDiagnostics.log(
            "folder move albums: ${plan.joins.size} photo(s) to the target album, " +
                "${plan.removals.values.sumOf { it.size }} out of ${plan.removals.size} source album(s)",
        )
        plan
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
        // The pending count is known only after the selection below, so each return's end mark
        // carries the count the pass attempted.
        PerfDiagnostics.mark("upload:start", 0)
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
        deviceFiles.sweepStaleUploadTemps()

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
        val albumOptInFolders = prefs.albumMirrorFolders()

        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false
        // #108: the video path re-encodes at its own tier, seeded from the shared value on upgrade.
        val compression = CompressionPreferences.settings(prefs)
        val mirrorStripToLocal = prefs[SettingsKeys.MIRROR_STRIP_TO_LOCAL] ?: false
        val mirrorCompressToLocal = prefs[SettingsKeys.MIRROR_COMPRESS_TO_LOCAL] ?: false
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
            albumJoiner.joinQueuedAlbum(userId, target.localUri, target.albumLinkId, cloudId)
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

        // Nothing selected for backup (onboarding's "Nothing for now") and nothing queued by hand: end
        // here, before the Wi-Fi and listing deferrals, which would otherwise report "Waiting for
        // Wi-Fi" or "Preparing backup" for a backup that isn't set up.
        if (!backupEverything && (selectedFolders == null || selectedFolders.isEmpty()) && !hasExplicitBypass) {
            Log.d(UPLOAD_TAG, "No backup folders configured, skipping upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.Idle, 0, 0))
            PerfDiagnostics.mark("upload:end", 0)
            return@withLock Result(attempted = 0, successCount = 0)
        }

        // Wi-Fi-only enforcement for the auto-sync drain (absent key = ON, matching the rest of the
        // app). ANY-Wi-Fi semantics: a metered Wi-Fi (hotspot, some routers) is still allowed, but
        // mobile data is not. Centralised here so the inline callers (pull-to-refresh, Settings sync,
        // editor) get the same guard the worker relies on. An explicit forced upload (a queued
        // album-add / "back up now") bypasses it — the user asked for that one regardless of network.
        val wifiOnly = prefs[SettingsKeys.SYNC_WIFI_ONLY] != false
        if (wifiOnly && !hasExplicitBypass && !networkObserver.currentlyOnWifi()) {
            Log.d(UPLOAD_TAG, "Wi-Fi-only on and not on Wi-Fi — skipping auto-sync upload")
            _progress.tryEmit(UploadProgress("", "", UploadStatus.WaitingForWifi, 0, 0))
            PerfDiagnostics.mark("upload:end", 0)
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
            PerfDiagnostics.mark("upload:end", 0)
            return@withLock Result(attempted = 0, successCount = 0)
        }

        // Recovery for force-queued album-adds. A photo the user added to an album has a row in the
        // upload_album_target table. An interrupted prior pass can leave its sync_state row stranded in
        // a non-LOCAL_ONLY status while it still has no cloud copy: that row is invisible to BOTH the
        // LOCAL_ONLY filter below and the cloudFileId-keyed album drain above, so the photo shows
        // "uploading" forever and redoing the album-add never re-queues it. Reset such rows to a clean
        // LOCAL_ONLY so this pass uploads them and then joins the album. HIDDEN is left untouched (the
        // user moved it out of backup on purpose), matching ForceUploadLocalUrisUseCase. So is UPLOADING:
        // resetStaleUploadingClaims already reset the claims a dead process stranded, so one seen here is
        // a live upload (an editor or metadata save runs outside this lock, and an album-add can target a
        // photo mid-upload), and resetting it would upload the photo a second time. Manual rows are
        // covered by the stranded-intent recovery, so no separate manual set is needed here.
        val albumTargetUris: Set<String> = uploadAlbumTargetDao.getAll().map { it.localUri }.toSet()

        // A device file queued for a cloud metadata edit is owned end to end by MetadataEditWorker (via
        // the synced replace path): it seeds its own UPLOADING row and uploads the corrected copy itself.
        // Exclude those URIs here so this backup selector never races the worker on the same file. The
        // race is real on a process kill: the pending_metadata_edit row survives, but resetStaleUploadingClaims
        // would reset the sync row to LOCAL_ONLY and this selector would upload it as a SECOND copy.
        val metadataEditUris: Set<String> = pendingMetadataEditDao.allPendingDeviceUris().toSet()

        val strandedForced = allStates.filter {
            it.localUri in albumTargetUris &&
                it.localUri !in metadataEditUris &&
                it.cloudFileId == null &&
                it.status != SyncStatus.LOCAL_ONLY &&
                it.status != SyncStatus.UPLOADING &&
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

        // Keep the metadata-edit worker's files out of the backup queue (see [metadataEditUris] above):
        // the worker uploads the corrected copy, so a backup upload here would be a duplicate.
        if (metadataEditUris.isNotEmpty()) {
            pending = pending.filterNot { it.localUri in metadataEditUris }
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
        // The same listing gives each pending row's MIME type, so the batch knows which videos queue
        // for the video lane.
        var mimeByUri: Map<String, String> = emptyMap()
        if (pending.isNotEmpty()) {
            val listing = localRepo.freshMedia()
            mimeByUri = listing.associate { it.uri to it.mimeType }
            val bucketByUri: Map<String, String?> = listing.associate { it.uri to it.bucketName }
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
            PerfDiagnostics.mark("upload:end", 0)
            return@withLock Result(attempted = 0, successCount = 0)
        }
        Log.d(UPLOAD_TAG, "Starting upload of ${pending.size} pending item(s)")

        val albumBatch = bucketAlbumMirror.prepare(userId, prefs)

        val successCount = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)
        val totalCount = pending.size

        // The batch's upload slots. At most UPLOAD_PARALLELISM uploads are on the wire at once, matching the
        // CDN-block parallelism inside PhotoUploadService so a single batch fully uses the pipe without
        // over-subscribing it. A video hands its slot back while it transcodes (see [releasedWhile]).
        val uploadSemaphore = Semaphore(UPLOAD_PARALLELISM)

        // Storage-full is a fatal-for-batch signal: the OS won't accept more bytes for *anyone*.
        // Set from inside a running upload; the workers check it before starting the next row, so
        // no hash is computed for a file that can't upload. AtomicBoolean
        // (not @Volatile var — @Volatile is a field-only annotation, not allowed on locals)
        // gives us safe cross-coroutine writes.
        val storageFullHit = AtomicBoolean(false)

        // Source URIs of the photos that upload cleanly, for the History thumbnails. Thread-safe
        // because the parallel tasks add to it concurrently.
        val successUris = java.util.concurrent.ConcurrentLinkedQueue<String>()
        // What compression saved on the files that uploaded, for the History entry.
        val savedBytes = AtomicLong(0L)

        try {
            // Compressible videos run in their own small pool, so a queue of them never holds up the photos;
            // both pools share the batch's upload slots, which is what caps the network at UPLOAD_PARALLELISM.
            // The stop and storage-full flags are plain booleans read before each row is claimed, never a
            // cancellation: a stop keeps the remaining rows from starting while an upload already running
            // finishes.
            val (videos, others) = pending.partition { state ->
                compression.videos && mimeByUri[state.localUri]?.startsWith("video/") == true
            }
            val shouldStop = { stopRequested.get() || storageFullHit.get() }
            suspend fun upload(state: SyncState) = uploadSemaphore.withPermit {
                // Re-check after taking the slot: rows ahead may have set a flag while this one waited.
                if (shouldStop()) return@withPermit
                uploadOne(
                    userId = userId,
                    state = state,
                    totalCount = totalCount,
                    manualRun = manualRun,
                    albumTargetUris = albumTargetUris,
                    albumOptInFolders = albumOptInFolders,
                    stripOnUpload = stripOnUpload,
                    compression = compression,
                    mirrorStripToLocal = mirrorStripToLocal,
                    mirrorCompressToLocal = mirrorCompressToLocal,
                    renameToCaptureDate = renameToCaptureDate,
                    deleteLocalAfterBackup = deleteLocalAfterBackup,
                    stripConfig = stripConfig,
                    albumBatch = albumBatch,
                    successCount = successCount,
                    successUris = successUris,
                    finishedCount = finishedCount,
                    onStorageFull = { storageFullHit.set(true) },
                    uploadSlot = uploadSemaphore,
                    savedBytes = savedBytes,
                )
            }
            coroutineScope {
                val videoPool = async { runUploadWorkers(videos, VIDEO_PIPELINE_DEPTH, shouldStop) { upload(it) } }
                val photoPool = async { runUploadWorkers(others, UPLOAD_PARALLELISM, shouldStop) { upload(it) } }
                awaitAll(videoPool, photoPool)
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
            savedBytes = savedBytes.get(),
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
        PerfDiagnostics.mark("upload:end", pending.size)
        Result(attempted = pending.size, successCount = finalSuccess)
    }

    /**
     * Upload a single pending photo. Extracted from [invoke] so each upload worker (see
     * [runUploadWorkers]) can call it once per row. Per-photo errors are caught here so one
     * failure doesn't tear down the other workers via structured concurrency.
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
        albumOptInFolders: eu.akoos.photos.data.preferences.AlbumMirrorFolders,
        stripOnUpload: Boolean,
        compression: CompressionSettings,
        mirrorStripToLocal: Boolean,
        mirrorCompressToLocal: Boolean,
        renameToCaptureDate: Boolean,
        deleteLocalAfterBackup: Boolean,
        stripConfig: MetadataStripConfig,
        albumBatch: BucketAlbumMirror.Batch,
        successCount: AtomicInteger,
        successUris: MutableCollection<String>,
        finishedCount: AtomicInteger,
        onStorageFull: () -> Unit,
        /** The batch's upload slots; this item holds one and hands it back while it transcodes. */
        uploadSlot: Semaphore,
        savedBytes: AtomicLong,
    ) {
        val temps = UploadTemps()
        try {
            val rawLocalItem = localRepo.queryByUri(state.localUri)
            if (rawLocalItem == null) {
                // The queued photo was deleted from the device before it uploaded. Drop its orphaned
                // LOCAL_ONLY row (status-guarded, so a row another pass already claimed is left alone)
                // so it stops showing as a phantom "Queued" tile and is not re-processed, and advance
                // the finished tally (NOT the success tally, which drives allFailed) so the batch's
                // "N of M" count reaches completion instead of sticking on the deleted photos.
                Log.w(UPLOAD_TAG, "Queued photo gone before upload, dropping it: ${state.localUri}")
                syncStateRepo.deleteLocalOnlyByUris(listOf(state.localUri))
                finishedCount.incrementAndGet()
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
                // Wi-Fi-only, enforced PER PHOTO (see [uploadDefersForWifiOnly]): an auto-queued photo
                // never rides mobile data, even when an explicit "back up now" or album-add opened this
                // pass. Only the explicitly-picked photos (which skip this whole block) go over cellular,
                // so a single manual pick uploads just that one rather than dragging the auto backlog
                // onto mobile. Read live so a Wi-Fi drop mid-batch stops the not-yet-started items.
                if (uploadDefersForWifiOnly(
                        state.queueSource,
                        wifiOnly = livePrefs[SettingsKeys.SYNC_WIFI_ONLY] != false,
                        onWifi = networkObserver.currentlyOnWifi(),
                    )
                ) {
                    Log.d(UPLOAD_TAG, "Skipping ${rawLocalItem.displayName}: Wi-Fi-only on and not on Wi-Fi")
                    return
                }
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
            // native PGP call and crash the process. A user stop is honoured only at the upload workers'
            // item boundary (before the claim), so a claimed item is never abandoned mid-pipeline. A row
            // stranded UPLOADING by a genuine process death is recovered on the next pass.
            // Optional rename: derive cloud displayName from the source's capture timestamp
            // (MediaStore DATE_TAKEN). The on-device file keeps its own name unless "mirror to
            // local" is on, which renames it to match — deferred until AFTER the upload succeeds
            // (below) so a failed upload never renames a file that has no Drive copy. Strip-on-upload
            // runs against the bytes independently, so a stripped + renamed photo still gets erased.
            var mirrorRenameTarget: String? = null
            val renamedItem = if (renameToCaptureDate) {
                val newName = uploadRenamedName(rawLocalItem.displayName, rawLocalItem.dateTaken, System.currentTimeMillis())
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

            val strippedUploadUri: String = bytePreparer.strip(
                userId, state, localItem, stripOnUpload, stripConfig, mirrorStripToLocal, compression, temps,
            )

            val prepared = bytePreparer.compress(
                state, localItem, stripOnUpload, stripConfig, compression, strippedUploadUri, temps,
                totalCount, finishedCount, uploadSlot,
            ) { _progress.tryEmit(it) }
            val finalUploadUri = prepared.finalUploadUri
            val compressIsMotionPhoto = prepared.compressIsMotionPhoto

            val sha1StartMs = System.currentTimeMillis()
            val hash = deviceFiles.computeSha1(finalUploadUri)
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
            val uploadTempFile = temps.compressedFile ?: temps.strippedFile
            // Kept for the batch's savings, since compressedFile is deleted below.
            val compressedSize = temps.compressedFile?.length() ?: 0L
            val sizedItem = if (uploadTempFile != null)
                localItem.copy(sizeBytes = uploadTempFile.length())
            else
                localItem
            // After a re-encode the name and MIME type follow the bytes; the device file keeps its own.
            val (uploadName, uploadMime) = UploadFormatNaming.forProducedFormat(
                sizedItem.displayName, sizedItem.mimeType, temps.producedMime,
            )
            val uploadItem = sizedItem.copy(displayName = uploadName, mimeType = uploadMime)

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
                if (temps.compressedFile != null && localItem.mimeType.startsWith("video/")) {
                    xAttrBuilder.probeVideoDimensions(temps.compressedFile!!)
                } else {
                    null
                }
            // Resolve Camera/Location + rotation-corrected dimensions from the ORIGINAL source
            // (pre-strip), gated against the strip config so the xAttr never re-leaks a field the
            // file had erased. uploadItem.dateTaken is already floored when timestamps are stripped.
            val xAttrMetadata = xAttrBuilder.buildXAttrMetadata(
                sourceUri = state.localUri,
                item = uploadItem,
                stripOnUpload = stripOnUpload,
                stripConfig = stripConfig,
                videoDimsOverride = videoDimsOverride,
            )
            val cloudId = cloudRepo.uploadFile(
                userId, uploadItem, hash, finalUploadUri, xAttrMetadata, onProgressForFile,
            )

            bytePreparer.mirrorCompressAfterUpload(
                state, localItem, stripOnUpload, mirrorStripToLocal, mirrorCompressToLocal, compression,
                compressIsMotionPhoto, temps,
            )

            temps.strippedFile?.delete()
            temps.strippedFile = null
            temps.compressedFile?.delete()
            temps.compressedFile = null
            temps.tsFloorFile?.delete()
            temps.tsFloorFile = null

            // The file is on Drive once uploadFile returns a real cloudId. Record that fact
            // non-cancellably so an interrupt in this window can't leave the row LOCAL_ONLY with
            // a null cloudFileId — which would re-select and re-upload the same file (a Drive
            // duplicate) on the next pass.
            // The row records the device file as it is now, so reconcile can tell a later edit by another
            // app from this upload's own mirror rewrite: the hash and the length of the rewritten device
            // bytes, or the size the device reported when this upload read a file it left alone.
            val deviceHash = if (temps.deviceRewritten && finalUploadUri != state.localUri) {
                deviceFiles.computeSha1(state.localUri) ?: hash
            } else {
                hash
            }
            val syncedState = state.copy(
                cloudFileId = cloudId,
                localHash = deviceHash,
                status = SyncStatus.SYNCED,
                lastSyncSuccessMs = System.currentTimeMillis(),
                backedUpAtMs = System.currentTimeMillis(),
                sizeBytes = (if (temps.deviceRewritten) deviceFiles.deviceFileLength(state.localUri) else null)
                    ?: rawLocalItem.sizeBytes.takeIf { it > 0L } ?: state.sizeBytes,
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
            mirrorRenameTarget?.let { deviceFiles.renameLocalInPlace(state.localUri, it) }

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
            if (compressedSize > 0L) savedBytes.addAndGet((localItem.sizeBytes - compressedSize).coerceAtLeast(0L))
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

            bucketAlbumMirror.mirror(userId, albumBatch, localItem.bucketName, albumOptInFolders, cloudId)

            // User-queued album-adds for this freshly-uploaded photo, read from the target table (the
            // single source of truth). These are album linkIds directly (the user picked an existing
            // album for a local-only photo), so skip the bucket-name resolve/create the mirror loop
            // above does. A manual "back up now" has no target rows, so this finds nothing for it.
            albumJoiner.joinQueuedAlbums(userId, state.localUri, cloudId)
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
            temps.strippedFile?.delete()
            temps.compressedFile?.delete()
            temps.tsFloorFile?.delete()
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

        /**
         * Whether an upload's mirror step actually rewrote the on-device file GPS-free, which is what
         * makes that file's stored `photo_location` fix stale. All three have to hold, and each fails
         * the same way when it does not: [stripGpsOnUpload] is the effective GPS strip (strip-on-upload
         * with GPS in its set), and with it off the file keeps its coordinates; [mirrorToLocal] gates the
         * in-place rewrite, and with it off only a temp copy is stripped while the device original is
         * left whole; [deviceRewriteSucceeded] is the write's own outcome, and the OS can refuse it,
         * leaving the original intact. A plain strip-on-upload with no mirror therefore never satisfies
         * this, so its still-located device file keeps its map point.
         *
         * Pure and side-effect-free, so the matrix is pinned by a plain JVM test.
         */
        fun mirrorRemovedDeviceGps(
            stripGpsOnUpload: Boolean,
            mirrorToLocal: Boolean,
            deviceRewriteSucceeded: Boolean,
        ): Boolean = stripGpsOnUpload && mirrorToLocal && deviceRewriteSucceeded

        /**
         * The cloud displayName a rename-on-upload derives from a source's capture timestamp,
         * formatted `yyyy-MM-dd_HH-mm-ss` and keeping the original extension. A non-positive
         * [dateTakenMs] (absent MediaStore DATE_TAKEN) falls back to [nowMs]. Pure and
         * side-effect-free so the naming is pinned by a plain JVM test.
         */
        fun uploadRenamedName(displayName: String, dateTakenMs: Long, nowMs: Long): String {
            val ext = displayName.substringAfterLast('.', "")
            val captureMs = dateTakenMs.takeIf { it > 0L } ?: nowMs
            val base = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(captureMs))
            return if (ext.isNotEmpty()) "$base.$ext" else base
        }
    }
}
