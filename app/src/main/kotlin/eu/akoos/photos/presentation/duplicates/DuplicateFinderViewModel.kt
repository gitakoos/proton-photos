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

package eu.akoos.photos.presentation.duplicates

import android.util.Log
import android.graphics.BitmapFactory
import android.net.Uri
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.LocalHashRow
import eu.akoos.photos.data.db.dao.PerceptualHashDao
import eu.akoos.photos.data.db.dao.PerceptualHashLite
import eu.akoos.photos.data.db.dao.SyncStateDao
import eu.akoos.photos.data.repository.drive.PerceptualHashScheduler
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.FindDuplicatesUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.util.PerceptualHash
import eu.akoos.photos.util.retryOnDbTear
import javax.inject.Inject

private const val TAG = "DuplicateFinder"

/** A lean projection of a stored perceptual-hash row: just the fingerprint and the freshness token,
 *  so the clustering pass never holds the full entity for every photo in the library. */
private data class LeanHash(val hash: Long, val freshness: String)

/** A cheap reference bundle the duplicate finder's combine emits so its per-emission transform
 *  allocates nothing on a large library: it just carries the current source lists. The expensive
 *  uri -> hash lookup maps are built once per sampled tick, not on every background hash write. */
private data class Sources(
    val items: List<GalleryItem>,
    val localHashRows: List<LocalHashRow>,
    val hashRows: List<PerceptualHashLite>,
    val deleted: Set<String>,
)

/** The per-tick prepared inputs for a grouping pass: the live (session-deleted-removed) item list plus
 *  the two lookup maps, alongside the raw source references the exact-duplicate change guard compares. */
private data class Prepared(
    val liveItems: List<GalleryItem>,
    val localHashes: Map<String, String>,
    val freshHashes: Map<String, LeanHash>,
    val srcItems: List<GalleryItem>,
    val srcDeleted: Set<String>,
    val srcLocalHashRows: List<LocalHashRow>,
)

/**
 * Whether the similar-photo grouping must re-run its O(n²) clustering. It re-clusters only when a
 * candidate is NEW or its hash CHANGED versus [last]; a pass whose candidates are all present in
 * [last] with the same hash (i.e. only removals, the delete case) returns false, since the shown
 * groups are pruned in place instead of rebuilt. A null [last] means nothing has been clustered yet.
 */
internal fun shouldRecluster(current: Map<String, Long>, last: Map<String, Long>?): Boolean {
    if (last == null) return true
    for ((key, hash) in current) {
        if (last[key] != hash) return true
    }
    return false
}

/**
 * Backs [DuplicateFinderScreen]. Streams the merged gallery + stored local hashes, runs the
 * Phase 1 exact-duplicate grouping off the main thread, and performs a safe "delete the extras"
 * that always keeps at least one copy of every group.
 */
