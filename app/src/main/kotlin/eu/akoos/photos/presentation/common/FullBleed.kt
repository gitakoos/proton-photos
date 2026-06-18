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

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * Renders content edge-to-edge inside a parent that applies [horizontalPadding] of side padding —
 * e.g. a full-span item in a LazyVerticalGrid whose contentPadding insets every item. The content is
 * measured at the full width (its constrained width plus 2 × [horizontalPadding]) and offset back
 * into the side inset, so a cover banner can span the screen while the rest of the grid stays inset.
 */
fun Modifier.fullBleedHorizontal(horizontalPadding: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontalPadding * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-(extra / 2), 0)
    }
}
