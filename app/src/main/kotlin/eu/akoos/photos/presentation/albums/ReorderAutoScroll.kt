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

package eu.akoos.photos.presentation.albums

/**
 * Pixels per second the albums grid should scroll while a card is held with its pointer at
 * [pointerY], negative towards the top of the list. Zero between the two bands, so a card parked in
 * the middle of the window stays where it was put.
 *
 * [contentTop] and [contentBottom] are the edges of the grid's content window rather than of the
 * whole viewport. The strips the grid pads for the floating header and for the bottom bar are as
 * good as past the edge for a finger, so a pointer out there pulls at [maxVelocity] instead of
 * flipping sign or running off the scale.
 *
 * The ramp inside a band is linear: grazing it creeps, the last pixel before the edge runs flat out.
 * Speed has to read as a consequence of where the finger is, or a list cannot be stopped on the row
 * the user is aiming for.
 *
 * A [band] deeper than half the window would leave the two sides fighting over the middle, so it is
 * clamped to half and the ramps meet rather than cross. A window with no room left — nothing on
 * screen to scroll within — yields no pull at all.
 */
internal fun edgeScrollVelocity(
    pointerY: Float,
    contentTop: Float,
    contentBottom: Float,
    band: Float,
    maxVelocity: Float,
): Float {
    val depth = band.coerceAtMost((contentBottom - contentTop) / 2f)
    if (depth <= 0f) return 0f
    val intoTop = depth - (pointerY - contentTop)
    if (intoTop > 0f) return -maxVelocity * (intoTop / depth).coerceAtMost(1f)
    val intoBottom = depth - (contentBottom - pointerY)
    if (intoBottom > 0f) return maxVelocity * (intoBottom / depth).coerceAtMost(1f)
    return 0f
}
