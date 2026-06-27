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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Date-format used by both the scrubber drag tooltip and the floating scroll-date label, keyed on
 * the active timeline grouping so the two surfaces always read the same way. Flat (None) still uses
 * the month label, matching the scrubber tooltip's long-standing behaviour.
 */
@Composable
internal fun rememberTimelineDateFormat(grouping: TimelineGrouping): SimpleDateFormat =
    remember(grouping) {
        when (grouping) {
            TimelineGrouping.None  -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Day   -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Month -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Year  -> SimpleDateFormat("yyyy", Locale.getDefault())
        }
    }

/**
 * Maps a GRID [firstVisibleIndex] (the grid interleaves date headers + the memories row, so it runs
 * ahead of the photo list) to the formatted capture date of the photo that index falls on. Uses the
 * same grid-fraction approximation the scrubber tooltip uses when it isn't dragging, so the floating
 * scroll-date label and the scrubber never disagree. Returns "" when there are no photos.
 */
internal fun timelineDateLabel(
    firstVisibleIndex: Int,
    totalGridItems: Int,
    items: List<GalleryItem>,
    dateFormat: SimpleDateFormat,
): String {
    if (items.isEmpty()) return ""
    val gridSpan = (totalGridItems - 1).coerceAtLeast(1)
    val idx = (firstVisibleIndex.toFloat() / gridSpan * (items.size - 1))
        .roundToInt().coerceIn(0, items.size - 1)
    return dateFormat.format(Date(items[idx].captureTimeMs))
}

