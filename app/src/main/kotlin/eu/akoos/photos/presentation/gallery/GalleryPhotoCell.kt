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
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.presentation.common.LocalVideoThumb
import eu.akoos.photos.presentation.common.rememberLocalVideoThumbnail
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.util.formatVideoTime

/**
 * linkId -> decrypted thumbnail `file://` URL, supplied once high in the nav tree from
 * ThumbnailUrlStore and inherited by every gallery cell below. The timeline projection no longer
 * carries the URL on the row, so a cloud cell whose caller passes no per-cell URL reads its own
 * entry here (keyed on the cell's stableKey, which is the cloud linkId). Screens that do thread a
 * per-cell URL (the main timeline) keep taking precedence via the `?:` in [PhotoCell].
 *
 * Holds a [State] rather than the bare map so the provider hands down a STABLE reference: the map
 * changes on every decrypt during a scroll, and providing the value itself would recompose the whole
 * nav root each time. Cells read `.current.value`, so only the tiles whose entry changed rebind.
 * Defaults to an empty map so a cell composed outside any provider falls back to the item's own URL.
 */
val LocalThumbnailUrls: ProvidableCompositionLocal<State<Map<String, String>>> =
    compositionLocalOf { mutableStateOf(emptyMap()) }

/** Primitive, Compose-stable snapshot of everything [PhotoCell] renders for one [GalleryItem].
 *  Computed in the caller's item scope so the cell itself takes only stable params (and stays
 *  skippable). [stableKey] doubles as the Coil memory-cache key. */
internal data class PhotoCellInputs(
    val imageData: String?,
    val stableKey: String,
    val isVideo: Boolean,
    // True when this is an on-device video (LocalOnly, or Synced with a local uri): the cell then
    // renders the OS thumbnail instead of decoding a fresh video frame per bind. Cloud-only videos
    // stay false (their poster is an already-decrypted image).
    val isLocalVideo: Boolean,
    // Video length in ms for the always-on bottom-start duration pill. Null for images and when
    // unknown; resolved local-first (the on-device file's duration) then cloud (durationMs).
    val durationMs: Long?,
    val isPlaceholder: Boolean,
    val showCloudBadge: Boolean,
    val showSyncedBadge: Boolean,
    val isFavorite: Boolean,
    val isOffline: Boolean,
    val typeBadgeRes: Int?,
    val typeBadgeCdRes: Int?,
)

/**
 * Whether [item] carries the favourite heart.
 *
 * A photo that lives only on the device answers from [favoriteIds], the device-side set keyed by its
 * MediaStore uri, because nothing else records the answer for it. A backed-up photo answers from
 * Drive PhotoTag 0 alone: the heart writes straight to Drive and another client can change it there
 * too, so a device-side copy of the answer would keep showing a favourite the server has already
 * dropped. That is the ordering [CategorizeItem] uses for the categories, and the favourite is one
 * of those tags in the same place.
 *
 * [liveCloudTags] is the tag set the local library holds for the photo right now, for a caller that
 * can read it. Null means "use the set the item carries", which is what a caller whose items come
 * straight from the library flow already has.
 */
internal fun isItemFavorite(
    item: GalleryItem,
    favoriteIds: Set<String>,
    liveCloudTags: Set<Int>? = null,
): Boolean = when (item) {
    is GalleryItem.LocalOnly -> item.local.uri in favoriteIds
    is GalleryItem.Synced    -> 0 in (liveCloudTags ?: item.cloud.tags)
    is GalleryItem.CloudOnly -> 0 in (liveCloudTags ?: item.cloud.tags)
}

