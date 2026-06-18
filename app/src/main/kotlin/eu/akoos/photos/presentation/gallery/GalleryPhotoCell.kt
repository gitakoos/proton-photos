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

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute

/** Primitive, Compose-stable snapshot of everything [PhotoCell] renders for one [GalleryItem].
 *  Computed in the caller's item scope so the cell itself takes only stable params (and stays
 *  skippable). [stableKey] doubles as the Coil memory-cache key. */
internal data class PhotoCellInputs(
    val imageData: String?,
    val stableKey: String,
    val isVideo: Boolean,
    val isPlaceholder: Boolean,
    val showCloudBadge: Boolean,
    val showSyncedBadge: Boolean,
    val isFavorite: Boolean,
    val typeBadgeRes: Int?,
    val typeBadgeCdRes: Int?,
)

/** Resolve a [GalleryItem] to [PhotoCellInputs]. The category look-ups run once here rather than
 *  repeatedly inside the cell. [downloadedCloudLinkIds] upgrades a downloaded CloudOnly tile to the
 *  green synced badge; [favoriteIds] adds the heart. Both default empty for surfaces that don't
 *  track them (search, device folders). */
internal fun photoCellInputsFor(
    item: GalleryItem,
    favoriteIds: Set<String> = emptySet(),
    downloadedCloudLinkIds: Set<String> = emptySet(),
): PhotoCellInputs {
    val cloudId = when (item) {
        is GalleryItem.CloudOnly -> item.cloud.linkId
        is GalleryItem.Synced    -> item.cloud.linkId
        is GalleryItem.LocalOnly -> null
    }
    val imageData = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced    -> item.local.uri
        is GalleryItem.CloudOnly -> item.cloud.thumbnailUrl
    }
    val isDownloaded = cloudId != null && cloudId in downloadedCloudLinkIds
    val mime = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }
    val favoriteKey = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced    -> item.local.uri
        is GalleryItem.CloudOnly -> item.cloud.linkId
    }
    val cats = CategorizeItem.classify(item)
    return PhotoCellInputs(
        imageData      = imageData,
        stableKey      = cloudId ?: favoriteKey,
        isVideo        = mime.startsWith("video/"),
        isPlaceholder  = imageData == null && item is GalleryItem.CloudOnly,
        showCloudBadge  = item is GalleryItem.CloudOnly && !isDownloaded,
        showSyncedBadge = item is GalleryItem.Synced || (item is GalleryItem.CloudOnly && isDownloaded),
        isFavorite     = favoriteKey in favoriteIds || 0 in cats,
        typeBadgeRes   = when {
            2 in cats -> R.drawable.ic_video_camera
            4 in cats -> R.drawable.ic_live
            8 in cats -> R.drawable.ic_panorama
            9 in cats -> R.drawable.ic_raw
            else -> null
        },
        typeBadgeCdRes = when {
            4 in cats -> R.string.cd_motion_photo
            8 in cats -> R.string.cd_panorama
            else -> null
        },
    )
}

/**
 * Pixel budget for grid thumbnails. The smallest grid cell (6 columns) is well under this on
 * any phone, so Coil downsamples the source on decode instead of reading the full ~2 MP
 * MediaStore JPEG into heap per cell. A single fixed size (rather than per-column sizing) keeps
 * one warm bitmap per photo across zoom levels — re-binds during scroll are memory-cache hits.
 */
private const val GRID_THUMB_PX = 320

