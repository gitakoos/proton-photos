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

    /**
     * Undo a delete: move each Drive linkId back out of Proton trash AND move each local file back
     * out of the device trash. Either list may be empty (a cloud-only or a device-only delete), so
     * one undo path restores whichever copies the delete actually removed.
     */
    data class Delete(
        val cloudLinkIds: List<String> = emptyList(),
        val localTrashedUris: List<String> = emptyList(),
        /**
         * For a synced photo (both copies deleted): the local-to-cloud pairing plus size, so the
         * undo can re-mark its SyncState SYNCED right away. Without this the local file reappears as
         * "not backed up" while the server is still moving the cloud copy out of trash, and the
         * backup would upload a fresh cloud duplicate.
         */
        val syncedRelinks: List<Relink> = emptyList(),
    ) : UndoAction() {
        data class Relink(val localUri: String, val cloudLinkId: String, val sizeBytes: Long)
        override val count: Int get() = maxOf(cloudLinkIds.size, localTrashedUris.size)
    }

    /** Undo an album removal: re-add each photo linkId to the album it was taken out of. */
    data class AlbumRemove(val albumLinkId: String, val photoLinkIds: List<String>) : UndoAction() {
        override val count: Int get() = photoLinkIds.size
    }
}
