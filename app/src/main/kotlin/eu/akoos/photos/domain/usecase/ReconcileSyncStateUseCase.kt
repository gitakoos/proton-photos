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

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoPairingRow
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncProgress
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.PerfDiagnostics
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReconcileUseCase"

/** A just-uploaded photo is marked SYNCED before the cloud listing has caught up, so its cloud
 *  linkId is briefly absent from the local cloud DB. Don't demote such a row (which would re-queue
 *  it for a duplicate upload and drop the green "backed up" badge) until it has been missing past
 *  this window; by then the listing has refreshed and a true cloud-side delete is real. */
private const val CLOUD_ABSENCE_GRACE_MS = 15 * 60 * 1000L

/** Failed reads of the cloud listing retried, after a growing pause, before a pass gives up. A
 *  whole-library read that lands mid-write can fail on a window refill the next read gets past. */
private const val CLOUD_READ_RETRIES = 5L

/**
 * Pairs the device's photos with their cloud copies and records the outcome in sync_state.
 *
 * One instance serves the whole app and runs one pass at a time. Callers for the same account share
 * a [SharedRunGate], so the callers a cold start fires together cost the pass already under way plus
 * at most one more, and passes for different accounts take turns. A caller returns once a pass that
 * began after it asked has finished.
 */
