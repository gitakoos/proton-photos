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

package eu.akoos.photos.presentation.folders

/**
 * Which photo a device folder may pin as its cover.
 *
 * A cover is the folder's public face: it is drawn on the Albums grid and at the top of the folder
 * itself, in front of anyone holding the phone. A hidden photo was put away precisely so it is not
 * drawn anywhere, so it can never be the one on the card. The cover is also stored by uri and
 * outlives the hide, so pinning one would keep showing it after the folder is revealed again.
 *
 * The rule lives here rather than in the row that offers the action, because the row is not the only
 * way in: the same answer gates the dock item, the confirmation in front of it and the write itself.
 */
object FolderCoverSelection {

    /**
     * The photo [selectedUris] names a cover for, or null when it names none.
     *
     * One photo is one cover, so anything but a single selection answers null, and a photo held in
     * [vaultedUris] answers null however it was selected.
     */
    fun pinnable(selectedUris: Set<String>, vaultedUris: Set<String>): String? =
        selectedUris.singleOrNull()
            ?.takeIf { it.isNotEmpty() && it !in vaultedUris }
}
