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

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * Long-press-then-drag multi-select for a photo [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 * Grabbing a cell anchors the selection; dragging extends or retracts the swept range, with edge
 * auto-scroll past the visible area. A long-press with no movement selects the single anchor cell,
 * entering selection mode. A plain single-finger drag falls through to the grid's own scroll.
 *
 * Returns a [Modifier] to apply to the grid. Generic over the selection element [T]: the main timeline
 * selects whole gallery items, albums select by link id.
 *
 * @param gridState the grid's scroll state, for hit-testing visible cells and edge auto-scroll
 * @param items the flat selectable list, in grid order
 * @param indexByKey grid cell key -> index into [items]; cells whose key is absent aren't selectable
 * @param selected the live selection, read so a re-grab extends from / deselects within the current set
 * @param onSelectionChange called with the new selection on every range change
 * @param tapGuard armed at the long-press anchor so the cell's release-tap can be skipped: without it
 *   the tap that ends a stationary long-press would toggle the just-selected cell back off
 * @param enabled when false a new drag never anchors (used to freeze selection while a bulk action
 *   like a multi-delete is in flight, so the swept range can't change under the running operation)
 */
@Composable
fun <T> rememberDragMultiSelectModifier(
    gridState: LazyGridState,
    items: List<T>,
    indexByKey: Map<String, Int>,
    selected: Set<T>,
    onSelectionChange: (Set<T>) -> Unit,
    tapGuard: MutableState<Boolean>? = null,
    enabled: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val edgeScrollPx = remember(density) { with(density) { 80.dp.toPx() } }
    val latestEnabled by rememberUpdatedState(enabled)

    // Read through updated-state holders: the pointerInput block is keyed on Unit so the long-press
    // detector survives list re-emits during loading. Reading items/indexByKey/selected at call time
    // keeps every handler on the current data without restarting the gesture.
    val latestSelected by rememberUpdatedState(selected)
    val latestItems by rememberUpdatedState(items)
    val latestIndexByKey by rememberUpdatedState(indexByKey)
    // Drag mode, decided once at the anchor: true = select the swept range, false = deselect it.
    var dragSelect by remember { mutableStateOf(true) }
    var dragAnchorIndex by remember { mutableStateOf(-1) }
    var dragLastIndex by remember { mutableStateOf(-1) }
    // Selection snapshot captured at the anchor; each paint sets the whole selection to it +/- the
    // swept range, so dragging back reverts the cells the range no longer covers.
    var dragInitial by remember { mutableStateOf(emptySet<T>()) }
    // Signed scroll speed (px/frame) while the finger sits in the top/bottom edge band; 0 stops it.
    var edgeScrollVelocity by remember { mutableFloatStateOf(0f) }

    val paintRange: (Int, Int) -> Unit = { lo, hi ->
        // Clamp to current bounds: indices come from indexByKey, rebuilt in lockstep with items, but a
        // mid-drag list shrink (a refresh re-emit) could otherwise make subList throw.
        val current = latestItems
        val last = current.size - 1
        if (last >= 0) {
            val hiC = hi.coerceIn(0, last)
            val loC = lo.coerceIn(0, hiC)
            val range = current.subList(loC, hiC + 1).toSet()
            onSelectionChange(if (dragSelect) dragInitial + range else dragInitial - range)
        }
    }

    // LazyGridItemInfo.offset is relative to the content start (after the top contentPadding); the
    // pointer offset is node-local (0 = node top). viewportStartOffset is exactly -(top contentPadding),
    // so subtracting it converts each cell into the pointer's space — read from layoutInfo it is exact
    // and scroll-invariant. (A hand-passed padding was off by a band; dropping it entirely then drifted
    // the hit a full row on the timeline, whose top padding is large under the floating header.)
    val itemIndexAt: (Offset) -> Int? = remember(Unit) {
        fun(offset: Offset): Int? {
            val startOffset = gridState.layoutInfo.viewportStartOffset
            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { cell ->
                val top = cell.offset.y - startOffset
                val left = cell.offset.x
                offset.y >= top && offset.y < top + cell.size.height &&
                    offset.x >= left && offset.x < left + cell.size.width
            } ?: return null
            val key = info.key as? String ?: return null
            return latestIndexByKey[key]
        }
    }

    // A single long-lived collector drives the edge auto-scroll: while the velocity the drag handler
    // sets is non-zero it scrolls each frame and extends the range to the cell now under the finger,
    // so holding at an edge keeps growing the selection past the visible screen.
    LaunchedEffect(Unit) {
        snapshotFlow { edgeScrollVelocity }.collect { velocity ->
            if (velocity == 0f) return@collect
            while (isActive && edgeScrollVelocity != 0f) {
                gridState.scrollBy(edgeScrollVelocity)
                if (dragAnchorIndex >= 0) {
                    val keys = latestIndexByKey
                    val edgeCell = if (edgeScrollVelocity < 0f) {
                        gridState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String) in keys }
                    } else {
                        gridState.layoutInfo.visibleItemsInfo.lastOrNull { (it.key as? String) in keys }
                    }
                    val edgeIndex = (edgeCell?.key as? String)?.let { keys[it] }
                    if (edgeIndex != null && edgeIndex != dragLastIndex) {
                        dragLastIndex = edgeIndex
                        paintRange(min(dragAnchorIndex, edgeIndex), max(dragAnchorIndex, edgeIndex))
                    }
                }
                withFrameNanos {}
            }
        }
    }

    return Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                // Don't anchor a fresh drag while disabled (e.g. a multi-delete is running) — the
                // selection must stay frozen until the in-flight operation finishes.
                if (!latestEnabled) {
                    dragAnchorIndex = -1
                    return@detectDragGesturesAfterLongPress
                }
                val idx = itemIndexAt(offset)
                if (idx == null) {
                    dragAnchorIndex = -1
                    return@detectDragGesturesAfterLongPress
                }
                // Arm the guard at the anchor so the cell's release-tap (which fires before onDragEnd)
                // is skipped instead of toggling this just-selected cell back off.
                tapGuard?.value = true
                // Grabbing an unselected cell selects the swept range (extending the existing selection),
                // grabbing a selected one deselects it. Dragging back reverts the cells now uncovered.
                dragInitial = latestSelected
                dragSelect = latestItems[idx] !in latestSelected
                dragAnchorIndex = idx
                dragLastIndex = idx
                paintRange(idx, idx)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDrag = { change, _ ->
                if (dragAnchorIndex < 0) return@detectDragGesturesAfterLongPress
                change.consume()
                val current = itemIndexAt(change.position)
                if (current != null && current != dragLastIndex) {
                    dragLastIndex = current
                    paintRange(min(dragAnchorIndex, current), max(dragAnchorIndex, current))
                }
                // Edge auto-scroll so the range can run past the visible screen; the velocity loop above
                // applies it each frame. The pointer y is grid-local.
                val viewportHeight = gridState.layoutInfo.viewportSize.height.toFloat()
                val y = change.position.y
                edgeScrollVelocity = when {
                    y < edgeScrollPx -> -((edgeScrollPx - y) / edgeScrollPx) * 24f
                    y > viewportHeight - edgeScrollPx ->
                        ((y - (viewportHeight - edgeScrollPx)) / edgeScrollPx) * 24f
                    else -> 0f
                }
            },
            onDragEnd = {
                dragAnchorIndex = -1
                dragLastIndex = -1
                dragInitial = emptySet()
                edgeScrollVelocity = 0f
                tapGuard?.value = false
            },
            onDragCancel = {
                dragAnchorIndex = -1
                dragLastIndex = -1
                dragInitial = emptySet()
                edgeScrollVelocity = 0f
                tapGuard?.value = false
            },
        )
    }
}

