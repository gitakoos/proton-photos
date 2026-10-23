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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.repository.drive.CloudTrashOutcome
import eu.akoos.photos.data.repository.drive.UploadPhase
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.domain.entity.AlbumNotFoundException
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SharedPhoto
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.VaultWipe
import java.io.File

/**
 * Shared in-memory [DrivePhotoRepository] fake for the use-case tests. Only the members those tests
 * touch are real:
 *  - [observeCloudPhotos] emits the live [photos] list so reconcile sees uploads + deletes.
 *  - [uploadFile] appends a [CloudPhoto] with a deterministic linkId, records the item / xAttr / linkId,
 *    and returns it.
 *  - [deleteFiles] removes matching linkIds from the list.
 *  - [addPhotosToAlbum] records membership into [albumMembership]; [failAddPhotosOnce] flips it to throw
 *    exactly once (the transient-failure scenarios), and [rejectAddPhotosOnce] to report every photo as
 *    failed in its result exactly once, the way the real add reports an unsent or refused photo;
 *    [loseAddReplyOnce] records the membership yet reports it failed. An album in [goneAlbums] throws
 *    [AlbumNotFoundException] on every add. [loadAlbumChildren] lists the recorded membership.
 *  - [onUploadStarted] runs inside [uploadFile] before anything is recorded, while the uploading row is
 *    still claimed, so a caller can act on a photo mid-upload.
 *  - [getAlbumIdsByPhoto] returns the seeded [albumIdsByPhoto] map.
 *  - [downloadFullResPhoto] returns a throwaway temp file carrying [downloadBytes].
 *  - [callLog] records the order of upload / add / delete calls so ordering invariants can be asserted.
 *  - [loadAlbums] / [createDriveAlbum] are minimal; [retryPendingOrphanDeletes] is a no-op;
 *    [cloudContentHash] returns null.
 * Every other member is an unused no-op / empty result.
 */
internal class FakeDrivePhotoRepository : DrivePhotoRepository {

    val photos = MutableStateFlow<List<CloudPhoto>>(emptyList())
    private val albumMembership = mutableMapOf<String, MutableList<String>>()
    private var counter = 0

    /** When true, the next [addPhotosToAlbum] call throws, then resets itself. Models a transient
     *  add failure so a caller's album-membership retry / safety guard can be exercised. */
    var failAddPhotosOnce = false

    /** When true, the next [uploadFile] call throws before recording anything, then resets itself.
     *  Models a transient upload failure so a caller's restore / no-trash guard can be exercised. */
    var failUploadOnce = false

    /** When non-null, the next [uploadFile] call throws this exact throwable before recording
     *  anything, then clears it. Lets a caller's transient-vs-permanent classification be exercised
     *  with a specific error TYPE (an [java.io.IOException] vs a plain non-network error). */
    var uploadErrorOnce: Throwable? = null

    /** When non-null, the next [addPhotosToAlbum] call throws this exact throwable, then clears it.
     *  Same purpose as [uploadErrorOnce] for the album re-add path. */
    var addPhotosErrorOnce: Throwable? = null

    /** When true, the next [addPhotosToAlbum] call returns every photo as failed without adding any,
     *  then resets itself. Models a request that never reached the server or a refused entry, which
     *  the real add reports in its result rather than by throwing. */
    var rejectAddPhotosOnce = false

    /** When true, the next [addPhotosToAlbum] call records the membership but still returns every
     *  photo as failed, then resets itself. Models an add that landed while its reply was lost. */
    var loseAddReplyOnce = false

    /** Albums Drive no longer holds: every [addPhotosToAlbum] aimed at one throws
     *  [AlbumNotFoundException], the way the real add reports a deleted album. */
    val goneAlbums = mutableSetOf<String>()

    /** Albums shared with this user that it may add to, as [loadSharedAddableAlbumsCached] lists them. */
    val sharedAddableAlbums = mutableListOf<Album>()

    /** Albums that report every photo as failed on each [addPhotosToAlbum], without adding any: a
     *  shared album whose copy keeps being refused. */
    val refusingAlbums = mutableSetOf<String>()

    /** Runs at the start of every [uploadFile] call, while the upload is in flight. */
    var onUploadStarted: (suspend (LocalMediaItem) -> Unit)? = null

    /** When true, the next [uploadFile] call throws a [kotlinx.coroutines.CancellationException] before
     *  recording anything, then resets itself. Models the user cancelling a replace mid-upload so a
     *  caller's cancellation restore / no-trash guard can be exercised. */
    var cancelUploadOnce = false

