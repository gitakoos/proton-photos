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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PrivacyTip
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
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onRequestDelete: () -> Unit,
    onHeaderHeightChanged: (Int) -> Unit,
) {
    val appColors = AppColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeaderHeightChanged(it.size.height) }
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(appColors.bg0.copy(alpha = 0.95f))
            .border(0.5.dp, PillBorder, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconBubble(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.gallery_cancel_selection),
            onClick = onCancel,
            diameter = 40.dp,
            iconSize = 20.dp,
            background = PillBg,
            borderColor = PillBorder,
            tint = appColors.fgPrimary,
        )
        val selectedPhotosCount = selectedItems.count { item ->
            val mt: String = when (item) {
                is GalleryItem.LocalOnly -> item.local.mimeType
                is GalleryItem.Synced    -> item.local.mimeType
                is GalleryItem.CloudOnly -> item.cloud.mimeType
            }
            !mt.startsWith("video/")
        }
        val selectedVideosCount = selectedCount - selectedPhotosCount
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
        Text(
            selectionLabel,
            color = appColors.fgPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cloud-only items decrypt to a temp file first; the ring tracks that resolution.
            val isSharing = multiShareState is MultiShareState.Working
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PillBg, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape)
                    .clickable(enabled = !isSharing) { onShare() },
                contentAlignment = Alignment.Center,
            ) {
                if (isSharing) {
                    val progress = multiShareState as MultiShareState.Working
                    CircularProgressIndicator(
                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.Share,
                        stringResource(R.string.share_action),
                        tint = appColors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            val isWorking = multiDeleteState is MultiDeleteState.Working
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PillBg, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape)
                    .clickable(enabled = !isWorking) { onRequestDelete() },
                contentAlignment = Alignment.Center,
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        color = ErrorColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.DeleteOutline,
                        stringResource(R.string.gallery_delete_selected),
                        tint = appColors.errorColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Bottom action dock for multi-select, alongside [GallerySelectionHeader]: Add to album, Back up
 * (device-only photos present), Download (cloud-only present), and a More menu (Strip / Hide) for
 * an all-device-only selection.
 */
@Composable
fun GallerySelectionBottomBar(
    selectedItems: Set<GalleryItem>,
    multiDownloadState: MultiDownloadState,
    multiHideState: MultiDeleteState,
    multiStripState: MultiStripState,
    addToAlbumState: AddToAlbumState,
    onDownload: () -> Unit,
    onRequestAddToAlbum: () -> Unit,
    onBackUp: () -> Unit,
    onStripMetadata: () -> Unit,
    onHideSelected: () -> Unit,
) {
    val appColors = AppColors.current
    val isAddingToAlbum = addToAlbumState is AddToAlbumState.Working
    val isDownloading = multiDownloadState is MultiDownloadState.Working
    val isStripping = multiStripState is MultiStripState.Working
    val isHiding = multiHideState is MultiDeleteState.Working
    val hasLocalOnly = selectedItems.any { it is GalleryItem.LocalOnly }
    val hasDownloadable = selectedItems.any { it is GalleryItem.CloudOnly }
    val allDeviceOnly = selectedItems.isNotEmpty() && selectedItems.all { it is GalleryItem.LocalOnly }

    Row(
        modifier = Modifier
            .background(PillBgOpaque, RoundedCornerShape(999.dp))
            .border(0.5.dp, PillBorder, RoundedCornerShape(999.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionAction(
            icon = Icons.Default.PhotoAlbum,
            label = stringResource(R.string.gallery_add_to_album),
            tint = appColors.accent,
            loading = isAddingToAlbum,
            enabled = !isAddingToAlbum,
            onClick = onRequestAddToAlbum,
        )
        if (hasLocalOnly) {
            SelectionAction(
                icon = Icons.Default.CloudUpload,
                label = stringResource(R.string.device_folder_upload_action),
                tint = appColors.accent,
                onClick = onBackUp,
            )
        }
        if (hasDownloadable) {
            SelectionAction(
                icon = Icons.Default.FileDownload,
                label = stringResource(R.string.gallery_download_selected),
                tint = appColors.accent,
                loading = isDownloading,
                enabled = !isDownloading,
                onClick = onDownload,
            )
        }
        if (allDeviceOnly) {
            Box {
                var moreExpanded by remember { mutableStateOf(false) }
                SelectionAction(
                    icon = Icons.Default.MoreVert,
                    label = stringResource(R.string.more_label),
                    tint = appColors.fgPrimary,
                    loading = isStripping || isHiding,
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
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.gallery_hide_selected),
                                color = appColors.fgPrimary,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.VisibilityOff, null,
                                tint = appColors.fgPrimary, modifier = Modifier.size(20.dp),
                            )
                        },
                        enabled = !isHiding,
                        onClick = {
                            moreExpanded = false
                            onHideSelected()
                        },
                    )
                }
            }
        }
    }
}

/** Icon-only action in the [GallerySelectionBottomBar]. No caption — labels run long in some
 *  locales and would push the pill off-screen; [label] is kept as the content description. */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}
