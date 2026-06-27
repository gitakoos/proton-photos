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

package eu.akoos.photos.presentation.location

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.gallery.PhotoCell
import eu.akoos.photos.presentation.gallery.photoCellInputsFor
import eu.akoos.photos.presentation.viewer.PhotoShareSheet
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Drawer content for the location detail, raised as a bottom sheet over the photo map when a pin is
 * tapped. Mirrors the device-folder / album detail layout: a hero header with the resolved
 * "City, Country" title + a photo count, a grid keyed by local uri (cloud-only by linkId) with
 * long-press drag multi-select, a selection bar carrying share / add-to-album / download, and a tap
 * opening the viewer on the same merged-library items the gallery uses. "Save as album" creates a
 * real Drive album named after the place and adds every photo here to it.
 *
 * Sits inside the host's `ModalBottomSheet` column rather than a full-screen Box: the content takes
 * a tall fixed height so the map peeks above, and the selection bar + action dock are re-parented
 * within the sheet bounds. A photo tap (non-selection mode) calls [onPhotoClick]; the host closes
 * the sheet first so the viewer rises over the map.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocationDetailSheet(
    latitude: Double,
    longitude: Double,
    onPhotoClick: (items: List<GalleryItem>, index: Int) -> Unit,
    viewModel: LocationDetailViewModel = hiltViewModel(),
) {
    // Clear any prior selection on load so a fresh pin opens unselected; keyed on the coords so
    // re-entering a different pin reloads cleanly.
    LaunchedEffect(latitude, longitude) {
        viewModel.clearSelection()
        viewModel.load(latitude, longitude)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val showScrollTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 4 } }

    // In selection mode the back button cancels the selection instead of dismissing the sheet —
    // mirrors the gallery, album-detail and device-folder behaviour.
    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }

    var showSaveAsAlbumConfirm by remember { mutableStateOf(false) }
    var showAddToAlbumSheet by remember { mutableStateOf(false) }
    val addToAlbumSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPhotoShareSheet by remember { mutableStateOf(false) }
    val photoShareSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The VM builds the system-share intent off the UI thread; the sheet launches the chooser.
    val shareCtx = LocalContext.current
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(Unit) {
        viewModel.shareIntent.collect { intent ->
            runCatching { shareCtx.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle)) }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val savedAsAlbumFmt = stringResource(R.string.location_saved_as_album_fmt)
    LaunchedEffect(state.saveAsAlbumResult) {
        val r = state.saveAsAlbumResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(savedAsAlbumFmt.format(r.albumName))
        viewModel.clearSaveAsAlbumResult()
    }

    val videoCount = remember(state.items) {
        state.items.count { item ->
            val mime = when (item) {
                is GalleryItem.LocalOnly -> item.local.mimeType
                is GalleryItem.Synced -> item.local.mimeType
                is GalleryItem.CloudOnly -> item.cloud.mimeType
            }
            mime.startsWith("video/")
        }
    }
    val photoCount = state.items.size - videoCount
    val photosText = pluralStringResource(R.plurals.count_photos_plural, photoCount, photoCount)
    val videosText = pluralStringResource(R.plurals.count_videos_plural, videoCount, videoCount)
    val countLabel = when {
        state.isLoading -> ""
        photoCount > 0 && videoCount > 0 -> "$photosText, $videosText"
        videoCount > 0 -> videosText
        else -> photosText
    }

    // Cover = the newest item's image (the list is sorted newest-first by capture time).
    val coverModel: Any? = remember(state.items) {
        state.items.firstOrNull()?.let { item ->
            when (item) {
                is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                is GalleryItem.Synced -> Uri.parse(item.local.uri)
                is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl?.let { Uri.parse(it) }
            }
        }
    }

    // Tall-but-not-full drawer surface: the map peeks above the sheet. The grid fills + scrolls
    // inside; the selection bar and action dock sit within these bounds.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .background(Bg0),
    ) {
        val cols = eu.akoos.photos.presentation.gallery.rememberDefaultGridColumns()
        // Drag-to-select: long-press a photo then drag to sweep a range (shares the timeline gesture).
        // Cells are keyed by local uri (cloud-only by linkId); the swept keys map to selected keys.
        val selectableKeys = remember(state.items) {
            state.items.map { item ->
                when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced -> item.local.uri
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                }
            }
        }
        val keyToIndex = remember(selectableKeys) { selectableKeys.mapIndexed { i, k -> k to i }.toMap() }
        // Armed at the long-press anchor so the cell's release-tap skips toggling the just-selected
        // cell back off (otherwise a stationary long-press would select then immediately deselect).
        val tapGuard = remember { mutableStateOf(false) }
        val dragSelectModifier = eu.akoos.photos.presentation.gallery.rememberDragMultiSelectModifier(
            gridState = gridState,
            items = selectableKeys,
            indexByKey = keyToIndex,
            selected = state.selectedKeys,
            onSelectionChange = viewModel::setSelectedKeys,
            tapGuard = tapGuard,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            // Match the main timeline grid (GalleryGrid): same default columns, 20.dp side inset and
            // 6.dp gap, so located photos render at the same size as the Photos page.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().then(dragSelectModifier),
        ) {
            // Hero header — cover, place name and count, plus the "Save as album" action.
            item(span = { GridItemSpan(maxLineSpan) }) {
                eu.akoos.photos.presentation.albums.components.AlbumHeroHeader(
                    coverModel = coverModel,
                    title = state.placeName,
                    photoCountText = countLabel,
                    canRename = false,
                    titleActions = if (state.items.isNotEmpty()) {
                        {
                            // Create a Drive album from this place — a progress ring while the
                            // round-trip runs, otherwise the library-add glyph.
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(PillBg, CircleShape)
                                    .border(0.5.dp, PillBorder, CircleShape)
                                    .clickable(enabled = !state.isSavingAsAlbum) { showSaveAsAlbumConfirm = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.isSavingAsAlbum) {
                                    CircularProgressIndicator(
                                        color = Accent, strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.LibraryAdd,
                                        contentDescription = stringResource(R.string.location_save_as_album),
                                        tint = Accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    } else null,
                )
            }

            if (state.isLoading) {
                items(9, span = { GridItemSpan(1) }) {
                    eu.akoos.photos.presentation.common.ShimmerSquare(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 4.dp,
                    )
                }
            } else if (state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.albums_no_photos), color = FgMute, fontSize = 14.sp)
                    }
                }
            } else {
                itemsIndexed(
                    items = state.items,
                    key = { _, item ->
                        when (item) {
                            is GalleryItem.LocalOnly -> item.local.uri
                            is GalleryItem.Synced -> item.local.uri
                            is GalleryItem.CloudOnly -> item.cloud.linkId
                        }
                    },
                ) { index, item ->
                    val itemKey = when (item) {
                        is GalleryItem.LocalOnly -> item.local.uri
                        is GalleryItem.Synced -> item.local.uri
                        is GalleryItem.CloudOnly -> item.cloud.linkId
                    }
                    val isSelected = itemKey in state.selectedKeys
                    val inputs = remember(item) { photoCellInputsFor(item) }
                    PhotoCell(
                        imageData = inputs.imageData,
                        stableKey = inputs.stableKey,
                        isVideo = inputs.isVideo,
                        isPlaceholder = inputs.isPlaceholder,
                        selected = isSelected,
                        isSelectionMode = state.isSelectionMode,
                        showCloudBadge = inputs.showCloudBadge,
                        showSyncedBadge = inputs.showSyncedBadge,
                        isFavorite = inputs.isFavorite,
                        typeBadgeRes = inputs.typeBadgeRes,
                        typeBadgeCdRes = inputs.typeBadgeCdRes,
                        onClick = {
                            // Skip the release-tap that follows a long-press select; it would
                            // otherwise toggle the just-anchored cell back off.
                            if (tapGuard.value) tapGuard.value = false
                            else if (state.isSelectionMode) viewModel.toggleSelection(itemKey)
                            else onPhotoClick(state.items, index)
                        },
                        // Long-press + drag is handled by the grid-level drag-select; a plain
                        // long-press there selects this single cell and enters selection mode.
                        onLongClick = null,
                    )
                }
            }
        }

        // The sheet's default drag handle sat on a separate surface band that clashed with the cover
        // (worst in dark mode). Instead the cover runs to the very top; a slim handle floats over a
        // faint top scrim so it reads as pull-down-to-dismiss without a colour break.
        if (!state.isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.8f)),
            )
        }

        // Selection-mode action bar — a single floating pill within the sheet, matching the gallery,
        // album-detail and device-folder selection toolbars: cancel, a photo/video count, then share.
        if (state.isSelectionMode) {
            val selectedItems = state.items.filter { item ->
                val k = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced -> item.local.uri
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                }
                k in state.selectedKeys
            }
            val selectedVideos = selectedItems.count { item ->
                val mime = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mime.startsWith("video/")
            }
            val selectedPhotos = selectedItems.size - selectedVideos
            val selPhotosText = pluralStringResource(R.plurals.count_photos_plural, selectedPhotos, selectedPhotos)
            val selVideosText = pluralStringResource(R.plurals.count_videos_plural, selectedVideos, selectedVideos)
            val selectionLabel = when {
                selectedPhotos > 0 && selectedVideos > 0 -> "$selPhotosText, $selVideosText"
                selectedVideos > 0 -> selVideosText
                else -> selPhotosText
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Bg0.copy(alpha = 0.95f))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.gallery_cancel_selection),
                    onClick = { viewModel.clearSelection() },
                    diameter = 40.dp,
                    iconSize = 18.dp,
                    background = PillBg,
                    borderColor = PillBorder,
                    tint = FgPrimary,
                )
                Text(
                    selectionLabel,
                    color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                // Share selected to other apps. A cloud-only one downloads first, one already on
                // the device shares directly. The ring tracks how many have been resolved.
                val isSharing = state.shareState is LocationOpState.Working
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable(enabled = !isSharing) { showPhotoShareSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSharing) {
                        val p = state.shareState as LocationOpState.Working
                        CircularProgressIndicator(
                            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_action),
                            tint = Accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Bottom action dock — secondary actions (add to album, download), the same floating
            // PillBgOpaque pill as the gallery and album selection docks, pinned at the sheet bottom.
            val isDownloading = state.downloadState is LocationOpState.Working
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .background(PillBgOpaque, RoundedCornerShape(999.dp))
                    .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Add selected to a cloud album — opens the gallery's picker sheet.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showAddToAlbumSheet = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhotoAlbum,
                        contentDescription = stringResource(R.string.gallery_add_to_album),
                        tint = Accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // Download selected to the device, mirroring the gallery's multi-download.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !isDownloading) { viewModel.downloadSelected() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDownloading) {
                        val dl = state.downloadState as LocationOpState.Working
                        CircularProgressIndicator(
                            progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f },
                            color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = stringResource(R.string.gallery_download_selected),
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // Jump-to-top pill — appears once scrolled down, hidden during selection so it never
        // collides with the bottom action dock. Mirrors the album / device-folder detail pages.
        AnimatedVisibility(
            visible = showScrollTop && !state.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            IconBubble(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.cd_scroll_to_top),
                onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                diameter = 44.dp,
                iconSize = 24.dp,
                background = PillBgOpaque,
                borderColor = PillBorder,
                tint = FgPrimary,
            )
        }

        // Unified progress pill — one surface for the multi-download and multi-share, matching the
        // album and device-folder bulk actions. Offset below the selection bar while selecting.
        val downloadingTpl = stringResource(R.string.op_downloading_fmt)
        val sharingTpl = stringResource(R.string.op_sharing_fmt)
        val dlState = state.downloadState
        val shState = state.shareState
        val opProgress = when {
            dlState is LocationOpState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    dlState.done, dlState.total, downloadingTpl.format(dlState.done, dlState.total),
                )
            shState is LocationOpState.Working ->
                eu.akoos.photos.presentation.common.OperationProgress(
                    shState.done, shState.total, sharingTpl.format(shState.done, shState.total),
                )
            else -> null
        }
        eu.akoos.photos.presentation.common.OperationProgressPill(
            progress = opProgress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (state.isSelectionMode) 64.dp else 8.dp),
        )

        eu.akoos.photos.presentation.common.ThemedSnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Unified share drawer for the selection — Send to app only, since a located set has no album
    // context and the public-link / share-with-people rows don't apply here.
    if (showPhotoShareSheet && state.selectedCount > 0) {
        PhotoShareSheet(
            sheetState = photoShareSheetState,
            canCreateLink = false,
            showPublicLink = false,
            showShareWithPeople = false,
            onDismiss = { showPhotoShareSheet = false },
            onSendToApp = { showPhotoShareSheet = false; viewModel.shareSelected() },
            onShareWithPeople = { showPhotoShareSheet = false },
            onManagePublicLink = { showPhotoShareSheet = false },
        )
    }

    // Add-to-album sheet — reuses the gallery's picker. Cloud-backed selections join the album now;
    // local-only selections back up first and join afterwards. Inline create is off here.
    if (showAddToAlbumSheet && state.selectedCount > 0) {
        val addCtx = LocalContext.current
        val addItems = state.items.filter { item ->
            val k = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced -> item.local.uri
                is GalleryItem.CloudOnly -> item.cloud.linkId
            }
            k in state.selectedKeys
        }.toSet()
        eu.akoos.photos.presentation.gallery.GalleryAddToAlbumDialog(
            selectedItems = addItems,
            cloudAlbums = albums,
            sheetState = addToAlbumSheetState,
            onCreateNew = { showAddToAlbumSheet = false },
            onCloudAlbumSelected = { album ->
                showAddToAlbumSheet = false
                viewModel.addSelectedToAlbum(album.linkId) { _, _ ->
                    scope.launch {
                        snackbarHostState.showSnackbar(addCtx.getString(R.string.device_folder_added_to_album))
                    }
                }
            },
            onDismiss = { showAddToAlbumSheet = false },
        )
    }

    // Confirm before creating a Drive album from every photo in this place.
    if (showSaveAsAlbumConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.location_save_as_album_confirm_title),
            message = stringResource(R.string.location_save_as_album_confirm_body_fmt, state.placeName),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                showSaveAsAlbumConfirm = false
                viewModel.saveAsAlbum()
            },
            onDismiss = { showSaveAsAlbumConfirm = false },
        )
    }
}
