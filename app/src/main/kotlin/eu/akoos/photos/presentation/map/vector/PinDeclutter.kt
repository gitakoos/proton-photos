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


package eu.akoos.photos.presentation.map.vector

/**
 * One city's footprint on screen: [x] and [y] are the spot on the globe (the pin's tip), [halfWidth] how far
 * the pin or its name reaches to either side, [above] how far the pin rises over the spot and [below] how far
 * the name hangs under it, all in pixels.
 */
internal class PinFootprint(
    val x: Float,
    val y: Float,
    val halfWidth: Float,
    val above: Float,
    val below: Float,
)

/**
 * Which of the [footprints] (given most important first) are drawn in full. A city is kept when its box,
 * padded by [gap], touches none already kept, so the busiest places claim their room first and the rest
 * show as plain dots until a zoom gives them space. The answer depends only on the screen positions and the
 * order, so drawing and tapping agree on what is shown.
 */
internal fun keptPins(footprints: List<PinFootprint>, gap: Float = 4f): BooleanArray {
    val kept = BooleanArray(footprints.size)
    val keptIndexes = ArrayList<Int>()
    for (i in footprints.indices) {
        val a = footprints[i]
        val clash = keptIndexes.any { j ->
            val b = footprints[j]
            a.x - a.halfWidth < b.x + b.halfWidth + gap &&
                a.x + a.halfWidth + gap > b.x - b.halfWidth &&
                a.y - a.above < b.y + b.below + gap &&
                a.y + a.below + gap > b.y - b.above
        }
        if (!clash) {
            kept[i] = true
            keptIndexes += i
        }
    }
    return kept
}
