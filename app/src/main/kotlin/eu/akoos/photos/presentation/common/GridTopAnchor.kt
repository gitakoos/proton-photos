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
import androidx.compose.runtime.remember

/**
 * Whether a grid whose first item changed from [previousFirstKey] to [firstKey] goes back to its first
 * row. After a stretch of listing nothing it does when the set that came back starts with another item
 * than [lastListedKey], the last one listed, so a new set never opens where an earlier one was left,
 * while the same set coming back (a search feed that blanks and refills on its restart) keeps its
 * place. Otherwise it does only when it sat exactly at the top, so a reader scrolled into the list
 * stays where they are.
 */
internal fun shouldReturnToTop(
    previousFirstKey: Any?,
    lastListedKey: Any?,
    firstKey: Any,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
): Boolean =
    if (previousFirstKey == null) firstKey != lastListedKey
    else firstVisibleIndex == 0 && firstVisibleOffset == 0

/**
 * Keeps a lazy grid on its first row when items land ahead of the one at the top.
 *
 * A lazy grid holds on to the key of its first visible item, so items that arrive ahead of it are laid
 * out above the viewport, in the top content padding under a floating header, until the user scrolls
 * back. [firstKey] is the key of the grid's first item, null while it lists nothing. When it changes,
 * the grid is asked for its first row on the very remeasure that applies the change, so the new rows
 * never draw out of view. The first composition only records the key: a grid restored on return from
 * the viewer or after a rotation keeps its position.
 */
@Composable
fun KeepGridAtTopOnChange(gridState: LazyGridState, firstKey: Any?) {
    val seen = remember { FirstKeyHolder(firstKey) }
    // Read after composition and before layout, where the grid still reports the position it had
    // before the change. A read in the composition body would recompose the caller on every scroll.
    SideEffect {
        val previous = seen.key
        if (previous == firstKey) return@SideEffect
        seen.key = firstKey
        if (firstKey == null) return@SideEffect
        if (shouldReturnToTop(
                previous, seen.lastListed, firstKey,
                gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
            )
        ) {
            gridState.requestScrollToItem(0)
        }
        seen.lastListed = firstKey
    }
}

private class FirstKeyHolder(var key: Any?) {
    var lastListed: Any? = key
}
