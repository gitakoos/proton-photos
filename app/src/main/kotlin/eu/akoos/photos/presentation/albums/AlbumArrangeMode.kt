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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.akoos.photos.presentation.common.ArrangeSession
import eu.akoos.photos.presentation.common.PhotoArrangeScreen
import eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns
import eu.akoos.photos.presentation.gallery.rememberSeamlessGrid

/** An album's arrange mode, drawn over the album with the same photo cells its grid uses. */
@Composable
internal fun AlbumArrangeMode(
    state: AlbumDetailUiState,
    session: ArrangeSession,
    viewModel: AlbumDetailViewModel,
) {
    val columns = rememberDefaultGridColumns()
    val seamless = rememberSeamlessGrid()
    val byId = remember(state.photos) { state.photos.associateBy { it.linkId } }
    val selecting = session.selected.isNotEmpty()
    PhotoArrangeScreen(
        session = session,
        columns = columns,
        seamless = seamless,
        cell = { key, selected, onTap ->
            val photo = byId[key]
            if (photo == null) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f))
            } else {
                PhotoCell(
                    photo = photo,
                    localUri = state.localUriByLinkId[key],
                    isSelected = selected,
                    isSelectionMode = selecting,
                    columns = columns,
                    seamless = seamless,
                    onTap = onTap,
                    onRequestThumbnail = viewModel::requestThumbnailDecrypt,
                    onCancelThumbnail = viewModel::cancelThumbnailDecrypt,
                )
            }
        },
        onToggle = viewModel::arrangeToggle,
        onReorder = viewModel::arrangeReorder,
        onMoveToStart = viewModel::arrangeMoveToStart,
        onMoveToEnd = viewModel::arrangeMoveToEnd,
        onMoveNextTo = viewModel::arrangeMoveNextTo,
        onCancel = viewModel::cancelArrange,
        onDone = viewModel::finishArrange,
    )
}
