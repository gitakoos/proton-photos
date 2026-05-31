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
        } else if (selectedFolders!!.isEmpty()) {
            // Explicit empty set → user disabled backup for all folders
            Log.d(TAG, "No folders selected for backup — nothing to upload")
            emptyList()
        } else {
            // Keep items whose bucket is in the selected set (or has no bucket name)
            allLocalItems.filter { it.bucketName == null || it.bucketName in selectedFolders!! }
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
        // Last-resort displayName-only index — pairs photos across reinstalls when
        // SyncState is gone, EXIF strip diverged sizeBytes, and captureTime drift
        // defeats byNameAndDate. Accepts the first cloud photo per displayName — cross-
        // folder filename collisions are rare for camera-roll content and the
        // alternative (re-upload as a duplicate) is much worse UX.
        val cloudByName = cloudItems.associateBy { it.displayName }
        Log.d(TAG, "reconcile: ${localItems.size} local, ${cloudItems.size} cloud " +
            "(${cloudByHash.size} with hash, ${cloudByNameAndDate.size} with captureTime)")

        val newStates = mutableListOf<SyncState>()

        for (local in localItems) {
            val existingSync = syncStateRepo.getByUri(local.uri)
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
            val byHash = existingSync?.localHash?.takeIf { it.isNotEmpty() }?.let { cloudByHash[it] }
            // captureTime in CloudPhoto is Unix seconds; LocalMediaItem.dateTaken is ms.
            val localCaptureTimeSec = local.dateTaken / 1000L
            val byNameAndDate = cloudByNameAndDate[local.displayName to localCaptureTimeSec]
            val byName = cloudByNameSize[local.displayName to local.sizeBytes]
            // Name-only fallback for reinstall pairing — see cloudByName comment above.
            val byNameOnly = cloudByName[local.displayName]
            val matchedCloud = byId ?: byHash ?: byNameAndDate ?: byName ?: byNameOnly
            if (matchedCloud == null) {
                Log.d(TAG, "LOCAL_ONLY: ${local.displayName} (size=${local.sizeBytes}, " +
                    "captureSec=$localCaptureTimeSec, existingCloudId=${existingSync?.cloudFileId})")
            }

            val status = if (matchedCloud != null) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY
            newStates += SyncState(
                localUri = local.uri,
                cloudFileId = matchedCloud?.linkId,
                localHash = existingSync?.localHash.orEmpty(),
                cloudHash = matchedCloud?.contentHash,
                status = status,
                lastSyncAttemptMs = System.currentTimeMillis(),
                lastSyncSuccessMs = if (status == SyncStatus.SYNCED) System.currentTimeMillis() else null,
                backedUpAtMs = existingSync?.backedUpAtMs,
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
        val syncedStates = syncStateRepo.observeAll(userId).first()
            .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
        for (state in syncedStates) {
            when {
                // Cloud counterpart removed → demote to LOCAL_ONLY (still re-uploadable from device).
                state.cloudFileId !in cloudByLinkId -> syncStateRepo.upsert(
                    state.copy(status = SyncStatus.LOCAL_ONLY, cloudFileId = null),
                    userId,
                )
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
        val inScopeUris = newStates.map { it.localUri }.toSet()
        val staleLocalOnly = syncStateRepo.observeAll(userId).first()
            .filter { it.status == SyncStatus.LOCAL_ONLY && it.localUri !in inScopeUris }
        if (staleLocalOnly.isNotEmpty()) {
            syncStateRepo.deleteLocalOnlyByUris(staleLocalOnly.map { it.localUri })
            Log.d(TAG, "reconcile: cleaned up ${staleLocalOnly.size} LOCAL_ONLY entries for excluded folders")
        }

        emit(SyncProgress(total, total, false))
    }
}
