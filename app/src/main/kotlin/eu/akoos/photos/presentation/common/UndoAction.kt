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

package eu.akoos.photos.presentation.common

/**
 * A just-completed action that can be reversed via the snackbar's Undo. [count] drives the
 * message; the payload is whatever the matching restore path needs. Lives in `common` so any
 * screen (not only the gallery) can surface an undoable action through one shared path.
 */
sealed class UndoAction {
    abstract val count: Int

    /** Undo a Hide: restore each private-vault URI back to MediaStore. */
    data class Hide(val hiddenUris: List<String>) : UndoAction() {
        override val count: Int get() = hiddenUris.size
    }

    /** Undo a cloud-trash delete: move each Drive linkId back out of Proton trash. */
    data class CloudTrash(val linkIds: List<String>) : UndoAction() {
        override val count: Int get() = linkIds.size
    }
}
