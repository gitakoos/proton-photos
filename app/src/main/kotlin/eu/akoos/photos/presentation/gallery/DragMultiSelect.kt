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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 */
@Composable
fun <T> rememberDragMultiSelectModifier(
    gridState: LazyGridState,
    items: List<T>,
    indexByKey: Map<String, Int>,
    selected: Set<T>,
    onSelectionChange: (Set<T>) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val edgeScrollPx = remember(density) { with(density) { 80.dp.toPx() } }

    // Read through an updated-state holder: the pointerInput block is keyed on `items`, so without this
    // it would snapshot a stale selection and a re-grab would wipe whatever was selected since.
    val latestSelected by rememberUpdatedState(selected)
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
        val last = items.size - 1
        if (last >= 0) {
            val hiC = hi.coerceIn(0, last)
            val loC = lo.coerceIn(0, hiC)
            val range = items.subList(loC, hiC + 1).toSet()
            onSelectionChange(if (dragSelect) dragInitial + range else dragInitial - range)
        }
    }

    // LazyGridItemInfo.offset is relative to the content start (after the top contentPadding); the
    // pointer offset is node-local (0 = node top). viewportStartOffset is exactly -(top contentPadding),
    // so subtracting it converts each cell into the pointer's space — read from layoutInfo it is exact
    // and scroll-invariant. (A hand-passed padding was off by a band; dropping it entirely then drifted
    // the hit a full row on the timeline, whose top padding is large under the floating header.)
    val itemIndexAt: (Offset) -> Int? = remember(items) {
        fun(offset: Offset): Int? {
            val startOffset = gridState.layoutInfo.viewportStartOffset
            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { cell ->
                val top = cell.offset.y - startOffset
                val left = cell.offset.x
                offset.y >= top && offset.y < top + cell.size.height &&
                    offset.x >= left && offset.x < left + cell.size.width
            } ?: return null
            val key = info.key as? String ?: return null
            return indexByKey[key]
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
                    val edgeCell = if (edgeScrollVelocity < 0f) {
                        gridState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String) in indexByKey }
                    } else {
                        gridState.layoutInfo.visibleItemsInfo.lastOrNull { (it.key as? String) in indexByKey }
                    }
                    val edgeIndex = (edgeCell?.key as? String)?.let { indexByKey[it] }
                    if (edgeIndex != null && edgeIndex != dragLastIndex) {
                        dragLastIndex = edgeIndex
                        paintRange(min(dragAnchorIndex, edgeIndex), max(dragAnchorIndex, edgeIndex))
                    }
                }
                withFrameNanos {}
            }
        }
    }

    return Modifier.pointerInput(items) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val idx = itemIndexAt(offset)
                if (idx == null) {
                    dragAnchorIndex = -1
                    return@detectDragGesturesAfterLongPress
                }
                // Grabbing an unselected cell selects the swept range (extending the existing selection),
                // grabbing a selected one deselects it. Dragging back reverts the cells now uncovered.
                dragInitial = latestSelected
                dragSelect = items[idx] !in latestSelected
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
            },
            onDragCancel = {
                dragAnchorIndex = -1
                dragLastIndex = -1
                dragInitial = emptySet()
                edgeScrollVelocity = 0f
            },
        )
    }
}
