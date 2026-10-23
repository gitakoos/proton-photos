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

import android.util.Log
import eu.akoos.photos.data.db.dao.ImageEmbeddingDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.label.PhotoLabelRepository
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import kotlinx.coroutines.CancellationException
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

private const val TAG = "CarryOverToReplacement"

/**
 * Hands what a photo holds under its Drive link to the copy that replaces it when a date or place edit
 * uploads a corrected copy and trashes the original: the favourite (a server tag the upload itself does
 * not set), the labels, the face data and the search index, all keyed by the link the copy replaces. Run
 * before the original is trashed, while its listing row still says whether it was a favourite. Each part
 * stands alone, so one that fails costs that part only and never the edit.
 */
class CarryOverToReplacement @Inject constructor(
    private val cloudRepo: DrivePhotoRepository,
    private val photoListingDao: PhotoListingDao,
    private val photoLabels: PhotoLabelRepository,
    private val rekeyPhotoFaces: RekeyPhotoFacesUseCase,
    private val imageEmbeddingDao: ImageEmbeddingDao,
) {
    /**
     * The favourite is read off the replaced photo's listing row, which still holds it at this point.
     * [original] is only the fallback for a row already gone: a queued edit carries the photo as it was
     * when the edit was asked for, so a heart set after that is on the row and not on [original].
     */
    suspend operator fun invoke(userId: UserId, oldLinkId: String, newLinkId: String, original: CloudPhoto? = null) {
        if (oldLinkId == newLinkId) return
        val replaced = step("read the original") { photoListingDao.getByLinkId(oldLinkId)?.toDomain() } ?: original
        if (replaced?.isFavoriteOnCloud == true) {
            step("favourite") { cloudRepo.setCloudFavorite(userId, replaced.copy(linkId = newLinkId), favorite = true) }
        }
        step("labels") { photoLabels.rekeyFor(userId, oldLinkId, newLinkId) }
        step("faces") { rekeyPhotoFaces.invoke(userId.id, oldLinkId, newLinkId) }
        step("search index") { imageEmbeddingDao.rekeyPhoto(userId.id, oldLinkId, newLinkId) }
    }

    private suspend fun <T> step(what: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "carrying the $what over failed: ${e.message}")
            null
        }
}
