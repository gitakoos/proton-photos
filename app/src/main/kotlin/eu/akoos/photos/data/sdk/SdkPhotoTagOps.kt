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

import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.PhotoTag
import me.proton.drive.sdk.entity.PhotoTagsUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when the SDK cannot do the tag change, so the caller's own request does it instead. */
class SdkTagException(message: String) : IllegalStateException(message)

/**
 * Photo tags and favourites through the Drive SDK's Photos client: one photo, one tag, added or removed.
 * Favourite is tag 0, like everywhere else in the app. Run only through [SdkRouting] ([SdkRoute.TAGS]), so
 * any failure here falls back to the app's own request.
 */
@Singleton
class SdkPhotoTagOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    suspend fun updateTag(userId: UserId, volumeId: String, linkId: String, tagId: Int, add: Boolean) {
        val update = photoTagsUpdate(volumeId, linkId, tagId, add)
        clientProvider.withPhotosClient(userId) { client ->
            client.updatePhotos(listOf(update)).collect { result ->
                if (result is NodeResultPair.Failure) throw result.error
            }
        } ?: throw SdkTagException("the SDK cannot run on this device")
    }
}

/** The SDK's change for [tagId] on one photo: a Drive PhotoTag id, 0 to 9, 0 being Favourite. */
internal fun photoTagsUpdate(volumeId: String, linkId: String, tagId: Int, add: Boolean): PhotoTagsUpdate {
    val tag = PhotoTag.fromLong(tagId.toLong()) ?: throw SdkTagException("no Drive tag $tagId")
    return PhotoTagsUpdate(
        nodeUid = LegacyNodeUid(volumeId = volumeId, linkId = linkId),
        tagsToAdd = if (add) listOf(tag) else emptyList(),
        tagsToRemove = if (add) emptyList() else listOf(tag),
    )
}
