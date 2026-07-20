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
    /** This user's own permission bitmask on the album, for shared-with-me albums only. Drive
     *  issues 4 (viewer) or 6 (viewer + editor); null means it was never learned, which happens for
     *  an album the user owns and for rows cached before the field existed. */
    val permissions: Long? = null,
) {
    val isShared: Boolean get() = sharingShareId != null
    val isSharedWithMe: Boolean get() = sharedByEmail != null

    /**
     * Whether this user may add photos to the album. Owning it is enough; otherwise the write bit
     * has to be present in the permission Drive granted.
     *
     * Null permissions on a shared album read as NO, deliberately. An unknown grant is not an
     * editor grant, and offering an add that the server then refuses is worse than not offering it.
     */
    val canAddPhotos: Boolean
        get() = !isSharedWithMe || ((permissions ?: 0L) and PERMISSION_WRITE) != 0L

    companion object {
        /** The write bit inside Drive's share permission bitmask: 4 = viewer, 6 = viewer + editor. */
        const val PERMISSION_WRITE = 2L
    }
}
