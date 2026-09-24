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

package eu.akoos.photos.presentation.common

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import eu.akoos.photos.presentation.albums.AlbumGroupingLadder
import eu.akoos.photos.presentation.albums.AlbumPhotoGrouping
import eu.akoos.photos.presentation.gallery.rememberGridPinchZoomModifier

/** A lazy grid item as laid out: its key, its position among the grid's items, and how far its top
 *  sits below the top of the grid, in pixels. */
data class VisibleGridItem(val key: Any, val index: Int, val offset: Int)

/**
 * The photo a change of date headers holds still: its cell key, and how far its row is scrolled past
 * the top of the grid, as [LazyGridState.requestScrollToItem] takes it. Negative for a row that sits
 * below the top, as one under a header does.
 */
data class RegroupAnchor(val key: Any, val scrollOffset: Int)

/**
 * The photo to hold still while a grid's date headers change: the first photo in [visible], or null
 * while one of the [leadingSlots] items (the hero cover) is on screen, or no photo is.
 *
 * A header cannot be held: new headers carry new keys, and a grid that loses its first item's key
 * keeps that item's old index, which points at another photo once the headers change. A leading item
 * keeps its key and its index, so the grid already stays on it. [isPhoto] tells a photo cell's key
 * from a header's; it is asked only until the first photo is found.
 */
fun regroupAnchor(
    visible: List<VisibleGridItem>,
    leadingSlots: Int,
    isPhoto: (key: Any) -> Boolean,
): RegroupAnchor? {
    val inOrder = visible.sortedBy { it.index }
    if (inOrder.isEmpty() || inOrder.first().index < leadingSlots) return null
    val photo = inOrder.firstOrNull { isPhoto(it.key) } ?: return null
    return RegroupAnchor(photo.key, -photo.offset)
}

/**
 * Two-finger pinch that steps a photo grid's date headers through [AlbumGroupingLadder] and leaves
 * its column count alone: [rememberGridPinchZoomModifier] with one level per grouping. Apply it after
 * the drag-select modifier, as the timeline does.
 *
 * [grouping] is the grouping on screen, and [onGroupingChange] receives each step. [groups],
 * [leadingSlots] and [keyOf] describe the grid as [photoSlotIndex] reads it, one header per group,
 * with [keyOf] giving the key each photo's cell is emitted under. The first photo on screen when a
 * step lands is put back at the same height once the new headers are laid out.
 */
@Composable
fun <T> rememberGroupingPinchModifier(
    gridState: LazyGridState,
    grouping: AlbumPhotoGrouping,
    onGroupingChange: (AlbumPhotoGrouping) -> Unit,
    groups: List<List<T>>,
    leadingSlots: Int,
    keyOf: (T) -> String,
): Modifier {
    val slotOf = { key: Any ->
        if (key !is String) -1
        else photoSlotIndex(key, groups, headerPerGroup = true, leadingSlots = leadingSlots, keyOf = keyOf)
    }
    // Written by the gesture and read only after composition, so it never recomposes the screen.
    val pending = remember { mutableStateOf<PendingRegroup?>(null) }
    SideEffect {
        val request = pending.value ?: return@SideEffect
        pending.value = null
        if (request.grouping != grouping) return@SideEffect
        val slot = slotOf(request.anchor.key)
        if (slot >= 0) gridState.requestScrollToItem(slot, request.anchor.scrollOffset)
    }
    val step by rememberUpdatedState<(Int) -> Unit> { level ->
        val next = AlbumGroupingLadder.atLevel(level)
        if (next != grouping) {
            val visible = gridState.layoutInfo.visibleItemsInfo.map {
                VisibleGridItem(it.key, it.index, it.offset.y)
            }
            pending.value = regroupAnchor(visible, leadingSlots) { slotOf(it) >= 0 }
                ?.let { PendingRegroup(next, it) }
            onGroupingChange(next)
        }
    }
    return rememberGridPinchZoomModifier(
        levelIndex = AlbumGroupingLadder.levelOf(grouping),
        levelCount = AlbumGroupingLadder.levels.size,
        onStep = { step(it) },
    )
}

private class PendingRegroup(val grouping: AlbumPhotoGrouping, val anchor: RegroupAnchor)
