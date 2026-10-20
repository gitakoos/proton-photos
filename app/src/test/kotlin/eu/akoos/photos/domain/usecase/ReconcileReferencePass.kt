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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.upload.LocalRewrites
import eu.akoos.photos.domain.entity.SyncProgress
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository

private const val TAG = "ReconcileReference"

private const val CLOUD_ABSENCE_GRACE_MS = 15 * 60 * 1000L

/**
 * The reference for [ReconcileSyncStateUseCase]'s decisions and writes: the same pass, statement for
 * statement with the comments left out, reading its inputs whole (the device scan, the cloud listing
 * as full [eu.akoos.photos.domain.entity.CloudPhoto]s) and running once per call. The gone-file rule
 * is spelled out here rather than taken from the pass's own helper, so the two check each other.
 * [ReconcileEquivalenceTest] runs both on the same fixtures and compares every write.
 */
internal class ReconcileReferencePass(
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val context: Context,
) {
    suspend operator fun invoke(userId: UserId): Flow<SyncProgress> = flow {
        emit(SyncProgress(total = 0, done = 0, running = true))

        val prefs: Preferences = context.settingsDataStore.data.first()
        val backupEverything = prefs[SettingsKeys.BACKUP_EVERYTHING] ?: false
        val selectedFolders: Set<String>? = prefs[SettingsKeys.SYNC_FOLDER_NAMES]
        val excludedFolders: Set<String> = prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet()

        val initialListingComplete = prefs.asMap().any { (k, v) ->
            k.name.startsWith("photo_listing_ever_complete_${userId.id}_") && v == true
        }
        val stripOnUpload = prefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false
        val compressOnUpload = prefs[SettingsKeys.COMPRESS_ON_UPLOAD] ?: false

        val scanStartMs = System.currentTimeMillis()
        val scan = localRepo.freshScan()
        val allLocalItems = scan.items
        // A row's file is gone only on a complete scan that found something and not the file, and
        // only for a row every stamp of which predates the scan.
        fun goneFromDevice(state: SyncState, deviceUris: Set<String>): Boolean =
            scan.complete &&
                deviceUris.isNotEmpty() &&
                state.localUri !in deviceUris &&
                state.lastSyncAttemptMs < scanStartMs &&
                state.lastSyncSuccessMs.let { it == null || it < scanStartMs } &&
                state.queuedAt.let { it == null || it < scanStartMs }

        val localItems = if (backupEverything) {
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
            Log.d(TAG, "No backup folders configured (first-run default), nothing to upload")
            emptyList()
        } else if (selectedFolders.isEmpty()) {
            Log.d(TAG, "No folders selected for backup, nothing to upload")
            emptyList()
        } else {
            allLocalItems.filter { it.bucketName == null || it.bucketName in selectedFolders }
                .also { Log.d(TAG, "Folder filter applied: ${it.size}/${allLocalItems.size} items") }
        }

        val cloudItems = cloudRepo.observeCloudPhotos(userId).first()

        val total = localItems.size + cloudItems.size
        var done = 0

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
        val freshAutoFolderUris = mutableListOf<String>()
        val pairedSyncedUris = mutableListOf<String>()

        val existingByUri = syncStateRepo.observeAll(userId).first().associateBy { it.localUri }
        val claimedCloudIds: Set<String> = existingByUri.values.asSequence()
            .filter { it.status == SyncStatus.SYNCED }
            .mapNotNull { it.cloudFileId }
            .toSet()
        var changeHashes = 0
        var changeHashBytes = 0L

        for (local in localItems) {
            val existingSync = existingByUri[local.uri]
            if (existingSync?.status == SyncStatus.HIDDEN) {
                done++
                if (done % 50 == 0) emit(SyncProgress(total, done, true))
                continue
            }
            if (existingSync?.status == SyncStatus.UPLOADING) {
                done++
                if (done % 50 == 0) emit(SyncProgress(total, done, true))
                continue
            }
            val byId = existingSync?.cloudFileId?.let { cloudByLinkId[it] }

            val changeCheck = byId != null && existingSync != null &&
                existingSync.status == SyncStatus.SYNCED &&
                existingSync.backedUpAtMs != null &&
                existingSync.localHash.isNotEmpty() &&
                existingSync.sizeBytes > 0L &&
                local.sizeBytes > 0L &&
                existingSync.sizeBytes != local.sizeBytes
            val hashChangeNow = changeCheck && changeHashes < DEVICE_CHANGE_HASHES_PER_PASS &&
                (changeHashes == 0 || changeHashBytes + local.sizeBytes <= DEVICE_CHANGE_HASH_BYTES_PER_PASS)
            if (hashChangeNow) {
                changeHashes++
                changeHashBytes += local.sizeBytes
            }
            val deviceHash: String? = if (hashChangeNow) localRepo.sha1(local.uri) else null
            val keepStoredSize = changeCheck && deviceHash == null
            val deviceChanged = deviceHash != null &&
                !LocalRewrites.consume(local.uri, local.sizeBytes) &&
                deviceHash != existingSync?.localHash &&
                cloudRepo.cloudContentHash(deviceHash)?.let { it == byId?.contentHash } != true

            val storedHash = existingSync?.localHash?.takeIf { it.isNotEmpty() }
            var recomputedHash: String? = null
            val localHashHex: String? = storedHash
                ?: if (initialListingComplete && cloudByHash.isNotEmpty())
                    localRepo.sha1(local.uri).also { recomputedHash = it } else null
            val byContentHash = localHashHex?.let { cloudRepo.cloudContentHash(it) }?.let { cloudByHash[it] }

            val localCaptureTimeSec = local.dateTaken / 1000L
            val nameDateTwin = cloudByNameAndDate[local.displayName to localCaptureTimeSec]
            val nameCandidate = nameDateTwin
                ?: cloudByNameSize[local.displayName to local.sizeBytes]?.takeIf { it.sizeBytes > 0 }
            val byNameUnverifiable = nameCandidate?.takeIf {
                it.contentHash.isNullOrEmpty() || stripOnUpload || compressOnUpload
            }
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
            val contentCertain = byContentHash != null || (byId != null && storedHash == null)
            newStates += SyncState(
                localUri = local.uri,
                cloudFileId = matchedCloud?.linkId,
                localHash = deviceHash ?: recomputedHash ?: existingSync?.localHash.orEmpty(),
                cloudHash = matchedCloud?.contentHash,
                status = status,
                lastSyncAttemptMs = System.currentTimeMillis(),
                lastSyncSuccessMs = if (status == SyncStatus.SYNCED) System.currentTimeMillis() else null,
                backedUpAtMs = if (deviceChanged) null
                    else existingSync?.backedUpAtMs ?: (if (contentCertain) System.currentTimeMillis() else null),
                sizeBytes = if (keepStoredSize && existingSync != null) existingSync.sizeBytes else local.sizeBytes,
            )
            if (status == SyncStatus.LOCAL_ONLY) {
                val src = existingSync?.queueSource
                val stampable = src == null || src == eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER
                if (stampable) freshAutoFolderUris += local.uri
            }
            if (status == SyncStatus.SYNCED) pairedSyncedUris += local.uri
            done++
            if (done % 50 == 0) emit(SyncProgress(total, done, true))
        }

        val localUriSet = allLocalItems.map { it.uri }.toSet()
        val nowMs = System.currentTimeMillis()
        val syncedStates = existingByUri.values
            .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
        val goneCandidates = mutableListOf<SyncState>()
        for (state in syncedStates) {
            when {
                state.cloudFileId !in cloudByLinkId ->
                    if (nowMs - (state.lastSyncSuccessMs ?: 0L) > CLOUD_ABSENCE_GRACE_MS) {
                        syncStateRepo.demoteToLocalIfCloudIdMatches(state.localUri, state.cloudFileId!!)
                        syncStateRepo.clearQueuedForSynced(state.localUri)
                    }
                goneFromDevice(state, localUriSet) -> goneCandidates += state
            }
        }
        if (goneCandidates.isNotEmpty()) {
            val confirmed = localRepo.confirmedGone(goneCandidates.map { it.localUri })
            for (state in goneCandidates) {
                if (state.localUri in confirmed) syncStateRepo.upsert(state.copy(status = SyncStatus.CLOUD_ONLY), userId)
            }
        }

        val localOnlyCount = newStates.count { it.status == SyncStatus.LOCAL_ONLY }
        val syncedCount    = newStates.count { it.status == SyncStatus.SYNCED }
        Log.d(TAG, "reconcile done: $syncedCount SYNCED, $localOnlyCount LOCAL_ONLY (will upload)")
        val (guardedDemotions, rest) = newStates.partition {
            it.status == SyncStatus.LOCAL_ONLY && existingByUri.containsKey(it.localUri)
        }
        syncStateRepo.upsertAll(rest, userId)
        val skippedDemotionUris = mutableSetOf<String>()
        for (state in guardedDemotions) {
            if (syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(state, userId) == 0) {
                skippedDemotionUris += state.localUri
            }
        }
        val autoFolderNow = System.currentTimeMillis()
        for (uri in freshAutoFolderUris) {
            if (uri in skippedDemotionUris) continue
            syncStateRepo.markQueued(uri, eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER, autoFolderNow)
        }
        for (uri in pairedSyncedUris) {
            syncStateRepo.clearQueuedForSynced(uri)
        }

        val inScopeUris = newStates.map { it.localUri }.toSet()

        val allLocalUris = allLocalItems.mapTo(HashSet(allLocalItems.size)) { it.uri }
        val goneQueued = syncStateRepo.observeAll(userId).first().filter {
            it.status == SyncStatus.LOCAL_ONLY && it.queued && goneFromDevice(it, allLocalUris)
        }.map { it.localUri }
        val confirmedQueued = if (goneQueued.isEmpty()) emptySet() else localRepo.confirmedGone(goneQueued)
        if (confirmedQueued.isNotEmpty()) {
            syncStateRepo.deleteLocalOnlyByUris(goneQueued.filter { it in confirmedQueued })
            Log.d(TAG, "reconcile: dropped ${confirmedQueued.size} queued LOCAL_ONLY rows whose file is gone")
        }

        val afterDropSnapshot = syncStateRepo.observeAll(userId).first()

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

        val recoverNow = System.currentTimeMillis()
        val strandedIntent = afterDropSnapshot
            .filter {
                it.status == SyncStatus.LOCAL_ONLY &&
                    !it.queued &&
                    it.localUri in allLocalUris &&
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

        if (initialListingComplete) {
            context.settingsDataStore.edit { p ->
                p[SettingsKeys.pairingSettledKey(userId.id)] = true
                p[SettingsKeys.cloudVerifiedAtKey(userId.id)] = System.currentTimeMillis()
            }
        }

        emit(SyncProgress(total, total, false))
    }
}