@Singleton
class ReconcileSyncStateUseCase @Inject constructor(
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val photoListingDao: PhotoListingDao,
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
) {
    private val gates = ConcurrentHashMap<String, SharedRunGate<Int>>()

    private val passTurns = Mutex()

    /** Emits a running mark at once, then the finished totals of a pass that began after this call.
     *  Throws what that pass threw. */
    suspend operator fun invoke(userId: UserId): Flow<SyncProgress> = flow {
        emit(SyncProgress(total = 0, done = 0, running = true))
        val gate = gates.computeIfAbsent(userId.id) {
            SharedRunGate(appScope, passTurns) { reconcile(userId) }
        }
        val total = gate.run()
        emit(SyncProgress(total, total, false))
    }

    /** One pass for [userId]. Returns how many device and cloud items it weighed. */
    private suspend fun reconcile(userId: UserId): Int {
        // Read folder filter from settings.
        // null (key absent) = first run, backup nothing; non-null set = backup only listed folders.
        // BACKUP_EVERYTHING short-circuits the folder filter entirely: every MediaStore
        // image/video goes through reconcile, regardless of bucket. That's the "auto-back
        // up like Google Photos" mode, opt-in via Settings.
        val prefs: Preferences = context.settingsDataStore.data.first()
        val backupEverything = prefs[SettingsKeys.BACKUP_EVERYTHING] ?: false
        val selectedFolders: Set<String>? = prefs[SettingsKeys.SYNC_FOLDER_NAMES]
        // Backup-everything carve-outs. Only consulted while [backupEverything] is true;
        // when the per-folder picker is in charge, exclusions don't apply (the user picked
        // an explicit allow-list; we don't second-guess it with a parallel deny-list).
        val excludedFolders: Set<String> = prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet()

        // Has the cloud library been listed end-to-end at least once? The content-hash recompute
        // below only runs when it has; a missing hash match against a half-walked listing would be
        // a wasted file read (the twin may simply be unlisted so far). Mirrors the upload-time gate.
        val initialListingComplete = prefs.asMap().any { (k, v) ->
            k.name.startsWith("photo_listing_ever_complete_${userId.id}_") && v == true
        }
        // Strip-on-upload and compress-on-upload both rewrite a photo's bytes before it reaches
        // Drive, so its cloud copy can't content-hash-match the untouched local original. When
        // either is on, the name/date match stays the fallback for those; when both are off, the
        // bytes are identical and a content-hash match is REQUIRED before pairing, so a different
        // file that merely shares a name (Drive allows that) can't be taken for a backup.
        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false
        val compressOnUpload = prefs[SettingsKeys.COMPRESS_ON_UPLOAD] ?: false
        // Videos have their own compression switch; reading only the photo one left a compressed
        // video unable to re-pair after a reinstall (its bytes no longer hash-match the original).
        val compressVideosOnUpload = prefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] ?: false

        // A scan that saw the device as it is now: the demotions and the gone-file drop below act on what the
        // device no longer holds, which a list kept from before could still show. Those steps also
        // need the scan to have seen the whole device, and leave alone a row written since it began (or
        // since this pass asked, if that was earlier); see [deviceFileGone]. The pairing itself works with
        // whatever the scan found.
        val askedAtMs = System.currentTimeMillis()
        val scan = localRepo.freshScan()
        val scanStartMs = if (scan.startedAtMs > 0L) minOf(askedAtMs, scan.startedAtMs) else askedAtMs
        val allLocalItems = scan.items
        if (!scan.complete) {
            Log.w(TAG, "device scan incomplete (${allLocalItems.size} items): gone-file steps skipped this pass")
        }
        PerfDiagnostics.mark("reconcile:start", allLocalItems.size)

        val localItems = if (backupEverything) {
            // Backup-everything mode: skip the folder selection entirely. Every local
            // image/video flows into reconcile and gets either marked SYNCED (already
            // on Drive) or LOCAL_ONLY (needs upload). Excluded buckets are dropped here
            // so the user can keep Screenshots / Movies / WhatsApp-Status out of Drive
            // without abandoning the everything-else guarantee. Items with a null bucket
            // (rare, usually orphan rows) bypass the exclusion since we can't match.
            if (excludedFolders.isNotEmpty()) {
                val kept = allLocalItems.filter { it.bucketName == null || it.bucketName !in excludedFolders }
                Log.d(TAG, "Backup-everything ON, ${excludedFolders.size} excluded: " +
                    "${kept.size}/${allLocalItems.size} items eligible")
                kept
            } else {
                Log.d(TAG, "Backup-everything ON: ${allLocalItems.size} items eligible (no folder filter)")
                allLocalItems
            }
        } else if (selectedFolders == null) {
            // Key absent → first run, user hasn't selected any folders yet; backup nothing.
            Log.d(TAG, "No backup folders configured (first-run default), nothing to upload")
            emptyList()
        } else if (selectedFolders.isEmpty()) {
            // Explicit empty set → user disabled backup for all folders
            Log.d(TAG, "No folders selected for backup, nothing to upload")
            emptyList()
        } else {
            // Keep items whose bucket is in the selected set (or has no bucket name)
            allLocalItems.filter { it.bucketName == null || it.bucketName in selectedFolders }
                .also { Log.d(TAG, "Folder filter applied: ${it.size}/${allLocalItems.size} items") }
        }

        val recorded = pairAndRecord(
            userId, allLocalItems, localItems, initialListingComplete, stripOnUpload, compressOnUpload,
            compressVideosOnUpload, scan.complete, scanStartMs,
        )
        clearQueue(userId, recorded.allLocalUris, recorded.inScopeUris, scan.complete, scanStartMs)

        // This pass paired against a complete cloud listing, so any still-LOCAL_ONLY row is genuinely
        // new and safe for the bulk upload to drain. Flip the gate UNCONDITIONALLY (even with zero
        // cloud photos: a fresh account must still let its locals upload). The upload defers until
        // this is set, so the post-completion reconcile always runs the content-hash pairing first.
        if (initialListingComplete) {
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.pairingSettledKey(userId.id)] = true
                // Same condition, same moment: this pass checked sync_state against the full cloud
                // set, so anything whose twin had gone has been demoted by now. The automatic
                // free-up sweep reads this to decide whether its picture of the cloud is recent
                // enough to delete a device copy against.
                p[SettingsKeys.cloudVerifiedAtKey(userId.id)] = System.currentTimeMillis()
            }
        }

        return recorded.total
    }

    /** What [pairAndRecord] leaves for the queue clean-up after it. */
    private class Recorded(
        val total: Int,
        val allLocalUris: Set<String>,
        val inScopeUris: Set<String>,
    )

    /**
     * Pairs every in-scope device file, demotes the rows whose cloud or device copy has gone, and
     * writes the outcome. Everything it holds is dropped when it returns, before the queue clean-up
     * reads the table again.
     */
    private suspend fun pairAndRecord(
        userId: UserId,
        allLocalItems: List<LocalMediaItem>,
        localItems: List<LocalMediaItem>,
        initialListingComplete: Boolean,
        stripOnUpload: Boolean,
        compressOnUpload: Boolean,
        compressVideosOnUpload: Boolean,
        scanComplete: Boolean,
        scanStartMs: Long,
    ): Recorded {
        // Unpacked, so the snapshot and the cloud rows can go after their last use below (the partition
        // and the demotion loop) instead of living through every write.
        val (total, newStates, freshAutoFolderUris, pairedSyncedUris, existingByUri, cloudByLinkId) =
            matchLocals(userId, localItems, initialListingComplete, stripOnUpload, compressOnUpload, compressVideosOnUpload)
        PerfDiagnostics.mark("reconcile:end", localItems.size)

        // Previously SYNCED entries whose cloud counterpart was deleted → LOCAL_ONLY (still on device,
        // no longer in Drive). The first loop already covers items in selected folders; this catches
        // items in non-selected folders or edge cases where the file wasn't in localItems.
        //
        // We also catch the inverse: SYNCED rows whose LOCAL file is gone (user deleted from the
        // gallery, or Free up space ran). Those need to flip to CLOUD_ONLY so the UI stops painting
        // a green "downloaded" indicator on a photo that no longer exists on this device.
        //
        // IMPORTANT: this check uses [allLocalItems] (every file MediaStore sees), NOT the
        // backup-filtered [localItems]. A photo downloaded into `DCIM/<AlbumName>/` is
        // still on the device even though that folder typically isn't in the backup selection
        // (the user doesn't want their downloads loop-uploaded). Using the filtered set would
        // demote every just-downloaded SyncState to CLOUD_ONLY on the next reconcile, breaking
        // the green "downloaded" indicator in album detail views (which rely solely on SyncState
        // unlike the main gallery, which also has a contentHash fallback).
        //
        // The same set of every device uri serves the gone-file checks of the queue clean-up.
        val allLocalUris: Set<String> = allLocalItems.mapTo(HashSet(allLocalItems.size)) { it.uri }
        val nowMs = System.currentTimeMillis()
        // Reuse the pre-loop snapshot: this pass only demotes rows that were SYNCED BEFORE
        // reconcile ran, and the new states aren't persisted until the upsertAll below.
        val syncedStates = existingByUri.values
            .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
        val goneCandidates = ArrayList<SyncState>()
        for (state in syncedStates) {
            when {
                // Cloud counterpart absent from the local cloud listing. A photo just uploaded is
                // SYNCED before the listing catches up, so demoting it now would re-queue it for a
                // duplicate upload and drop its green badge. Only demote once it has been missing
                // past the grace window; by then a true cloud-side delete is real.
                state.cloudFileId !in cloudByLinkId ->
                    if (nowMs - (state.lastSyncSuccessMs ?: 0L) > CLOUD_ABSENCE_GRACE_MS) {
                        // Demote only while the row still carries the cloud id this snapshot saw: a twin
                        // genuinely gone still matches and is demoted, but one an upload re-promoted
                        // mid-pass now carries a different id and is left alone (its pairing survives).
                        syncStateRepo.demoteToLocalIfCloudIdMatches(state.localUri, state.cloudFileId!!)
                        // The cloud copy is genuinely gone (past the grace window). This row's prior
                        // upload intent is spent, so clear it: without this the stranded-intent
                        // recovery below would re-queue a photo the user deleted from the cloud and
                        // upload it straight back, on every pass.
                        syncStateRepo.clearQueuedForSynced(state.localUri)
                    }
                // Local file removed from MediaStore → demote to CLOUD_ONLY so we stop telling the
                // user the photo is "on this device" when it actually isn't. Only on a scan that saw
                // the whole device, and only for a row that predates it: a download saved while this
                // pass ran is recorded SYNCED, yet the scan, begun before it, could not list its file.
                deviceFileGone(state, scanComplete, allLocalUris, scanStartMs) -> goneCandidates += state
            }
        }
        // The scan's word alone is not proof: a torn read can skip a row, and a trashed file is still
        // restorable. Only a file a lookup by id no longer finds is demoted.
        if (goneCandidates.isNotEmpty()) {
            val confirmed = localRepo.confirmedGone(goneCandidates.map { it.localUri })
            for (state in goneCandidates) {
                if (state.localUri in confirmed) {
                    syncStateRepo.upsert(state.copy(status = SyncStatus.CLOUD_ONLY), userId)
                }
            }
        }

        val localOnlyCount = newStates.count { it.status == SyncStatus.LOCAL_ONLY }
        val syncedCount    = newStates.count { it.status == SyncStatus.SYNCED }
        Log.d(TAG, "reconcile done: $syncedCount SYNCED, $localOnlyCount LOCAL_ONLY (will upload)")
        // Split the batch so a row an upload promoted to SYNCED+cloudFileId after the pre-loop snapshot
        // is never clobbered back to LOCAL_ONLY. Only a computed-LOCAL_ONLY row that ALREADY EXISTED in
        // that snapshot is clobber-prone (an upload cannot claim a sync_state row that does not exist
        // yet); route those through the guarded update, which no-ops on a row now finished uploading.
        // Everything else stays in the plain batch: a genuinely new row (which must still INSERT) and
        // every SYNCED/promote write.
        val (guardedDemotions, rest) = newStates.partition {
            it.status == SyncStatus.LOCAL_ONLY && existingByUri.containsKey(it.localUri)
        }
        syncStateRepo.upsertAll(rest, userId)
        // A guarded update that changed no row means the row is SYNCED now (the upload won the race),
        // so its uri must not be re-queued below: a finished upload is never queued for a duplicate.
        val skippedDemotionUris = mutableSetOf<String>()
        for (state in guardedDemotions) {
            if (syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(state, userId) == 0) {
                skippedDemotionUris += state.localUri
            }
        }
        // Now that the fresh rows are durable, stamp each newly-unmatched in-scope local as
        // queued=AUTO_FOLDER (why it is up for backup + when). markQueued is a per-row UPDATE, so it
        // must run AFTER the upsert that created the row. The processor selects on LOCAL_ONLY AND
        // queued, so this stamp is what makes a folder-selected backup upload.
        val autoFolderNow = System.currentTimeMillis()
        for (uri in freshAutoFolderUris) {
            if (uri in skippedDemotionUris) continue
            syncStateRepo.markQueued(uri, eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER, autoFolderNow)
        }
        // RULE 1: clear the queued flag on every row paired to a cloud copy this pass. Unguarded
        // (clearQueuedForSynced) because these rows are known backed up; a still-pending upload's
        // intent is never touched here (those rows are LOCAL_ONLY, not in this set).
        for (uri in pairedSyncedUris) {
            syncStateRepo.clearQueuedForSynced(uri)
        }

        // Every in-scope local this pass wrote a row for; the out-of-scope de-queue below spares these.
        val inScopeUris: Set<String> = newStates.mapTo(HashSet(newStates.size)) { it.localUri }
        return Recorded(total, allLocalUris, inScopeUris)
    }

    /** What the pairing loop hands on: the rows it computed, and the two lookups the demotion pass
     *  after it still reads. The other lookups it builds are dropped when it returns. */
    private data class Matched(
        val total: Int,
        val newStates: List<SyncState>,
        val freshAutoFolderUris: List<String>,
        val pairedSyncedUris: List<String>,
        val existingByUri: Map<String, SyncState>,
        val cloudByLinkId: Map<String, PhotoPairingRow>,
    )

    /** Matches each in-scope device file to its cloud copy and computes the row it gets. */
    private suspend fun matchLocals(
        userId: UserId,
        localItems: List<LocalMediaItem>,
        initialListingComplete: Boolean,
        stripOnUpload: Boolean,
        compressOnUpload: Boolean,
        compressVideosOnUpload: Boolean,
    ): Matched {
        // Cloud DB was already refreshed by the caller (GalleryViewModel.refresh / doSync).
        // Avoid calling refreshCloudPhotosIncremental here: it can replay old "add" events
        // and re-insert photos the caller just deleted from the cloud DB.
        val cloudItems = cloudPairingRows(userId)

        val total = localItems.size + cloudItems.size

        // Four lookups, queried in priority order:
        //   1. byId            direct cloud-linkId (most reliable after a successful upload).
        //   2. byHash          content-hash match (definitive: same SHA-256 = same bytes).
        //   3. byNameAndDate   displayName + capture time. Survives metadata-stripping (which
        //                      changes size but not name/captureTime) AND survives a full app
        //                      reinstall / "Clear app data" because it doesn't depend on
        //                      existingSync. captureTime is recorded at upload-time from the
        //                      original local file's DATE_TAKEN, so for any photo our app
        //                      uploaded the cloud side has the matching value to 1-second
        //                      precision (item.dateTaken / 1000 in PhotoUploadService).
        //   4. byName          last-resort name+size. Kept for compatibility with rows that
        //                      somehow have no captureTime, but matches first by captureTime
        //                      because size is fragile across strip-on-upload.
        // One walk in listing order fills all four, a later row replacing an earlier one under the
        // same key, so each lookup keeps the same row a per-lookup associateBy would.
        val cloudByLinkId = HashMap<String, PhotoPairingRow>()
        val cloudByHash = HashMap<String, PhotoPairingRow>()
        val cloudByNameAndDate = HashMap<NameKey, PhotoPairingRow>()
        val cloudByNameSize = HashMap<NameKey, PhotoPairingRow>()
        for (cloud in cloudItems) {
            cloudByLinkId[cloud.linkId] = cloud
            val hash = cloud.contentHash
            if (!hash.isNullOrEmpty()) cloudByHash[hash] = cloud
            if (cloud.captureTime > 0) cloudByNameAndDate[NameKey(cloud.displayName, cloud.captureTime)] = cloud
            cloudByNameSize[NameKey(cloud.displayName, cloud.sizeBytes)] = cloud
        }
        Log.d(TAG, "reconcile: ${localItems.size} local, ${cloudItems.size} cloud " +
            "(${cloudByHash.size} with hash, ${cloudByNameAndDate.size} with captureTime)")

        val newStates = ArrayList<SyncState>(localItems.size)
        // In-scope local URIs that need an AUTO_FOLDER queue stamp this pass. Covers TWO cases: a
        // freshly-created LOCAL_ONLY row (unmatched, no row before), AND an already-LOCAL_ONLY row that
        // is not yet queued (or is queued AUTO_FOLDER), the latter re-queuing a row after its folder is
        // re-selected. A row carrying an explicit MANUAL / ALBUM_ADD source is deliberately left out so
        // that intent is never relabelled AUTO_FOLDER. Stamped via markQueued after the bulk upsert.
        val freshAutoFolderUris = mutableListOf<String>()
        // Local URIs paired to a cloud copy this pass (status SYNCED). Their queued flag is cleared
        // after the bulk upsert (RULE 1) so a just-paired row can't be re-queued for a duplicate.
        val pairedSyncedUris = mutableListOf<String>()

        // One snapshot of every SyncState row, indexed by local URI, instead of a per-item
        // getByUri() DAO round-trip inside the loop below (an N+1 that, at tens of thousands of
        // local items, issued tens of thousands of point queries). The same snapshot also feeds
        // the SYNCED-demotion pass further down: that loop reads rows reconcile hasn't written
        // yet, so the pre-loop view is exactly what it needs.
        val existingByUri = syncStateRepo.observeAll(userId).first().associateBy { it.localUri }
        // Cloud twins already paired to a local file this install. The name+date re-pair below rescues
        // only an UNCLAIMED twin, so it can never steal a cloud copy that already belongs to another
        // local. After a reinstall this set is empty (the rows were wiped), which is exactly when a
        // downloaded file needs to re-pair instead of re-uploading a duplicate.
        val claimedCloudIds: Set<String> = existingByUri.values.asSequence()
            .filter { it.status == SyncStatus.SYNCED }
            .mapNotNull { it.cloudFileId }
            .toSet()

        for (local in localItems) {
            val existingSync = existingByUri[local.uri]
            // HIDDEN rows belong to a photo the user moved into the Hidden vault: the local
            // MediaStore copy is gone (or about to be), so reconcile must NOT generate a new
            // SyncState entry that would overwrite the HIDDEN status with LOCAL_ONLY/SYNCED.
            // Without this skip the next reconcile after a hide would flip the row back to a
            // regular cloud entry and the hidden marker would be lost (manifesting as the
            // hidden photo reappearing as cloud-only after a refresh).
            if (existingSync?.status == SyncStatus.HIDDEN) continue
            // UPLOADING means an editor save's cloud-fanout is in flight against this URI.
            // The editor owns the row until its upload finishes; touching it here would
            // race the editor and produce a duplicate Drive entry. Same skip shape as HIDDEN.
            if (existingSync?.status == SyncStatus.UPLOADING) continue
            val byId = existingSync?.cloudFileId?.let { cloudByLinkId[it] }

            // Content hash is the AUTHORITATIVE matcher: it compares the actual file bytes (the local
            // bare SHA-1 converted to Drive's HMAC ContentHash), so it pairs the same photo whatever
            // its name and (unlike name+size) can't be fooled into pairing two DIFFERENT files that
            // share a name (Drive allows duplicate names). Use the stored hash; if a reinstall wiped
            // it, recompute from the file once (persisted below) when the cloud library is fully
            // listed so there's a complete set to match against. This also pairs a renamed cloud copy.
            val storedHash = existingSync?.localHash?.takeIf { it.isNotEmpty() }
            var recomputedHash: String? = null
            val localHashHex: String? = storedHash
                ?: if (initialListingComplete && cloudByHash.isNotEmpty())
                    localRepo.sha1(local.uri).also { recomputedHash = it } else null
            val byContentHash = localHashHex?.let { cloudRepo.cloudContentHash(it) }?.let { cloudByHash[it] }

            // captureTime in the cloud listing is Unix seconds; LocalMediaItem.dateTaken is ms.
            val localCaptureTimeSec = local.dateTaken / 1000L
            // A compressed or strip-transcoded photo went up as JPEG (IMG.heic as IMG.jpg), a compressed
            // video as MP4 (clip.mov as clip.mp4), so while that rewrite is on, look for that name too.
            val rewritesFormat =
                if (local.mimeType.startsWith("video/")) compressVideosOnUpload else compressOnUpload || stripOnUpload
            val nameDateTwin = cloudByNameAndDate[NameKey(local.displayName, localCaptureTimeSec)]
                ?: UploadFormatNaming.reencodedName(local.displayName, local.mimeType)
                    ?.takeIf { rewritesFormat }
                    ?.let { cloudByNameAndDate[NameKey(it, localCaptureTimeSec)] }
            val nameCandidate = nameDateTwin
                ?: cloudByNameSize[NameKey(local.displayName, local.sizeBytes)]?.takeIf { it.sizeBytes > 0 }
            // Trust a name+date / name+size match ONLY when a content hash can't settle it: the cloud
            // photo carries no ContentHash to check, or strip-on-upload / compress-on-upload changed
            // the bytes so the same photo can't hash-match its rewritten cloud copy. When the cloud
            // photo HAS a hash and neither strip nor compress was on, byContentHash above is the only
            // thing that may pair it; trusting the name alone could mark a different same-named file
            // as backed up, and Free-up-space could then delete a local that was never really
            // uploaded. (No name-only fallback either: a recurring camera name like IMG_0001.jpg must
            // never pair on its own.)
            val byNameUnverifiable = nameCandidate?.takeIf {
                it.contentHash.isNullOrEmpty() ||
                    uploadRewritesBytes(local.mimeType, stripOnUpload, compressOnUpload, compressVideosOnUpload)
            }
            // Last resort, downloads-only: re-pair to a still-present cloud twin by name + EXACT capture
            // second even when that twin HAS a hash. A download stamps the twin's own capture time into
            // the file and rewrites the bytes for date/GPS, so the hash no longer matches yet the second
            // is identical. Restricted to an UNCLAIMED twin, and it deliberately stays out of
            // [contentCertain] below, so it suppresses a duplicate re-upload after a reinstall WITHOUT
            // ever letting free-up-space delete the local on this looser proof.
            val byNameDateRepair = repairTwinLinkId(
                strong = byId != null || byContentHash != null,
                hasLegacyNameMatch = byNameUnverifiable != null,
                nameDateTwinLinkId = nameDateTwin?.linkId,
                claimedTwinLinkIds = claimedCloudIds,
            )?.let { cloudByLinkId[it] }
            val matchedCloud = byId ?: byContentHash ?: byNameUnverifiable ?: byNameDateRepair
            if (matchedCloud == null) {
                Log.d(TAG, "LOCAL_ONLY: ${local.displayName} (size=${local.sizeBytes}, " +
                    "captureSec=$localCaptureTimeSec, existingCloudId=${existingSync?.cloudFileId})")
            }

            val status = if (matchedCloud != null) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY
            // Free-up-space only deletes the local copy of a row that carries a backedUpAtMs stamp.
            // The upload path sets it; a reconcile pairing must set it too so a photo that's provably
            // on Drive (e.g. paired after a reinstall) becomes reclaimable, but ONLY for a
            // content-certain match (direct cloud id or content-hash). A name/date-only match is not
            // proof the bytes are on Drive, so its stamp stays null and free-up-space leaves it alone.
            val contentCertain = byId != null || byContentHash != null
            newStates += SyncState(
                localUri = local.uri,
                cloudFileId = matchedCloud?.linkId,
                localHash = recomputedHash ?: existingSync?.localHash.orEmpty(),
                cloudHash = matchedCloud?.contentHash,
                status = status,
                lastSyncAttemptMs = System.currentTimeMillis(),
                lastSyncSuccessMs = if (status == SyncStatus.SYNCED) System.currentTimeMillis() else null,
                backedUpAtMs = existingSync?.backedUpAtMs ?: (if (contentCertain) System.currentTimeMillis() else null),
                sizeBytes = local.sizeBytes,
            )
            // An in-scope local that needs backing up gets an AUTO_FOLDER queue stamp (after the upsert
            // below). This fires for a brand-new LOCAL_ONLY row AND for an existing LOCAL_ONLY row whose
            // source is null or already AUTO_FOLDER, re-queuing a folder-backup row once its folder is
            // re-selected. Any other source (MANUAL / ALBUM_ADD / EDITOR) is left untouched so
            // an explicit or editor intent is never relabelled AUTO_FOLDER (those rows are already
            // queued, so the selector still picks them up).
            if (status == SyncStatus.LOCAL_ONLY) {
                val src = existingSync?.queueSource
                val stampable = src == null || src == eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER
                if (stampable) freshAutoFolderUris += local.uri
            }
            // RULE 1: every row that lands SYNCED has its queued flag cleared (after the upsert below),
            // so a photo paired here can't be re-queued for a duplicate by a later grace-window demotion.
            if (status == SyncStatus.SYNCED) pairedSyncedUris += local.uri
        }

        return Matched(total, newStates, freshAutoFolderUris, pairedSyncedUris, existingByUri, cloudByLinkId)
    }

    /**
     * The cloud listing as [PhotoPairingRow]s: the rows and order of the timeline stream, in one read.
     * A failed read is retried after a growing pause, and the failure after the last retry is thrown.
     */
    private suspend fun cloudPairingRows(userId: UserId): List<PhotoPairingRow> {
        var attempt = 0L
        while (true) {
            try {
                return photoListingDao.getOwnStreamPairingRows(userId.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "cloud listing read failed (attempt $attempt), retrying: ${e.message}")
                delay((300L * (attempt + 1)).coerceAtMost(3_000L))
                if (attempt >= CLOUD_READ_RETRIES) throw e
                attempt++
            }
        }
    }

    /**
     * The queue clean-up after the pairing writes. Each step reads the table again, so it acts on
     * the rows as the writes before it left them, this pass's own included.
     */
    private suspend fun clearQueue(
        userId: UserId,
        allLocalUris: Set<String>,
        inScopeUris: Set<String>,
        scanComplete: Boolean,
        scanStartMs: Long,
    ) {
        // A queued photo the user deleted from the device before it uploaded leaves an orphaned
        // LOCAL_ONLY row. The folder-scope cleanup below deliberately spares an explicit MANUAL /
        // ALBUM_ADD / EDITOR intent, and the stranded-intent recovery further down would re-queue such
        // a row every pass, so its now-missing file keeps the queue count from ever completing. Drop
        // any QUEUED LOCAL_ONLY row whose file is gone from the WHOLE device (checked against
        // allLocalItems, every file MediaStore sees, NOT the folder-filtered inScopeUris, so an
        // out-of-folder photo that still exists is untouched), whatever queued it. deleteLocalOnlyByUris
        // is status-guarded, so a row another pass just claimed to UPLOADING is left alone. The delete
        // takes an explicit intent with it, so it runs only on a scan that saw the whole device, and
        // never on a row queued since the scan began (a "back up now" on a photo taken mid-pass).
        // The scan's word is then checked by a lookup by id, which alone drops the row: a torn read can
        // skip a file, and a trashed one is still restorable.
        val goneQueued = syncStateRepo.observeAll(userId).first()
            .filter {
                it.status == SyncStatus.LOCAL_ONLY && it.queued &&
                    deviceFileGone(it, scanComplete, allLocalUris, scanStartMs)
            }
            .map { it.localUri }
        val confirmedGone = if (goneQueued.isEmpty()) emptySet() else localRepo.confirmedGone(goneQueued)
        if (confirmedGone.isNotEmpty()) {
            syncStateRepo.deleteLocalOnlyByUris(goneQueued.filter { it in confirmedGone })
            Log.d(TAG, "reconcile: dropped ${confirmedGone.size} queued LOCAL_ONLY rows whose file is gone")
        }

        // One post-delete snapshot serves both this out-of-scope de-queue and the stranded-intent
        // recovery below. Their row sets are disjoint (this pass is queued rows with a null/AUTO_FOLDER
        // source, the next is un-queued rows with a MANUAL/ALBUM_ADD/EDITOR source), so the clearQueued
        // writes here never fall into that filter and a single read is enough.
        val afterDropSnapshot = syncStateRepo.observeAll(userId).first()

        // De-queue stale LOCAL_ONLY entries for items no longer in scope (e.g. the user unchecked
        // their folder/album from the backup selection). The queue is the source of truth, so clearing
        // their queued flag is enough to drop them from the pending set while the row survives, not
        // deleted. It is still a real local-not-backed-up file, so a later
        // re-select can re-queue it (case (a) above) instead of having to rediscover a deleted row.
        // clearQueued carries an AND status='LOCAL_ONLY' guard, so it can never touch a row that a
        // concurrent upload has already flipped to UPLOADING (a live claim) or SYNCED.
        // HIDDEN rows are excluded by the status filter: they never have a corresponding MediaStore
        // file anyway, so they look "out of scope" by every other heuristic.
        // Only an ordinary folder backup is de-queued by scope cleanup: a row whose queueSource is
        // AUTO_FOLDER (reconcile put it there because its folder was selected) or null (a legacy row
        // with no recorded source). An explicit intent is NEVER de-queued here: a MANUAL "back up
        // now", an ALBUM_ADD (the photo must upload to join its album), or an EDITOR save is out of
        // the normal folder scope by design, and clearing its queued flag would make the very next
        // upload pass skip it so the explicit upload would silently never run.
        val staleLocalOnly = afterDropSnapshot
            .filter {
                it.status == SyncStatus.LOCAL_ONLY &&
                    it.queued &&
                    it.localUri !in inScopeUris &&
                    (it.queueSource == null ||
                        it.queueSource == eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER)
            }
        if (staleLocalOnly.isNotEmpty()) {
            for (state in staleLocalOnly) syncStateRepo.clearQueued(state.localUri)
            Log.d(TAG, "reconcile: de-queued ${staleLocalOnly.size} out-of-scope LOCAL_ONLY entries")
        }

        // Stranded-intent recovery. An explicit intent (MANUAL / ALBUM_ADD / EDITOR) can lose its
        // queued flag yet keep its source and never get picked up again, seen on-device as a manual
        // "back up now" row stuck at LOCAL_ONLY, queued=0, queueSource=MANUAL with no cloud copy.
        // Re-queue any such row under its ORIGINAL source so it retries. A null source is deliberately
        // left alone: it means either a plain out-of-scope local that was never queued, or a MANUAL
        // upload the user cancelled via clearManualQueue (which nulls the source on purpose), both
        // must stay un-queued. Reads only LOCAL_ONLY rows, so it never touches an UPLOADING claim; a
        // row that flips to UPLOADING between this snapshot and markQueued is harmless (claimForUpload
        // guards on status, and a queued flag on an UPLOADING row is cleared when it reaches SYNCED).
        val recoverNow = System.currentTimeMillis()
        val strandedIntent = afterDropSnapshot
            .filter {
                it.status == SyncStatus.LOCAL_ONLY &&
                    !it.queued &&
                    // The file must still be on the device: a deleted intent has nothing to upload, and
                    // re-queuing it would loop forever against a gone file (the gone-file drop above
                    // clears the queued ones; this stops the un-queued survivors coming back).
                    it.localUri in allLocalUris &&
                    // A stranded upload has no cloud copy by definition. Requiring cloudFileId == null
                    // stops a backed-up photo whose cloud copy was later removed (it demotes to
                    // LOCAL_ONLY) from being re-queued into an endless re-upload of a deletion.
                    it.cloudFileId == null &&
                    (it.queueSource == eu.akoos.photos.domain.entity.QueueSource.MANUAL ||
                        it.queueSource == eu.akoos.photos.domain.entity.QueueSource.ALBUM_ADD ||
                        it.queueSource == eu.akoos.photos.domain.entity.QueueSource.EDITOR)
            }
        if (strandedIntent.isNotEmpty()) {
            for (state in strandedIntent) {
                syncStateRepo.markQueued(state.localUri, state.queueSource!!, recoverNow)
            }
            Log.d(TAG, "reconcile: re-queued ${strandedIntent.size} stranded explicit-intent LOCAL_ONLY entries")
        }
    }
}