/** Resolve a [GalleryItem] to [PhotoCellInputs]. The category look-ups run once here rather than
 *  repeatedly inside the cell. [downloadedCloudLinkIds] upgrades a downloaded CloudOnly tile to the
 *  green synced badge; [favoriteIds] adds the heart on a device-only tile (a backed-up one reads its
 *  Drive tag, see [isItemFavorite]); [offlinePinIds] adds the offline badge to a CloudOnly tile
 *  pinned for offline. All default empty for surfaces that don't track them.
 *
 *  [pairedVaultUris] raises the same green badge on a vaulted tile whose photo kept a Drive copy; the
 *  vault's own records are the only thing that can still say so, since vaulting removes the MediaStore
 *  row that made the photo a Synced item.
 *
 *  [cloudThumbnailUrl] is the freshly-decrypted `file://` URL for this cell's cloud row, resolved from
 *  ThumbnailUrlStore in the caller's item scope. The timeline projection no longer carries the URL on
 *  the row, so a CloudOnly cell binds this value (falling back to any URL still on the item, e.g. a
 *  Synced/album row); a decrypt that lands mid-scroll repaints just this tile. */
internal fun photoCellInputsFor(
    item: GalleryItem,
    favoriteIds: Set<String> = emptySet(),
    downloadedCloudLinkIds: Set<String> = emptySet(),
    offlinePinIds: Set<String> = emptySet(),
    cloudThumbnailUrl: String? = null,
    pairedVaultUris: Set<String> = emptySet(),
): PhotoCellInputs {
    val cloudId = when (item) {
        is GalleryItem.CloudOnly -> item.cloud.linkId
        is GalleryItem.Synced    -> item.cloud.linkId
        is GalleryItem.LocalOnly -> null
    }
    val imageData = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced    -> item.local.uri
        // Prefer the store's fresh URL; fall back to whatever the item still carries (album/shared
        // rows keep it on the entity) so non-timeline callers that pass no store value are unchanged.
        is GalleryItem.CloudOnly -> cloudThumbnailUrl ?: item.cloud.thumbnailUrl
    }
    val isDownloaded = cloudId != null && cloudId in downloadedCloudLinkIds
    // A vaulted photo has no MediaStore row, so it reaches a grid as LocalOnly whether or not it was
    // backed up, and its own type cannot say which. The vault's cloud-id records can, and a Drive copy
    // the hide left untouched is exactly what the green cloud means everywhere else.
    val isPairedVaultPhoto = item is GalleryItem.LocalOnly && item.local.uri in pairedVaultUris
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
    // Video length for the bottom-start pill. Prefer the on-device file's duration when a local
    // twin exists and reports a real length, otherwise fall back to the cloud xAttr duration.
    // Both are milliseconds; null (or non-positive) means "no pill".
    val durationMs = when (item) {
        is GalleryItem.LocalOnly -> item.local.duration.takeIf { it > 0 }
        is GalleryItem.Synced    -> item.local.duration.takeIf { it > 0 } ?: item.cloud.durationMs
        is GalleryItem.CloudOnly -> item.cloud.durationMs
    }
    val cats = CategorizeItem.classify(item)
    val isVideo = mime.startsWith("video/")
    // An on-device video (LocalOnly, or a Synced twin) can show the OS poster instead of a decoded
    // frame. CloudOnly videos have no local file here, so they keep the decrypted-image path.
    val hasLocalUri = item is GalleryItem.LocalOnly || item is GalleryItem.Synced
    return PhotoCellInputs(
        imageData      = imageData,
        stableKey      = cloudId ?: favoriteKey,
        isVideo        = isVideo,
        isLocalVideo   = isVideo && hasLocalUri,
        durationMs     = durationMs,
        isPlaceholder  = imageData == null && item is GalleryItem.CloudOnly,
        showCloudBadge  = item is GalleryItem.CloudOnly && !isDownloaded,
        showSyncedBadge = item is GalleryItem.Synced ||
            (item is GalleryItem.CloudOnly && isDownloaded) || isPairedVaultPhoto,
        isFavorite     = isItemFavorite(item, favoriteIds),
        isOffline      = item is GalleryItem.CloudOnly && cloudId != null && cloudId in offlinePinIds,
        typeBadgeRes   = when {
            // Videos are already marked by the center play icon, so no separate video badge here.
            4 in cats -> R.drawable.ic_live
            8 in cats -> R.drawable.ic_panorama
            9 in cats -> R.drawable.ic_raw
            else -> null
        },
        typeBadgeCdRes = when {
            4 in cats -> R.string.cd_motion_photo
            8 in cats -> R.string.cd_panorama
            9 in cats -> R.string.gallery_filter_raw
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

/** The single secondary corner badge kept at the compact (4-column) density tier, picked by
 *  priority (duration > offline > type > favorite). [None] means no secondary is present. */
private enum class CompactSecondary { Duration, Offline, Type, Favorite, None }

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
    // On-device video: render the system thumbnail instead of decoding a video frame through Coil,
    // so the poster is instant on open rather than popping in. Defaults false (unchanged path).
    isLocalVideo: Boolean = false,
    // Video length in ms; drives the always-on bottom-start duration pill (null = no pill).
    durationMs: Long? = null,
    isPlaceholder: Boolean = false,
    selected: Boolean = false,
    isSelectionMode: Boolean = false,
    isHiddenOnDevice: Boolean = false,
    showCloudBadge: Boolean = false,
    showSyncedBadge: Boolean = false,
    isFavorite: Boolean = false,
    isOffline: Boolean = false,
    typeBadgeRes: Int? = null,
    typeBadgeCdRes: Int? = null,
    showTypeBadges: Boolean = true,
    // Live column count of the grid this cell is in. Drives badge-density tiers: at 4 columns only
    // the cloud/status badge plus one highest-priority secondary show, at 5+ only the cloud badge.
    // Defaults to 3 (the big-tile tier that shows every badge) for callers with no column grid.
    columns: Int = 3,
    // Tile corner rounding. Defaults to the standard 10.dp; the seamless (edge-to-edge) grid passes
    // 0.dp for square corners. Applies to both the tile clip and the selection border.
    cornerRadius: Dp = 10.dp,
    // Per-cell aspect ratio (width / height) for the staggered mosaic grid. Null keeps the fixed
    // square-grid tile shape; the fixed grid never passes this so its tiles are unchanged.
    aspectRatioOverride: Float? = null,
    // Mosaic-only: reports the loaded thumbnail's intrinsic width / height so a cell with no stored
    // dimensions (a cloud photo) can size its tile from the decoded image. Null on the fixed grid.
    onIntrinsicAspect: ((Float) -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // Cloud rows arrive here with a null [imageData] on every screen that doesn't thread a per-cell
    // URL (the timeline still does, so its value wins). Fall back to the shared store map for this
    // cell's linkId. [stableKey] is the cloud linkId for a cloud cell, and a local cell's stableKey
    // (its uri) simply misses the map. The lookup is O(1); the resolved value drives both the
    // placeholder decision and the image request below.
    val resolvedImageData = imageData ?: LocalThumbnailUrls.current.value[stableKey]

    // Badge-density tiers by the grid's live column count. As tiles shrink the corner badges crowd,
    // so denser grids drop the lower-priority ones. The cloud/status badge is always kept (it is the
    // most important state and its own `when` already only draws for Synced/CloudOnly). The center
    // play icon is not a badge and stays for every video regardless of tier.
    //   <= 3 columns (big tiles):  show every badge, as before.
    //   == 4 columns (compact):    cloud badge + exactly ONE highest-priority secondary.
    //   >= 5 columns (minimal):    cloud badge only.
    // Secondary priority (high to low): video duration > offline pin > type badge > favorite.
    val isDurationSecondary = isVideo && durationMs != null && durationMs > 0
    val compactSecondary: CompactSecondary = when {
        isDurationSecondary -> CompactSecondary.Duration
        isOffline           -> CompactSecondary.Offline
        typeBadgeRes != null -> CompactSecondary.Type
        isFavorite          -> CompactSecondary.Favorite
        else                -> CompactSecondary.None
    }
    val allowDuration = when {
        columns <= 3 -> true
        columns == 4 -> compactSecondary == CompactSecondary.Duration
        else         -> false
    }
    val allowOffline = when {
        columns <= 3 -> true
        columns == 4 -> compactSecondary == CompactSecondary.Offline
        else         -> false
    }
    val allowTypeFavorite = when {
        columns <= 3 -> true
        columns == 4 -> compactSecondary == CompactSecondary.Type ||
            compactSecondary == CompactSecondary.Favorite
        else         -> false
    }

    Box(
        modifier = Modifier
            // Slightly taller than a square so the corner badges cover less of the photo.
            .aspectRatio(aspectRatioOverride ?: 0.85f)
            .clip(RoundedCornerShape(cornerRadius))
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
                if (selected) Modifier.border(2.5.dp, Accent, RoundedCornerShape(cornerRadius))
                else Modifier
            ),
    ) {
        if (resolvedImageData == null) {
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
            val context = LocalContext.current
            // For an on-device video, pull the OS poster (system-cached, near-instant) rather than
            // routing the raw video uri through Coil's frame decoder, which re-decodes a frame on
            // every re-bind (the disk cache is off) and pops in after the grid opens. The state is
            // Loading until the poster lands (the Bg2 tile shows through, nothing pops), Loaded with
            // a bitmap to draw, or Unavailable pre-Q / when the provider refuses -> fall back to the
            // frame-decoder request below so the tile is never blank.
            val osThumb: LocalVideoThumb? =
                if (isLocalVideo) rememberLocalVideoThumbnail(resolvedImageData, GRID_THUMB_PX).value
                else null
            val useOsThumb = osThumb is LocalVideoThumb.Loaded || osThumb is LocalVideoThumb.Loading

            // A decode failure (corrupt file or unsupported format) leaves Coil with nothing to
            // draw, so the tile would otherwise be a blank Bg2 square. Track the Error state and
            // swap in a muted broken-image glyph so the slot still reads as "a file that can't be
            // previewed" rather than empty space. Re-armed per source so a new bind starts clean.
            var decodeFailed by remember(resolvedImageData, stableKey) { mutableStateOf(false) }

            // Report the drawn thumbnail's intrinsic aspect to the mosaic grid, and clear the error
            // flag. Shared by both the OS-poster and Coil paths so mosaic sizing is identical.
            val onImageState: (AsyncImagePainter.State) -> Unit = { st ->
                when (st) {
                    is AsyncImagePainter.State.Success -> {
                        decodeFailed = false
                        onIntrinsicAspect?.let { report ->
                            val size = st.painter.intrinsicSize
                            if (size.width > 0f && size.height > 0f) {
                                report(size.width / size.height)
                            }
                        }
                    }
                    is AsyncImagePainter.State.Error -> decodeFailed = true
                    else -> Unit
                }
            }
            val imageModifier = Modifier.fillMaxSize()
                .then(if (selected) Modifier.background(Accent.copy(alpha = 0.15f)) else Modifier)

            if (useOsThumb) {
                // Draw the OS poster once it lands; while Loading the tile stays on its Bg2
                // background so nothing pops. Coil still owns the draw (memory cache + crossfade
                // off) and reports intrinsic size the same way as the image path.
                val loaded = osThumb as? LocalVideoThumb.Loaded
                if (loaded != null) {
                    val request = remember(loaded.bitmap, stableKey) {
                        ImageRequest.Builder(context)
                            .data(loaded.bitmap)
                            .size(GRID_THUMB_PX)
                            .memoryCacheKey(stableKey)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model              = request,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        onState            = onImageState,
                        modifier           = imageModifier,
                    )
                }
            } else {
                // Image tiles, cloud video posters, and the local-video fallback (pre-Q or a
                // provider that refused loadThumbnail): decode at tile size, addressed by a stable
                // memory-cache key so a warm thumbnail is an O(1) cache hit on re-bind instead of a
                // fresh decode. crossfade(false) drops the per-bind fade that churned while flinging.
                val request = remember(resolvedImageData, stableKey) {
                    ImageRequest.Builder(context)
                        .data(resolvedImageData)
                        .size(GRID_THUMB_PX)
                        .memoryCacheKey(stableKey)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model              = request,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    onState            = onImageState,
                    modifier           = imageModifier,
                )
            }
            if (decodeFailed) {
                // Same muted treatment as the loading placeholder above, over the Bg2 tile.
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = FgDim.copy(alpha = 0.45f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                )
            }
        }

        // Status badge — shown only for cloud-related states.
        //   LocalOnly  = no badge (device-only photos need no indicator)
        //   Synced     = green cloud (backed up AND on device)
        //   CloudOnly  = white cloud (only in Drive — not on device)
        // A CloudOnly cell upgrades to the green badge once its linkId has a SYNCED local copy:
        // the user downloaded it but the static item snapshot still reads CloudOnly. Mirrors the
        // same upgrade the photo viewer applies. A vaulted LocalOnly tile upgrades the same way when
        // the vault records a Drive copy for it.
        when {
            showSyncedBadge -> SyncedCloudBadge()
            showCloudBadge  -> CloudBadge()
        }

        // Bottom-start overlays: the offline-pin badge and the video duration pill share one Row
        // here so they sit side by side (badge first, then the duration) and never overlap. This
        // corner clears the bottom-end cloud/synced badge + type pill, the top-end hidden eye, and
        // the top-start selection circle. The duration pill is always on (no setting) but hides in
        // selection mode alongside the play icon; a local video shows only the pill, a cloud-only
        // offline video shows both.
        val showOfflineBadge = isOffline && allowOffline
        val showDurationPill = isDurationSecondary && !isSelectionMode && allowDuration
        if (showOfflineBadge || showDurationPill) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (showOfflineBadge) OfflineBadge()
                if (showDurationPill) DurationPill(durationMs!!)
            }
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
        // At the compact tier only the single winning secondary shows, so a Type winner drops the
        // favorite heart and a Favorite winner drops the type icon; the big-tile tier keeps both.
        val pillShowType = typeBadgeRes != null && allowTypeFavorite &&
            (columns <= 3 || compactSecondary == CompactSecondary.Type)
        val pillShowFavorite = isFavorite && allowTypeFavorite &&
            (columns <= 3 || compactSecondary == CompactSecondary.Favorite)
        if (showTypeBadges && (pillShowType || pillShowFavorite)) {
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
                if (pillShowFavorite) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
                if (pillShowType) {
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

/** Green cloud — backed up to Drive AND still on this device. Safe to remove from device.
 *  Internal rather than private so the Free up space screen marks its photos with the very same
 *  badge: that screen asks the user to confirm each photo has a Drive copy before its device copy is
 *  deleted, and a second badge that merely looked alike could drift from this one. */
@Composable
internal fun BoxScope.SyncedCloudBadge() {
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

/** Offline-pin badge for a cloud photo pinned for offline. Rendered inside the shared
 *  bottom-start Row (see [PhotoCell]), which supplies the corner alignment and inset. */
@Composable
private fun OfflineBadge() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.OfflinePin,
            contentDescription = stringResource(R.string.offline_make_available),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Always-on video duration pill. Rendered inside the shared bottom-start Row (see [PhotoCell]),
 *  which supplies the corner alignment and inset. Matches the 20.dp badge height so the pill and
 *  the offline badge read as one size when both are shown. */
@Composable
private fun DurationPill(durationMs: Long) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            formatVideoTime(durationMs),
            color = Color.White,
            fontSize = 10.sp,
            // Drop the default bottom font padding so the number sits centered in the 20.dp pill.
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
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

