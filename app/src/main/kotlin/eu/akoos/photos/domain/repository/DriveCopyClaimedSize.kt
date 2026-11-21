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

package eu.akoos.photos.domain.repository

import me.proton.core.domain.entity.UserId

/**
 * The plaintext size a Drive photo's upload declared for its file (its XAttr's Common.Size, the claimed size).
 * An upload reads exactly the length it declares, so a declared size below the length of the bytes its
 * content hash names marks a copy that lacks the end of its file.
 */
interface DriveCopyClaimedSize {
    /**
     * The declared size of [linkId], or null when it cannot be read: no XAttr in [fresh] or the cache and
     * [fetchIfUncached] false, a fetch or decrypt that failed, or an XAttr without a size. [fresh] is read
     * first when given, so a caller holding the link's details needs no lookup of its own.
     */
    suspend fun claimedSize(
        userId: UserId,
        linkId: String,
        fetchIfUncached: Boolean,
        fresh: DriveCopyXAttr? = null,
    ): Long?
}

/** A Drive photo's encrypted XAttr with the keys that open it, as its link details carry them. */
data class DriveCopyXAttr(
    val armoredXAttr: String,
    val nodeKey: String,
    val nodePassphrase: String,
    val parentLinkId: String,
    val volumeId: String,
)
