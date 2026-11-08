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

import kotlinx.coroutines.flow.toList
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.MemberRole
import javax.inject.Inject
import javax.inject.Singleton

/** One album shared with this user, as the SDK lists it; [name] is null when it would not decrypt. */
data class SdkSharedAlbum(
    val linkId: String,
    val volumeId: String,
    val shareId: String,
    val name: String?,
    val photoCount: Int,
    val coverLinkId: String?,
    val lastActivityTimeMs: Long?,
    val sharedByEmail: String?,
    val permissions: Long?,
)

/**
 * Albums other users shared with this user, through the Drive SDK: the list and leaving one. The list is
 * all or nothing: an album the SDK names without its share id cannot be opened by the rest of the app,
 * so it fails the whole call and the app's own listing answers instead. Run only through [SdkRouting]
 * ([SdkRoute.SHARED]).
 */
@Singleton
class SdkSharedOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    suspend fun listSharedAlbums(userId: UserId): List<SdkSharedAlbum> =
        clientProvider.withPhotosClient(userId) { client ->
            val uids = client.enumerateSharedWithMeNodeUids().toList().distinct()
            if (uids.isEmpty()) return@withPhotosClient emptyList()
            client.enumerateNodes(uids).toList().filterIsInstance<AlbumNode>().map { it.toSharedAlbum() }
        } ?: throw SdkSharedException("the SDK cannot run on this device")

    /** Leaves the album on [volumeId]: this user's membership ends, the album stays with its owner. */
    suspend fun leave(userId: UserId, volumeId: String, albumLinkId: String) {
        clientProvider.withPhotosClient(userId) { client ->
            client.leaveSharedNode(LegacyNodeUid(volumeId = volumeId, linkId = albumLinkId))
        } ?: throw SdkSharedException("the SDK cannot run on this device")
    }
}

/** Thrown when the SDK cannot do the shared-album operation, so the app's own path does it instead. */
class SdkSharedException(message: String) : IllegalStateException(message)

internal fun AlbumNode.toSharedAlbum(): SdkSharedAlbum {
    val uid = uid as? LegacyNodeUid ?: throw SdkSharedException("an album came back without a link id")
    @Suppress("DEPRECATION")
    val shareId = deprecatedShareId?.takeIf { it.isNotBlank() }
        ?: throw SdkSharedException("an album came back without its share id")
    return SdkSharedAlbum(
        linkId = uid.linkId,
        volumeId = uid.volumeId,
        shareId = shareId,
        name = name.getOrNull()?.takeIf { it.isNotBlank() },
        photoCount = photoCount.toInt(),
        coverLinkId = (coverPhotoNodeUid as? LegacyNodeUid)?.linkId,
        lastActivityTimeMs = lastActivityTime?.toEpochMilli(),
        sharedByEmail = ownedBy.email ?: membership?.sharedBy?.getOrNull()?.emailAddress,
        permissions = membership?.role?.let(::permissionsOf),
    )
}

/** Drive's member permission bits for [role]: read 4, write 2, admin 16. */
internal fun permissionsOf(role: MemberRole): Long? = when (role) {
    MemberRole.VIEWER -> 4L
    MemberRole.EDITOR -> 6L
    MemberRole.ADMIN -> 22L
    MemberRole.INHERITED -> null
}
