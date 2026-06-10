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

package eu.akoos.photos.presentation.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.CardBg
import eu.akoos.photos.presentation.theme.CardBorder
import eu.akoos.photos.presentation.theme.DeleteTint
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.Line2
import eu.akoos.photos.presentation.theme.PanelChip
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder

// ── Filmstrip ──────────────────────────────────────────────────────────────────

@Composable
internal fun Filmstrip(
    items: List<GalleryItem>,
    currentPage: Int,
    onThumbnailClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val density   = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val viewportPx = constraints.maxWidth
        val itemPx     = with(density) { 54.dp.roundToPx() }

        LaunchedEffect(currentPage) {
            listState.animateScrollToItem(
                index        = currentPage,
                scrollOffset = -(viewportPx / 2 - itemPx / 2),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            userScrollEnabled = false,
        ) {
            itemsIndexed(
                items,
                key = { index, item ->
                    when (item) {
                        is GalleryItem.LocalOnly -> "L:${item.local.uri}"
                        is GalleryItem.Synced    -> "S:${item.local.uri}"
                        is GalleryItem.CloudOnly -> "C:${item.cloud.linkId}"
                    }
                },
            ) { index, item ->
                val isCurrent = index == currentPage
                val thumbModel: Any? = when (item) {
                    is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                    is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                    is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
                }
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Bg2)
                        .then(
                            if (isCurrent)
                                Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            else
                                Modifier.alpha(0.45f)
                        )
                        .clickable { onThumbnailClick(index) },
                ) {
                    if (thumbModel != null) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// ── Bubble button ──────────────────────────────────────────────────────────────

@Composable
internal fun ViewerBubble(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(PillBg, bubbleShape)
            .border(0.5.dp, PillBorder, bubbleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ── Rename dialog ──────────────────────────────────────────────────────────────

@Composable
internal fun RenameDialog(
    currentName: String,
    isCloud: Boolean,
    isWorking: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (newName: String, replaceOriginal: Boolean) -> Unit,
) {
    var text by remember(currentName) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = currentName,
                selection = androidx.compose.ui.text.TextRange(0, splitName(currentName).first.length),
            ),
        )
    }
    val trimmed = text.text.trim()
    val unchanged = trimmed == currentName
    val canSubmit = !isWorking && trimmed.isNotEmpty() && !unchanged

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!isWorking) onDismiss() }) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Bg2)
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.rename_sheet_title), color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (isCloud) stringResource(R.string.rename_sheet_subtitle_cloud)
                else stringResource(R.string.rename_sheet_subtitle_local),
                color = FgMute, fontSize = 13.sp,
            )

            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                enabled = !isWorking,
                label = { Text(stringResource(R.string.rename_sheet_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = FgPrimary,
                    unfocusedTextColor = FgPrimary,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = FgMute,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = FgMute.copy(alpha = 0.4f),
                    cursorColor = Accent,
                ),
            )

            if (errorMessage != null) {
                Text(errorMessage, color = ErrorColor, fontSize = 12.sp)
            }

            // Two primary actions stacked, then a Cancel.
            RenameOptionButton(
                title = if (isCloud) stringResource(R.string.rename_sheet_option_cloud_title)
                    else stringResource(R.string.rename_sheet_option_local_title),
                subtitle = if (isCloud) stringResource(R.string.rename_sheet_option_cloud_subtitle)
                    else stringResource(R.string.rename_sheet_option_local_subtitle),
                accent = true,
                enabled = canSubmit,
                onClick = { onConfirm(trimmed, /* replaceOriginal = */ true) },
            )
            RenameOptionButton(
                title = stringResource(R.string.rename_sheet_option_copy_title),
                subtitle = if (isCloud) stringResource(R.string.rename_sheet_option_copy_subtitle_cloud)
                    else stringResource(R.string.rename_sheet_option_copy_subtitle_local),
                accent = false,
                enabled = canSubmit,
                onClick = { onConfirm(trimmed, /* replaceOriginal = */ false) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !isWorking, onClick = onDismiss),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (isWorking) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Text(stringResource(R.string.cancel), color = FgPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun RenameOptionButton(
    title: String,
    subtitle: String,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (accent) Accent.copy(alpha = if (enabled) 1f else 0.4f)
             else PanelChip.let { if (enabled) it else it.copy(alpha = 0.6f) }
    val fg = if (accent) Color.White else FgPrimary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/** Returns (basename, ".ext") — used to pre-select the basename in the rename field. */
internal fun splitName(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) to name.substring(dot)
           else name to ""
}

// ── Delete confirmation sheet ──────────────────────────────────────────────────

@Composable
internal fun DeleteConfirmSheet(
    item: GalleryItem,
    onDismiss: () -> Unit,
    onDelete: (freeUpSpace: Boolean, deleteFromCloud: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (item) {
                is GalleryItem.LocalOnly  -> "Delete photo?"
                is GalleryItem.Synced     -> "Remove photo?"
                is GalleryItem.CloudOnly  -> "Delete from Proton Drive?"
            },
            color = FgPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )

        when (item) {
            is GalleryItem.LocalOnly -> {
                Text(
                    "This will move the photo to your device's Recently Deleted folder.",
                    color = FgDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                DeleteButton("Move to trash") { onDelete(true, false) }
            }

            is GalleryItem.Synced -> {
                Text(
                    stringResource(R.string.viewer_delete_subtitle_synced),
                    color = FgDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                DangerRow(
                    title = stringResource(R.string.viewer_delete_remove_from_device),
                    subtitle = stringResource(R.string.viewer_delete_remove_from_device_desc),
                    onClick = { onDelete(true, false) },
                )
                DangerRow(
                    title = stringResource(R.string.viewer_delete_remove_from_cloud),
                    subtitle = stringResource(R.string.viewer_delete_remove_from_cloud_desc),
                    onClick = { onDelete(false, true) },
                )
                DangerRow(
                    title = stringResource(R.string.viewer_delete_everywhere),
                    subtitle = stringResource(R.string.viewer_delete_everywhere_desc),
                    isDestructive = true,
                    onClick = { onDelete(true, true) },
                )
            }

            is GalleryItem.CloudOnly -> {
                Text(
                    stringResource(R.string.viewer_delete_subtitle_cloud_only),
                    color = FgDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                DeleteButton(stringResource(R.string.viewer_delete_move_to_drive_trash)) { onDelete(false, true) }
            }
        }

        // Cancel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    CardBg,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
                .border(
                    0.5.dp,
                    CardBorder,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onDismiss)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.cancel), color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Two-line action row used inside [DeleteConfirmSheet] for the Synced-photo delete options
 * (Remove from device / Remove from cloud / Delete everywhere). Three near-identical
 * Column-based rows shared the same recipe before this helper unified them: fill width,
 * rounded background, thin border, clickable, title + subtitle Text pair. The destructive
 * variant swaps the neutral CardBg/CardBorder/FgPrimary palette for DeleteTint/ErrorColor.
 */
@Composable
private fun DangerRow(
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (isDestructive) DeleteTint else CardBg
    val borderColor = if (isDestructive) ErrorColor.copy(alpha = 0.3f) else CardBorder
    val titleColor = if (isDestructive) ErrorColor else FgPrimary
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(0.5.dp, borderColor, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = FgMute, fontSize = 12.sp)
    }
}

@Composable
internal fun DeleteButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                DeleteTint,
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .border(
                0.5.dp,
                ErrorColor.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ErrorColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToAlbumSheet(
    sheetState: androidx.compose.material3.SheetState,
    cloudAlbums: List<Album>,
    currentPhotoAlbumIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onCloudAlbumPicked: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Bg2,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Add to Album",
                color = FgPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            if (cloudAlbums.isEmpty()) {
                Text(
                    "No albums yet. Create one from the Albums tab.",
                    color = FgMute,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }

            // Cloud albums (Drive) — shown when the item has a cloud counterpart.
            if (cloudAlbums.isNotEmpty()) {
                Text(
                    "DRIVE ALBUMS",
                    color = FgMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                cloudAlbums.forEach { album ->
                    val isMember = album.linkId in currentPhotoAlbumIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCloudAlbumPicked(album.linkId) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (album.coverThumbnailUrl != null) {
                            AsyncImage(
                                model = album.coverThumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Bg0),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Bg0, RoundedCornerShape(8.dp)),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(album.name, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (isMember) "Tap to remove from album"
                                else "${album.photoCount} photos",
                                color = if (isMember) Accent else FgMute,
                                fontSize = 12.sp,
                            )
                        }
                        // Member indicator: filled accent-coloured check tile on the trailing
                        // edge of the row. Doubles as the "tap removes" affordance because the
                        // whole row's onClick handles both add + remove based on this state.
                        if (isMember) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(Accent, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_status_in_album),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Line2, thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}
