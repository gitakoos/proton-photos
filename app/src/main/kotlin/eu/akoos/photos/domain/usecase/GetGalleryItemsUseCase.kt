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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Cap the merge+sort to a few runs/sec during a DB write burst (the cold cloud listing).
 *  Sub-frame-budget so the steady-state single emit is imperceptible. */
private const val RECOMPUTE_THROTTLE_MS = 280L

@Singleton
class GetGalleryItemsUseCase @Inject constructor(
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    @AppScope private val appScope: CoroutineScope,
) {
    // One shared upstream merge per user, so the associateBy/groupBy/sort runs ONCE across all
    // collectors (Gallery, Search, Calendar, …) instead of re-running per cold subscription.
    private val cache = ConcurrentHashMap<UserId, SharedFlow<List<GalleryItem>>>()

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
                syncStateRepo.observeAll(userId).distinctUntilChanged(),
            ) { local, cloud, syncStates ->
                Triple(local, cloud, syncStates)
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
                .map { (local, cloud, syncStates) -> merge(local, cloud, syncStates) }
                // The merge/sort over the full library is CPU work that must not run on the
                // collector's Main dispatcher — a decrypt burst re-emits this per write.
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        }

    // internal (not private) so the unit test exercises this pure merge / classification / sort
    // logic directly, instead of driving it through the shared flow + Dispatchers.Default pipeline
    // above (which a virtual-time test harness cannot pump).
    internal fun merge(
        local: List<LocalMediaItem>,
        cloud: List<CloudPhoto>,
        syncStates: List<SyncState>,
    ): List<GalleryItem> {
        val syncByUri = syncStates.associateBy { it.localUri }
        // Fast lookup of cloud entries by both linkId AND contentHash so we can pair a local
        // file with its cloud counterpart even when the SyncState is missing (e.g. user just
        // downloaded the album from a fresh install — no SyncState row exists yet but the
        // contentHash matches).
        val cloudByLinkId = cloud.associateBy { it.linkId }
        val cloudByHash = cloud.filter { !it.contentHash.isNullOrEmpty() }
            .associateBy { it.contentHash!! }
        // Content-based pairing fallback for cloud photos THIS app never uploaded — e.g. a library
        // backed up by Proton Drive's own app. With no SyncState row, neither cloudFileId nor
        // localHash resolves, so a device photo that also lives on Drive would otherwise render
        // twice (LocalOnly + CloudOnly). Group by (lower-case name, capture second); a local item
        // with exactly ONE unclaimed match there pairs as Synced. Grouping (not associateBy) lets
        // the loop REFUSE to guess when two cloud photos share the key, rather than merge a wrong pair.
        val cloudByNameAndDate: Map<Pair<String, Long>, List<CloudPhoto>> =
            cloud.groupBy { it.displayName.lowercase() to it.captureTime }
        // Name + exact byte size, videos only (see [cloudFromVideoSize] below). Grouped, not
        // associateBy, so an ambiguous (name,size) refuses to guess just like name+date.
        val cloudByNameSize: Map<Pair<String, Long>, List<CloudPhoto>> =
            cloud.groupBy { it.displayName.lowercase() to it.sizeBytes }

        val result = mutableListOf<GalleryItem>()
        val usedCloudIds = mutableSetOf<String>()

        for (localItem in local) {
            val sync = syncByUri[localItem.uri]

            // Resolve the matching cloud photo in priority order:
            //   1. SyncState.cloudFileId (most reliable, set explicitly on upload + download)
            //   2. contentHash equality (covers downloads without a SyncState row + restores)
            //   3. name + capture-second (covers cloud photos this app never uploaded, e.g. backed
            //      up by Proton Drive's own app — only when the match is unambiguous)
            // Each cloud entry can only be claimed by ONE local item — that's why we check
            // [usedCloudIds] before pairing. Without this, a download that races a sync can
            // produce two Synced entries pointing at the same cloud linkId.
            val cloudFromSync = sync?.cloudFileId
                ?.takeIf { it !in usedCloudIds }
                ?.let { cloudByLinkId[it] }
            // localHash is the bare SHA-1; cloudByHash is keyed by the cloud ContentHash
            // (HMAC-SHA256(rootNodeHashKey, sha1Hex)), so convert before the lookup — this pairs a
            // photo to its cloud copy by content even when rename-on-upload changed the cloud name.
            val cloudFromHash = if (cloudFromSync == null)
                sync?.localHash?.takeIf { it.isNotEmpty() }
                    ?.let { cloudRepo.cloudContentHash(it) }
                    ?.let { cloudByHash[it] }
                    ?.takeIf { it.linkId !in usedCloudIds }
            else null
            // Only when our own bookkeeping gave us nothing: pair ONLY if exactly one unclaimed
            // cloud photo carries this name+second, so an ambiguous burst/duplicate falls back to
            // the LocalOnly + CloudOnly behaviour instead of merging the wrong pair.
            val cloudFromContent = if (cloudFromSync == null && cloudFromHash == null)
                cloudByNameAndDate[localItem.displayName.lowercase() to (localItem.dateTaken / 1000L)]
                    ?.filter { it.linkId !in usedCloudIds }
                    ?.singleOrNull()
            else null
            // Videos can't reliably name+capture-time pair: a video's capture time isn't a stable
            // cross-client key the way a photo's EXIF date is, so after a fresh reinstall (no
            // SyncState/stored hash yet) a video stays LocalOnly until the next full sync recomputes
            // hashes — while photos pair immediately. Fall back to name + EXACT byte size for videos
            // only: a video's length is highly distinctive and singleOrNull refuses any ambiguous
            // match, so this pairs the same file early without the capture-time mismatch.
            val cloudFromVideoSize = if (cloudFromSync == null && cloudFromHash == null &&
                    cloudFromContent == null && localItem.mimeType.startsWith("video/"))
                cloudByNameSize[localItem.displayName.lowercase() to localItem.sizeBytes]
                    ?.filter { it.linkId !in usedCloudIds && it.sizeBytes > 0 }
                    ?.singleOrNull()
            else null

            val cloudPhoto = cloudFromSync ?: cloudFromHash ?: cloudFromContent ?: cloudFromVideoSize

            if (cloudPhoto != null) {
                usedCloudIds += cloudPhoto.linkId
                result += GalleryItem.Synced(cloudPhoto, localItem)
            } else {
                result += GalleryItem.LocalOnly(localItem)
            }
        }

        for (cloudPhoto in cloud) {
            if (cloudPhoto.linkId !in usedCloudIds) {
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
        val n = result.size
        val times = LongArray(n)
        val ids = arrayOfNulls<String>(n)
        for (i in 0 until n) {
            times[i] = result[i].captureTimeMs
            ids[i] = result[i].stableId
        }
        val order = (0 until n).sortedWith(Comparator { a, b ->
            val byTime = times[b].compareTo(times[a]) // descending captureTime
            if (byTime != 0) byTime else ids[a]!!.compareTo(ids[b]!!) // ascending stableId tiebreak
        })
        return order.map { result[it] }
    }
}