/**
 * One grid tile. All inputs are primitives / stable so Compose can skip a cell whose data is
 * unchanged — a selection toggle or a thumbnail-decrypt re-emission then recomposes only the
 * cells that actually changed, not the whole visible page. Thumbnail decrypt requests are driven
 * centrally from the visible range in [PhotoGrid]; this cell is pure presentation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoCell(
    imageData: String?,
    stableKey: String,
    isVideo: Boolean = false,
    isPlaceholder: Boolean = false,
    selected: Boolean = false,
    isSelectionMode: Boolean = false,
    isHiddenOnDevice: Boolean = false,
    showCloudBadge: Boolean = false,
    showSyncedBadge: Boolean = false,
    isFavorite: Boolean = false,
    typeBadgeRes: Int? = null,
    typeBadgeCdRes: Int? = null,
    showTypeBadges: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            // Slightly taller than a square so the corner badges cover less of the photo.
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(10.dp))
            .background(Bg2)
            // The timeline owns long-press at the grid level (drag-to-select), so it passes no
            // [onLongClick] and the cell stays tap-only. Surfaces without a grid-level gesture
            // (e.g. device folders) pass a handler and get long-press here.
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)
            )
            .then(
                if (selected) Modifier.border(2.5.dp, Accent, RoundedCornerShape(10.dp))
                else Modifier
            ),
    ) {
        if (isPlaceholder || imageData == null) {
            // Placeholder while the on-demand decrypt is in progress. The Bg2-filled Box
            // (from the parent) already provides the dark tile background; the centered
            // photo icon is the visual cue that this slot is "loading", at low opacity
            // so it reads as a hint rather than competing with surrounding tiles.
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = FgDim.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        } else {
            // Decode at tile size, not the source's native resolution, and address the bitmap by a
            // stable memory-cache key so a warm thumbnail is an O(1) cache hit on every re-bind
            // during scroll instead of a fresh full-res decode. crossfade(false) drops the per-bind
            // fade animation that churned while flinging. Mirrors CloudPhotoCell's request.
            val context = LocalContext.current
            val request = remember(imageData, stableKey) {
                ImageRequest.Builder(context)
                    .data(imageData)
                    .size(GRID_THUMB_PX)
                    .memoryCacheKey(stableKey)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model              = request,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
                    .then(if (selected) Modifier.background(Accent.copy(alpha = 0.15f)) else Modifier),
            )
        }

        // Status badge — shown only for cloud-related states.
        //   LocalOnly  = no badge (device-only photos need no indicator)
        //   Synced     = green cloud (backed up AND on device)
        //   CloudOnly  = white cloud (only in Drive — not on device)
        // A CloudOnly cell upgrades to the green badge once its linkId has a SYNCED local copy:
        // the user downloaded it but the static item snapshot still reads CloudOnly. Mirrors the
        // same upgrade the photo viewer applies.
        when {
            showSyncedBadge -> SyncedCloudBadge()
            showCloudBadge  -> CloudBadge()
        }

        // Hidden-eye overlay — drawn on the cell when its cloud linkId is referenced by a
        // SyncState row with status HIDDEN. A heavy black scrim PLUS a real RenderEffect
        // blur (Android 12+) ensures none of the underlying content is readable even by
        // squinting — a plain dim still showed recognisable shapes and colours. Pre-S
        // devices fall back to the scrim alone (Compose's blur modifier no-ops on older
        // platforms without the BlurEffect API).
        if (isHiddenOnDevice) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            Modifier.blur(28.dp)
                        else
                            Modifier,
                    )
                    .background(Color.Black.copy(alpha = 0.7f)),
            )
            HiddenEyeBadge()
        }

        // Video play indicator — shown for any video item when not in selection mode
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

        // Type + favorite badge pill, just left of the bottom-end cloud / synced badge (or in the
        // corner for a local-only photo that has no cloud badge; 28dp clears the cloud badge's
        // 20dp box + 5dp inset + a 3dp gap). One distinct icon per type, with the favorite heart
        // additive in front. The category flags are resolved once in the grid's item scope, so
        // there's no per-cell file IO or bitmap decode here. Visible in selection mode too.
        if (showTypeBadges && (typeBadgeRes != null || isFavorite)) {
            val pillEndPad = if (!showCloudBadge && !showSyncedBadge) 5.dp else 28.dp
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = pillEndPad, bottom = 5.dp)
                    // Same 20.dp box height + 12.dp icon as the cloud badge so the two badges
                    // read as one size, not a smaller squished pill.
                    .height(20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (isFavorite) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                if (typeBadgeRes != null) {
                    Icon(
                        painterResource(typeBadgeRes),
                        contentDescription = typeBadgeCdRes?.let { stringResource(it) },
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        // Selection circle
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .size(22.dp)
                    .align(Alignment.TopStart),
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, stringResource(R.string.cd_status_selected),
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                    )
                }
            }
        }
    }
}

/** White cloud — photo exists only in Drive, not on this device. */
@Composable
private fun BoxScope.CloudBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = stringResource(R.string.cd_status_cloud_only),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Green cloud — backed up to Drive AND still on this device. Safe to remove from device. */
@Composable
private fun BoxScope.SyncedCloudBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = stringResource(R.string.cd_status_backed_up_device),
            tint = Color(0xFF30D158),
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Crossed-out eye — this cloud photo's local twin lives in the Hidden vault. Visible only
 *  from this device, since HIDDEN_PHOTO_URIS / SyncStatus.HIDDEN are per-installation state.
 *  Placed in the top-end corner so the bottom-end cloud / device badge can coexist. */
@Composable
private fun BoxScope.HiddenEyeBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = stringResource(R.string.cd_status_hidden_local),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Phone icon — only on this device, NOT backed up. Do NOT delete without backup! */
@Composable
private fun BoxScope.DeviceBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Phone,
            contentDescription = stringResource(R.string.cd_status_device_only),
            tint = FgMute,
            modifier = Modifier.size(11.dp),
        )
    }
}
