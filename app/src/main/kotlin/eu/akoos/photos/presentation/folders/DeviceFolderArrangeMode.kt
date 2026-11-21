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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ArrangeSession
import eu.akoos.photos.presentation.common.PhotoArrangeScreen
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns
import eu.akoos.photos.presentation.gallery.rememberSeamlessGrid

/** A device folder's arrange mode, drawn over the folder with the same photo cells its grid uses. */
@Composable
internal fun DeviceFolderArrangeMode(
    items: List<GalleryItem>,
    session: ArrangeSession,
    viewModel: DeviceFolderDetailViewModel,
) {
    val columns = rememberDefaultGridColumns()
    val seamless = rememberSeamlessGrid()
    val byKey = remember(items) { items.associateBy(::arrangeKeyOf) }
    val selecting = session.selected.isNotEmpty()
    PhotoArrangeScreen(
        session = session,
        columns = columns,
        seamless = seamless,
        cell = { key, selected, onTap ->
            val item = byKey[key]
            if (item == null) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f))
            } else {
                val inputs = remember(item) { photoCellInputsFor(item) }
                PhotoCell(
                    imageData = inputs.imageData,
                    stableKey = inputs.stableKey,
                    isVideo = inputs.isVideo,
                    isLocalVideo = inputs.isLocalVideo,
                    durationMs = inputs.durationMs,
                    isPlaceholder = inputs.isPlaceholder,
                    selected = selected,
                    isSelectionMode = selecting,
                    showCloudBadge = inputs.showCloudBadge,
                    showSyncedBadge = inputs.showSyncedBadge,
                    isFavorite = inputs.isFavorite,
                    isOffline = inputs.isOffline,
                    typeBadgeRes = inputs.typeBadgeRes,
                    typeBadgeCdRes = inputs.typeBadgeCdRes,
                    isLocalGif = inputs.isLocalGif,
                    columns = columns,
                    cornerRadius = if (seamless) 0.dp else 10.dp,
                    onClick = onTap,
                    onLongClick = null,
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