    /** Seeded `photoLinkId -> Set<albumLinkId>` map returned by [getAlbumIdsByPhoto]. */
    var albumIdsByPhoto: Map<String, Set<String>> = emptyMap()

    /** When true, the next [getVerifiedAlbumIdsByPhoto] call throws, then resets itself. Models an
     *  album enumeration that could not be verified complete, so a destructive caller must abort before
     *  it uploads or trashes anything. */
    var failVerifiedAlbumIdsOnce = false

    /** Bytes handed back by [downloadFullResPhoto] as the "downloaded original". */
    var downloadBytes: ByteArray = "fake-original-bytes".toByteArray()

    /** Ordered record of the mutating cloud calls: "UPLOAD", "ADD:<albumLinkId>", "DELETE". */
    val callLog = mutableListOf<String>()
    val uploadedItems = mutableListOf<LocalMediaItem>()
    val uploadedXAttrs = mutableListOf<UploadXAttrMetadata>()
    val uploadedLinkIds = mutableListOf<String>()

    fun add(photo: CloudPhoto) {
        photos.value = photos.value + photo
    }

    fun removeByLinkId(linkId: String) {
        photos.value = photos.value.filterNot { it.linkId == linkId }
    }

    fun albumMembers(albumLinkId: String): List<String> = albumMembership[albumLinkId].orEmpty()

    override fun observeCloudPhotos(userId: UserId): Flow<List<CloudPhoto>> = photos.asStateFlow()

    override fun observeHiddenAlbumMemberLinkIds(): Flow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()

    override suspend fun uploadFile(
        userId: UserId,
        item: LocalMediaItem,
        sha1HexContentDigest: String,
        uploadUri: String,
        xAttrMetadata: UploadXAttrMetadata,
        onProgress: ((phase: UploadPhase, doneBytes: Long, totalBytes: Long) -> Unit)?,
    ): String {
        onUploadStarted?.invoke(item)
        if (cancelUploadOnce) {
            cancelUploadOnce = false
            throw kotlinx.coroutines.CancellationException("simulated upload cancel")
        }
        uploadErrorOnce?.let { uploadErrorOnce = null; throw it }
        if (failUploadOnce) {
            failUploadOnce = false
            error("simulated transient upload failure")
        }
        val linkId = "cloud-${counter++}-${item.uri.hashCode().toUInt().toString(16)}"
        callLog += "UPLOAD"
        uploadedItems += item
        uploadedXAttrs += xAttrMetadata
        uploadedLinkIds += linkId
        add(
            CloudPhoto(
                linkId = linkId,
                shareId = "share",
                volumeId = "vol1",
                captureTime = item.dateTaken / 1000L,
                displayName = item.displayName,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
                thumbnailUrl = null,
                revisionId = "rev-$linkId",
                contentHash = sha1HexContentDigest.takeIf { it.isNotEmpty() },
            ),
        )
        return linkId
    }

