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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncProgress
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject

private const val TAG = "ReconcileUseCase"

/** A just-uploaded photo is marked SYNCED before the cloud listing has caught up, so its cloud
 *  linkId is briefly absent from the local cloud DB. Don't demote such a row (which would re-queue
 *  it for a duplicate upload and drop the green "backed up" badge) until it has been missing past
 *  this window — by then the listing has refreshed and a true cloud-side delete is real. */
private const val CLOUD_ABSENCE_GRACE_MS = 15 * 60 * 1000L

class ReconcileSyncStateUseCase @Inject constructor(
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(userId: UserId): Flow<SyncProgress> = flow {
        emit(SyncProgress(total = 0, done = 0, running = true))

        // Read folder filter from settings.
        // null (key absent) = first run, backup nothing; non-null set = backup only listed folders.
        // BACKUP_EVERYTHING short-circuits the folder filter entirely — every MediaStore
        // image/video goes through reconcile, regardless of bucket. That's the "auto-back
        // up like Google Photos" mode, opt-in via Settings.
        val prefs: Preferences = context.settingsDataStore.data.first()
        val backupEverything = prefs[SettingsKeys.BACKUP_EVERYTHING] ?: false
        val selectedFolders: Set<String>? = prefs[SettingsKeys.SYNC_FOLDER_NAMES]
        // Backup-everything carve-outs. Only consulted while [backupEverything] is true —
        // when the per-folder picker is in charge, exclusions don't apply (the user picked
        // an explicit allow-list; we don't second-guess it with a parallel deny-list).
        val excludedFolders: Set<String> = prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet()

        // Has the cloud library been listed end-to-end at least once? The content-hash recompute
        // below only runs when it has — a missing hash match against a half-walked listing would be
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

        val allLocalItems = localRepo.observeLocalMedia().first()

        val localItems = if (backupEverything) {
            // Backup-everything mode: skip the folder selection entirely. Every local
            // image/video flows into reconcile and gets either marked SYNCED (already
            // on Drive) or LOCAL_ONLY (needs upload). Excluded buckets are dropped here
            // so the user can keep Screenshots / Movies / WhatsApp-Status out of Drive
            // without abandoning the everything-else guarantee. Items with a null bucket
            // (rare — usually orphan rows) bypass the exclusion since we can't match.
            if (excludedFolders.isNotEmpty()) {
                val kept = allLocalItems.filter { it.bucketName == null || it.bucketName !in excludedFolders }
                Log.d(TAG, "Backup-everything ON, ${excludedFolders.size} excluded — " +
                    "${kept.size}/${allLocalItems.size} items eligible")
                kept
            } else {
                Log.d(TAG, "Backup-everything ON — ${allLocalItems.size} items eligible (no folder filter)")
                allLocalItems
            }
        } else if (selectedFolders == null) {
            // Key absent → first run, user hasn't selected any folders yet; backup nothing.
            Log.d(TAG, "No backup folders configured (first-run default) — nothing to upload")
            emptyList()
        } else if (selectedFolders.isEmpty()) {
            // Explicit empty set → user disabled backup for all folders
            Log.d(TAG, "No folders selected for backup — nothing to upload")
            emptyList()
        } else {
            // Keep items whose bucket is in the selected set (or has no bucket name)
            allLocalItems.filter { it.bucketName == null || it.bucketName in selectedFolders }
                .also { Log.d(TAG, "Folder filter applied: ${it.size}/${allLocalItems.size} items") }
        }

        // Cloud DB was already refreshed by the caller (GalleryViewModel.refresh / doSync).
        // Avoid calling refreshCloudPhotosIncremental here — it can replay old "add" events
        // and re-insert photos the caller just deleted from the cloud DB.
        val cloudItems = cloudRepo.observeCloudPhotos(userId).first()

        val total = localItems.size + cloudItems.size
        var done = 0

        // Four lookups, queried in priority order:
        //   1. byId            — direct cloud-linkId (most reliable after a successful upload).
        //   2. byHash          — content-hash match (definitive: same SHA-256 = same bytes).
        //   3. byNameAndDate   — displayName + capture time. Survives metadata-stripping (which
        //                        changes size but not name/captureTime) AND survives a full app
        //                        reinstall / "Clear app data" because it doesn't depend on
        //                        existingSync. captureTime is recorded at upload-time from the
        //                        original local file's DATE_TAKEN, so for any photo our app
        //                        uploaded the cloud side has the matching value to 1-second
        //                        precision (item.dateTaken / 1000 in PhotoUploadService).
        //   4. byName          — last-resort name+size. Kept for compatibility with rows that
        //                        somehow have no captureTime, but matches first by captureTime
        //                        because size is fragile across strip-on-upload.
        val cloudByLinkId = cloudItems.associateBy { it.linkId }
        val cloudByHash = cloudItems.filter { !it.contentHash.isNullOrEmpty() }
            .associateBy { it.contentHash!! }
        val cloudByNameAndDate = cloudItems
            .filter { it.captureTime > 0 }
            .associateBy { it.displayName to it.captureTime }
        val cloudByNameSize = cloudItems.associateBy { it.displayName to it.sizeBytes }
        Log.d(TAG, "reconcile: ${localItems.size} local, ${cloudItems.size} cloud " +
            "(${cloudByHash.size} with hash, ${cloudByNameAndDate.size} with captureTime)")

        val newStates = mutableListOf<SyncState>()
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
        // the SYNCED-demotion pass further down — that loop reads rows reconcile hasn't written
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
            // HIDDEN rows belong to a photo the user moved into the Hidden vault — the local
            // MediaStore copy is gone (or about to be), so reconcile must NOT generate a new
            // SyncState entry that would overwrite the HIDDEN status with LOCAL_ONLY/SYNCED.
            // Without this skip the next reconcile after a hide would flip the row back to a
            // regular cloud entry and the hidden marker would be lost (manifesting as the
            // hidden photo reappearing as cloud-only after a refresh).
            if (existingSync?.status == SyncStatus.HIDDEN) {
                done++
                if (done % 50 == 0) emit(SyncProgress(total, done, true))
                continue
            }
            // UPLOADING means an editor save's cloud-fanout is in flight against this URI.
            // The editor owns the row until its upload finishes — touching it here would
            // race the editor and produce a duplicate Drive entry. Same skip shape as HIDDEN.
            if (existingSync?.status == SyncStatus.UPLOADING) {
                done++
                if (done % 50 == 0) emit(SyncProgress(total, done, true))
                continue
            }
            val byId = existingSync?.cloudFileId?.let { cloudByLinkId[it] }

            // Content hash is the AUTHORITATIVE matcher: it compares the actual file bytes (the local
            // bare SHA-1 converted to Drive's HMAC ContentHash), so it pairs the same photo whatever
            // its name and — unlike name+size — can't be fooled into pairing two DIFFERENT files that
            // share a name (Drive allows duplicate names). Use the stored hash; if a reinstall wiped
            // it, recompute from the file once (persisted below) when the cloud library is fully
            // listed so there's a complete set to match against. This also pairs a renamed cloud copy.
            val storedHash = existingSync?.localHash?.takeIf { it.isNotEmpty() }
            var recomputedHash: String? = null
            val localHashHex: String? = storedHash
                ?: if (initialListingComplete && cloudByHash.isNotEmpty())
                    localRepo.sha1(local.uri).also { recomputedHash = it } else null
            val byContentHash = localHashHex?.let { cloudRepo.cloudContentHash(it) }?.let { cloudByHash[it] }

            // captureTime in CloudPhoto is Unix seconds; LocalMediaItem.dateTaken is ms.
            val localCaptureTimeSec = local.dateTaken / 1000L
            val nameDateTwin = cloudByNameAndDate[local.displayName to localCaptureTimeSec]
            val nameCandidate = nameDateTwin
                ?: cloudByNameSize[local.displayName to local.sizeBytes]?.takeIf { it.sizeBytes > 0 }
            // Trust a name+date / name+size match ONLY when a content hash can't settle it: the cloud
            // photo carries no ContentHash to check, or strip-on-upload / compress-on-upload changed
            // the bytes so the same photo can't hash-match its rewritten cloud copy. When the cloud
            // photo HAS a hash and neither strip nor compress was on, byContentHash above is the only
            // thing that may pair it; trusting the name alone could mark a different same-named file
            // as backed up, and Free-up-space could then delete a local that was never really
            // uploaded. (No name-only fallback either: a recurring camera name like IMG_0001.jpg must
            // never pair on its own.)
            val byNameUnverifiable = nameCandidate?.takeIf {
                it.contentHash.isNullOrEmpty() || stripOnUpload || compressOnUpload
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
            // on Drive (e.g. paired after a reinstall) becomes reclaimable — but ONLY for a
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
            done++
            if (done % 50 == 0) emit(SyncProgress(total, done, true))
        }

        // Previously SYNCED entries whose cloud counterpart was deleted → LOCAL_ONLY (still on device,
        // no longer in Drive). The first loop already covers items in selected folders; this catches
        // items in non-selected folders or edge cases where the file wasn't in localItems.
        //
        // We also catch the inverse — SYNCED rows whose LOCAL file is gone (user deleted from the
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
        val localUriSet = allLocalItems.map { it.uri }.toSet()
        val nowMs = System.currentTimeMillis()
        // Reuse the pre-loop snapshot: this pass only demotes rows that were SYNCED BEFORE
        // reconcile ran, and the new states aren't persisted until the upsertAll below.
        val syncedStates = existingByUri.values
            .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
        for (state in syncedStates) {
            when {
                // Cloud counterpart absent from the local cloud listing. A photo just uploaded is
                // SYNCED before the listing catches up, so demoting it now would re-queue it for a
                // duplicate upload and drop its green badge. Only demote once it has been missing
                // past the grace window — by then a true cloud-side delete is real.
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
                // user the photo is "on this device" when it actually isn't.
                state.localUri !in localUriSet -> syncStateRepo.upsert(
                    state.copy(status = SyncStatus.CLOUD_ONLY),
                    userId,
                )
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

        // De-queue stale LOCAL_ONLY entries for items no longer in scope (e.g. the user unchecked
        // their folder/album from the backup selection). Previously these rows were DELETED; now the
        // queue is the source of truth, so clearing their queued flag is enough to drop them from the
        // pending set while the row survives. It is still a real local-not-backed-up file, so a later
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
        val inScopeUris = newStates.map { it.localUri }.toSet()
        val staleLocalOnly = syncStateRepo.observeAll(userId).first()
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
        val strandedIntent = syncStateRepo.observeAll(userId).first()
            .filter {
                it.status == SyncStatus.LOCAL_ONLY &&
                    !it.queued &&
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

        // This pass paired against a complete cloud listing, so any still-LOCAL_ONLY row is genuinely
        // new and safe for the bulk upload to drain. Flip the gate UNCONDITIONALLY (even with zero
        // cloud photos — a fresh account must still let its locals upload). The upload defers until
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

        emit(SyncProgress(total, total, false))
    }
}

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
