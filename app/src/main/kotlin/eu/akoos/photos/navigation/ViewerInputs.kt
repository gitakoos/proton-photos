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

package eu.akoos.photos.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem

/**
 * What one viewer was opened with. Every entry point hands these over through nav-scope state that all
 * viewers share, and a viewer opened on top of another one (from a person or a place page) rewrites that
 * state, so each viewer back-stack entry reads its own copy, held by [ViewerInputsHolder].
 */
internal data class ViewerInputs(
    val items: List<GalleryItem>,
    val initialIndex: Int,
    val hiddenCloudLinkIds: Set<String>,
    /** True when the viewer was opened from an album's grid; [album] is then the album it came from. */
    val fromAlbum: Boolean,
    val album: Album?,
    val trashMode: Boolean,
    val secure: Boolean,
    val autoplay: Boolean,
) {
    /**
     * The inputs of a viewer that is closing: the flags its exit resets in nav scope drop here too, so a
     * secure viewer clears FLAG_SECURE in the same frame the vault screen underneath sets it again, not
     * after it once the exit animation ends. The photos stay, since the leaving viewer still draws one.
     */
    fun closed(): ViewerInputs = copy(secure = false, autoplay = false, trashMode = false)
}

/**
 * The [ViewerInputs] of one viewer back-stack entry, taken when the entry first composes. Scoped to the
 * entry, so it survives the viewer leaving the composition while a page it opened is on top.
 */
internal class ViewerInputsHolder(opened: ViewerInputs) : ViewModel() {
    /** State, so a reset recomposes the viewer in the same frame (see [ViewerInputs.closed]). */
    var inputs: ViewerInputs by mutableStateOf(opened)
        private set

    fun close() {
        inputs = inputs.closed()
    }

    /** The reset a trash action makes as it hands the photo back to the Trash screen and closes. */
    fun endTrashMode() {
        inputs = inputs.copy(trashMode = false)
    }
}
