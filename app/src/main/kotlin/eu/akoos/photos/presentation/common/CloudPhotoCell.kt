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

package eu.akoos.photos.presentation.common

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import kotlinx.coroutines.delay

/**
 * Square 1:1 photo tile shared by Gallery and AlbumDetail.
 *
 * Renders either a local thumbnail (Uri) or a cloud CDN thumbnail URL via Coil's AsyncImage.
 * When [cloudLinkId] is provided and [cloudThumbnailUrl] is null, a 120 ms-debounced decrypt
 * request is queued through [onRequestThumbnail] / [onCancelThumbnail]; this is the same
 * fast-scroll optimisation the Gallery grid relies on (a flung cell is disposed before its
 * 120 ms window elapses, so the request only fires for tiles the user actually pauses on).
 *
 * Visual surface — selection border, badges, video play overlay, optional hidden-on-device
 * blur — mirrors the Gallery tile pixel-for-pixel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudPhotoCell(
    // What the cell renders
    localUri: String?,                  // null for cloud-only
    cloudThumbnailUrl: String?,         // null for local-only
    cloudLinkId: String?,               // null for local-only — used to request decrypt
    isVideo: Boolean,                   // shows the play overlay
    // Selection
    isSelectionMode: Boolean,
    isSelected: Boolean,
    // Badges (Gallery shows: synced/cloud-only marker, AlbumDetail shows: nothing or different)
    showCloudBadge: Boolean = false,    // top-right small cloud icon when item is cloud-only
    showSyncedBadge: Boolean = false,   // top-right small check when item is synced
    // Hidden-on-device overlay (Gallery's hidden-vault scrim + blur + eye badge)
    isHiddenOnDevice: Boolean = false,
    // Callbacks
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRequestThumbnail: (String) -> Unit,
    onCancelThumbnail: (String) -> Unit,
    // Style overrides (defaults are Gallery's)
    cornerRadiusDp: Dp = 10.dp,
    placeholderIconSizeDp: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    // Coil model: prefer local Uri (faster, no network), otherwise the CDN URL.
    val imageModel: Any? = when {
        localUri != null -> android.net.Uri.parse(localUri)
        else             -> cloudThumbnailUrl
    }

    // ── Lazy thumbnail wiring ────────────────────────────────────────────────
    //
    // Cloud-only and Synced cells whose thumbnailUrl is missing represent rows
    // that the sync pass populated in metadata-only mode: rows decrypt on
    // visibility, cancel on leave. The DAO updates the row when the decrypt
    // completes and the Flow-based observation re-emits the new thumbnailUrl
    // into the upstream item, which triggers recomposition and AsyncImage
    // renders the freshly-cached file.
    //
    // Synced cells already render the local file URI, so a missing thumbnailUrl
    // is invisible to the user — we still queue the decrypt though, so the
    // cloud-only view (or another device viewing the same row) gets it ready.
    val pendingLinkId: String? = cloudLinkId.takeIf { it != null && cloudThumbnailUrl == null }
    if (pendingLinkId != null) {
        // 120 ms debounce: during fast flings hundreds of cells fly in and out per second.
        // Without the gate every one of them launched a DAO lookup + scheduler request, then
        // immediately got cancelled when the cell rolled past — pure overhead. Waiting
        // 120 ms before queueing the work means a fast-scroll cell is disposed BEFORE the
        // request fires (the LaunchedEffect coroutine cancels with the cell), so the
        // request path only runs for cells the user actually pauses on.
        LaunchedEffect(pendingLinkId) {
            delay(120)
            onRequestThumbnail(pendingLinkId)
        }
        DisposableEffect(pendingLinkId) {
            onDispose { onCancelThumbnail(pendingLinkId) }
        }
    }

    // True when we have no model to hand to Coil yet — covers the cloud-only
    // metadata-only state (Synced still has localUri so it never hits this branch).
    val isAwaitingThumbnail = imageModel == null

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(Bg2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (isSelected) Modifier.border(2.5.dp, Accent, RoundedCornerShape(cornerRadiusDp))
                else Modifier
            ),
    ) {
        if (isAwaitingThumbnail) {
            // Placeholder while the on-demand decrypt is in progress. The Bg2-filled Box
            // already provides the dark tile background; the centered photo icon is the
            // visual cue that this slot is "loading", at low opacity so it reads as a
            // hint rather than competing with surrounding tiles.
            Icon(
                Icons.Default.Photo,
                contentDescription = null,
                tint = FgDim.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(placeholderIconSizeDp),
            )
        } else {
            // Decode at tile size, not the source's native resolution. Without a Size hint
            // Coil reads each ~2 MP source bitmap into heap before downsampling for the
            // ~200 px grid cell — N visible cells × 2 MP eats hundreds of MB and stutters
            // the first scroll. `Size.ORIGINAL` would inherit that bug; explicit pixel
            // budget keeps the decoded bitmap small.
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(imageModel) {
                ImageRequest.Builder(context)
                    .data(imageModel)
                    .size(512)
                    .build()
            }
            AsyncImage(
                model              = request,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
                    .then(if (isSelected) Modifier.background(Accent.copy(alpha = 0.15f)) else Modifier),
            )
        }

        // Status badge — bottom-end corner. Synced wins when both flags are set
        // (Gallery never sets both, but we still pick one to avoid stacking).
        when {
            showSyncedBadge -> SyncedCloudBadge()
            showCloudBadge  -> CloudBadge()
        }

        // Hidden-eye overlay — heavy black scrim PLUS a real RenderEffect blur (Android 12+)
        // ensures none of the underlying content is readable even by squinting. Pre-S
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

        // Video play indicator — shown for any video item when not in selection mode.
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

        // Selection circle — top-start corner, 22 dp circle inside 5 dp padding.
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .size(22.dp)
                    .align(Alignment.TopStart),
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check, "Selected",
                            tint = Color.White, modifier = Modifier.size(14.dp),
                        )
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
            contentDescription = "Only in Drive",
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
            contentDescription = "Backed up, also on device",
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
            contentDescription = "Hidden on this device",
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}