/**
 * Staggered-grid twin of [rememberDragMultiSelectModifier] for the opt-in mosaic timeline. The
 * gesture logic is identical — long-press anchors, drag sweeps a contiguous range, holding at an
 * edge auto-scrolls — but it hit-tests against [LazyStaggeredGridState.layoutInfo], whose visible
 * items expose per-item `offset` / `size` just like the fixed grid. Under variable tile heights the
 * edge auto-scroll still lands on whatever cell sits under the finger, so a fling-past selection
 * degrades to "the visible edge cell" rather than a precise off-screen index, but never crashes.
 */
@Composable
fun <T> rememberStaggeredDragMultiSelectModifier(
    gridState: LazyStaggeredGridState,
    items: List<T>,
    indexByKey: Map<String, Int>,
    selected: Set<T>,
    onSelectionChange: (Set<T>) -> Unit,
    tapGuard: MutableState<Boolean>? = null,
    enabled: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val edgeScrollPx = remember(density) { with(density) { 80.dp.toPx() } }
    val latestEnabled by rememberUpdatedState(enabled)

    val latestSelected by rememberUpdatedState(selected)
    val latestItems by rememberUpdatedState(items)
    val latestIndexByKey by rememberUpdatedState(indexByKey)
    var dragSelect by remember { mutableStateOf(true) }
    var dragAnchorIndex by remember { mutableStateOf(-1) }
    var dragLastIndex by remember { mutableStateOf(-1) }
    var dragInitial by remember { mutableStateOf(emptySet<T>()) }
    var edgeScrollVelocity by remember { mutableFloatStateOf(0f) }

    val paintRange: (Int, Int) -> Unit = { lo, hi ->
        val current = latestItems
        val last = current.size - 1
        if (last >= 0) {
            val hiC = hi.coerceIn(0, last)
            val loC = lo.coerceIn(0, hiC)
            val range = current.subList(loC, hiC + 1).toSet()
            onSelectionChange(if (dragSelect) dragInitial + range else dragInitial - range)
        }
    }

    val itemIndexAt: (Offset) -> Int? = remember(Unit) {
        fun(offset: Offset): Int? {
            val startOffset = gridState.layoutInfo.viewportStartOffset
            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { cell ->
                val top = cell.offset.y - startOffset
                val left = cell.offset.x
                offset.y >= top && offset.y < top + cell.size.height &&
                    offset.x >= left && offset.x < left + cell.size.width
            } ?: return null
            val key = info.key as? String ?: return null
            return latestIndexByKey[key]
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { edgeScrollVelocity }.collect { velocity ->
            if (velocity == 0f) return@collect
            while (isActive && edgeScrollVelocity != 0f) {
                gridState.scrollBy(edgeScrollVelocity)
                if (dragAnchorIndex >= 0) {
                    val keys = latestIndexByKey
                    val edgeCell = if (edgeScrollVelocity < 0f) {
                        gridState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String) in keys }
                    } else {
                        gridState.layoutInfo.visibleItemsInfo.lastOrNull { (it.key as? String) in keys }
                    }
                    val edgeIndex = (edgeCell?.key as? String)?.let { keys[it] }
                    if (edgeIndex != null && edgeIndex != dragLastIndex) {
                        dragLastIndex = edgeIndex
                        paintRange(min(dragAnchorIndex, edgeIndex), max(dragAnchorIndex, edgeIndex))
                    }
                }
                withFrameNanos {}
            }
        }
    }

    return Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                // Don't anchor a fresh drag while disabled (e.g. a multi-delete is running) — the
                // selection must stay frozen until the in-flight operation finishes.
                if (!latestEnabled) {
                    dragAnchorIndex = -1
                    return@detectDragGesturesAfterLongPress
                }
                val idx = itemIndexAt(offset)
                if (idx == null) {
                    dragAnchorIndex = -1
                    return@detectDragGesturesAfterLongPress
                }
                // Arm the guard at the anchor so the cell's release-tap (which fires before onDragEnd)
                // is skipped instead of toggling this just-selected cell back off.
                tapGuard?.value = true
                dragInitial = latestSelected
                dragSelect = latestItems[idx] !in latestSelected
                dragAnchorIndex = idx
                dragLastIndex = idx
                paintRange(idx, idx)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDrag = { change, _ ->
                if (dragAnchorIndex < 0) return@detectDragGesturesAfterLongPress
                change.consume()
                val current = itemIndexAt(change.position)
                if (current != null && current != dragLastIndex) {
                    dragLastIndex = current
                    paintRange(min(dragAnchorIndex, current), max(dragAnchorIndex, current))
                }
                val viewportHeight = gridState.layoutInfo.viewportSize.height.toFloat()
                val y = change.position.y
                edgeScrollVelocity = when {
                    y < edgeScrollPx -> -((edgeScrollPx - y) / edgeScrollPx) * 24f
                    y > viewportHeight - edgeScrollPx ->
                        ((y - (viewportHeight - edgeScrollPx)) / edgeScrollPx) * 24f
                    else -> 0f
                }
            },
            onDragEnd = {
                dragAnchorIndex = -1
                dragLastIndex = -1
                dragInitial = emptySet()
                edgeScrollVelocity = 0f
                tapGuard?.value = false
            },
            onDragCancel = {
                dragAnchorIndex = -1
                dragLastIndex = -1
                dragInitial = emptySet()
                edgeScrollVelocity = 0f
                tapGuard?.value = false
            },
        )
    }
}
