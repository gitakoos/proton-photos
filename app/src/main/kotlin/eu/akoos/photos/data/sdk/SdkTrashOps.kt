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
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.NodeResultPair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moving photos to the Drive trash, back, and out of it for good through the Drive SDK's Photos client, one
 * batch at a time, answering as the app's own requests do: [moveToTrash] and [deleteForever] return the
 * links the server refused, a link it no longer holds counting as done, and [restore] returns nothing,
 * since the server's per-link answers to a Photos restore come back wrong for links it did restore. Run
 * only through [SdkRouting] ([SdkRoute.TRASH]).
 */
@Singleton
class SdkTrashOps @Inject constructor(
    private val clientProvider: SdkDriveClientProvider,
) {
    suspend fun moveToTrash(userId: UserId, volumeId: String, linkIds: List<String>): Set<String> =
        clientProvider.withPhotosClient(userId) { client ->
            refusedLinks(client.trashNodes(nodeUids(volumeId, linkIds)))
        } ?: throw SdkTrashException("the SDK cannot run on this device")

    /** Deletes trashed photos permanently; the explicit list only, never the whole trash. */
    suspend fun deleteForever(userId: UserId, volumeId: String, linkIds: List<String>): Set<String> =
        clientProvider.withPhotosClient(userId) { client ->
            refusedLinks(client.deleteNodes(nodeUids(volumeId, linkIds)))
        } ?: throw SdkTrashException("the SDK cannot run on this device")

    suspend fun restore(userId: UserId, volumeId: String, linkIds: List<String>) {
        clientProvider.withPhotosClient(userId) { client ->
            // Drained so the restore runs; the per-link codes are not trusted, see the class comment.
            client.restoreNodes(nodeUids(volumeId, linkIds)).collect { }
        } ?: throw SdkTrashException("the SDK cannot run on this device")
    }

    private suspend fun refusedLinks(results: Flow<NodeResultPair>): Set<String> {
        val refused = mutableSetOf<String>()
        results.collect { result ->
            if (result is NodeResultPair.Failure &&
                !linkAlreadyGone(result.error.error?.primaryCode, result.error.error?.secondaryCode)
            ) {
                refused += (result.nodeUid as LegacyNodeUid).linkId
            }
        }
        return refused
    }

    private fun nodeUids(volumeId: String, linkIds: List<String>) =
        linkIds.map { LegacyNodeUid(volumeId = volumeId, linkId = it) }
}

/** Thrown when the SDK cannot move the photos, so the caller's own request does it instead. */
class SdkTrashException(message: String) : IllegalStateException(message)

/** Proton's per-link code for a link the server no longer holds, which a trash counts as done. */
private const val LINK_NOT_EXISTS_CODE = 2501L

/** Whether a refused link was refused only because it is already gone. */
internal fun linkAlreadyGone(primaryCode: Long?, secondaryCode: Long?): Boolean =
    primaryCode == LINK_NOT_EXISTS_CODE || secondaryCode == LINK_NOT_EXISTS_CODE
