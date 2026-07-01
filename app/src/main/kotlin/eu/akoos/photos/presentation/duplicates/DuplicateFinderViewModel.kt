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

package eu.akoos.photos.presentation.duplicates

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PerceptualHashDao
import eu.akoos.photos.data.db.entity.PerceptualHashEntity
import eu.akoos.photos.data.repository.drive.PerceptualHashScheduler
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.util.PerceptualHash
import javax.inject.Inject

private const val TAG = "DuplicateFinder"

/**
 * Backs [DuplicateFinderScreen]. Streams the merged gallery + stored local hashes, runs the
 * Phase 1 exact-duplicate grouping off the main thread, and performs a safe "delete the extras"
 * that always keeps at least one copy of every group.
 */
@HiltViewModel
class DuplicateFinderViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val syncStateRepo: SyncStateRepository,
    private val findDuplicates: FindDuplicatesUseCase,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
    private val perceptualHashDao: PerceptualHashDao,
    private val perceptualHashScheduler: PerceptualHashScheduler,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val deviceGroups: List<FindDuplicatesUseCase.DuplicateGroup> = emptyList(),
        val cloudGroups: List<FindDuplicatesUseCase.DuplicateGroup> = emptyList(),
        /** Perceptual-hash near-duplicate groups from the stored fingerprint cache, kept
         *  device/cloud-homogeneous. */
        val similarDeviceGroups: List<FindDuplicatesUseCase.DuplicateGroup> = emptyList(),
        val similarCloudGroups: List<FindDuplicatesUseCase.DuplicateGroup> = emptyList(),
        val scanningSimilar: Boolean = false,
        /** One-shot system trash/delete consent intent for the device-delete path. */
        val pendingDeleteIntent: android.app.PendingIntent? = null,
        val isDeleting: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var primaryUserId: UserId? = null

    /** Deferred cloud-delete work held while the Android 11+ system trash dialog is up. */
    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    // The item list last handed to the background hash scheduler. The combined flow re-emits on
    // every single hash write, but the scheduler only needs to (re)scan when the library itself
    // changed, so guarding on identity avoids a full re-request per hashed row.
    private var lastScheduledItems: List<GalleryItem>? = null

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            accountManager.getPrimaryUserId().flatMapLatest { userId ->
                primaryUserId = userId
                if (userId == null) {
                    flowOf(Triple(emptyList<GalleryItem>(), emptyMap<String, String>(), emptyList<PerceptualHashEntity>()))
                } else {
                    combine(
                        getGalleryItems.invoke(userId),
                        syncStateRepo.observeAll(userId),
                        perceptualHashDao.observeAll(),
                    ) { items, syncStates, storedHashes ->
                        val localHashes = syncStates.associate { it.localUri to it.localHash }
                        Triple(items, localHashes, storedHashes)
                    }
                }
            }
                // The combined flow re-emits on every single background hash write; sample so a
                // burst of writes collapses to a periodic regroup instead of one full pairwise pass
                // per hashed row.
                .sample(400L)
                .collect { (items, localHashes, storedHashes) ->
                val result = findDuplicates(items, localHashes)
                groupSimilarFromStored(items, storedHashes)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        deviceGroups = result.deviceGroups,
                        cloudGroups = result.cloudGroups,
                    )
                }
                if (items !== lastScheduledItems) {
                    lastScheduledItems = items
                    primaryUserId?.let { perceptualHashScheduler.request(items, it) }
                }
            }
        }
    }

    /**
     * Build the near-duplicate groups from the STORED perceptual-hash cache. Synced photos are
     * excluded (a backed-up pair, not two copies); the remaining LocalOnly / CloudOnly candidates
     * are looked up by stable id in [storedHashes], split device/cloud so groups stay homogeneous,
     * and clustered by [group]. Anything not yet hashed is simply absent here and gets filled in by
     * [perceptualHashScheduler] in the background — this re-runs as the cache Flow re-emits. While
     * any candidate still lacks a fresh stored hash the scan is reported as in progress.
     */
    private suspend fun groupSimilarFromStored(items: List<GalleryItem>, storedHashes: List<PerceptualHashEntity>) {
        // The pairwise distance scan is O(n²); run it off the main thread so a large library
        // can't jank scrolling while groups are computed.
        data class Clustered(
            val deviceGroups: List<FindDuplicatesUseCase.DuplicateGroup>,
            val cloudGroups: List<FindDuplicatesUseCase.DuplicateGroup>,
            val anyMissing: Boolean,
        )
        val clustered = withContext(Dispatchers.Default) {
            val byKey = storedHashes.associateBy { it.key }
            val deviceHashed = mutableListOf<Pair<GalleryItem, Long>>()
            val cloudHashed = mutableListOf<Pair<GalleryItem, Long>>()
            var anyMissing = false
            for (item in items) {
                when (item) {
                    is GalleryItem.LocalOnly -> {
                        val row = freshRowFor(item.local.uri, "${item.local.dateModified}_${item.local.sizeBytes}", byKey)
                        if (row != null) deviceHashed.add(item to row.hash) else anyMissing = true
                    }
                    is GalleryItem.CloudOnly -> {
                        val linkId = item.cloud.linkId
                        val row = freshRowFor(linkId, linkId, byKey)
                        if (row != null) cloudHashed.add(item to row.hash) else anyMissing = true
                    }
                    is GalleryItem.Synced -> {
                        // Fingerprinted from the local file (see PerceptualHashScheduler); grouped with
                        // the cloud-backed candidates since a Synced photo lives on Drive too.
                        val row = freshRowFor(item.local.uri, "${item.local.dateModified}_${item.local.sizeBytes}", byKey)
                        if (row != null) cloudHashed.add(item to row.hash) else anyMissing = true
                    }
                }
            }
            Clustered(
                group(deviceHashed, FindDuplicatesUseCase.GroupType.DEVICE),
                group(cloudHashed, FindDuplicatesUseCase.GroupType.CLOUD),
                anyMissing,
            )
        }
        _uiState.update {
            it.copy(
                similarDeviceGroups = clustered.deviceGroups,
                similarCloudGroups = clustered.cloudGroups,
                scanningSimilar = clustered.anyMissing,
            )
        }
    }

    /** The stored row for [key] when it exists, matches the expected [freshness], and was computed
     *  under the current algorithm version; null when missing or stale (the scheduler recomputes it). */
    private fun freshRowFor(
        key: String,
        freshness: String,
        byKey: Map<String, PerceptualHashEntity>,
    ): PerceptualHashEntity? = byKey[key]?.takeIf {
        it.freshness == freshness && it.algoVersion == PerceptualHash.DHASH_ALGO_VERSION
    }

    /** Greedy single-link clustering: emit homogeneous groups of items within the similarity threshold. */
    private fun group(
        hashed: List<Pair<GalleryItem, Long>>,
        type: FindDuplicatesUseCase.GroupType,
    ): List<FindDuplicatesUseCase.DuplicateGroup> {
        val visited = BooleanArray(hashed.size)
        val groups = mutableListOf<FindDuplicatesUseCase.DuplicateGroup>()
        for (i in hashed.indices) {
            if (visited[i]) continue
            visited[i] = true
            val cluster = mutableListOf(hashed[i].first)
            for (j in i + 1 until hashed.size) {
                if (visited[j]) continue
                if (PerceptualHash.distance(hashed[i].second, hashed[j].second)
                    <= PerceptualHash.SIMILARITY_THRESHOLD
                ) {
                    visited[j] = true
                    cluster.add(hashed[j].first)
                }
            }
            if (cluster.size > 1) {
                groups.add(
                    FindDuplicatesUseCase.DuplicateGroup(type, cluster.sortedBy { it.captureTimeMs }),
                )
            }
        }
        return groups.sortedByDescending { it.items.size }
    }

    /** Cloud thumbnails decrypt lazily — request one when its review cell becomes visible. */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        cloudRepo.requestThumbnailDecrypt(userId, linkId)
    }

    fun cancelThumbnailDecrypt(linkId: String) {
        cloudRepo.cancelThumbnailDecrypt(linkId)
    }

    /**
     * Delete every copy of [group] EXCEPT the ones in [keepIds]. Enforces the two hard invariants:
     * the keep set must be non-empty (never wipe a whole group), and the operation only ever runs
     * over (group − keepers), which by construction is a strict subset of a proven-duplicate group.
     *
     * @param keepIds [GalleryItem.stableId] of the copies the user chose to keep.
     */
    fun deleteExtras(group: FindDuplicatesUseCase.DuplicateGroup, keepIds: Set<String>) {
        // Invariant #2: refuse to delete when nothing would be kept.
        if (keepIds.isEmpty()) return
        val toDelete = group.items.filter { it.stableId !in keepIds }
        if (toDelete.isEmpty()) return

        viewModelScope.launch {
            val userId = primaryUserId ?: runCatching { accountManager.getPrimaryUserId().first() }.getOrNull()
            if (userId == null) return@launch
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
            try {
                Log.d(TAG, "deleteExtras: group=${group.items.size}, keep=${keepIds.size}, toDelete=${toDelete.size}")
                // Remove every copy each chosen duplicate actually has: a LocalOnly loses its device
                // file, a CloudOnly its Drive copy, a Synced photo BOTH. The use case only acts on the
                // copies an item has, so both flags are safe for any mix, and keepIds (checked above)
                // guarantees at least one copy of the group survives.
                val result = deletePhotoUseCase(
                    userId = userId,
                    items = toDelete,
                    freeUpSpace = true,
                    deleteFromCloud = true,
                )
                when (result) {
                    is DeletePhotoUseCase.Result.Success ->
                        removeFromGroup(group, toDelete.map { it.stableId }.toSet())
                    is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                        pendingPermissionResult = result
                        _uiState.update { it.copy(pendingDeleteIntent = result.pendingIntent) }
                    }
                    is DeletePhotoUseCase.Result.CloudDeleteFailed ->
                        _uiState.update { it.copy(errorMessage = "drive") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Any failure while deleting duplicates surfaces as a toast, never an app crash.
                Log.e(TAG, "deleteExtras failed for ${toDelete.size} item(s)", e)
                _uiState.update { it.copy(errorMessage = "delete") }
            } finally {
                _uiState.update { it.copy(isDeleting = false) }
            }
        }
    }

    /** Called after the user confirmed the Android 11+ system trash dialog (device path). */
    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        _uiState.update { it.copy(pendingDeleteIntent = null) }
        if (pending == null) return
        viewModelScope.launch {
            val userId = primaryUserId ?: runCatching { accountManager.getPrimaryUserId().first() }.getOrNull() ?: return@launch
            try {
                deletePhotoUseCase.completeAfterPermissionGranted(
                    userId = userId,
                    cloudLinkIds = pending.cloudLinkIds,
                    items = pending.itemsBeingDeleted,
                    freeUpSpace = pending.freeUpSpace,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "completeAfterPermissionGranted failed for ${pending.itemsBeingDeleted.size} item(s)", e)
                _uiState.update { it.copy(errorMessage = "delete") }
            }
            // The deleted local URIs leave the MediaStore feed, so the gallery flow re-emits and the
            // grouping recomputes automatically — no manual group surgery needed for the device path.
        }
    }

    /** User cancelled the system trash dialog — drop the deferred work. */
    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        _uiState.update { it.copy(pendingDeleteIntent = null) }
    }

    fun consumeError() = _uiState.update { it.copy(errorMessage = null) }

    /** Drop the just-deleted ids from the matching group; remove the group if ≤ 1 copy remains. */
    private fun removeFromGroup(group: FindDuplicatesUseCase.DuplicateGroup, deletedIds: Set<String>) {
        fun List<FindDuplicatesUseCase.DuplicateGroup>.prune() = mapNotNull { g ->
            if (g !== group) g
            else {
                val remaining = g.items.filter { it.stableId !in deletedIds }
                if (remaining.size > 1) g.copy(items = remaining) else null
            }
        }
        _uiState.update {
            it.copy(
                deviceGroups = it.deviceGroups.prune(),
                cloudGroups = it.cloudGroups.prune(),
                similarDeviceGroups = it.similarDeviceGroups.prune(),
                similarCloudGroups = it.similarCloudGroups.prune(),
            )
        }
    }
}
