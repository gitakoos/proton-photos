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
        // Strip-on-upload rewrites a photo's bytes, so its cloud copy can't content-hash-match the
        // local original. When it's on, the name/date match stays the fallback for those; when off,
        // the bytes are identical and a content-hash match is REQUIRED before pairing — so a
        // different file that merely shares a name (Drive allows that) can't be taken for a backup.
        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false

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

        // One snapshot of every SyncState row, indexed by local URI, instead of a per-item
        // getByUri() DAO round-trip inside the loop below (an N+1 that, at tens of thousands of
        // local items, issued tens of thousands of point queries). The same snapshot also feeds
        // the SYNCED-demotion pass further down — that loop reads rows reconcile hasn't written
        // yet, so the pre-loop view is exactly what it needs.
        val existingByUri = syncStateRepo.observeAll(userId).first().associateBy { it.localUri }

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
            val nameCandidate = cloudByNameAndDate[local.displayName to localCaptureTimeSec]
                ?: cloudByNameSize[local.displayName to local.sizeBytes]?.takeIf { it.sizeBytes > 0 }
            // Trust a name+date / name+size match ONLY when a content hash can't settle it: the cloud
            // photo carries no ContentHash to check, or strip-on-upload changed the bytes so the same
            // photo can't hash-match its stripped cloud copy. When the cloud photo HAS a hash and
            // nothing was stripped, byContentHash above is the only thing that may pair it — trusting
            // the name alone could mark a different same-named file as backed up, and Free-up-space
            // could then delete a local that was never really uploaded. (No name-only fallback either:
            // a recurring camera name like IMG_0001.jpg must never pair on its own.)
            val byNameUnverifiable = nameCandidate?.takeIf {
                it.contentHash.isNullOrEmpty() || stripOnUpload
            }
            val matchedCloud = byId ?: byContentHash ?: byNameUnverifiable
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
        // backup-filtered [localItems]. A photo downloaded into `Pictures/Proton Photos/` is
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
                        syncStateRepo.upsert(
                            state.copy(status = SyncStatus.LOCAL_ONLY, cloudFileId = null),
                            userId,
                        )
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
        syncStateRepo.upsertAll(newStates, userId)

        // Clean up stale LOCAL_ONLY entries for items that are no longer in scope
        // (e.g. the user unchecked their folder/album from the backup selection).
        // Without this, removed-folder items stay LOCAL_ONLY forever and keep being uploaded.
        // HIDDEN rows are excluded by the status filter — they never have a corresponding
        // MediaStore file anyway, so they look "out of scope" by every other heuristic.
        // Force-marked uploads (PENDING_ALBUM_ADDS) must survive this cleanup. A photo or video the
        // user explicitly chose to back up — or to add to an album — from an unselected folder is
        // out of the normal scope by design; deleting its LOCAL_ONLY row here would make the very
        // next upload pass skip it, so the forced upload would silently never run.
        val forcedUris = (prefs[SettingsKeys.PENDING_ALBUM_ADDS] ?: emptySet())
            // Split at the FIRST '=': the localUri (content:// URI) has none, but the albumLinkId is
            // base64 and ends in '=' padding, so substringBeforeLast would yield a wrong key and fail
            // to spare the forced row from the cleanup below.
            .map { it.substringBefore('=') }
            .toSet()
        val inScopeUris = newStates.map { it.localUri }.toSet()
        val staleLocalOnly = syncStateRepo.observeAll(userId).first()
            .filter {
                it.status == SyncStatus.LOCAL_ONLY &&
                    it.localUri !in inScopeUris &&
                    it.localUri !in forcedUris
            }
        if (staleLocalOnly.isNotEmpty()) {
            syncStateRepo.deleteLocalOnlyByUris(staleLocalOnly.map { it.localUri })
            Log.d(TAG, "reconcile: cleaned up ${staleLocalOnly.size} LOCAL_ONLY entries for excluded folders")
        }

        // This pass paired against a complete cloud listing, so any still-LOCAL_ONLY row is genuinely
        // new and safe for the bulk upload to drain. Flip the gate UNCONDITIONALLY (even with zero
        // cloud photos — a fresh account must still let its locals upload). The upload defers until
        // this is set, so the post-completion reconcile always runs the content-hash pairing first.
        if (initialListingComplete) {
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.pairingSettledKey(userId.id)] = true
            }
        }

        emit(SyncProgress(total, total, false))
    }
}
