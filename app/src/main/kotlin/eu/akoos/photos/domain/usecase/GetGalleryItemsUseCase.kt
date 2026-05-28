package eu.akoos.photos.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import javax.inject.Inject

class GetGalleryItemsUseCase @Inject constructor(
    private val localRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
) {
    fun invoke(userId: UserId): Flow<List<GalleryItem>> =
        combine(
            localRepo.observeLocalMedia(),
            cloudRepo.observeCloudPhotos(userId),
            syncStateRepo.observeAll(userId),
        ) { local, cloud, syncStates ->
            merge(local, cloud, syncStates)
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

        val result = mutableListOf<GalleryItem>()
        val usedCloudIds = mutableSetOf<String>()

        for (localItem in local) {
            val sync = syncByUri[localItem.uri]

            // Resolve the matching cloud photo in priority order:
            //   1. SyncState.cloudFileId (most reliable, set explicitly on upload + download)
            //   2. contentHash equality (covers downloads without a SyncState row + restores)
            // Each cloud entry can only be claimed by ONE local item — that's why we check
            // [usedCloudIds] before pairing. Without this, a download that races a sync can
            // produce two Synced entries pointing at the same cloud linkId.
            val cloudFromSync = sync?.cloudFileId
                ?.takeIf { it !in usedCloudIds }
                ?.let { cloudByLinkId[it] }
            val cloudFromHash = if (cloudFromSync == null)
                sync?.localHash?.takeIf { it.isNotEmpty() }
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

        for (cloudPhoto in cloud) {
            if (cloudPhoto.linkId !in usedCloudIds) {
                result += GalleryItem.CloudOnly(cloudPhoto)
            }
        }

        return result.sortedByDescending { it.captureTimeMs }
    }
}
