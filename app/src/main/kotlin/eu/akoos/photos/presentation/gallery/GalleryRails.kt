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

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import eu.akoos.photos.R
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.Accent2
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.ArcTrack
import eu.akoos.photos.presentation.theme.Bg2
import eu.akoos.photos.presentation.theme.ErrorColor
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBgOpaque
import eu.akoos.photos.presentation.theme.PillBorder

@Composable
internal fun AlbumsFilterRail(
    onHiddenAlbumClick: () -> Unit = {},
    onNewAlbumClick: () -> Unit = {},
    selectedFilter: AlbumDisplayFilter = AlbumDisplayFilter.All,
    onFilterSelected: (AlbumDisplayFilter) -> Unit = {},
    onOpenSheet: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The configured default narrowing. The pill highlights only when the current filter differs
    // from this default, so leaving the filter at its default reads as "unfiltered".
    val defaultFilterOrdinal by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.ALBUMS_DEFAULT_FILTER] ?: 0 }
    }.collectAsState(initial = 0)
    val defaultFilter = AlbumDisplayFilter.entries[
        defaultFilterOrdinal.coerceIn(0, AlbumDisplayFilter.entries.lastIndex)
    ]
    val hiddenLabel = stringResource(R.string.gallery_filter_hidden)
    val newAlbumLabel = stringResource(R.string.albums_new_album)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // All / Cloud / Local view filter. Tapping the label cycles All to Cloud to Local; the
        // filter icon after the separator opens the sheet (default + remember-last). Highlighted
        // when off the configured default, so a filter sitting at its default reads as unfiltered.
        item(key = "album_filter") {
            val active = selectedFilter != defaultFilter
            val label = when (selectedFilter) {
                AlbumDisplayFilter.All -> stringResource(R.string.albums_filter_all)
                AlbumDisplayFilter.Cloud -> stringResource(R.string.albums_filter_cloud)
                AlbumDisplayFilter.Local -> stringResource(R.string.albums_filter_local)
            }
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(if (active) AppColors.current.chipSelectedBg else PillBg, pillShape)
                    .border(0.5.dp, if (active) Accent else PillBorder, pillShape)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Label part cycles to the next narrowing on tap.
                Row(
                    modifier = Modifier
                        .clickable {
                            val next = AlbumDisplayFilter.entries[
                                (selectedFilter.ordinal + 1) % AlbumDisplayFilter.entries.size
                            ]
                            onFilterSelected(next)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(label, color = if (active) FgPrimary else FgDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                // Hairline separator + filter button, mirroring the timeline All pill. Opens the
                // sheet where the default filter and the remember-last toggle are set.
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(0.5.dp)
                        .background(if (active) Accent.copy(alpha = 0.4f) else PillBorder),
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onOpenSheet() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        stringResource(R.string.albums_filter_sheet_title),
                        tint = if (active) Accent else FgDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        // Hidden albums: compact icon button, matching the other icon pills.
        item(key = "hidden") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onHiddenAlbumClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, hiddenLabel, tint = FgDim, modifier = Modifier.size(15.dp))
            }
        }
        // New album: compact icon button that opens the create-album dialog.
        item(key = "new_album") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onNewAlbumClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, newAlbumLabel, tint = FgDim, modifier = Modifier.size(17.dp))
            }
        }
    }
}

// ── Storage color helper ──────────────────────────────────────────────────────

/** Returns green / amber / red based on storage fill ratio. */
private fun storageArcColor(fraction: Float): Color = when {
    fraction < 0.70f -> Color(0xFF30D158)   // green
    fraction < 0.90f -> Color(0xFFFF9F0A)   // amber
    else             -> Color(0xFFFF453A)   // red
}

// ── Avatar button ─────────────────────────────────────────────────────────────

