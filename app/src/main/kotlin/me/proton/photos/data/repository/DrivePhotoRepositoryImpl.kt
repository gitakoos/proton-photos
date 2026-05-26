package me.proton.photos.data.repository

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import me.proton.photos.data.crypto.DriveCryptoHelper
import me.proton.photos.data.repository.drive.AlbumService
import me.proton.photos.data.repository.drive.AlbumSharingService
import me.proton.photos.data.repository.drive.CloudTrashService
import me.proton.photos.data.repository.drive.PhotoDownloadService
import me.proton.photos.data.repository.drive.PhotoStreamService
import me.proton.photos.data.repository.drive.PhotoUploadService
import me.proton.photos.data.repository.drive.PhotosShareService
import me.proton.photos.data.repository.drive.RecentUploadsTracker
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.AlbumChild
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.CloudTrashItem
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.domain.entity.PendingInvitation
import me.proton.photos.domain.entity.ShareInvitation
import me.proton.photos.domain.entity.ShareMember
import me.proton.photos.domain.repository.DrivePhotoRepository
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
) : DrivePhotoRepository {

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

    override suspend fun createDriveAlbum(userId: UserId, name: String): Album =
        albumService.createDriveAlbum(userId, name)

    override suspend fun loadAlbumChildren(userId: UserId, albumLinkId: String): List<AlbumChild> =
        albumService.loadAlbumChildren(userId, albumLinkId)

    override suspend fun loadAlbumPhotos(userId: UserId, albumLinkId: String, volumeId: String?): List<CloudPhoto> =
        albumService.loadAlbumPhotos(userId, albumLinkId, volumeId)

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

    override suspend fun uploadFile(userId: UserId, item: LocalMediaItem, hash: String, uploadUri: String): String =
        uploadService.uploadFile(userId, item, hash, uploadUri)

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

    override suspend fun deleteShare(userId: UserId, shareId: String) =
        albumSharingService.deleteShare(userId, shareId)

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
    }
}
