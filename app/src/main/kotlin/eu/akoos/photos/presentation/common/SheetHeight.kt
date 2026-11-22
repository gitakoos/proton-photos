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

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** A tall sheet's content at [fraction] of its keyboard-closed room, so the keyboard shrinks it instead of lifting the sheet. */
fun Modifier.tallSheetHeight(fraction: Float): Modifier = composed {
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    // Read during layout, in the same frame as the sheet's own keyboard padding, so the two never step apart.
    layout { measurable, constraints ->
        if (constraints.maxHeight == Constraints.Infinity) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val height = tallSheetHeightPx(constraints.maxHeight, fraction, ime.getBottom(this), navigationBars.getBottom(this))
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** [maxHeight] already lacks the keyboard's lift above the navigation bar, so it is added back before the fraction and taken off after. */
internal fun tallSheetHeightPx(maxHeight: Int, fraction: Float, imeBottom: Int, navBottom: Int): Int {
    val keyboardLift = (imeBottom - navBottom).coerceAtLeast(0)
    val height = (maxHeight + keyboardLift) * fraction - keyboardLift
    return height.roundToInt().coerceIn(0, maxHeight)
}