/** A display name with a capture second or a byte size: the name matchers' lookup key. Equal exactly
 *  when both parts are, as a Pair of the two is, without boxing the number. */
private data class NameKey(val name: String, val value: Long)

/**
 * Whether the upload may have rewritten this file's bytes, so its cloud copy can't hash-match the
 * local original and a name match has to be trusted. Photos and videos have separate compression switches.
 */
internal fun uploadRewritesBytes(
    mimeType: String,
    stripOnUpload: Boolean,
    compressPhotosOnUpload: Boolean,
    compressVideosOnUpload: Boolean,
): Boolean = stripOnUpload ||
    if (mimeType.startsWith("video/")) compressVideosOnUpload else compressPhotosOnUpload

/**
 * Pure gate for reconcile's downloads-only name+date re-pair. Returns the cloud twin's linkId to
 * suppress a duplicate re-upload, or null to leave the file LOCAL_ONLY.
 *
 * Refuses in three cases so it can only ever RESCUE a downloaded file, never mislabel a real local:
 *  - [strong]: a direct cloud-id or content-hash match already settled it (authoritative), or
 *  - [hasLegacyNameMatch]: the existing name matcher already applied (no-hash / strip / compress), or
 *  - the name+exact-second twin is already [claimedTwinLinkIds] by another local this install.
 * The caller keeps the result out of its content-certain set, so a match here never makes the row
 * free-up-space deletable; it only stops the re-upload.
 */