@Composable
internal fun AvatarButton(
    initial: String,
    storageFraction: Float,
    isSyncing: Boolean,
    hasActiveUpload: Boolean = false,
    hasActiveDownload: Boolean = false,
    isOffline: Boolean = false,
    updateAvailable: Boolean = false,
    newsUnread: Boolean = false,
    onClick: () -> Unit,
    onUpdateClick: () -> Unit = onClick,
    onUploadClick: () -> Unit = onClick,
    onDownloadClick: () -> Unit = onClick,
) {
    // Animate the arc smoothly when storage data first loads
    val animatedFraction by animateFloatAsState(
        targetValue = storageFraction,
        animationSpec = tween(durationMillis = 800),
        label = "storage_arc",
    )
    val arcColor   = storageArcColor(storageFraction)
    val trackColor = ArcTrack
    val pillBgColor = PillBg

    val transferActive = hasActiveUpload || hasActiveDownload
    val ringActive = isSyncing || transferActive
    // Status glyphs (update / offline) surface only when no upload/download/sync runs: an active
    // process always wins the pill, matching the requested precedence.
    val showStatus = !transferActive && !isSyncing && (updateAvailable || isOffline)
    // Green while photos actually move (issue #57); a plain sync keeps the existing blue.
    val ringColor = if (transferActive) Color(0xFF34D399) else Color(0xFF60AFFF)
    // The pill grows left to fit however many glyphs sit beside the avatar.
    val leftGlyphs = when {
        transferActive -> (if (hasActiveUpload) 1 else 0) + (if (hasActiveDownload) 1 else 0)
        showStatus -> (if (updateAvailable) 1 else 0) + (if (isOffline) 1 else 0)
        else -> 0
    }
    val pillWidth by animateDpAsState(
        targetValue = 46.dp + (leftGlyphs * 28).dp,
        animationSpec = tween(durationMillis = 320),
        label = "avatar_pill_width",
    )

    // Spinning ring: rotates continuously while syncing or transferring
    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "spin_angle",
    )

    Box(
        modifier = Modifier
            .height(46.dp)
            .width(pillWidth)
            .drawBehind {
                val strokePx = 2.6.dp.toPx()
                val h = size.height
                val radius = h / 2f
                // Pill fill + border track around the whole rounded rectangle.
                drawRoundRect(color = pillBgColor, cornerRadius = CornerRadius(radius, radius))
                drawRoundRect(
                    color = trackColor,
                    topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, h - strokePx),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = strokePx),
                )
                // Idle storage arc lives on the avatar-end circle; the active ring is a comet that
                // travels the whole pill outline, so the spin follows the widened shape.
                val arcRect = Size(h - strokePx, h - strokePx)
                val arcTopLeft = androidx.compose.ui.geometry.Offset(size.width - h + strokePx / 2f, strokePx / 2f)
                if (ringActive) {
                    val inset = strokePx / 2f
                    val outline = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = inset, top = inset,
                                right = size.width - inset, bottom = h - inset,
                                radiusX = radius, radiusY = radius,
                            ),
                        )
                    }
                    val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(outline, true) }
                    val len = measure.length
                    val cometLen = len * 0.32f
                    val startD = (spinAngle / 360f) * len
                    val comet = androidx.compose.ui.graphics.Path()
                    measure.getSegment(startD, minOf(startD + cometLen, len), comet, true)
                    if (startD + cometLen > len) {
                        measure.getSegment(0f, startD + cometLen - len, comet, true)
                    }
                    drawPath(comet, color = ringColor, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                } else if (animatedFraction > 0f) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedFraction,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcRect,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }
            // Tapping the avatar itself always opens Settings; the status glyphs below carry their
            // own smaller tap targets (update / uploads / downloads) that consume the tap first.
            .clickable(onClick = onClick),
    ) {
        // Left area: transfer arrows always win; otherwise the status glyphs (update / offline).
        if (transferActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasActiveUpload) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.activity_tab_uploads),
                        tint = ringColor,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onUploadClick)
                            .size(13.dp),
                    )
                }
                if (hasActiveDownload) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.activity_tab_downloads),
                        tint = ringColor,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onDownloadClick)
                            .size(13.dp),
                    )
                }
            }
        } else if (showStatus) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (updateAvailable) {
                    Icon(
                        Icons.Default.NewReleases,
                        contentDescription = null,
                        tint = Accent2,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onUpdateClick)
                            .size(15.dp),
                    )
                }
                if (isOffline) {
                    // Soft red so "no connection" reads at a glance without shouting.
                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(14.dp))
                }
            }
        }
        // Avatar cluster at the right end: a 46 dp circle region, same layout as before.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Avatar gradient circle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Accent, Accent2),
                            start = Offset(0f, 0f),
                            end   = Offset(80f, 80f),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = initial.ifEmpty { "?" },
                    color      = Color.White,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Gear badge
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .background(Bg2, CircleShape)
                    .border(1.dp, PillBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(8.dp),
                )
            }
            // Unread-news dot. A quiet mark at the opposite corner from the gear, drawn on its own so
            // it never touches the pill's sync/transfer/update precedence beside the avatar.
            if (newsUnread) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .background(Accent2, CircleShape)
                        .border(1.5.dp, Bg2, CircleShape),
                )
            }
        }
    }
}