@HiltViewModel
class DuplicateFinderViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val syncStateDao: SyncStateDao,
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

    /** stableIds deleted this session. Filtered out of every recomputed group so a stale DB/stream
     *  re-emit (the source still lists a just-trashed link for a beat, then the row settles) can't
     *  resurrect a deleted duplicate with a dead thumbnail. Session-scoped; a real re-add carries a
     *  fresh id, so keeping ids here for the session is safe. */
    private val recentlyDeleted = MutableStateFlow<Set<String>>(emptySet())

    /** Deferred cloud-delete work held while the Android 11+ system trash dialog is up. */
    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    // The item list last handed to the background hash scheduler. The combined flow re-emits on
    // every single hash write, but the scheduler only needs to (re)scan when the library itself
    // changed, so guarding on identity avoids a full re-request per hashed row.
    private var lastScheduledItems: List<GalleryItem>? = null

    // The candidate (key -> hash) input of the last similar-photo clustering pass. Deleting photos
    // re-emits the combined flow, but the shown groups are already pruned in place, so a pure removal
    // must NOT re-run the O(n²) cluster (that repeated allocation is what exhausted the heap on a
    // very large library). Only a new or changed fingerprint reclusters. See [shouldRecluster].
    private var lastClusteredFingerprint: Map<String, Long>? = null

    // Inputs + result of the last exact-duplicate pass. findDuplicates depends ONLY on the items, the
    // session-deleted set, and the local content hashes, never the perceptual hashes whose background
    // fill drives most re-emits, so when all three source references are unchanged the identical groups
    // are reused instead of rebuilt, which stops the fingerprint fill from re-running it every tick.
    private var lastDupItems: List<GalleryItem>? = null
    private var lastDupDeleted: Set<String>? = null
    private var lastDupLocalHashRows: List<LocalHashRow>? = null
    private var lastDupResult: FindDuplicatesUseCase.Result? = null

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            accountManager.getPrimaryUserId().flatMapLatest { userId ->
                primaryUserId = userId
                if (userId == null) {
                    flowOf(Sources(emptyList(), emptyList(), emptyList(), emptySet()))
                } else {
                    // The combine wakes on EVERY background hash write (the scheduler fills the table
                    // one row at a time on a large library), so its transform stays allocation-free and
                    // only bundles the current source references. The lean projections keep a 50k
                    // library from materialising the full SyncState / fingerprint entities here.
                    combine(
                        getGalleryItems.invoke(userId),
                        syncStateDao.observeLocalHashes(userId.id),
                        perceptualHashDao.observeLite(PerceptualHash.DHASH_ALGO_VERSION),
                        recentlyDeleted,
                    ) { items, localHashRows, hashRows, deleted ->
                        Sources(items, localHashRows, hashRows, deleted)
                    }
                }
            }
                // The combined flow re-emits on every single background hash write; sample so a
                // burst of writes collapses to a periodic regroup instead of one full pairwise pass
                // per hashed row.
                .sample(400L)
                // Build the per-tick lookup maps AFTER the sample, off the main thread, so the two
                // 50k maps are rebuilt a couple of times a second at most, not once per hash write
                // (rebuilding them in the combine transform is what pinned the heap during the fill).
                .map { s ->
                    // Drop items deleted this session BEFORE grouping so a stale source re-emit
                    // can't bring a just-deleted duplicate back into a group with a dead preview.
                    val live = if (s.deleted.isEmpty()) s.items
                        else s.items.filterNot { it.stableId in s.deleted }
                    val localHashes = HashMap<String, String>(s.localHashRows.size)
                    for (r in s.localHashRows) localHashes[r.localUri] = r.localHash
                    val freshHashes = HashMap<String, LeanHash>(s.hashRows.size)
                    for (r in s.hashRows) freshHashes[r.key] = LeanHash(r.hash, r.freshness)
                    Prepared(live, localHashes, freshHashes, s.items, s.deleted, s.localHashRows)
                }
                .flowOn(Dispatchers.Default)
                // A delete writes to sync_state / perceptual_hash while these observers read the
                // same tables, which can fault a cursor window mid-read on a large library. Re-run
                // the stream instead of letting that torn read force-close the screen.
                .retryOnDbTear(TAG) { _uiState.update { it.copy(isLoading = false) } }
                .collect { p ->
                val items = p.liveItems
                // Exact-duplicate grouping depends only on the items, the session-deleted set, and the
                // local hashes, none of which the background fingerprint fill touches, so reuse the
                // last result when all three source references are unchanged instead of rebuilding it.
                val result = if (
                    lastDupResult != null &&
                    p.srcItems === lastDupItems &&
                    p.srcDeleted === lastDupDeleted &&
                    p.srcLocalHashRows === lastDupLocalHashRows
                ) {
                    lastDupResult!!
                } else {
                    findDuplicates(items, p.localHashes).also {
                        lastDupItems = p.srcItems
                        lastDupDeleted = p.srcDeleted
                        lastDupLocalHashRows = p.srcLocalHashRows
                        lastDupResult = it
                    }
                }
                groupSimilarFromStored(items, p.freshHashes)
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
     * [perceptualHashScheduler] in the background, this re-runs as the cache Flow re-emits. While
     * any candidate still lacks a fresh stored hash the scan is reported as in progress.
     */
    private suspend fun groupSimilarFromStored(items: List<GalleryItem>, freshHashes: Map<String, LeanHash>) {
        // Resolve each candidate to its fresh hash off the main thread, and fingerprint the input
        // (candidate key -> hash) so a pass that only removed candidates can skip the O(n²) cluster.
        data class Prepared(
            val deviceHashed: List<Pair<GalleryItem, Long>>,
            val cloudHashed: List<Pair<GalleryItem, Long>>,
            val fingerprint: Map<String, Long>,
            val anyMissing: Boolean,
        )
        val prepared = withContext(Dispatchers.Default) {
            val deviceHashed = ArrayList<Pair<GalleryItem, Long>>()
            val cloudHashed = ArrayList<Pair<GalleryItem, Long>>()
            val fingerprint = HashMap<String, Long>(items.size)
            var anyMissing = false
            for (item in items) {
                when (item) {
                    is GalleryItem.LocalOnly -> {
                        val hash = freshHashFor(item.local.uri, "${item.local.dateModified}_${item.local.sizeBytes}", freshHashes)
                        if (hash != null) { deviceHashed.add(item to hash); fingerprint["d:${item.stableId}"] = hash } else anyMissing = true
                    }
                    is GalleryItem.CloudOnly -> {
                        val linkId = item.cloud.linkId
                        val hash = freshHashFor(linkId, linkId, freshHashes)
                        if (hash != null) { cloudHashed.add(item to hash); fingerprint["c:${item.stableId}"] = hash } else anyMissing = true
                    }
                    is GalleryItem.Synced -> {
                        // Fingerprinted from the local file (see PerceptualHashScheduler); grouped with
                        // the cloud-backed candidates since a Synced photo lives on Drive too.
                        val hash = freshHashFor(item.local.uri, "${item.local.dateModified}_${item.local.sizeBytes}", freshHashes)
                        if (hash != null) { cloudHashed.add(item to hash); fingerprint["c:${item.stableId}"] = hash } else anyMissing = true
                    }
                }
            }
            Prepared(deviceHashed, cloudHashed, fingerprint, anyMissing)
        }

        // A pass that only REMOVED candidates (a delete) leaves the shown groups already pruned in
        // place, so skip the expensive re-cluster and just drop any vanished member. Only a new or
        // changed fingerprint reclusters. This is what stops a burst of deletes on a very large
        // library from re-allocating the cluster buckets on every re-emit until the heap is gone.
        if (!shouldRecluster(prepared.fingerprint, lastClusteredFingerprint)) {
            val liveIds = items.mapTo(HashSet(items.size)) { it.stableId }
            _uiState.update {
                it.copy(
                    similarDeviceGroups = it.similarDeviceGroups.retainingLive(liveIds),
                    similarCloudGroups = it.similarCloudGroups.retainingLive(liveIds),
                    scanningSimilar = prepared.anyMissing,
                )
            }
            return
        }

        val clustered = withContext(Dispatchers.Default) {
            Pair(
                group(prepared.deviceHashed, FindDuplicatesUseCase.GroupType.DEVICE),
                group(prepared.cloudHashed, FindDuplicatesUseCase.GroupType.CLOUD),
            )
        }
        lastClusteredFingerprint = prepared.fingerprint
        _uiState.update {
            it.copy(
                similarDeviceGroups = clustered.first,
                similarCloudGroups = clustered.second,
                scanningSimilar = prepared.anyMissing,
            )
        }
    }

    /** The stored hash for [key] when it exists and matches the expected [freshness]; null when
     *  missing or stale. The map is already filtered to the current algorithm version in the combine. */
    private fun freshHashFor(
        key: String,
        freshness: String,
        freshHashes: Map<String, LeanHash>,
    ): Long? = freshHashes[key]?.takeIf { it.freshness == freshness }?.hash

    /** Drop any group member whose id is no longer live (deleted since the last cluster), collapsing a
     *  group to nothing when one copy remains. A group with every member still present is returned as
     *  is, so the common no-op case allocates nothing. */
    private fun List<FindDuplicatesUseCase.DuplicateGroup>.retainingLive(
        liveIds: Set<String>,
    ): List<FindDuplicatesUseCase.DuplicateGroup> = mapNotNull { g ->
        val remaining = g.items.filter { it.stableId in liveIds }
        when {
            remaining.size == g.items.size -> g
            remaining.size > 1 -> g.copy(items = remaining)
            else -> null
        }
    }

    /**
     * True single-link clustering. Any two items within [PerceptualHash.SIMILARITY_THRESHOLD] join the
     * same cluster, TRANSITIVELY: a near-duplicate run A~B~C groups fully even when A and C are just past
     * the threshold from each other, so a burst of similar shots lands in one group instead of being
     * split by the first item it was compared against. Same threshold, so it never widens what counts as
     * similar; it only stops under-grouping a chain. The clustering itself is delegated to the
     * memory-bounded [PerceptualHash.clusterSimilar], which produces the identical clusters a full
     * pairwise sweep would without accumulating a candidate-pair set that a very large library can OOM on.
     */
    private fun group(
        hashed: List<Pair<GalleryItem, Long>>,
        type: FindDuplicatesUseCase.GroupType,
    ): List<FindDuplicatesUseCase.DuplicateGroup> {
        val n = hashed.size
        if (n < 2) return emptyList()
        val hashes = LongArray(n) { hashed[it].second }
        val root = PerceptualHash.clusterSimilar(hashes, PerceptualHash.SIMILARITY_THRESHOLD)
        val byRoot = HashMap<Int, MutableList<GalleryItem>>()
        for (i in 0 until n) byRoot.getOrPut(root[i]) { mutableListOf() }.add(hashed[i].first)
        return byRoot.values
            .filter { it.size > 1 }
            .map { members ->
                FindDuplicatesUseCase.DuplicateGroup(type, members.sortedBy { it.captureTimeMs })
            }
            .sortedByDescending { it.items.size }
    }

    /** Cloud thumbnails decrypt lazily, request one when its review cell becomes visible. */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        cloudRepo.requestThumbnailDecrypt(userId, linkId)
    }

    fun cancelThumbnailDecrypt(linkId: String) {
        cloudRepo.cancelThumbnailDecrypt(linkId)
    }

    /** Full-resolution facts for a cloud copy: the decrypted file uri plus its true byte size and pixel
     *  dimensions. The photo listing carries none of these for the cloud volume, so the review resolves
     *  them on demand for the copy on screen. */
    data class CloudFullRes(val uri: String, val sizeBytes: Long, val width: Int, val height: Int)

    private val _cloudFullRes = MutableStateFlow<Map<String, CloudFullRes>>(emptyMap())
    val cloudFullRes: StateFlow<Map<String, CloudFullRes>> = _cloudFullRes.asStateFlow()
    private val fullResInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Downloads and decrypts one cloud copy's full-resolution file, then publishes its uri, size and
     *  dimensions (bounds decoded without allocating the bitmap). Runs once per link and only for the
     *  copy the review is showing, so an unopened group is never fetched. */
    fun requestCloudFullRes(photo: CloudPhoto) {
        val linkId = photo.linkId
        // A cached entry whose decrypted temp was cleaned by the cache prune leaves a stale uri, so drop
        // it and fetch again rather than hand back a path that no longer resolves (a black image).
        val existing = _cloudFullRes.value[linkId]
        if (existing != null) {
            if (fullResFileExists(existing.uri)) return
            _cloudFullRes.update { it - linkId }
        }
        if (!fullResInFlight.add(linkId)) return
        val userId = primaryUserId ?: run { fullResInFlight.remove(linkId); return }
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching {
                val file = cloudRepo.downloadFullResPhoto(userId, photo)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                CloudFullRes(Uri.fromFile(file).toString(), file.length(), bounds.outWidth, bounds.outHeight)
            }.getOrNull()
            if (info != null) _cloudFullRes.update { it + (linkId to info) }
            fullResInFlight.remove(linkId)
        }
    }

    /** Whether a resolved full-res file uri still points at a real file (the cache prune can remove it). */
    private fun fullResFileExists(uri: String): Boolean =
        runCatching { Uri.parse(uri).path?.let { java.io.File(it).exists() } == true }.getOrDefault(false)

    /**
     * Delete every copy of [group] EXCEPT the ones in [keepIds]. Enforces the two hard invariants:
     * the keep set must be non-empty (never wipe a whole group), and the operation only ever runs
     * over (group − keepers), which by construction is a strict subset of a proven-duplicate group.
     *
     * @param keepIds [GalleryItem.stableId] of the copies the user chose to keep.
     */
    fun deleteExtras(group: FindDuplicatesUseCase.DuplicateGroup, keepIds: Set<String>) {
        val toDelete = DuplicateDeletion.deletableExtras(
            groupIds = group.items.map { it.stableId },
            keepIds = keepIds,
            alreadyDeleted = recentlyDeleted.value,
        ).let { ids -> group.items.filter { it.stableId in ids } }
        if (toDelete.isEmpty()) return

        viewModelScope.launch {
            val userId = primaryUserId ?: runCatching { accountManager.getPrimaryUserId().first() }.getOrNull()
            if (userId == null) return@launch
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
            try {
                Log.d(TAG, "deleteExtras: group=${group.items.size}, keep=${keepIds.size}, toDelete=${toDelete.size}")
                // Remove every copy each chosen duplicate actually has: a LocalOnly loses its device
                // file, a CloudOnly its Drive copy, a Synced photo BOTH. The use case only acts on the
                // copies an item has, so both flags are safe for any mix, and the keeper check above
                // guarantees at least one copy of the group is still there afterwards.
                val result = deletePhotoUseCase(
                    userId = userId,
                    items = toDelete,
                    freeUpSpace = true,
                    deleteFromCloud = true,
                )
                when (result) {
                    is DeletePhotoUseCase.Result.Success -> {
                        removeFromGroup(group, toDelete.map { it.stableId }.toSet())
                        markDeleted(toDelete)
                    }
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
                // This reports a refused Drive delete by RETURNING it, the same way the first attempt
                // does, so ignoring the answer recorded the copies as gone while the Drive half was
                // still there: the card left the screen, the quota did not move, and nothing said so.
                // Mirrors the branches the pre-dialog path already takes.
                val result = deletePhotoUseCase.completeAfterPermissionGranted(
                    userId = userId,
                    cloudLinkIds = pending.cloudLinkIds,
                    items = pending.itemsBeingDeleted,
                    freeUpSpace = pending.freeUpSpace,
                )
                when (result) {
                    is DeletePhotoUseCase.Result.Success -> markDeleted(pending.itemsBeingDeleted)
                    is DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                        // The system already carried out the device delete before handing back
                        // here, so this copy IS gone from the phone even though Drive kept its own.
                        // It has to be recorded as deleted for exactly that reason: left out, the
                        // rule guarding the last copy would still count it as the one being kept,
                        // and the other copy could then go from both places with none left behind.
                        markDeleted(pending.itemsBeingDeleted)
                        _uiState.update { it.copy(errorMessage = "drive") }
                    }
                    is DeletePhotoUseCase.Result.NeedsMediaWritePermission ->
                        _uiState.update { it.copy(errorMessage = "delete") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "completeAfterPermissionGranted failed for ${pending.itemsBeingDeleted.size} item(s)", e)
                _uiState.update { it.copy(errorMessage = "delete") }
            }
            // The deleted local URIs leave the MediaStore feed, so the gallery flow re-emits and the
            // grouping recomputes automatically, no manual group surgery needed for the device path.
        }
    }

    /** User cancelled the system trash dialog, drop the deferred work. */
    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        _uiState.update { it.copy(pendingDeleteIntent = null) }
    }

    /** The system trash dialog could not even be shown (an OEM threw on launch). Drop the deferred
     *  work and surface the normal failure toast instead of leaving the request stuck. */
    fun onDeleteLaunchFailed() {
        pendingPermissionResult = null
        _uiState.update { it.copy(pendingDeleteIntent = null, errorMessage = "delete") }
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

    /** Record [items] as deleted this session: keeps them out of future groups (via [recentlyDeleted])
     *  and evicts their perceptual-hash rows so a stale fingerprint can't cluster them back into a
     *  similar group. Synced/LocalOnly hash under the local uri, CloudOnly under the linkId. */
    private suspend fun markDeleted(items: List<GalleryItem>) {
        if (items.isEmpty()) return
        recentlyDeleted.update { set -> set + items.map { it.stableId } }
        val keys = items.map { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced    -> item.local.uri
                is GalleryItem.CloudOnly -> item.cloud.linkId
            }
        }
        runCatching { perceptualHashDao.deleteByKeys(keys) }
            .onFailure { Log.w(TAG, "perceptual-hash evict failed: ${it.message}") }
    }
}