/** Vertical fast-scroll handle for the photos grid. Auto-hides on scroll idle. */
@Composable
fun BoxScope.TimelineScrubber(
    gridState: LazyGridState,
    items: List<GalleryItem>,
    grouping: TimelineGrouping,
    topPadding: Dp,
    bottomPadding: Dp,
    // Maps an item to the grid cell key the host screen used, so the drag tooltip can resolve the
    // exact first-visible photo. Defaults to the main timeline's scheme; albums/search pass their own.
    keyOf: (GalleryItem) -> String = ::defaultScrubberKey,
    // Reports the drag state up so a sibling overlay (the floating scroll-date label) can yield while
    // the user is scrubbing — the two date surfaces sit near the top and must not both show at once.
    onDraggingChange: (Boolean) -> Unit = {},
) {
    val colors = AppColors.current
    val density = LocalDensity.current

    val totalItems = items.size
    val hasContent = totalItems > 0

    val firstVisibleIndex by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }
    // The grid interleaves date headers + the "On this day" row with photo cells, so its item count
    // and indices run ahead of the photo list. Scrub in GRID space (this count) and map to a photo
    // only for the tooltip — using the raw grid index against the photo list drifted the thumb,
    // tooltip and seek further off the more headers accumulated down the timeline.
    val totalGridItems by remember(gridState) {
        derivedStateOf { gridState.layoutInfo.totalItemsCount }
    }

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightDp = 56.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    var isScrolling by remember { mutableStateOf(false) }
    val visible = hasContent && (isScrolling || isDragging)

    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    // Where the grabbed handle sits (px from the track top). Seeded at grab, moved by the drag
    // delta, so the handle scrubs relative to where you grabbed it rather than to the raw touch Y.
    var dragThumbPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling) {
                    isScrolling = true
                } else {
                    delay(1500)
                    isScrolling = false
                }
            }
    }

    LaunchedEffect(gridState, totalItems) {
        snapshotFlow { dragTargetIndex }
            .collectLatest { target ->
                if (target >= 0) gridState.scrollToItem(target)
            }
    }

    val dateFormat = rememberTimelineDateFormat(grouping)
    // Grid cell key → item, mirroring PhotoGrid's keyOf. Built LAZILY (then cached) so steady
    // scroll — including the large-library decrypt re-emissions — never pays for a full-list map;
    // it materialises only when the user actually drags the scrubber and the exact date is wanted.
    val keyToItemLazy = remember(items, keyOf) {
        lazy { items.associateBy(keyOf) }
    }
    val tooltipDate by remember(items, grouping) {
        derivedStateOf {
            if (!hasContent) ""
            else if (isDragging) {
                // Exact: the date of the first visible PHOTO cell (the grid's first item can be a
                // date header, so skip to a photo via its key). Falls back to the fraction if the
                // layout hasn't settled yet.
                val map = keyToItemLazy.value
                val key = gridState.layoutInfo.visibleItemsInfo
                    .firstNotNullOfOrNull { info -> (info.key as? String)?.takeIf { it in map } }
                key?.let { map[it]?.captureTimeMs }
                    ?.let { dateFormat.format(Date(it)) }
                    ?: timelineDateLabel(firstVisibleIndex, totalGridItems, items, dateFormat)
            } else {
                // Tooltip is hidden when not dragging — a cheap grid-fraction approximation is fine.
                timelineDateLabel(firstVisibleIndex, totalGridItems, items, dateFormat)
            }
        }
    }

    // Returns a GRID index (the grid includes headers), so scrollToItem lands on the right row.
    fun fractionToIndex(fraction: Float): Int {
        if (totalGridItems <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * (totalGridItems - 1)).roundToInt()
    }

    val scrollFraction: Float = if (totalGridItems <= 1) 0f
        else firstVisibleIndex.toFloat() / (totalGridItems - 1)

    val maxTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbTopPx = (scrollFraction * maxTrackPx).coerceIn(0f, maxTrackPx)

    val topPaddingPx = with(density) { topPadding.roundToPx() }

    AnimatedVisibility(
        visible = isDragging && tooltipDate.isNotEmpty(),
        enter = fadeIn(tween(100)),
        exit = fadeOut(tween(150)),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 48.dp)
            .offset { IntOffset(0, topPaddingPx + thumbTopPx.roundToInt()) }
            .wrapContentSize(),
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = tooltipDate,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInHorizontally(tween(160)) { it / 2 },
        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 2 },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = topPadding, bottom = bottomPadding)
            .width(36.dp),
    ) {
        Box(modifier = Modifier.fillMaxHeight()) {
            // Track — visual only; just measures its height. Deliberately NO tap/drag gesture: a tap
            // or a scroll-touch near the right edge must not jump the timeline. Only the grab handle
            // below scrubs (matches Ente / Google Photos, where you grab the handle to move).
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .padding(end = 6.dp)
                    .onSizeChanged { trackHeightPx = it.height.toFloat() },
            )

            // Grab handle — the ONLY scrub target. The 36dp-wide box is the touch area around the
            // slim visual thumb; dragging it moves the timeline by the drag delta.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .size(width = 36.dp, height = thumbHeightDp)
                    .pointerInput(totalItems) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                onDraggingChange(true)
                                dragThumbPx = thumbTopPx
                            },
                            onDragEnd = { isDragging = false; onDraggingChange(false) },
                            onDragCancel = { isDragging = false; onDraggingChange(false) },
                        ) { _, dragAmount ->
                            dragThumbPx = (dragThumbPx + dragAmount.y).coerceIn(0f, maxTrackPx)
                            val fraction = if (maxTrackPx <= 0f) 0f else dragThumbPx / maxTrackPx
                            dragTargetIndex = fractionToIndex(fraction)
                        }
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(width = 6.dp, height = thumbHeightDp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isDragging) colors.accent else colors.fgDim.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

/** Main-timeline grid key scheme — the default for [TimelineScrubber]'s `keyOf`. */
private fun defaultScrubberKey(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "local_${item.local.uri}"
    is GalleryItem.Synced    -> "synced_${item.local.uri}"
    is GalleryItem.CloudOnly -> "cloud_${item.cloud.linkId}"
}

/**
 * Staggered-grid twin of [TimelineScrubber] for the opt-in mosaic timeline. Index-based position is
 * inherently approximate under variable tile heights — the thumb tracks the first-visible item index
 * against the total item count, so the handle and the seek are close but not pixel-exact when tiles
 * of very different heights scroll past. The drag tooltip still resolves the exact first-visible
 * photo via its cell key, matching the fixed-grid behaviour.
 */