// ── Filter rail ───────────────────────────────────────────────────────────────

@Composable
internal fun FilterRail(
    totalCount: Int,
    contentFilter: ContentFilter,
    onSearchClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onClearContentFilter: () -> Unit,
    onOpenTimelineFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isContentFilterActive = contentFilter != ContentFilter()
    val photosLabel = stringResource(R.string.gallery_filter_photos)
    val videosLabel = stringResource(R.string.filter_type_videos)
    val localLabel = stringResource(R.string.filter_sync_local)
    val backedUpLabel = stringResource(R.string.filter_sync_backedup)
    val allLabel = stringResource(R.string.gallery_filter_all)
    val filterSummary = remember(contentFilter, photosLabel, videosLabel, localLabel, backedUpLabel) {
        buildContentFilterSummary(contentFilter, photosLabel, videosLabel, localLabel, backedUpLabel)
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // ── All pill — shows content filter summary when active ───────────────
        item(key = "all") {
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(
                        if (isContentFilterActive) Accent.copy(alpha = 0.18f) else AppColors.current.filterPillBg,
                        pillShape
                    )
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Summary + count — tap to clear the active content filter (unchanged behaviour).
                Row(
                    modifier = Modifier
                        .clickable(enabled = isContentFilterActive) { onClearContentFilter() }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!isContentFilterActive) {
                        Icon(Icons.Default.Check, null, tint = FgPrimary, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        filterSummary ?: allLabel,
                        color = if (isContentFilterActive) Accent else FgPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (totalCount > 0) {
                        Text(
                            formatCount(totalCount),
                            color = if (isContentFilterActive) Accent.copy(alpha = 0.7f) else FgDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                // Hairline separator + filter button. Opens the Timeline filter (hide device
                // folders / albums from the timeline) right here, so it is discoverable from the
                // timeline instead of only from Settings.
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(0.5.dp)
                        .background(if (isContentFilterActive) Accent.copy(alpha = 0.4f) else PillBorder),
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onOpenTimelineFilter() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        stringResource(R.string.settings_timeline_filter),
                        tint = if (isContentFilterActive) Accent else FgDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // ── Search button ─────────────────────────────────────────────────────
        item(key = "search_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(PillBg, pillShape)
                    .border(0.5.dp, PillBorder, pillShape)
                    .clickable { onSearchClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, stringResource(R.string.search_title),
                    tint = FgDim, modifier = Modifier.size(18.dp))
            }
        }

    }
}

// ── Category rail ─────────────────────────────────────────────────────────────

/** The canonical Drive-web order, also the fallback when no custom order is saved. */
private val DEFAULT_CATEGORY_ORDER = listOf(
    GalleryFilter.Favorites, GalleryFilter.Screenshots, GalleryFilter.Videos,
    GalleryFilter.LivePhotos, GalleryFilter.Selfies, GalleryFilter.Portraits,
    GalleryFilter.Bursts, GalleryFilter.Panoramas, GalleryFilter.Raw,
    GalleryFilter.Offline,
)

/** Resolve the saved CSV into a category list: saved entries first (in their stored order), then
 *  any default category not yet saved (so a newly added category still appears). Unknown / dropped
 *  names are ignored. */
private fun resolveCategoryOrder(savedCsv: String?): List<GalleryFilter> {
    val saved = savedCsv?.split(',')
        ?.mapNotNull { name -> DEFAULT_CATEGORY_ORDER.firstOrNull { it.name == name } }
        ?: emptyList()
    return saved + DEFAULT_CATEGORY_ORDER.filterNot { it in saved }
}

@Composable
private fun categoryLabel(filter: GalleryFilter): String = when (filter) {
    GalleryFilter.Favorites   -> stringResource(R.string.gallery_filter_favorites)
    GalleryFilter.Screenshots -> stringResource(R.string.gallery_filter_screenshots)
    GalleryFilter.Videos      -> stringResource(R.string.filter_type_videos)
    GalleryFilter.LivePhotos  -> stringResource(R.string.gallery_filter_live_photos)
    GalleryFilter.Selfies     -> stringResource(R.string.gallery_filter_selfies)
    GalleryFilter.Portraits   -> stringResource(R.string.gallery_filter_portraits)
    GalleryFilter.Bursts      -> stringResource(R.string.gallery_filter_bursts)
    GalleryFilter.Panoramas   -> stringResource(R.string.gallery_filter_panoramas)
    GalleryFilter.Raw         -> stringResource(R.string.gallery_filter_raw)
    GalleryFilter.Offline     -> stringResource(R.string.filter_offline)
    else -> filter.name
}

/**
 * Horizontally scrollable row of category chips under the timeline's top rail (Favorites,
 * Screenshots, Videos, Live / Motion, Selfies, Portraits, Bursts, Panoramas, RAW), each with its
 * Drive-web glyph and label. Tapping one filters the timeline to that category; tapping the active
 * one clears back to All. The order is arranged on the Timeline filter settings screen
 * ([CategoryReorderList]) and read here.
 */
@Composable
internal fun CategoryRail(
    selectedFilter: GalleryFilter,
    onFilterSelected: (GalleryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val savedCsv by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.CATEGORY_RAIL_ORDER] }
    }.collectAsState(initial = null)
    val order = remember(savedCsv) { resolveCategoryOrder(savedCsv) }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        itemsIndexed(order, key = { _, f -> f.name }) { _, cat ->
            val selected = selectedFilter == cat
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(pillShape)
                    .background(if (selected) Accent.copy(alpha = 0.18f) else PillBg, pillShape)
                    .then(if (!selected) Modifier.border(0.5.dp, PillBorder, pillShape) else Modifier)
                    .clickable { onFilterSelected(if (selected) GalleryFilter.All else cat) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GalleryCategoryIcon(cat, tint = if (selected) Accent else FgDim)
                Text(
                    categoryLabel(cat),
                    color = if (selected) Accent else FgDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Arrow-driven reorder list for the timeline category bar, shown on the Timeline filter settings
 * screen. Each row carries the category's glyph + label with a drag handle; dragging a row lifts
 * it to follow the finger and reorders the list live, then rewrites the persisted
 * [SettingsKeys.CATEGORY_RAIL_ORDER] so [CategoryRail] reflects the new order.
 */
@Composable
internal fun CategoryReorderList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AppColors.current

    val savedCsv by remember {
        context.settingsDataStore.data.map { it[SettingsKeys.CATEGORY_RAIL_ORDER] }
    }.collectAsState(initial = null)
    var order by remember { mutableStateOf<List<GalleryFilter>>(emptyList()) }
    LaunchedEffect(savedCsv) { order = resolveCategoryOrder(savedCsv) }

    fun persistOrder() {
        val snapshot = order
        scope.launch {
            runCatching {
                context.settingsDataStore.edit {
                    it[SettingsKeys.CATEGORY_RAIL_ORDER] = snapshot.joinToString(",") { f -> f.name }
                }
            }
        }
    }

    var draggedCat by remember { mutableStateOf<GalleryFilter?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    Column(modifier = modifier) {
        order.forEachIndexed { index, cat ->
            key(cat) {
                val isDragged = cat == draggedCat
                Row(
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffsetY else 0f }
                        .fillMaxWidth()
                        .background(
                            if (isDragged) colors.bg2 else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GalleryCategoryIcon(cat, tint = FgDim)
                    Text(
                        categoryLabel(cat),
                        color = FgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    // Drag handle — grab and drag; the row lifts and follows the finger while the
                    // list reorders live as it passes neighbours. Keyed on the item so the gesture
                    // survives the reorder recompositions mid-drag.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .pointerInput(Unit) {
                                val rowH = 52.dp.toPx()
                                detectDragGestures(
                                    onDragStart = { draggedCat = cat; dragOffsetY = 0f },
                                    onDragEnd = { draggedCat = null; dragOffsetY = 0f; persistOrder() },
                                    onDragCancel = { draggedCat = null; dragOffsetY = 0f },
                                ) { change, drag ->
                                    change.consume()
                                    dragOffsetY += drag.y
                                    val cur = order.indexOf(cat)
                                    if (cur < 0) return@detectDragGestures
                                    if (dragOffsetY > rowH / 2 && cur < order.lastIndex) {
                                        order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                        dragOffsetY -= rowH
                                    } else if (dragOffsetY < -rowH / 2 && cur > 0) {
                                        order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                        dragOffsetY += rowH
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            tint = FgDim,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            if (index < order.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 44.dp),
                    thickness = 0.5.dp,
                    color = colors.cardBorder,
                )
            }
        }
    }
}

// ── Bottom dock ───────────────────────────────────────────────────────────────

@Composable
internal fun BottomDock(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .background(PillBgOpaque, pillShape)
            .border(0.5.dp, PillBorder, pillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DockTab(
            icon = Icons.Default.Photo,
            label = stringResource(R.string.gallery_tab_photos),
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
        )
        DockTab(
            icon = Icons.Default.Collections,
            label = stringResource(R.string.gallery_tab_albums),
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
        )
        DockTab(
            icon = Icons.Default.Share,
            label = stringResource(R.string.gallery_tab_shared),
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
        )
    }
}

@Composable
private fun DockTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Compact padding + maxLines/softWrap=false so the labels never wrap onto two lines
    // on narrow screens (6.1"-class and smaller screens). The text shrinks to
    // ellipsis if a localised label is unusually long instead of breaking the pill.
    Row(
        modifier = Modifier
            .background(
                if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Accent else FgDim,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = if (selected) Accent else FgDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Shared filter rail ────────────────────────────────────────────────────────

enum class SharedFilter { SharedWithMe, SharedByMe }

/** Albums-tab view filter. All shows cloud albums + device folders in one common stream; Cloud
 *  and Local narrow to one kind. Picked from the filter sheet opened by [AlbumsFilterRail]. */
enum class AlbumDisplayFilter { All, Cloud, Local }

@Composable
internal fun SharedFilterRail(
    selectedFilter: SharedFilter,
    onFilterSelected: (SharedFilter) -> Unit,
    activeEmailFilter: String? = null,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        // Single toggle pill — tap flips between "with me" and "by me". Direction icon
        // makes the active scope obvious (incoming arrow for with-me, outgoing for by-me).
        item(key = "shared_toggle") {
            val isWithMe = selectedFilter == SharedFilter.SharedWithMe
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .background(AppColors.current.chipSelectedBg, pillShape)
                    .clickable {
                        onFilterSelected(
                            if (isWithMe) SharedFilter.SharedByMe else SharedFilter.SharedWithMe,
                        )
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (isWithMe) Icons.AutoMirrored.Filled.CallReceived
                    else Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(
                        if (isWithMe) R.string.share_shared_with_me
                        else R.string.share_shared_by_me,
                    ),
                    color = FgPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = FgDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        // Filter button — highlighted when an email filter is active
        item(key = "filter_button") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (activeEmailFilter != null) Accent.copy(alpha = 0.15f) else PillBg, pillShape)
                    .border(0.5.dp, if (activeEmailFilter != null) Accent else PillBorder, pillShape)
                    .clickable { onFilterClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.FilterList, stringResource(R.string.share_filter_account),
                    tint = if (activeEmailFilter != null) Accent else FgDim,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