internal fun repairTwinLinkId(
    strong: Boolean,
    hasLegacyNameMatch: Boolean,
    nameDateTwinLinkId: String?,
    claimedTwinLinkIds: Set<String>,
): String? = if (strong || hasLegacyNameMatch) null
    else nameDateTwinLinkId?.takeUnless { it in claimedTwinLinkIds }

/**
 * Whether [state] was written at or after [scanStartMs], the moment a pass asked for its device scan.
 * The paths that save a new file and record it (a download, a "back up now", an editor save) stamp
 * one of these times as they write, so a row stamped this late may name a file the scan could not
 * have seen yet. At or after, not after: a write in the same millisecond is as unknown as a later one.
 */
internal fun writtenSinceScan(state: SyncState, scanStartMs: Long): Boolean =
    state.lastSyncAttemptMs >= scanStartMs ||
        (state.lastSyncSuccessMs ?: Long.MIN_VALUE) >= scanStartMs ||
        (state.queuedAt ?: Long.MIN_VALUE) >= scanStartMs

/**
 * Whether reconcile may take [state]'s device file as gone, the one judgement behind its gone-file
 * steps (the demotion to CLOUD_ONLY and the drop of a queued row). It needs a scan that saw the whole
 * device ([scanComplete]) and found something ([deviceUris], every uri it returned, is not empty), no
 * trace of the file in it, and a row that predates the scan ([writtenSinceScan]). Short of any of
 * these the step leaves the row for a later pass to judge on a scan of its own. A row that passes is
 * a candidate only: the step acts on it once [LocalMediaRepository.confirmedGone] agrees.
 */
