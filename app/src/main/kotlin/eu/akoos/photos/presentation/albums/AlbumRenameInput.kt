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
 * The one rule both album rename surfaces gate their confirm action on.
 *
 * A rename is worth sending only when the field carries a name at all and that name differs from
 * the one the album already has. Both ViewModels reject exactly these two cases, so keeping the
 * rule here means the confirm action never offers a call that cannot land.
 */
object AlbumRenameInput {

    /** True when [input] names something, and something other than [currentName]. */
    fun isAcceptable(input: String, currentName: String): Boolean =
        input.isNotBlank() && input.trim() != currentName
}
