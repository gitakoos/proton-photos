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

package eu.akoos.photos.presentation.albums

/**
 * Which share id the album screen holds after a call that can create one.
 *
 * Managing a share — its members, its invitations, their permissions, revoking the link — is
 * addressed by share id, so every one of those actions is dead until the id is in state. Sharing
 * mints it, and these rules decide what to keep: a call that reports no id, or a blank one, names
 * no share and so can never erase an id that already works.
 */
object AlbumShareIds {

    /** The id to hold given the one already held and what a create call reported. */
    fun resolve(current: String?, created: String?): String? =
        created?.takeIf { it.isNotBlank() } ?: current

    /**
     * [resolve] across a batch, where each entry is one call's reported id and a failed call
     * contributes null. The last id that names a share wins: a batch can find the album's share
     * unusable and recreate it partway through, and the id from after that is the live one.
     */
    fun resolveBatch(current: String?, created: List<String?>): String? =
        resolve(current, created.lastOrNull { !it.isNullOrBlank() })
}
