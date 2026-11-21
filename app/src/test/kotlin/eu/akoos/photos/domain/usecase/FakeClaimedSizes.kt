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

import eu.akoos.photos.domain.repository.DriveCopyClaimedSize
import eu.akoos.photos.domain.repository.DriveCopyXAttr
import me.proton.core.domain.entity.UserId

/** A [DriveCopyClaimedSize] answering from [sizes] by link id (absent = unreadable), recording each ask. */
internal class FakeClaimedSizes(val sizes: MutableMap<String, Long> = mutableMapOf()) : DriveCopyClaimedSize {
    /** Each ask as link id to whether it allowed a fetch. */
    val asked = ArrayList<Pair<String, Boolean>>()

    /** The fresh XAttr each ask carried, by link id. */
    val freshGiven = HashMap<String, DriveCopyXAttr?>()

    override suspend fun claimedSize(
        userId: UserId,
        linkId: String,
        fetchIfUncached: Boolean,
        fresh: DriveCopyXAttr?,
    ): Long? {
        asked += linkId to fetchIfUncached
        freshGiven[linkId] = fresh
        return sizes[linkId]
    }
}
