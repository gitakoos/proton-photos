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

import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import javax.inject.Inject
import me.proton.core.domain.entity.UserId

/** Asks Drive itself whether a photo link is live: present and in the active state, so neither trashed nor
 *  deleted. Throws when Drive cannot be asked, so a caller never reads "gone" into a network failure. */
class CloudLinkCheck @Inject constructor(
    private val cloudRepo: DrivePhotoRepository,
    private val linkDetails: LinkDetailHelpers,
) {
    suspend fun isLive(userId: UserId, linkId: String): Boolean {
        val details = linkDetails.batchFetchLinkDetails(userId, cloudRepo.getVolumeId(userId), listOf(linkId), strict = true)
        return details[linkId]?.link?.state == LINK_STATE_ACTIVE
    }

    private companion object {
        const val LINK_STATE_ACTIVE = 1
    }
}
