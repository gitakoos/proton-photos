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

package eu.akoos.photos.presentation.albums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.StatusSynced

@Composable
internal fun AvatarCircle(letter: String, tint: Color, size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(tint.copy(alpha = 0.2f), CircleShape)
            .border(1.5.dp, Bg0, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = tint,
            fontSize = if (size < 28.dp) 11.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoCell(
    photo: CloudPhoto,
    localUri: String? = null,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    /** True: long-press pops the per-cell menu. False: long-press toggles multi-select via [onLongPress].
     *  Off for shared-with-me albums and while already in multi-select. */
    showLongPressMenu: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSetAsCover: () -> Unit = {},
    onRemoveFromAlbum: () -> Unit = {},
    onRequestThumbnail: (linkId: String) -> Unit = {},
    onCancelThumbnail: (linkId: String) -> Unit = {},
) {
    val imageModel: Any? = when {
        localUri != null -> android.net.Uri.parse(localUri)
        photo.thumbnailUrl != null -> photo.thumbnailUrl
        else -> null
    }

    // Lazy-decrypt: a null thumbnailUrl means the row is metadata-only. Decrypt while visible, cancel on scroll-away.
    if (photo.thumbnailUrl == null && localUri == null) {
        androidx.compose.runtime.DisposableEffect(photo.linkId) {
            onRequestThumbnail(photo.linkId)
            onDispose { onCancelThumbnail(photo.linkId) }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val appColors = AppColors.current

    Box(
        modifier = Modifier
            // Slightly taller than square so corner badges cover less of the photo.
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(if (isSelected) 8.dp else 6.dp))
            .background(Bg2)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    if (showLongPressMenu) menuExpanded = true
                    else onLongPress()
                },
            )
            .then(if (isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Loading placeholder while the on-demand decrypt runs (parent already fills the Bg2 tile).
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = FgDim.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
            )
        }

        // Cloud badge — green when the file is also on-device, white when cloud-only.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(18.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = if (localUri != null) StatusSynced else Color.White,
                modifier = Modifier.size(11.dp),
            )
        }

        // Type + favorite badge pill from server category tags. 25dp end-padding clears the cloud badge (18dp + 4dp + 3dp).
        val typeBadge: Pair<Int, Int?>? = when {
            2 in photo.tags -> R.drawable.ic_video_camera to null
            4 in photo.tags -> R.drawable.ic_live to R.string.cd_motion_photo
            8 in photo.tags -> R.drawable.ic_panorama to R.string.cd_panorama
            9 in photo.tags -> R.drawable.ic_raw to null
            else -> null
        }
        val isFavorite = 0 in photo.tags
        if (typeBadge != null || isFavorite) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 25.dp, bottom = 4.dp)
                    // Match the cloud badge's 18.dp box so both badges read as one size.
                    .height(18.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (isFavorite) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
                if (typeBadge != null) {
                    Icon(
                        painterResource(typeBadge.first),
                        contentDescription = typeBadge.second?.let { stringResource(it) },
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        if (photo.mimeType.startsWith("video/") && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        if (isSelectionMode) {
            Box(modifier = Modifier.padding(4.dp).size(20.dp).align(Alignment.TopStart)) {
                if (isSelected) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(0.8f), CircleShape),
                    )
                }
            }
        }

        // Per-cell long-press menu, anchored to this Box so it pops over the pressed photo.
        if (showLongPressMenu) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = appColors.cardBg,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, appColors.pillBorder),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gallery_action_select), color = appColors.fgPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onLongPress()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.album_set_as_cover), color = appColors.fgPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onSetAsCover()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.album_remove_from_album), color = appColors.fgPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            tint = ErrorColor,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRemoveFromAlbum()
                    },
                )
            }
        }
    }
}
