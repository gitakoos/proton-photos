/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

package eu.akoos.photos.domain.entity

data class Album(
    val linkId: String,
    val name: String,
    val photoCount: Int,
    val coverLinkId: String?,
    val lastActivityTimeMs: Long?,
    val coverThumbnailUrl: String? = null,
    /** Non-null when the album has been shared (contains the child share ID). */
    val sharingShareId: String? = null,
    /** Non-null when a public share URL exists for this album. */
    val sharingShareUrlId: String? = null,
    /** Non-null for shared-with-me albums — the email of the user who shared it. */
    val sharedByEmail: String? = null,
    /** The volume this album lives in; may differ from the current user's own volume for shared-with-me albums. */
    val volumeId: String? = null,
) {
    val isShared: Boolean get() = sharingShareId != null
    val isSharedWithMe: Boolean get() = sharedByEmail != null
}
