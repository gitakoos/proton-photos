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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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

    fun invoke(userId: UserId): Flow<List<GalleryItem>> =
        cache.getOrPut(userId) {
            combine(
                localRepo.observeLocalMedia(),
                cloudRepo.observeCloudPhotos(userId),
                syncStateRepo.observeAll(userId),
            ) { local, cloud, syncStates ->
                merge(local, cloud, syncStates)
            }
                // The merge/sort over the full library is CPU work that must not run on the
                // collector's Main dispatcher — a decrypt burst re-emits this per write.
                .flowOn(Dispatchers.Default)
                .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        }

    private fun merge(
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

            val cloudPhoto = cloudFromSync ?: cloudFromHash ?: cloudFromContent

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
        // captureTimeMs and stableId are computed getters; evaluate each exactly once by
        // precomputing a lightweight (captureTimeMs, stableId, item) key per item, then sort the
        // keys. Identical ordering to compareByDescending(captureTimeMs).thenBy(stableId).
        return result
            .map { Triple(it.captureTimeMs, it.stableId, it) }
            .sortedWith(
                compareByDescending<Triple<Long, String, GalleryItem>> { it.first }
                    .thenBy { it.second }
            )
            .map { it.third }
    }
}