    override suspend fun addPhotosToAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): DrivePhotoRepository.AddPhotosToAlbumResult {
        callLog += "ADD:$albumLinkId"
        if (albumLinkId in goneAlbums) throw AlbumNotFoundException(albumLinkId)
        addPhotosErrorOnce?.let { addPhotosErrorOnce = null; throw it }
        if (failAddPhotosOnce) {
            failAddPhotosOnce = false
            error("simulated transient album-add failure")
        }
        if (rejectAddPhotosOnce || albumLinkId in refusingAlbums) {
            rejectAddPhotosOnce = false
            return DrivePhotoRepository.AddPhotosToAlbumResult(
                succeededLinkIds = emptyList(),
                failedLinkIds = photoLinkIds,
            )
        }
        albumMembership.getOrPut(albumLinkId) { mutableListOf() }.addAll(photoLinkIds)
        if (loseAddReplyOnce) {
            loseAddReplyOnce = false
            return DrivePhotoRepository.AddPhotosToAlbumResult(
                succeededLinkIds = emptyList(),
                failedLinkIds = photoLinkIds,
            )
        }
        return DrivePhotoRepository.AddPhotosToAlbumResult(
            succeededLinkIds = photoLinkIds,
            failedLinkIds = emptyList(),
        )
    }

    override suspend fun deleteFiles(userId: UserId, linkIds: List<String>): CloudTrashOutcome {
        callLog += "DELETE"
        photos.value = photos.value.filterNot { it.linkId in linkIds }
        return CloudTrashOutcome(trashedLinkIds = linkIds.toSet(), failedLinkIds = emptySet())
    }

    override suspend fun getAlbumIdsByPhoto(userId: UserId): Map<String, Set<String>> = albumIdsByPhoto

    override suspend fun getVerifiedAlbumIdsByPhoto(userId: UserId): Map<String, Set<String>> {
        if (failVerifiedAlbumIdsOnce) {
            failVerifiedAlbumIdsOnce = false
            throw IllegalStateException("simulated incomplete album enumeration")
        }
        return albumIdsByPhoto
    }

    override suspend fun downloadFullResPhoto(
        userId: UserId,
        photo: CloudPhoto,
        preResolvedLinkDetail: eu.akoos.photos.data.api.dto.BatchLinkDto?,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)?,
    ): File = File.createTempFile("fake-fullres-", ".bin").apply {
        writeBytes(downloadBytes)
        deleteOnExit()
    }

    override suspend fun loadAlbums(userId: UserId): List<Album> = emptyList()
    override suspend fun createDriveAlbum(userId: UserId, name: String): Album =
        Album(linkId = "album-$name", name = name, photoCount = 0, coverLinkId = null, lastActivityTimeMs = null)

    override suspend fun retryPendingOrphanDeletes(userId: UserId) {}
    override fun cloudContentHash(localSha1Hex: String): String? = null

    // ── Unused members: never reached by the use cases under test ────────────────────
    override suspend fun getVolumeId(userId: UserId): String = "vol1"
    override suspend fun getShareId(userId: UserId, volumeId: String): String = "share"
    override fun observePhotosByLinkIds(linkIds: List<String>): Flow<List<CloudPhoto>> =
        MutableStateFlow(emptyList())
    override suspend fun refreshCloudPhotos(userId: UserId, force: Boolean) {}
    override suspend fun refreshCloudPhotosIncremental(userId: UserId) {}
    override suspend fun loadAlbumsCached(): List<Album> = emptyList()

    override fun observeAlbumsCached(): Flow<List<Album>> = kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun getCachedAlbumIdsForPhoto(photoLinkId: String): Set<String> = emptySet()
    override suspend fun loadSharedAddableAlbumsCached(): List<Album> = sharedAddableAlbums.toList()
    override suspend fun loadSharedWithMeAlbumsCached(): List<Album> = emptyList()
    override suspend fun prefetchAlbumsMembership(userId: UserId, albums: List<Album>) {}
    override suspend fun prefetchSharedAlbumsMembership(userId: UserId, albums: List<Album>) {}
    override suspend fun prefetchSharedAlbumCovers(userId: UserId, albums: List<Album>) {}
    override suspend fun getAlbumMemberships(userId: UserId): Map<String, String> = emptyMap()
    override suspend fun loadAlbumChildren(userId: UserId, albumLinkId: String): List<AlbumChild> =
        albumMembers(albumLinkId).map { AlbumChild(linkId = it, captureTime = null, addedTime = null) }
    override suspend fun loadAlbumPhotos(
        userId: UserId,
        albumLinkId: String,
        volumeId: String?,
        sharingShareId: String?,
        onLinkIdsResolved: ((List<String>) -> Unit)?,
    ): List<CloudPhoto> = emptyList()
    override suspend fun loadAlbumPhotosCached(albumLinkId: String): List<CloudPhoto> = emptyList()
    override suspend fun copyCloudPhotoAs(
        userId: UserId,
        photo: CloudPhoto,
        newName: String,
    ): String = error("unused")
    /** Every favourite write, as (linkId, favorite). */
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun setCloudFavorite(userId: UserId, photo: CloudPhoto, favorite: Boolean): Boolean {
        favoriteCalls += photo.linkId to favorite
        return true
    }
    override suspend fun setCloudTag(userId: UserId, photo: CloudPhoto, tagId: Int, add: Boolean): Boolean = false
    override suspend fun deleteAlbum(userId: UserId, albumLinkId: String, deletePhotosToo: Boolean) {}
    override suspend fun removePhotosFromAlbum(
        userId: UserId,
        albumLinkId: String,
        photoLinkIds: List<String>,
    ): List<String> = emptyList()
    override suspend fun renameAlbum(userId: UserId, albumLinkId: String, newName: String) {}
    override suspend fun setAlbumCover(userId: UserId, albumLinkId: String, coverPhotoLinkId: String) {}
    override suspend fun getCloudTrash(userId: UserId): List<CloudTrashItem> = emptyList()
    override suspend fun restoreFromCloudTrash(
        userId: UserId,
        linkIds: List<String>,
    ): eu.akoos.photos.data.repository.drive.CloudRestoreOutcome =
        eu.akoos.photos.data.repository.drive.CloudRestoreOutcome(emptySet(), emptySet(), false)
    override suspend fun deleteFromCloudForever(
        userId: UserId,
        linkIds: List<String>,
    ): eu.akoos.photos.data.repository.drive.CloudDeleteOutcome =
        eu.akoos.photos.data.repository.drive.CloudDeleteOutcome(emptySet(), emptySet())
    override suspend fun createAlbumShareLink(
        userId: UserId,
        albumLinkId: String,
    ): eu.akoos.photos.domain.entity.AlbumShareLink =
        eu.akoos.photos.domain.entity.AlbumShareLink(url = "", shareId = "")
    override suspend fun createPhotoShareLink(userId: UserId, photoLinkId: String): String = ""
    override suspend fun getPhotoShareLink(userId: UserId, photoLinkId: String): String? = null
    override suspend fun revokePhotoShareLink(userId: UserId, photoLinkId: String) {}
    override suspend fun setPhotoLinkPassword(userId: UserId, photoLinkId: String, password: String?): String = ""
    override suspend fun inviteToAlbum(userId: UserId, albumLinkId: String, email: String, permissions: Int): String = ""
    override suspend fun saveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    ): DrivePhotoRepository.SaveSharedAlbumOutcome = error("unused")
    override fun startSaveSharedAlbumToOwnLibrary(
        userId: UserId,
        sharingShareId: String,
        sourceAlbumLinkId: String,
        sourceAlbumDecryptedName: String,
        sourceVolumeId: String,
    ) {}
    override val saveSharedAlbumState: StateFlow<DrivePhotoRepository.SaveSharedAlbumProgress> =
        MutableStateFlow(DrivePhotoRepository.SaveSharedAlbumProgress.Idle)
    override fun acknowledgeSaveSharedAlbumResult() {}
    override fun cancelSaveSharedAlbumToOwnLibrary() {}
    override suspend fun deleteShare(userId: UserId, shareId: String) {}
    override suspend fun leaveSharedAlbum(userId: UserId, shareId: String, albumLinkId: String) {}
    override suspend fun revokeShareUrlOnly(userId: UserId, shareId: String) {}
    override suspend fun changeMemberPermission(userId: UserId, shareId: String, memberId: String, permissions: Int) {}
    override suspend fun changeInvitationPermission(userId: UserId, shareId: String, invitationId: String, permissions: Int) {}
    override suspend fun loadSharedWithMeAlbums(userId: UserId): List<Album> = emptyList()
    override suspend fun loadSharedByMePhotos(userId: UserId): List<SharedPhoto> = emptyList()
    override fun observeSharedByMePhotos(linkIds: List<String>): Flow<List<SharedPhoto>> =
        MutableStateFlow(emptyList())
    override suspend fun loadShareInvitations(userId: UserId, shareId: String): List<ShareInvitation> = emptyList()
    override suspend fun revokeShareInvitation(userId: UserId, shareId: String, invitationId: String) {}
    override suspend fun loadShareMembers(userId: UserId, shareId: String): List<ShareMember> = emptyList()
    override suspend fun removeShareMember(userId: UserId, shareId: String, memberId: String) {}
    override suspend fun loadPendingInvitations(userId: UserId): List<PendingInvitation> = emptyList()
    override suspend fun declineInvitation(userId: UserId, invitationId: String) {}
    override suspend fun acceptInvitation(userId: UserId, invitationId: String) {}
    override suspend fun clearCacheForSignOut(userId: UserId, vault: VaultWipe) {}
    override fun requestThumbnailDecrypt(userId: UserId, linkId: String) {}
    override fun cancelThumbnailDecrypt(linkId: String) {}
    override fun prefetchThumbnailDecrypt(userId: UserId, linkIds: List<String>) {}
    override suspend fun clearCachedThumbnailUrls() {}
    override fun requestThumbnailDecrypt(userId: UserId, linkIds: List<String>) {}
    override fun backfillThumbnails(userId: UserId) {}
    override suspend fun backfillCloudGps(userId: UserId) {}
    override suspend fun backfillVideoDurations(userId: UserId) {}
    override suspend fun backfillLocalExif(userId: UserId) {}
    override suspend fun backfillFaces(userId: UserId?) {}
    override suspend fun backfillSemantic(userId: UserId?) {}
}