@Composable
fun BoxScope.TimelineScrubberStaggered(
    gridState: LazyStaggeredGridState,
    items: List<GalleryItem>,
    grouping: TimelineGrouping,
    topPadding: Dp,
    bottomPadding: Dp,
    keyOf: (GalleryItem) -> String = ::defaultScrubberKey,
    onDraggingChange: (Boolean) -> Unit = {},
) {
    val colors = AppColors.current
    val density = LocalDensity.current

    val totalItems = items.size
    val hasContent = totalItems > 0

    val firstVisibleIndex by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }
    val totalGridItems by remember(gridState) {
        derivedStateOf { gridState.layoutInfo.totalItemsCount }
    }

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightDp = 56.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    var isScrolling by remember { mutableStateOf(false) }
    val visible = hasContent && (isScrolling || isDragging)

    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragThumbPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling) {
                    isScrolling = true
                } else {
                    delay(1500)
                    isScrolling = false
                }
            }
    }

    LaunchedEffect(gridState, totalItems) {
        snapshotFlow { dragTargetIndex }
            .collectLatest { target ->
                if (target >= 0) gridState.scrollToItem(target)
            }
    }

    val dateFormat = rememberTimelineDateFormat(grouping)
    val keyToItemLazy = remember(items, keyOf) {
        lazy { items.associateBy(keyOf) }
    }
    val tooltipDate by remember(items, grouping) {
        derivedStateOf {
            if (!hasContent) ""
            else if (isDragging) {
                val map = keyToItemLazy.value
                val key = gridState.layoutInfo.visibleItemsInfo
                    .firstNotNullOfOrNull { info -> (info.key as? String)?.takeIf { it in map } }
                key?.let { map[it]?.captureTimeMs }
                    ?.let { dateFormat.format(Date(it)) }
                    ?: timelineDateLabel(firstVisibleIndex, totalGridItems, items, dateFormat)
            } else {
                timelineDateLabel(firstVisibleIndex, totalGridItems, items, dateFormat)
            }
        }
    }

    fun fractionToIndex(fraction: Float): Int {
        if (totalGridItems <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * (totalGridItems - 1)).roundToInt()
    }

    val scrollFraction: Float = if (totalGridItems <= 1) 0f
        else firstVisibleIndex.toFloat() / (totalGridItems - 1)

    val maxTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbTopPx = (scrollFraction * maxTrackPx).coerceIn(0f, maxTrackPx)

    val topPaddingPx = with(density) { topPadding.roundToPx() }

    AnimatedVisibility(
        visible = isDragging && tooltipDate.isNotEmpty(),
        enter = fadeIn(tween(100)),
        exit = fadeOut(tween(150)),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 48.dp)
            .offset { IntOffset(0, topPaddingPx + thumbTopPx.roundToInt()) }
            .wrapContentSize(),
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = tooltipDate,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInHorizontally(tween(160)) { it / 2 },
        exit = fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 2 },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = topPadding, bottom = bottomPadding)
            .width(36.dp),
    ) {
        Box(modifier = Modifier.fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .padding(end = 6.dp)
                    .onSizeChanged { trackHeightPx = it.height.toFloat() },
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .size(width = 36.dp, height = thumbHeightDp)
                    .pointerInput(totalItems) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                onDraggingChange(true)
                                dragThumbPx = thumbTopPx
                            },
                            onDragEnd = { isDragging = false; onDraggingChange(false) },
                            onDragCancel = { isDragging = false; onDraggingChange(false) },
                        ) { _, dragAmount ->
                            dragThumbPx = (dragThumbPx + dragAmount.y).coerceIn(0f, maxTrackPx)
                            val fraction = if (maxTrackPx <= 0f) 0f else dragThumbPx / maxTrackPx
                            dragTargetIndex = fractionToIndex(fraction)
                        }
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(width = 6.dp, height = thumbHeightDp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isDragging) colors.accent else colors.fgDim.copy(alpha = 0.7f)),
                )
            }
        }
    }
}
