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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.OfflinePin
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.presentation.common.LocalVideoThumb
import eu.akoos.photos.presentation.common.rememberLocalVideoThumbnail
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.Bg0
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.StatusSynced
import eu.akoos.photos.presentation.util.formatVideoTime

/** Pixel budget for the OS video poster in album tiles. Matches the gallery grid so a synced
 *  video shares one warm bitmap size across surfaces. */
private const val ALBUM_THUMB_PX = 320

/** The single secondary corner badge kept at the compact (4-column) density tier, picked by
 *  priority (duration > offline > type > favorite). [None] means no secondary is present. */
private enum class AlbumCompactSecondary { Duration, Offline, Type, Favorite, None }

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
    /** True when this album photo's linkId is pinned for offline; draws the bottom-start badge. */
    isOffline: Boolean = false,
    /** Live column count of the album grid. Drives the same badge-density tiers as the gallery cell:
     *  <= 3 shows every badge, == 4 keeps the cloud badge plus one highest-priority secondary, >= 5
     *  keeps only the cloud badge. Defaults to 3 (all badges) for any caller with no column grid. */
    columns: Int = 3,
    /** Edge-to-edge grid: square corners (0.dp) on both the tile clip and the selection border. */
    seamless: Boolean = false,
    /** True: long-press pops the per-cell menu. False: long-press toggles multi-select via [onLongPress].
     *  Off for shared-with-me albums and while already in multi-select. */
    showLongPressMenu: Boolean = false,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
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

    // An on-device video twin can show the OS poster instead of decoding a fresh video frame per
    // bind (which pops in after the grid opens). Cloud-only videos have no local file, so they keep
    // the decrypted-thumbnail path below.
    val isLocalVideoTile = localUri != null && photo.mimeType.startsWith("video/")

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
            .clip(RoundedCornerShape(if (seamless) 0.dp else if (isSelected) 8.dp else 6.dp))
            .background(Bg2)
            // Tap-only by default: with no long-press handler the cell is a plain clickable, and the
            // grid-level drag-select owns the stationary long-press (single-select + range sweep)
            // while the call-site tap-guard skips the release-tap. combinedClickable is used only to
            // carry a long-press: the menu variant pops the per-cell menu, and an explicit onLongPress
            // (unused by album detail, which leaves it null) routes there instead.
            .then(
                when {
                    showLongPressMenu -> Modifier.combinedClickable(onClick = onTap, onLongClick = { menuExpanded = true })
                    onLongPress != null -> Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    else -> Modifier.clickable(onClick = onTap)
                },
            )
            .then(if (isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(if (seamless) 0.dp else 8.dp)) else Modifier),
    ) {
        // For an on-device video, pull the OS poster (system-cached, near-instant) rather than
        // routing the raw video uri through Coil's frame decoder. Loading keeps the Bg2 tile (nothing
        // pops); Loaded draws the bitmap; Unavailable (pre-Q or the provider refused) falls through
        // to the plain image path so the tile is never blank.
        val osThumb: LocalVideoThumb? =
            if (isLocalVideoTile) rememberLocalVideoThumbnail(localUri!!, ALBUM_THUMB_PX).value
            else null
        when {
            osThumb is LocalVideoThumb.Loaded -> AsyncImage(
                model = osThumb.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            osThumb is LocalVideoThumb.Loading -> Unit // Bg2 tile shows through until the poster lands.
            imageModel != null -> AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
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
        }

        // Badge-density tiers by the album grid's live column count. As tiles shrink the corner
        // badges crowd, so denser grids drop the lower-priority ones. The cloud badge is always kept
        // (top priority); the center play icon is not a badge and stays for every video.
        //   <= 3 columns: every badge, as before.
        //   == 4 columns: cloud badge + exactly ONE highest-priority secondary.
        //   >= 5 columns: cloud badge only.
        // Secondary priority (high to low): video duration > offline pin > type badge > favorite.
        val isVideo = photo.mimeType.startsWith("video/")
        val hasDuration = isVideo && photo.durationMs != null && photo.durationMs > 0
        val typeBadge: Pair<Int, Int?>? = when {
            // Videos are already marked by the center play icon, so no separate video badge here.
            4 in photo.tags -> R.drawable.ic_live to R.string.cd_motion_photo
            8 in photo.tags -> R.drawable.ic_panorama to R.string.cd_panorama
            9 in photo.tags -> R.drawable.ic_raw to R.string.gallery_filter_raw
            else -> null
        }
        val isFavorite = 0 in photo.tags
        val compactSecondary: AlbumCompactSecondary = when {
            hasDuration      -> AlbumCompactSecondary.Duration
            isOffline        -> AlbumCompactSecondary.Offline
            typeBadge != null -> AlbumCompactSecondary.Type
            isFavorite       -> AlbumCompactSecondary.Favorite
            else             -> AlbumCompactSecondary.None
        }
        val allowDuration = when {
            columns <= 3 -> true
            columns == 4 -> compactSecondary == AlbumCompactSecondary.Duration
            else         -> false
        }
        val allowOffline = when {
            columns <= 3 -> true
            columns == 4 -> compactSecondary == AlbumCompactSecondary.Offline
            else         -> false
        }
        val allowType = when {
            columns <= 3 -> true
            columns == 4 -> compactSecondary == AlbumCompactSecondary.Type
            else         -> false
        }
        val allowFavorite = when {
            columns <= 3 -> true
            columns == 4 -> compactSecondary == AlbumCompactSecondary.Favorite
            else         -> false
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

        // Bottom-start overlays: the offline-pin badge and the always-on video duration pill share
        // one Row so they sit side by side (badge first, then the duration) and never overlap. This
        // corner clears the bottom-end cloud badge + type pill, the top-start selection circle, and
        // the centred play icon. The pill hides in selection mode alongside the play icon; it shows
        // only when a real length is known (durationMs backfilled on the cloud photo).
        val showOfflineBadge = isOffline && allowOffline
        val showDurationPill = hasDuration && !isSelectionMode && allowDuration
        if (showOfflineBadge || showDurationPill) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (showOfflineBadge) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.OfflinePin,
                            contentDescription = stringResource(R.string.offline_make_available),
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                if (showDurationPill) {
                    Box(
                        modifier = Modifier
                            .height(18.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            formatVideoTime(photo.durationMs),
                            color = Color.White,
                            fontSize = 10.sp,
                            // Drop the default bottom font padding so the number sits centered in the pill.
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        )
                    }
                }
            }
        }

        // Type + favorite badge pill from server category tags. 25dp end-padding clears the cloud
        // badge (18dp + 4dp + 3dp). At the compact tier only the single winning secondary shows, so
        // a Type winner drops the favorite heart and a Favorite winner drops the type icon.
        val pillShowType = typeBadge != null && allowType
        val pillShowFavorite = isFavorite && allowFavorite
        if (pillShowType || pillShowFavorite) {
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
                if (pillShowFavorite) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
                if (pillShowType) {
                    Icon(
                        painterResource(typeBadge.first),
                        contentDescription = typeBadge.second?.let { stringResource(it) },
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        if (isVideo && !isSelectionMode) {
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
                        onLongPress?.invoke()
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
