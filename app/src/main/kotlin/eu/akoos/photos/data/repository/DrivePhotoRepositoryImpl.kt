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

package eu.akoos.photos.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.repository.drive.AlbumService
import eu.akoos.photos.data.repository.drive.AlbumSharingService
import eu.akoos.photos.data.repository.drive.CloudTrashService
import eu.akoos.photos.data.repository.drive.PhotoDownloadService
import eu.akoos.photos.data.repository.drive.PhotoStreamService
import eu.akoos.photos.data.repository.drive.PhotoUploadService
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.data.repository.drive.RecentUploadsTracker
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin façade that satisfies the [DrivePhotoRepository] contract by delegating to the
 * focused Drive services under `data/repository/drive/`. Every public method is a
 * one-line delegator; no business logic lives here.
 *
 * The façade exists so ViewModels can keep depending on a single `DrivePhotoRepository`
 * interface while the Drive backend is split into per-concern services internally.
 */
@Singleton
class DrivePhotoRepositoryImpl @Inject constructor(
    private val cryptoHelper: DriveCryptoHelper,
    private val shareService: PhotosShareService,
    private val albumService: AlbumService,
    private val streamService: PhotoStreamService,
    private val downloadService: PhotoDownloadService,
    private val uploadService: PhotoUploadService,
    private val recentUploadsTracker: RecentUploadsTracker,
    private val cloudTrashService: CloudTrashService,
    private val albumSharingService: AlbumSharingService,
    private val thumbnailScheduler: ThumbnailDecryptScheduler,
    private val photoListingDao: PhotoListingDao,
) : DrivePhotoRepository {

    // Long-lived scope for fire-and-forget DAO lookups in the thumbnail request path.
    // Cells call requestThumbnailDecrypt as a non-suspend bridge from Compose, but the
    // actual entity fetch is a DAO suspend call — launching it on an IO supervisor scope
    // keeps the Compose call site cheap and isolates DB failures from leaking out.
    private val thumbnailRequestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun getVolumeId(userId: UserId): String = shareService.getVolumeId(userId)

    override suspend fun getShareId(userId: UserId, volumeId: String): String =
        shareService.getShareId(userId, volumeId)

    override fun observeCloudPhotos(userId: UserId): Flow<List<CloudPhoto>> =
        streamService.observeCloudPhotos(userId)

    override fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>> =
        streamService.observePhotosByLinkIds(linkIds)

    override suspend fun refreshCloudPhotos(userId: UserId): Unit =
        streamService.refreshCloudPhotos(userId)

    override suspend fun refreshCloudPhotosIncremental(userId: UserId): Unit =
        streamService.refreshCloudPhotosIncremental(userId)

    override suspend fun loadAlbums(userId: UserId): List<Album> =
        albumService.loadAlbums(userId)

    override suspend fun loadAlbumsCached(): List<Album> =
        albumService.loadAlbumsCached()

    override suspend fun prefetchAlbumsMembership(userId: UserId, albums: List<Album>) =
        albumService.prefetchAlbumsMembership(userId, albums)

    override suspend fun createDriveAlbum(userId: UserId, name: String): Album =
        albumService.createDriveAlbum(userId, name)

    override suspend fun loadAlbumChildren(userId: UserId, albumLinkId: String): List<AlbumChild> =
        albumService.loadAlbumChildren(userId, albumLinkId)

    override suspend fun loadAlbumPhotos(
        userId: UserId,
        albumLinkId: String,
        volumeId: String?,
        sharingShareId: String?,
        onLinkIdsResolved: ((List<String>) -> Unit)?,
    ): List<CloudPhoto> =
        albumService.loadAlbumPhotos(userId, albumLinkId, volumeId, sharingShareId, onLinkIdsResolved)

    override suspend fun loadAlbumPhotosCached(albumLinkId: String): List<CloudPhoto> =
        albumService.loadAlbumPhotosCached(albumLinkId)

    override suspend fun addPhotosToAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): DrivePhotoRepository.AddPhotosToAlbumResult =
        albumService.addPhotosToAlbum(userId, albumLinkId, photoLinkIds)

    override suspend fun deleteAlbum(userId: UserId, albumLinkId: String): Unit =
        albumService.deleteAlbum(userId, albumLinkId)

    override suspend fun getAlbumMemberships(userId: UserId): Map<String, String> =
        albumService.getAlbumMemberships(userId)

    override suspend fun getAlbumIdsByPhoto(userId: UserId): Map<String, Set<String>> =
        albumService.getAlbumIdsByPhoto(userId)

    override suspend fun removePhotosFromAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): List<String> = albumService.removePhotosFromAlbum(userId, albumLinkId, photoLinkIds)

    override suspend fun renameAlbum(userId: UserId, albumLinkId: String, newName: String): Unit =
        albumService.renameAlbum(userId, albumLinkId, newName)

    override suspend fun setAlbumCover(userId: UserId, albumLinkId: String, coverPhotoLinkId: String): Unit =
        albumService.setAlbumCover(userId, albumLinkId, coverPhotoLinkId)

    override suspend fun downloadFullResPhoto(
        userId: UserId,
        photo: CloudPhoto,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)?,
    ): File = downloadService.downloadFullResPhoto(userId, photo, onProgress)

    override suspend fun uploadFile(
        userId: UserId,
        item: LocalMediaItem,
        sha1HexContentDigest: String,
        uploadUri: String,
        onProgress: ((
            phase: eu.akoos.photos.data.repository.drive.UploadPhase,
            doneBytes: Long,
            totalBytes: Long,
        ) -> Unit)?,
    ): String = uploadService.uploadFile(userId, item, sha1HexContentDigest, uploadUri, onProgress)

    override suspend fun renameOrCopyCloudPhoto(
        userId: UserId,
        photo: CloudPhoto,
        newName: String,
        trashOriginal: Boolean,
    ): String = cloudTrashService.renameOrCopyCloudPhoto(userId, photo, newName, trashOriginal)

    override suspend fun setCloudFavorite(userId: UserId, photo: CloudPhoto, favorite: Boolean): Boolean =
        cloudTrashService.setCloudFavorite(userId, photo, favorite)

    override suspend fun deleteFiles(userId: UserId, linkIds: List<String>) =
        cloudTrashService.deleteFiles(userId, linkIds)

    override suspend fun getCloudTrash(userId: UserId): List<CloudTrashItem> =
        cloudTrashService.getCloudTrash(userId)

    override suspend fun restoreFromCloudTrash(userId: UserId, linkIds: List<String>) =
        cloudTrashService.restoreFromCloudTrash(userId, linkIds)

    override suspend fun deleteFromCloudForever(userId: UserId, linkIds: List<String>) =
        cloudTrashService.deleteFromCloudForever(userId, linkIds)

    override suspend fun createAlbumShareLink(userId: UserId, albumLinkId: String): String =
        albumSharingService.createAlbumShareLink(userId, albumLinkId)

    override suspend fun inviteToAlbum(userId: UserId, albumLinkId: String, email: String) =
        albumSharingService.inviteToAlbum(userId, albumLinkId, email)

    override suspend fun saveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    ): DrivePhotoRepository.SaveSharedAlbumOutcome {
        val result = albumSharingService.saveSharedAlbumToOwnLibrary(
            userId = userId,
            sharingShareId = sharingShareId,
            sourceAlbumLinkId = sourceAlbumLinkId,
            sourceAlbumDecryptedName = sourceAlbumDecryptedName,
            sourceVolumeId = sourceVolumeId,
        )
        return DrivePhotoRepository.SaveSharedAlbumOutcome(
            newAlbumLinkId = result.newAlbumLinkId,
            copiedCount = result.copiedCount,
            failedCount = result.failedCount,
            totalRequested = result.totalRequested,
        )
    }

    override fun startSaveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    ) {
        albumSharingService.startSaveSharedAlbumToOwnLibrary(
            userId = userId,
            sharingShareId = sharingShareId,
            sourceAlbumLinkId = sourceAlbumLinkId,
            sourceAlbumDecryptedName = sourceAlbumDecryptedName,
            sourceVolumeId = sourceVolumeId,
        )
    }

    override val saveSharedAlbumState: kotlinx.coroutines.flow.StateFlow<DrivePhotoRepository.SaveSharedAlbumProgress> =
        albumSharingService.saveToLibraryState
            .map { state ->
                when (state) {
                    is AlbumSharingService.SaveToLibraryState.Idle -> DrivePhotoRepository.SaveSharedAlbumProgress.Idle
                    is AlbumSharingService.SaveToLibraryState.Running -> DrivePhotoRepository.SaveSharedAlbumProgress.Running(
                        sourceAlbumLinkId = state.sourceAlbumLinkId,
                        copied = state.copied,
                        total = state.total,
                    )
                    is AlbumSharingService.SaveToLibraryState.Done -> DrivePhotoRepository.SaveSharedAlbumProgress.Done(
                        sourceAlbumLinkId = state.sourceAlbumLinkId,
                        newAlbumLinkId = state.newAlbumLinkId,
                        copiedCount = state.copiedCount,
                        failedCount = state.failedCount,
                        totalRequested = state.totalRequested,
                    )
                    is AlbumSharingService.SaveToLibraryState.Failed -> DrivePhotoRepository.SaveSharedAlbumProgress.Failed(
                        sourceAlbumLinkId = state.sourceAlbumLinkId,
                        reason = state.reason,
                    )
                    is AlbumSharingService.SaveToLibraryState.Cancelled -> DrivePhotoRepository.SaveSharedAlbumProgress.Cancelled(
                        sourceAlbumLinkId = state.sourceAlbumLinkId,
                        copied = state.copied,
                        total = state.total,
                    )
                }
            }
            .let { flow ->
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                flow.stateIn(
                    scope = GlobalScope,
                    started = SharingStarted.Eagerly,
                    initialValue = DrivePhotoRepository.SaveSharedAlbumProgress.Idle,
                )
            }

    override fun acknowledgeSaveSharedAlbumResult() {
        albumSharingService.acknowledgeSaveToLibraryResult()
    }

    override fun cancelSaveSharedAlbumToOwnLibrary() {
        albumSharingService.cancelSaveToLibrary()
    }


    override suspend fun deleteShare(userId: UserId, shareId: String) =
        albumSharingService.deleteShare(userId, shareId)

    override suspend fun leaveSharedAlbum(userId: UserId, shareId: String, albumLinkId: String) =
        albumSharingService.leaveSharedAlbum(userId, shareId, albumLinkId)

    override suspend fun revokeShareUrlOnly(userId: UserId, shareId: String) =
        albumSharingService.revokeShareUrlOnly(userId, shareId)

    override suspend fun changeMemberPermission(
        userId: UserId,
        shareId: String,
        memberId: String,
        permissions: Int,
    ) = albumSharingService.changeMemberPermission(userId, shareId, memberId, permissions)

    override suspend fun changeInvitationPermission(
        userId: UserId,
        shareId: String,
        invitationId: String,
        permissions: Int,
    ) = albumSharingService.changeInvitationPermission(userId, shareId, invitationId, permissions)

    override suspend fun loadSharedWithMeAlbums(userId: UserId): List<Album> =
        albumSharingService.loadSharedWithMeAlbums(userId)

    override suspend fun loadShareInvitations(userId: UserId, shareId: String): List<ShareInvitation> =
        albumSharingService.loadShareInvitations(userId, shareId)

    override suspend fun revokeShareInvitation(userId: UserId, shareId: String, invitationId: String) =
        albumSharingService.revokeShareInvitation(userId, shareId, invitationId)

    override suspend fun loadShareMembers(userId: UserId, shareId: String): List<ShareMember> =
        albumSharingService.loadShareMembers(userId, shareId)

    override suspend fun removeShareMember(userId: UserId, shareId: String, memberId: String) =
        albumSharingService.removeShareMember(userId, shareId, memberId)

    override suspend fun loadPendingInvitations(userId: UserId): List<PendingInvitation> =
        albumSharingService.loadPendingInvitations(userId)

    override suspend fun declineInvitation(userId: UserId, invitationId: String) =
        albumSharingService.declineInvitation(userId, invitationId)

    override suspend fun acceptInvitation(userId: UserId, invitationId: String) =
        albumSharingService.acceptInvitation(userId, invitationId)

    override fun clearCacheForSignOut() {
        // Wipe all plaintext key material before the user's tokens disappear, so even if the
        // process keeps running afterwards a heap inspection can't pull keys from this Singleton.
        shareService.wipeKeyCache()
        cryptoHelper.clearAllCaches()
        recentUploadsTracker.clearInMemory()
        thumbnailScheduler.clear()
        // Cancel any in-flight Save-to-my-library copy and reset its state to Idle so a
        // re-login by a different user doesn't pick up a stale Running banner against the
        // old account's album linkId. The Job is rooted in a Singleton-scoped SupervisorJob
        // that otherwise survives ViewModel teardown.
        runCatching { albumSharingService.cancelSaveToLibrary() }
        // Drop the cached cloud album list so the next signed-in user doesn't briefly see the
        // previous account's albums on cold launch. Fire-and-forget — sign-out should not
        // block on a Room write.
        thumbnailRequestScope.launch { albumService.clearAlbumCache() }
    }

    override fun requestThumbnailDecrypt(userId: UserId, linkId: String) {
        // Look up the persisted encrypted material; the scheduler dedup's so it's safe to
        // fire this from a LaunchedEffect that may re-run on recomposition.
        thumbnailRequestScope.launch {
            val entity = photoListingDao.getByLinkId(linkId) ?: return@launch
            thumbnailScheduler.request(userId, entity)
        }
    }

    override fun cancelThumbnailDecrypt(linkId: String) {
        thumbnailScheduler.cancel(linkId)
    }

    override suspend fun clearCachedThumbnailUrls() {
        photoListingDao.clearCachedThumbnailUrls()
    }

    override fun prefetchThumbnailDecrypt(userId: UserId, linkIds: List<String>) {
        if (linkIds.isEmpty()) return
        thumbnailRequestScope.launch {
            val entities = photoListingDao.getByLinkIds(linkIds)
            if (entities.isNotEmpty()) thumbnailScheduler.prefetch(userId, entities)
        }
    }

    override fun requestThumbnailDecrypt(userId: UserId, linkIds: List<String>) {
        if (linkIds.isEmpty()) return
        thumbnailRequestScope.launch {
            val entities = photoListingDao.getByLinkIds(linkIds)
            entities.forEach { thumbnailScheduler.request(userId, it) }
        }
    }

    override fun backfillThumbnails(userId: UserId) {
        thumbnailScheduler.backfillAll(userId)
    }
}
