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

package eu.akoos.photos.data.sdk

import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.NodeResultPair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Album writes through the Drive SDK's Photos client: create, rename and cover, delete, and adding and
 * removing photos. Adds and removes answer as the app's own requests do, with the links the server refused,
 * and hand each finished link to `onDone` as its answer arrives, so a call that fails partway lets the
 * caller's own request take only the links still left. A photo the album already holds counts as added.
 * Run only through [SdkRouting]: [SdkRoute.ALBUMS], and [SdkRoute.SHARED] for an album another user shared.
 */
@Singleton
class SdkAlbumOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    /** Creates an album on this user's photo volume and returns its link id. */
    suspend fun createAlbum(userId: UserId, name: String): String =
        clientProvider.withPhotosClient(userId) { client ->
            val uid = client.createAlbum(name).uid as? LegacyNodeUid
                ?: throw SdkAlbumException("the new album came back without a link id")
            uid.linkId
        } ?: throw SdkAlbumException("the SDK cannot run on this device")

    suspend fun addPhotos(
        userId: UserId,
        volumeId: String,
        albumLinkId: String,
        photoLinkIds: List<String>,
        onDone: (linkId: String) -> Unit = {},
    ): Set<String> =
        clientProvider.withPhotosClient(userId) { client ->
            refusedPhotos(
                client.addPhotosToAlbum(nodeUid(volumeId, albumLinkId), photoLinkIds.map { nodeUid(volumeId, it) }),
                onDone,
                countsAsDone = ::alreadyInAlbum,
            )
        } ?: throw SdkAlbumException("the SDK cannot run on this device")

    /**
     * Adds this user's photos (on [ownVolumeId]) to an album another user shared, on [albumVolumeId]: the
     * SDK copies each photo onto the album owner's volume, its Live Photo or burst companions with it.
     * Answers like [addPhotos], keyed by this user's own link ids.
     */
    suspend fun addPhotosToForeignAlbum(
        userId: UserId,
        albumVolumeId: String,
        albumLinkId: String,
        ownVolumeId: String,
        photoLinkIds: List<String>,
        onDone: (linkId: String) -> Unit = {},
    ): Set<String> =
        clientProvider.withPhotosClient(userId) { client ->
            refusedPhotos(
                client.addPhotosToAlbum(nodeUid(albumVolumeId, albumLinkId), photoLinkIds.map { nodeUid(ownVolumeId, it) }),
                onDone,
                countsAsDone = ::alreadyInAlbum,
            )
        } ?: throw SdkAlbumException("the SDK cannot run on this device")

    /** Drops photos from an album; the photos themselves stay. [volumeId] is the album's own volume. */
    suspend fun removePhotos(
        userId: UserId,
        volumeId: String,
        albumLinkId: String,
        photoLinkIds: List<String>,
        onDone: (linkId: String) -> Unit = {},
    ): Set<String> =
        clientProvider.withPhotosClient(userId) { client ->
            refusedPhotos(
                client.removePhotosFromAlbum(nodeUid(volumeId, albumLinkId), photoLinkIds.map { nodeUid(volumeId, it) }),
                onDone,
                countsAsDone = { false },
            )
        } ?: throw SdkAlbumException("the SDK cannot run on this device")

    /** Renames the album and/or sets its cover; a rename keeps the name's session key, so shares still read it. */
    suspend fun updateAlbum(
        userId: UserId,
        volumeId: String,
        albumLinkId: String,
        name: String? = null,
        coverLinkId: String? = null,
    ) {
        clientProvider.withPhotosClient(userId) { client ->
            client.updateAlbum(
                nodeUid(volumeId, albumLinkId),
                name = name,
                coverPhotoUid = coverLinkId?.let { nodeUid(volumeId, it) },
            )
        } ?: throw SdkAlbumException("the SDK cannot run on this device")
    }

    /**
     * Deletes the album; [deletePhotosToo] also deletes the photos it alone holds. Returns false, deleting
     * nothing, when the album holds such photos and [deletePhotosToo] is off, so the caller can ask the user.
     */
    suspend fun deleteAlbum(userId: UserId, volumeId: String, albumLinkId: String, deletePhotosToo: Boolean): Boolean =
        clientProvider.withPhotosClient(userId) { client ->
            try {
                client.deleteAlbum(nodeUid(volumeId, albumLinkId), force = deletePhotosToo, saveToTimeline = false)
                true
            } catch (e: ProtonDriveSdkException) {
                if (holdsPhotosNowhereElse(e.error)) false else throw e
            }
        } ?: throw SdkAlbumException("the SDK cannot run on this device")

    private suspend fun refusedPhotos(
        results: Flow<NodeResultPair>,
        onDone: (String) -> Unit,
        countsAsDone: (ProtonSdkError?) -> Boolean,
    ): Set<String> {
        val refused = mutableSetOf<String>()
        results.collect { result ->
            val linkId = (result.nodeUid as LegacyNodeUid).linkId
            if (result is NodeResultPair.Failure && !countsAsDone(result.error.error)) {
                refused += linkId
            } else {
                onDone(linkId)
            }
        }
        return refused
    }

    private fun nodeUid(volumeId: String, linkId: String) = LegacyNodeUid(volumeId = volumeId, linkId = linkId)
}

/** Thrown when the SDK cannot do the album change, so the caller's own request does it instead. */
class SdkAlbumException(message: String) : IllegalStateException(message)

/** Whether the SDK refused a photo only because the album already holds it. */
internal fun alreadyInAlbum(error: ProtonSdkError?): Boolean = error?.type == "PhotoAlreadyInTargetException"

/** Whether a refused album delete means the album holds photos that exist nowhere else. */
internal fun holdsPhotosNowhereElse(error: ProtonSdkError?): Boolean =
    error?.additionalData is ProtonSdkError.Data.AlbumContainsPhotosNotInTimeline
