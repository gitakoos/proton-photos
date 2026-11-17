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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.repository.SyncStateSnapshot
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.util.PerfDiagnostics
import eu.akoos.photos.util.retryOnDbTear
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Cap the merge+sort to a few runs/sec during a DB write burst (the cold cloud listing).
 *  Sub-frame-budget so the steady-state single emit is imperceptible. */
private const val RECOMPUTE_THROTTLE_MS = 280L

/** How many of the newest device photos the timeline's first list is built from; see [GetGalleryItemsUseCase.withQuickStart]. */
private const val QUICK_START_COUNT = 2_000

/** How many of the newest device photos are read live on top of a kept snapshot at a cold start. */
private const val QUICK_START_LIVE_COUNT = 200

@Singleton
class GetGalleryItemsUseCase @Inject constructor(
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateSnapshot: SyncStateSnapshot,
    @AppScope private val appScope: CoroutineScope,
) {
    // One shared upstream merge per user, so the associateBy/groupBy/sort runs ONCE across all
    // collectors (Gallery, Search, Calendar, …) instead of re-running per cold subscription.
    private val cache = ConcurrentHashMap<UserId, SharedFlow<List<GalleryItem>>>()

    private val firstMergeMarked = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    fun invoke(userId: UserId): Flow<List<GalleryItem>> =
        cache.getOrPut(userId) {
            combine(
                // distinctUntilChanged on each source BEFORE the merge: the cold listing's stub
                // pre-pass and the cache-hit chunks that wrote nothing new make Room re-emit a
                // structurally identical list. Dropping those here means the N-log-N merge+sort
                // below never re-runs for a write that changed nothing.
                localRepo.observeLocalMedia().distinctUntilChanged(),
                cloudRepo.observeCloudPhotos(userId).distinctUntilChanged(),
                // The app-wide lean copy of the sync rows: distinct already, and read again at most
                // once a second however fast a backup writes the table.
                syncStateSnapshot.observe(userId),
                // Client-side hidden albums: the member linkIds to drop from EVERY listing. This is
                // the single choke point all surfaces share (they all collect this flow), so one
                // filter here hides a hidden album's photos from timeline, search, map, calendar,
                // memories, folders and pickers at once. Empty + query-free when nothing is hidden.
                cloudRepo.observeHiddenAlbumMemberLinkIds().distinctUntilChanged(),
            ) { local, cloud, sync, hidden ->
                MergeInput(local, cloud, sync, hidden)
            }
                // Throttle the EXPENSIVE recompute. On a large library the cold listing writes the
                // DB thousands of times; without this gate the merge+sort would re-run on the full,
                // growing list per write (seconds of CPU + GC thrash). `sample` caps the merge to a
                // few runs/sec regardless of the DB write rate. It cannot drop the final state: the
                // source flows are infinite (Room observers stay subscribed), so the last write's
                // value sits in sample's conflated buffer and is delivered on the next tick (≤ the
                // period), then cached by shareIn(replay=1) for any late collector. `distinctUntilChanged`
                // above already suppressed no-op re-emits, so a quiet library samples its one real value.
                .sample(RECOMPUTE_THROTTLE_MS)
                .map { input ->
                    // The first merge of the process is timed (library size in, size out) so a cold
                    // start on a big library shows how long the merge takes next to the device scan.
                    val first = firstMergeMarked.compareAndSet(false, true)
                    if (first) PerfDiagnostics.mark("gallerymerge:start", input.local.size)
                    merge(input.local, input.cloud, input.sync, input.hiddenLinkIds).also {
                        if (first) PerfDiagnostics.mark("gallerymerge:end", it.size)
                    }
                }
                // The merge/sort over the full library is CPU work that must not run on the
                // collector's Main dispatcher — a decrypt burst re-emits this per write.
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                // Survive a torn cursor read on any source: a concurrent delete/upsert can fault a
                // cursor window mid-read on a large library. Without this the fault would fail this
                // shared flow's sharing coroutine in appScope (which has no exception handler) and
                // crash the whole app, every collector at once (timeline, duplicate finder, search).
                // Re-subscribing rebuilds the stream and the next emission refills each screen.
                .retryOnDbTear("GetGalleryItems")
                // Conflated, so a slow collector skips to the newest list instead of holding up to a
                // buffer's worth of whole-library lists queued for it. Dropped once no one has
                // collected it for the stop window, so no merged list outlives the screens reading it.
                .conflate()
                .shareIn(appScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)
        }

    /**
     * [full] (the feed from [invoke] or [invokeLocalOnly]) with a first list put ahead of it for a
     * screen that fills in as soon as it can. The full feed waits for the scan of the whole device, which
     * takes seconds on a library of a hundred thousand photos; this list is merged from only the newest
     * [QUICK_START_COUNT] device photos and so is on screen well before. It is emitted only while the full
     * feed has not yet emitted, and the full feed replaces it as soon as it does.
     *
     * For a screen that shows the library, never one that acts on what is absent from it: the first list
     * lacks the older device photos.
     */
    fun withQuickStart(userId: UserId?, full: Flow<List<GalleryItem>>): Flow<List<GalleryItem>> = channelFlow {
        val order = Mutex()
        var fullSeen = false
        val quick = launch {
            // The whole list the last complete scan of an earlier run found, with the newest photos read live on
            // top (what was shot while the app was closed), else the newest photos alone.
            val kept = localRepo.lastKnownMedia()?.takeIf { it.isNotEmpty() }
            val newest = if (kept != null) withLiveNewest(kept, localRepo.newestMedia(QUICK_START_LIVE_COUNT)) else localRepo.newestMedia(QUICK_START_COUNT)
            if (newest.isEmpty()) return@launch
            val items = if (userId != null) {
                val cloud = cloudRepo.observeCloudPhotos(userId).first()
                val sync = syncStateSnapshot.observe(userId).first()
                val hidden = cloudRepo.observeHiddenAlbumMemberLinkIds().first()
                // A whole kept list holds every device file a cloud photo can pair with, so none is held back.
                val shownCloud = if (kept != null) cloud else cloudForQuickStart(cloud, newest, sync)
                withContext(Dispatchers.Default) { merge(newest, shownCloud, sync, hidden) }
            } else {
                withContext(Dispatchers.Default) { merge(newest, emptyList(), emptyList()) }
            }
            // Checked and sent under one lock with the full feed's own, so the list cannot land after it.
            order.withLock { if (!fullSeen) send(items) }
        }
        full.collect { items ->
            order.withLock {
                fullSeen = true
                quick.cancel()
                send(items)
            }
        }
    }

    /** Session-free gallery feed for the no-account (local-only) mode: the device's own media with
     *  no Proton session. Merges the local source against zero cloud photos and empty sync state, so
     *  it yields EXACTLY the [GalleryItem.LocalOnly] rows [invoke] produces, sorted identically. The
     *  album-hide filter never removes a local-only item (those carry no cloud linkId), so the hidden
     *  member source is not consulted here. Reads and writes no per-user cache and mints no UserId:
     *  a structural null-session branch. */
    fun invokeLocalOnly(): Flow<List<GalleryItem>> = localOnlyFeed

    /**
     * [invokeLocalOnly]'s one merge, shared by every guest surface (timeline, search, map, people)
     * the way [invoke]'s is for a signed-in user, so the device library is merged once rather than
     * once per collector. Built on first use, so a signed-in session never starts it.
     *
     * Conflated, so a slow collector skips to the newest list instead of holding the others back
     * with copies queued for it. Dropped once no one has collected it for the stop window, so a
     * later one-shot reader merges a current scan rather than a snapshot kept from a screen long
     * closed, and no guest-mode list outlives the session that needed it.
     */
    private val localOnlyFeed: SharedFlow<List<GalleryItem>> by lazy {
        localRepo.observeLocalMedia()
            .distinctUntilChanged()
            .map { merge(it, emptyList(), emptyList()) }
            // The merge/sort over the full library is CPU work that must not run on the collector's
            // Main dispatcher, matching invoke()'s reasoning for the same local source.
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .retryOnDbTear("GetGalleryItemsLocalOnly")
            .conflate()
            .shareIn(appScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)
    }

    /** Bundles the four merge inputs so the sample+map pipeline carries them without a tuple type. */
    private data class MergeInput(
        val local: List<LocalMediaItem>,
        val cloud: List<CloudPhoto>,
        val sync: SyncStateSnapshot.Rows,
        val hiddenLinkIds: Set<String>,
    )

    // internal (not private) so the unit test exercises this pure merge / classification / sort
    // logic directly, instead of driving it through the shared flow + Dispatchers.Default pipeline
    // above (which a virtual-time test harness cannot pump).
    internal fun merge(
        local: List<LocalMediaItem>,
        cloud: List<CloudPhoto>,
        syncStates: List<SyncState>,
        hiddenLinkIds: Set<String> = emptySet(),
    ): List<GalleryItem> = mergeRows(
        local = local,
        cloud = cloud,
        syncByUri = syncStates.associateBy { it.localUri },
        cloudFileIdOf = { it.cloudFileId },
        localHashOf = { it.localHash },
        hiddenSyncedLinkIds = syncStates.asSequence()
            .filter { it.status == SyncStatus.HIDDEN }
            .mapNotNull { it.cloudFileId }
            .toSet(),
        hiddenLinkIds = hiddenLinkIds,
    )

    /** [merge] over the shared lean copy of the sync rows, which the feed runs on. It reads the same
     *  columns of each row, so it pairs, badges and hides exactly as the full rows do. */
    internal fun merge(
        local: List<LocalMediaItem>,
        cloud: List<CloudPhoto>,
        sync: SyncStateSnapshot.Rows,
        hiddenLinkIds: Set<String> = emptySet(),
    ): List<GalleryItem> = mergeRows(
        local = local,
        cloud = cloud,
        syncByUri = sync.byUri,
        cloudFileIdOf = { it.cloudFileId },
        localHashOf = { it.localHash },
        hiddenSyncedLinkIds = sync.hiddenCloudIds,
        hiddenLinkIds = hiddenLinkIds,
    )

    /**
     * The merge itself, over whichever form of the sync rows the caller holds: [syncByUri] maps a
     * device file's uri to its row, read through [cloudFileIdOf] and [localHashOf], and
     * [hiddenSyncedLinkIds] holds the cloud ids on the rows of vaulted photos.
     */
    private fun <R : Any> mergeRows(
        local: List<LocalMediaItem>,
        cloud: List<CloudPhoto>,
        syncByUri: Map<String, R>,
        cloudFileIdOf: (R) -> String?,
        localHashOf: (R) -> String,
        hiddenSyncedLinkIds: Set<String>,
        hiddenLinkIds: Set<String>,
    ): List<GalleryItem> {
        // A device file pairs with a Drive photo on the same basis the sync layer links them: the
        // file's sync_state row names the photo, or the bytes the row recorded hash to the photo's
        // Drive content hash. A shared name, capture time or size is no proof the bytes are the same:
        // paired on one, a different file of the same name read as the photo's copy on this device,
        // and the Drive photo could not be saved from the gallery. An unproven pair shows as two
        // items, the device file and the Drive photo, until a backup, reconcile or save links them.
        val cloudByLinkId = cloud.associateBy { it.linkId }
        // Read only when a local item's SyncState carries no cloudFileId, so a steady-state library
        // (every device photo already paired by cloudFileId) never builds it.
        val cloudByHash: Map<String, CloudPhoto> by lazy(LazyThreadSafetyMode.NONE) {
            cloud.filter { !it.contentHash.isNullOrEmpty() }.associateBy { it.contentHash!! }
        }

        val result = mutableListOf<GalleryItem>()
        val usedCloudIds = mutableSetOf<String>()

        for (localItem in local) {
            val sync = syncByUri[localItem.uri]

            // Resolve the matching cloud photo in priority order:
            //   1. SyncState.cloudFileId (set explicitly on upload, download and reconcile)
            //   2. the row's recorded hash equal to the photo's Drive content hash
            // Each cloud entry can only be claimed by ONE local item — that's why we check
            // [usedCloudIds] before pairing. Without this, a download that races a sync can
            // produce two Synced entries pointing at the same cloud linkId.
            val cloudFromSync = sync?.let(cloudFileIdOf)
                ?.takeIf { it !in usedCloudIds }
                ?.let { cloudByLinkId[it] }
            // localHash is the bare SHA-1; cloudByHash is keyed by the cloud ContentHash
            // (HMAC-SHA256(rootNodeHashKey, sha1Hex)), so convert before the lookup — this pairs a
            // photo to its cloud copy by content even when rename-on-upload changed the cloud name.
            val cloudFromHash = if (cloudFromSync == null)
                sync?.let(localHashOf)?.takeIf { it.isNotEmpty() }
                    ?.let { cloudRepo.cloudContentHash(it) }
                    ?.let { cloudByHash[it] }
                    ?.takeIf { it.linkId !in usedCloudIds }
            else null

            val cloudPhoto = cloudFromSync ?: cloudFromHash

            if (cloudPhoto != null) {
                usedCloudIds += cloudPhoto.linkId
                result += GalleryItem.Synced(cloudPhoto, localItem)
            } else {
                result += GalleryItem.LocalOnly(localItem)
            }
        }

        // Claimed as it is listed, so a link the listing carries twice still shows once: the grid keys
        // a cloud item by its link and throws on a repeat.
        for (cloudPhoto in cloud) {
            if (usedCloudIds.add(cloudPhoto.linkId)) {
                result += GalleryItem.CloudOnly(cloudPhoto)
            }
        }

        // Single merge-sort key: GalleryItem.captureTimeMs already routes each state through
        // TimestampSanity, so a sub-floor cloud value falls back to its local twin's DATE_TAKEN
        // instead of sinking to the list tail. linkId/uri tiebreak keeps equal-timestamp bursts
        // in a stable total order across re-emissions.
        //
        // captureTimeMs and stableId are computed getters; evaluate each EXACTLY once. Sort an
        // index array against precomputed primitive key arrays (no per-item Triple, no Long
        // boxing, no double .map over the whole library), then materialise the ordered list in a
        // single pass. Ordering is identical to compareByDescending(captureTimeMs).thenBy(stableId).
        // Drop photos in a client-side hidden album just before the sort, so pairing above is
        // unaffected (a hidden Synced pair still claims its cloud id) and every downstream surface
        // sharing this flow sees the photo gone. Nothing is deleted; unhiding re-includes it.
        // Fold the vaulted-photo signal into the same drop set. A photo moved into the Hidden vault
        // keeps its Drive copy but leaves MediaStore, so the pairing above renders its twin as
        // CloudOnly; a HIDDEN row's cloudFileId is exactly that twin's cloud.linkId (the key
        // cloudByLinkId is built on), so unioning it here drops the twin from every surface instead
        // of leaving it visible. HIDDEN is a tiny subset and the union allocates only when non-empty.
        val effectiveHiddenLinkIds = when {
            hiddenSyncedLinkIds.isEmpty() -> hiddenLinkIds
            hiddenLinkIds.isEmpty() -> hiddenSyncedLinkIds
            else -> hiddenLinkIds + hiddenSyncedLinkIds
        }
        val visible = if (effectiveHiddenLinkIds.isEmpty()) result
            else result.filterNot { isInHiddenAlbum(it, effectiveHiddenLinkIds) }

        val n = visible.size
        val times = LongArray(n)
        val ids = arrayOfNulls<String>(n)
        for (i in 0 until n) {
            times[i] = visible[i].captureTimeMs
            ids[i] = visible[i].stableId
        }
        val order = (0 until n).sortedWith(Comparator { a, b ->
            val byTime = times[b].compareTo(times[a]) // descending captureTime
            if (byTime != 0) byTime else ids[a]!!.compareTo(ids[b]!!) // ascending stableId tiebreak
        })
        return order.map { visible[it] }
    }

    /** A gallery item belongs to a hidden album when its cloud copy's linkId is a hidden-album
     *  member. A LocalOnly item has no cloud linkId, so an album hide never removes a device-only
     *  photo (those use the separate hidden vault). */
    internal fun isInHiddenAlbum(item: GalleryItem, hiddenLinkIds: Set<String>): Boolean {
        if (hiddenLinkIds.isEmpty()) return false
        val linkId = when (item) {
            is GalleryItem.Synced -> item.cloud.linkId
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
        return linkId != null && linkId in hiddenLinkIds
    }
}

/**
 * The Drive photos the timeline's quick first list may show. One paired to a device file that list does not hold
 * (an older photo, outside the newest few thousand) is left out until the whole scan arrives: shown before then it
 * would read as on Drive only, and an action picked on it would act on the Drive copy alone. Pure.
 */
/** [kept] with [live] laid over it: a uri in both is taken from [live], which is read now. */
internal fun withLiveNewest(kept: List<LocalMediaItem>, live: List<LocalMediaItem>): List<LocalMediaItem> {
    if (live.isEmpty()) return kept
    val liveUris = live.mapTo(HashSet(live.size * 2)) { it.uri }
    return live + kept.filter { it.uri !in liveUris }
}

internal fun cloudForQuickStart(
    cloud: List<CloudPhoto>,
    newest: List<LocalMediaItem>,
    sync: SyncStateSnapshot.Rows,
): List<CloudPhoto> {
    val held = newest.mapTo(HashSet()) { it.uri }
    val pairedElsewhere = sync.rows.asSequence()
        .filter { it.status == SyncStatus.SYNCED && it.localUri !in held }
        .mapNotNullTo(HashSet()) { it.cloudFileId }
    return if (pairedElsewhere.isEmpty()) cloud else cloud.filterNot { it.linkId in pairedElsewhere }
}
