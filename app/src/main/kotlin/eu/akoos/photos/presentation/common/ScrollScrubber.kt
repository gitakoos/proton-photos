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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import eu.akoos.photos.presentation.theme.AppColors
import kotlin.math.roundToInt

/**
 * Generic vertical fast-scroll handle for a [LazyGridState], mapping drag fraction → grid item.
 * Unlike the timeline scrubber this has no date axis and shows no label — it only tracks the
 * scroll position so a long album / device-folder grid can be jumped through quickly. The handle
 * appears while scrolling or dragging and fades on idle, matching the timeline scrubber's feel.
 *
 * Pass [minItemsToShow] to suppress it on short lists (roughly one screenful is plenty to scroll
 * by hand). The grid's total item count is used directly, so spanned header/pinned cells count
 * the same as the grid sees them.
 */
@Composable
fun BoxScope.ScrollScrubber(
    gridState: LazyGridState,
    topPadding: Dp,
    bottomPadding: Dp,
    minItemsToShow: Int = 0,
) {
    val totalItems by remember(gridState) {
        derivedStateOf { gridState.layoutInfo.totalItemsCount }
    }
    val firstVisibleIndex by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }
    ScrollScrubberBody(
        totalItems = totalItems,
        firstVisibleIndex = firstVisibleIndex,
        isScrollInProgress = { gridState.isScrollInProgress },
        scrollToItem = { gridState.scrollToItem(it) },
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        minItemsToShow = minItemsToShow,
    )
}

/**
 * Shared implementation behind the grid overload. Takes the raw reactive inputs
 * (current item count + first-visible index), a poll for whether the underlying state is scrolling,
 * and a suspend scroll lambda — so the same handle drives the [LazyGridState].
 */
@Composable
private fun BoxScope.ScrollScrubberBody(
    totalItems: Int,
    firstVisibleIndex: Int,
    isScrollInProgress: () -> Boolean,
    scrollToItem: suspend (Int) -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
    minItemsToShow: Int,
) {
    val colors = AppColors.current
    val density = LocalDensity.current

    val hasEnough = totalItems > minItemsToShow

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightDp = 56.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    var isScrolling by remember { mutableStateOf(false) }
    val visible = hasEnough && (isScrolling || isDragging)

    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    // Where the grabbed handle sits (px from the track top). Seeded at grab, moved by the drag
    // delta, so the handle scrubs relative to where you grabbed it rather than to the raw touch Y.
    var dragThumbPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        snapshotFlow { isScrollInProgress() }
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

    LaunchedEffect(Unit) {
        snapshotFlow { dragTargetIndex }
            .collectLatest { target ->
                if (target >= 0) scrollToItem(target)
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
            // Track — visual only; just measures its height. No tap/drag gesture so a scroll-touch
            // near the right edge can't jump the list; only the grab handle scrubs.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .padding(end = 6.dp)
                    .onSizeChanged { trackHeightPx = it.height.toFloat() },
            )

            // Grab handle — the only scrub target. The 36dp box is the touch area around the slim
            // visual thumb; dragging it moves the grid by the drag delta.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .size(width = 36.dp, height = thumbHeightDp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragThumbPx = thumbTopPx
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
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
