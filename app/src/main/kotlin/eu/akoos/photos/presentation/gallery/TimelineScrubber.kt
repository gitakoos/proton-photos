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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.LazyGridState
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

/** Vertical fast-scroll handle for the photos grid. Auto-hides on scroll idle. */
@Composable
fun BoxScope.TimelineScrubber(
    gridState: LazyGridState,
    items: List<GalleryItem>,
    grouping: TimelineGrouping,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    val colors = AppColors.current
    val density = LocalDensity.current

    val totalItems = items.size
    val hasContent = totalItems > 0

    val firstVisibleIndex by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightDp = 56.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    var isScrolling by remember { mutableStateOf(false) }
    val visible = hasContent && (isScrolling || isDragging)

    var dragTargetIndex by remember { mutableIntStateOf(-1) }

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

    val dateFormat = remember(grouping) {
        when (grouping) {
            // None level mirrors the Month format — when the user is on the flat
            // ungrouped wall the tooltip still benefits from the broader month label.
            TimelineGrouping.None  -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Day   -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Month -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            TimelineGrouping.Year  -> SimpleDateFormat("yyyy", Locale.getDefault())
        }
    }
    val tooltipDate by remember(items, grouping) {
        derivedStateOf {
            if (!hasContent) ""
            else {
                val idx = firstVisibleIndex.coerceIn(0, totalItems - 1)
                dateFormat.format(Date(items[idx].captureTimeMs))
            }
        }
    }

    fun fractionToIndex(fraction: Float): Int {
        if (totalItems <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * (totalItems - 1)).roundToInt()
    }

    val scrollFraction: Float = if (totalItems <= 1) 0f
        else firstVisibleIndex.toFloat() / (totalItems - 1)

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
                    .onSizeChanged { trackHeightPx = it.height.toFloat() }
                    .pointerInput(totalItems) {
                        detectTapGestures { offset ->
                            val fraction = if (trackHeightPx <= 0f) 0f
                                else offset.y / trackHeightPx
                            dragTargetIndex = fractionToIndex(fraction)
                        }
                    }
                    .pointerInput(totalItems) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
                        ) { change, _ ->
                            val y = change.position.y.coerceIn(0f, trackHeightPx)
                            val fraction = if (trackHeightPx <= 0f) 0f else y / trackHeightPx
                            dragTargetIndex = fractionToIndex(fraction)
                        }
                    },
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .padding(end = 4.dp)
                    .size(width = 6.dp, height = thumbHeightDp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isDragging) colors.accent else colors.fgDim.copy(alpha = 0.7f)),
            )
        }
    }
}