internal fun deviceFileGone(
    state: SyncState,
    scanComplete: Boolean,
    deviceUris: Set<String>,
    scanStartMs: Long,
): Boolean =
    scanComplete &&
        deviceUris.isNotEmpty() &&
        state.localUri !in deviceUris &&
        !writtenSinceScan(state, scanStartMs)

/**
 * Runs [block] for its callers one run at a time, and answers each caller with a run that began
 * after it asked.
 *
 * A run begins when it starts [block], which it does only once it holds [turns], so the runs of
 * every gate sharing that mutex take turns too. A caller joins the current run while that run has
 * not begun. Otherwise, with no run under way it starts one, and with a run under way it waits for
 * the next, which starts once the current one has ended and is shared by every caller that asks
 * before it begins. A burst of callers costs the run already under way plus one more.
 *
 * Runs execute on [scope], not in a caller. A caller that stops waiting leaves the run to the
 * callers still waiting; a run is cancelled once no caller is left, and a run every caller left
 * before it began never begins. A failed run throws to exactly the callers that shared it, and the
 * next call starts a new run.
 */
internal class SharedRunGate<T>(
    private val scope: CoroutineScope,
    private val turns: Mutex,
    private val block: suspend () -> T,
) {
    private inner class Run {
        val outcome = CompletableDeferred<T>()
        var waiting = 0
        var begun = false

        /** Every caller has left and the run is being cancelled, so no caller may join it. */
        var abandoned = false
        var job: Job? = null
    }

    private val lock = Any()

    /** The run started last, from its start until it has finished. */
    private var current: Run? = null

    /** The run that starts once [current] has finished. */
    private var next: Run? = null

    suspend fun run(): T {
        var startNow = false
        val joined = synchronized(lock) {
            val started = current
            val run = when {
                started == null -> Run().also {
                    current = it
                    startNow = true
                }
                !started.begun && !started.abandoned -> started
                else -> next ?: Run().also { next = it }
            }
            run.waiting++
            run
        }
        if (startNow) start(joined)
        try {
            return joined.outcome.await()
        } finally {
            leave(joined)
        }
    }

    private fun start(run: Run) {
        val job = scope.launch {
            val result = runCatching {
                turns.withLock {
                    if (!begin(run)) throw CancellationException("no caller waits for this run")
                    block()
                }
            }
            run.outcome.completeWith(result)
        }
        job.invokeOnCompletion { cause ->
            // A run cancelled before it began, or while it ran, still releases whoever waits on it.
            if (cause != null) run.outcome.completeExceptionally(cause)
            finish(run)
        }
        // Every caller of a next run can leave between its promotion and this point.
        val abandoned = synchronized(lock) {
            run.job = job
            if (run.waiting == 0) run.abandoned = true
            run.abandoned
        }
        if (abandoned) job.cancel()
    }

    /** Marks [run] begun, from which point no caller joins it, unless every caller has left it. */
    private fun begin(run: Run): Boolean = synchronized(lock) {
        if (!run.abandoned) run.begun = true
        run.begun
    }

    private fun finish(run: Run) {
        val following = synchronized(lock) {
            if (current !== run) return
            current = next
            next = null
            current
        }
        if (following != null) start(following)
    }

    private fun leave(run: Run) {
        val job = synchronized(lock) {
            run.waiting--
            if (run.waiting > 0 || run.outcome.isCompleted) return
            if (run === next) {
                next = null
                return
            }
            // A run whose job is not assigned yet is cancelled by [start] once it is.
            run.abandoned = true
            run.job
        }
        job?.cancel()
    }
}
