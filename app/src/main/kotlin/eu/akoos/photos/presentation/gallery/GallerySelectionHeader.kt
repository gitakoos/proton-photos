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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import eu.akoos.photos.presentation.theme.PillBorder

/**
 * Floating top toolbar shown while the gallery is in multi-select mode.
 *
 * Renders the cancel pill, a photo/video count label, and the action pills
 * (Add-to-album / Download / Delete / More). The More dropdown bundles
 * Strip metadata and Hide so the bar stays compact for long counters.
 *
 * The owning screen wraps the call in [androidx.compose.animation.AnimatedVisibility]
 * to handle show/hide transitions; this composable only owns the layout.
 *
 * @param onHeaderHeightChanged forwarded from a [onGloballyPositioned] modifier
 *        so the screen can match the grid's top inset to the rendered toolbar
 *        height (the floating header has variable height across locales).
 */
@Composable
fun GallerySelectionHeader(
    selectedItems: Set<GalleryItem>,
    selectedCount: Int,
    multiDownloadState: MultiDownloadState,
    multiShareState: MultiShareState,
    multiDeleteState: MultiDeleteState,
    multiHideState: MultiDeleteState,
    multiStripState: MultiStripState,
    addToAlbumState: AddToAlbumState,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestAddToAlbum: () -> Unit,
    onBackUp: () -> Unit,
    onStripMetadata: () -> Unit,
    onHideSelected: () -> Unit,
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
            // One containing pill behind all the controls — keeps them readable
            // over busy photo content without washing the whole top edge.
            .clip(RoundedCornerShape(28.dp))
            .background(appColors.bg0.copy(alpha = 0.95f))
            .border(0.5.dp, PillBorder, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Cancel
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
        // Split the selection counter by media type — gallery items can be local-only,
        // synced or cloud-only, but in every case we only care about photo-vs-video.
        // Reusing the same helper-style logic as AlbumDetailScreen for visual parity.
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
            // Let the counter shrink (and ellipsize) on narrow screens instead of
            // squashing the fixed-size action pills to its right.
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Add to album — a direct button. There is deliberately no whole-library
            // select-all: selecting thousands of items at once makes every downstream
            // bulk action pathological, and per-day group selection on the date headers
            // covers the realistic bulk case.
            val isAddingToAlbum = addToAlbumState is AddToAlbumState.Working
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PillBg, CircleShape)
                    .border(0.5.dp, PillBorder, CircleShape)
                    .clickable(enabled = !isAddingToAlbum) { onRequestAddToAlbum() },
                contentAlignment = Alignment.Center,
            ) {
                if (isAddingToAlbum) {
                    CircularProgressIndicator(
                        color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoAlbum,
                        stringResource(R.string.gallery_add_to_album),
                        tint = appColors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Back up to Drive — force-uploads the not-yet-backed-up (local-only) photos in the
            // selection. Shown only when at least one selected photo isn't on Drive yet.
            if (selectedItems.any { it is GalleryItem.LocalOnly }) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { onBackUp() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        stringResource(R.string.device_folder_upload_action),
                        tint = appColors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // Share — always available: local items share their MediaStore URI directly,
            // cloud-only items decrypt to a temp file first. A determinate ring tracks how
            // many items in the batch have been resolved (cloud decrypts dominate the wait).
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
            // Three primary actions stay outside the dropdown — they're the ones
            // users reach for most often during a bulk select. Less-frequent
            // operations (Strip metadata, Hide) collapse into the More menu so the
            // bar fits even when the selection-count text is long.
            //
            // Download saves cloud originals to the device. It only makes sense when the
            // selection holds at least one cloud-only photo — both LocalOnly and Synced
            // (green-cloud) items already have a device copy, so a green-cloud + device-only
            // selection shows no download button (the action would skip every item anyway:
            // DownloadPhotosUseCase filters to CloudOnly).
            val hasDownloadable = selectedItems.any { it is GalleryItem.CloudOnly }
            val isDownloading = multiDownloadState is MultiDownloadState.Working
            if (hasDownloadable) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable(enabled = !isDownloading) { onDownload() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDownloading) {
                        val progress = multiDownloadState as MultiDownloadState.Working
                        CircularProgressIndicator(
                            progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                            color = Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.FileDownload,
                            stringResource(R.string.gallery_download_selected),
                            tint = appColors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            // Delete
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
            // More menu — Strip metadata + Hide live here. Both entries only apply to an
            // all-device-only selection, so for mixed / cloud-leaning selections the menu
            // would be empty. Compute that up front and skip the whole ⋮ button when there's
            // nothing to show — otherwise the user sees a squashed pill that does nothing.
            val hasMoreMenuItems = selectedItems.isNotEmpty() &&
                selectedItems.all { it is GalleryItem.LocalOnly }
            if (hasMoreMenuItems) Box {
                var moreExpanded by remember { mutableStateOf(false) }
                val isStripping = multiStripState is MultiStripState.Working
                val isHiding = multiHideState is MultiDeleteState.Working
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PillBg, CircleShape)
                        .border(0.5.dp, PillBorder, CircleShape)
                        .clickable { moreExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isStripping || isHiding || isAddingToAlbum) {
                        CircularProgressIndicator(
                            color = Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.MoreVert,
                            "More",
                            tint = appColors.fgPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = appColors.cardBg,
                    border = BorderStroke(0.5.dp, appColors.pillBorder),
                ) {
                    // "Strip metadata" only fits device-only (LocalOnly) photos —
                    // backed-up + cloud-only items already went through the upload
                    // pipeline's strip toggle (Settings → Privacy & Metadata) and
                    // re-stripping post-upload is a no-op on Drive. Hiding the entry
                    // for mixed / cloud-leaning selections matches the user's mental
                    // model of "this action prepares my device file" — same gating
                    // pattern as the hide-selected entry above.
                    val allDeviceOnly = selectedItems.isNotEmpty() &&
                        selectedItems.all { it is GalleryItem.LocalOnly }
                    if (allDeviceOnly) DropdownMenuItem(
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
                    // Hide selected applies only to LocalOnly/Synced — CloudOnly
                    // items have no device counterpart. Same gating as the
                    // per-section Strip action in the viewer Details.
                    val hasHideable = selectedItems.isNotEmpty() &&
                        selectedItems.all { it is GalleryItem.LocalOnly }
                    if (hasHideable) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Hide selected",
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
}
