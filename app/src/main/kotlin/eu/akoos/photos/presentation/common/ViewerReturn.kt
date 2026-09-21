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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * The photo the fullscreen viewer was showing when it closed, published for the grid underneath to
 * land on.
 *
 * Set only when the user actually paged somewhere: a viewer closed on the photo it opened leaves
 * the grid where it already restored itself, and moving it would drag that row to the top for
 * nothing. Cleared by the first grid that acts on it, so a later visit does not reposition someone
 * who has since scrolled away.
 */
val LocalViewerReturnKey = compositionLocalOf { mutableStateOf<String?>(null) }

/**
 * Position of the photo [key] identifies among a lazy grid's own items, or -1 when this grid does
 * not list it.
 *
 * Photos are rarely all a grid emits: a cover, a banner or a per-group header takes a whole row and
 * pushes every photo after it down a slot. [leadingSlots] counts the full-width items emitted ahead
 * of the first photo, and [groups] holds the photos exactly as the grid walks them, one header each
 * when [headerPerGroup]. A grid that emits one flat run passes a single group and no header.
 *
 * Counting through the groups rather than looking the photo up in a flat list is deliberate: it
 * mirrors the emission itself, so a grid whose source list happens to be ordered differently from
 * what it renders cannot quietly land the user on the wrong row.
 */
fun <T> photoSlotIndex(
    key: String,
    groups: List<List<T>>,
    headerPerGroup: Boolean,
    leadingSlots: Int,
    keyOf: (T) -> String,
): Int {
    if (leadingSlots < 0) return -1
    var slot = leadingSlots
    for (group in groups) {
        if (headerPerGroup) slot++
        for (item in group) {
            if (keyOf(item) == key) return slot
            slot++
        }
    }
    return -1
}

/**
 * Puts a fixed grid back on the photo the viewer closed on. See [photoSlotIndex] for what [groups],
 * [headerPerGroup] and [leadingSlots] have to describe.
 */
@Composable
fun <T> ReturnToViewerPhoto(
    gridState: LazyGridState,
    groups: List<List<T>>,
    headerPerGroup: Boolean,
    leadingSlots: Int = 0,
    keyOf: (T) -> String,
) {
    ReturnToViewerPhoto(
        pending = LocalViewerReturnKey.current,
        groups = groups,
        headerPerGroup = headerPerGroup,
        leadingSlots = leadingSlots,
        keyOf = keyOf,
        scrollToSlot = { slot -> gridState.scrollToItem(slot) },
    )
}

/** Mosaic variant of the above, for the staggered layout the timeline offers. */
@Composable
fun <T> ReturnToViewerPhoto(
    gridState: LazyStaggeredGridState,
    groups: List<List<T>>,
    headerPerGroup: Boolean,
    leadingSlots: Int = 0,
    keyOf: (T) -> String,
) {
    ReturnToViewerPhoto(
        pending = LocalViewerReturnKey.current,
        groups = groups,
        headerPerGroup = headerPerGroup,
        leadingSlots = leadingSlots,
        keyOf = keyOf,
        scrollToSlot = { slot -> gridState.scrollToItem(slot) },
    )
}

@Composable
private fun <T> ReturnToViewerPhoto(
    pending: MutableState<String?>,
    groups: List<List<T>>,
    headerPerGroup: Boolean,
    leadingSlots: Int,
    keyOf: (T) -> String,
    scrollToSlot: suspend (Int) -> Unit,
) {
    val key = pending.value ?: return
    val photoCount = groups.sumOf { it.size }
    // Keyed on the count as well as the request: navigating back re-runs the screen's load, so the
    // photos can arrive a frame or two late and the first attempt would search an empty grid.
    LaunchedEffect(key, photoCount) {
        val slot = photoSlotIndex(key, groups, headerPerGroup, leadingSlots, keyOf)
        if (slot < 0) {
            // Let an empty grid try again on the next emission. A grid that does have photos but
            // not this one has answered the question, so the request is dropped.
            if (photoCount > 0) pending.value = null
            return@LaunchedEffect
        }
        // Jump, never animate: the grid has not drawn yet on the frame it comes back, so landing
        // there directly is what makes the position look like it was never lost.
        scrollToSlot(slot)
        // Cleared last. Clearing first would recompose this away and cancel the scroll with it.
        pending.value = null
    }
}
