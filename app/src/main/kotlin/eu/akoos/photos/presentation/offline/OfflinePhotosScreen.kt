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

package eu.akoos.photos.presentation.offline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ReturnToViewerPhoto
import eu.akoos.photos.presentation.common.SelectionAction
import eu.akoos.photos.presentation.common.SelectionDrawer
import eu.akoos.photos.presentation.common.floatingHeaderContentTopPadding
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns
import eu.akoos.photos.presentation.gallery.rememberSeamlessGrid
import eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier
import eu.akoos.photos.presentation.common.FloatingHeader
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute

/**
 * Dedicated grid of every cloud photo the user has made available offline. Reuses the timeline's
 * [PhotoCell], the shared drag-to-select gesture, and the same selection drawer as the gallery, so
 * selection behaves identically. The only bulk action here is removing the chosen photos from
 * offline. The floating pill header sits over the scrolling grid.
 */
@Composable
fun OfflinePhotosScreen(
    onBack: () -> Unit,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit = { _, _ -> },
    viewModel: OfflinePhotosViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val inSelectionMode by viewModel.inSelectionMode.collectAsStateWithLifecycle()
    // Computed once: the List feeds the drag-select keys, the Set feeds the per-cell offline badge.
    val selectableKeys = remember(items) {
        items.mapNotNull { (it as? GalleryItem.CloudOnly)?.cloud?.linkId }
    }
    val pinnedIds = remember(selectableKeys) { selectableKeys.toSet() }

    BackHandler(enabled = inSelectionMode) { viewModel.clearSelection() }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPad = floatingHeaderContentTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0),
    ) {
        val cols = rememberDefaultGridColumns()
        val seamless = rememberSeamlessGrid()
        val gridState = rememberLazyGridState()
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(top = contentTopPad),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.OfflinePin, null, tint = FgMute, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.offline_screen_empty),
                        color = FgDim, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
        } else {
            // Shared drag-to-select: long-press a cell to select it, then sweep a contiguous range.
            // Keyed by linkId — the same key the grid cells use — so the swept keys map to selection.
            val keyToIndex = remember(selectableKeys) {
                selectableKeys.mapIndexed { i, k -> k to i }.toMap()
            }
            val tapGuard = remember { mutableStateOf(false) }
            val dragMod = rememberDragMultiSelectModifier(
                gridState = gridState,
                items = selectableKeys,
                indexByKey = keyToIndex,
                selected = selectedIds,
                onSelectionChange = viewModel::setSelected,
                tapGuard = tapGuard,
            )
            // Land back on the photo the viewer closed on. One flat run of cloud photos, no header
            // and nothing ahead of them.
            val returnGroups = remember(items) { listOf(items) }
            ReturnToViewerPhoto(
                gridState = gridState,
                groups = returnGroups,
                headerPerGroup = false,
                keyOf = { it.stableId },
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                modifier = Modifier.fillMaxSize().then(dragMod),
                contentPadding = PaddingValues(
                    start = if (seamless) 0.dp else 8.dp,
                    top = contentTopPad,
                    end = if (seamless) 0.dp else 8.dp,
                    bottom = 100.dp + navBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
                verticalArrangement = Arrangement.spacedBy(if (seamless) 2.dp else 4.dp),
            ) {
                itemsIndexed(
                    items,
                    key = { index, item -> (item as? GalleryItem.CloudOnly)?.cloud?.linkId ?: index },
                ) { index, item ->
                    val linkId = (item as? GalleryItem.CloudOnly)?.cloud?.linkId
                    val inputs = photoCellInputsFor(item, offlinePinIds = pinnedIds)
                    val pendingLinkId = (item as? GalleryItem.CloudOnly)
                        ?.cloud?.takeIf { it.thumbnailUrl == null }?.linkId
                    if (pendingLinkId != null) {
                        LaunchedEffect(pendingLinkId) {
                            delay(120)
                            viewModel.requestThumbnailDecrypt(pendingLinkId)
                        }
                        DisposableEffect(pendingLinkId) {
                            onDispose { viewModel.cancelThumbnailDecrypt(pendingLinkId) }
                        }
                    }
                    PhotoCell(
                        imageData = inputs.imageData,
                        stableKey = inputs.stableKey,
                        isVideo = inputs.isVideo,
                        isLocalVideo = inputs.isLocalVideo,
                        durationMs = inputs.durationMs,
                        isPlaceholder = inputs.isPlaceholder,
                        selected = linkId != null && linkId in selectedIds,
                        isSelectionMode = inSelectionMode,
                        showCloudBadge = inputs.showCloudBadge,
                        showSyncedBadge = inputs.showSyncedBadge,
                        isFavorite = inputs.isFavorite,
                        isOffline = inputs.isOffline,
                        typeBadgeRes = inputs.typeBadgeRes,
                        typeBadgeCdRes = inputs.typeBadgeCdRes,
                        columns = cols,
                        cornerRadius = if (seamless) 0.dp else 10.dp,
                        onClick = {
                            // Skip the release-tap that follows a long-press select.
                            if (tapGuard.value) tapGuard.value = false
                            else if (inSelectionMode) {
                                if (linkId != null) viewModel.toggleSelection(linkId)
                            } else {
                                onPhotoClick(items, index)
                            }
                        },
                    )
                }
            }
        }

        // Floating pill header over the scrolling grid, hidden while selecting so its back arrow
        // cannot pop the screen and drop the selection; the drawer then carries cancel and count.
        if (!inSelectionMode) {
            FloatingHeader(
                title = stringResource(R.string.offline_screen_title),
                onBack = onBack,
            )
        }

        // Selection drawer: the shared surface every multi-select uses. Offline's one bulk action is
        // removing the chosen photos from offline.
        val selectedItems = remember(items, selectedIds) {
            items.filter { (it as? GalleryItem.CloudOnly)?.cloud?.linkId in selectedIds }
        }
        val actions = buildList {
            add(
                SelectionAction(
                    icon = Icons.Default.OfflinePin,
                    label = stringResource(R.string.offline_remove),
                    onClick = { viewModel.removeSelectedFromOffline() },
                )
            )
        }
        SelectionDrawer(
            visible = inSelectionMode,
            items = selectedItems,
            actions = actions,
            onDismiss = { viewModel.clearSelection() },
            contentScrolling = gridState.isScrollInProgress,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
