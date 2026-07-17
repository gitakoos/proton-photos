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

package eu.akoos.photos.presentation.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.common.IconBubble
import eu.akoos.photos.presentation.common.allLocalOnly
import eu.akoos.photos.presentation.common.anyHideable
import eu.akoos.photos.presentation.common.anyLocalOnly
import eu.akoos.photos.presentation.common.SelectionTopBar
import eu.akoos.photos.presentation.common.SelectionTopButton
import androidx.compose.foundation.layout.Spacer
import eu.akoos.photos.presentation.common.hasDownloadable
import eu.akoos.photos.presentation.common.selectionMimeCounts
import eu.akoos.photos.presentation.common.SelectionDockItem
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Floating top toolbar for multi-select mode: cancel, photo/video count, Share, Delete. The
 * remaining bulk actions live in [GallerySelectionBottomBar].
 *
 * @param onHeaderHeightChanged forwarded from [onGloballyPositioned] so the screen can match the
 *        grid's top inset to the rendered toolbar height.
 */
@Composable
fun GallerySelectionHeader(
    selectedItems: Set<GalleryItem>,
    selectedCount: Int,
    multiShareState: MultiShareState,
    multiDeleteState: MultiDeleteState,
    allSelected: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    onRequestDelete: () -> Unit,
    onHeaderHeightChanged: (Int) -> Unit,
) {
    val appColors = AppColors.current

    val mimeCounts = selectionMimeCounts(selectedItems)
    val selectedPhotosCount = mimeCounts.photos
    val selectedVideosCount = mimeCounts.videos
    val selPhotosText = pluralStringResource(
        R.plurals.count_photos_plural, selectedPhotosCount, selectedPhotosCount,
    )
    val selVideosText = pluralStringResource(
        R.plurals.count_videos_plural, selectedVideosCount, selectedVideosCount,
    )
    val selectionLabel = when {
        selectedPhotosCount > 0 && selectedVideosCount > 0 -> "$selPhotosText, $selVideosText"
        selectedVideosCount > 0 -> selVideosText
        else -> selPhotosText
    }
    val sharing = multiShareState as? MultiShareState.Working
    SelectionTopBar(
        onCancel = onCancel,
        countText = selectionLabel,
        modifier = Modifier.onGloballyPositioned { onHeaderHeightChanged(it.size.height) },
    ) {
        SelectionTopButton(
            icon = Icons.Default.SelectAll,
            contentDescription = stringResource(
                if (allSelected) R.string.gallery_deselect_all else R.string.select_all,
            ),
            active = allSelected,
            onClick = onSelectAll,
        )
        Spacer(Modifier.size(4.dp))
        SelectionTopButton(
            icon = Icons.Default.Share,
            contentDescription = stringResource(R.string.share_action),
            working = sharing != null,
            progress = sharing?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
            enabled = sharing == null,
            onClick = onShare,
        )
        // Hide sits at the top level (matching Search and the folder view). Offered for any
        // non-empty selection: device-backed photos move into the vault, cloud-only photos hide
        // client-side by linkId.
        if (anyHideable(selectedItems)) {
            Spacer(Modifier.size(4.dp))
            SelectionTopButton(
                icon = Icons.Default.VisibilityOff,
                contentDescription = stringResource(R.string.gallery_hide_selected),
                enabled = multiDeleteState !is MultiDeleteState.Working,
                onClick = onHide,
            )
        }
        Spacer(Modifier.size(4.dp))
        SelectionTopButton(
            icon = Icons.Default.DeleteOutline,
            contentDescription = stringResource(R.string.gallery_delete_selected),
            tint = appColors.errorColor,
            working = multiDeleteState is MultiDeleteState.Working,
            enabled = multiDeleteState !is MultiDeleteState.Working,
            onClick = onRequestDelete,
        )
    }
}

/**
 * Bottom action dock for multi-select, alongside [GallerySelectionHeader]: Add to album, Back up
 * (device-only photos present), Download (cloud-only present), Make available offline (cloud-only
 * present), and a More menu (Strip / Hide) for an all-device-only selection.
 */
@Composable
fun GallerySelectionBottomBar(
    selectedItems: Set<GalleryItem>,
    offlinePinIds: Set<String>,
    multiDownloadState: MultiDownloadState,
    multiStripState: MultiStripState,
    addToAlbumState: AddToAlbumState,
    showLabels: Boolean,
    onDownload: () -> Unit,
    onMakeAvailableOffline: () -> Unit,
    onRequestAddToAlbum: () -> Unit,
    onBackUp: () -> Unit,
    onStripMetadata: () -> Unit,
) {
    val appColors = AppColors.current
    val isAddingToAlbum = addToAlbumState is AddToAlbumState.Working
    val isDownloading = multiDownloadState is MultiDownloadState.Working
    val isStripping = multiStripState is MultiStripState.Working
    val hasLocalOnly = anyLocalOnly(selectedItems)
    val hasDownloadable = hasDownloadable(selectedItems)
    val allDeviceOnly = allLocalOnly(selectedItems)
    // Offline pin only fetches bytes that aren't already on the device — gate to cloud-only items.
    val hasCloudOnly = selectedItems.any { it is GalleryItem.CloudOnly }

    Row(
        modifier = Modifier
            .background(PillBgOpaque, RoundedCornerShape(999.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionDockItem(
            icon = Icons.Default.PhotoAlbum,
            label = stringResource(R.string.sel_label_album),
            showLabel = showLabels,
            working = isAddingToAlbum,
            enabled = !isAddingToAlbum,
            onClick = onRequestAddToAlbum,
        )
        if (hasLocalOnly) {
            SelectionDockItem(
                icon = Icons.Default.CloudUpload,
                label = stringResource(R.string.sel_label_upload),
                showLabel = showLabels,
                onClick = onBackUp,
            )
        }
        if (hasDownloadable) {
            SelectionDockItem(
                icon = Icons.Default.FileDownload,
                label = stringResource(R.string.sel_label_download),
                showLabel = showLabels,
                working = isDownloading,
                enabled = !isDownloading,
                onClick = onDownload,
            )
        }
        if (hasCloudOnly) {
            SelectionDockItem(
                icon = Icons.Default.OfflinePin,
                label = stringResource(R.string.sel_label_offline),
                showLabel = showLabels,
                onClick = onMakeAvailableOffline,
            )
        }
        if (allDeviceOnly) {
            Box {
                var moreExpanded by remember { mutableStateOf(false) }
                SelectionDockItem(
                    icon = Icons.Default.MoreVert,
                    label = stringResource(R.string.more_label),
                    showLabel = showLabels,
                    working = isStripping,
                    onClick = { moreExpanded = true },
                )
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = appColors.cardBg,
                    border = BorderStroke(0.5.dp, appColors.pillBorder),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.gallery_strip_metadata),
                                color = appColors.fgPrimary,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PrivacyTip, null,
                                tint = appColors.fgPrimary, modifier = Modifier.size(20.dp),
                            )
                        },
                        enabled = !isStripping,
                        onClick = {
                            moreExpanded = false
                            onStripMetadata()
                        },
                    )
                }
            }
        }
    }
}

// Bottom-dock items are the shared SelectionDockItem from SelectionBars.kt (icon + optional caption).
