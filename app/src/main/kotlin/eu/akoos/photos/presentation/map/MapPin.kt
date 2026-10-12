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

package eu.akoos.photos.presentation.map

import eu.akoos.photos.domain.entity.GalleryItem

/**
 * One geotagged fix ready to plot: the raw coordinates plus the [GalleryItem] its id resolved to in
 * the merged library, so the marker has a thumbnail source. [item] is null when the fix has no
 * matching library entry yet (the merge hasn't reached it), in which case the pin keeps the
 * placeholder. The thumbnail loads through the same Coil path as the gallery — `photoCellInputsFor`
 * routes a local uri or a cloud thumbnail into the request — so cloud pins decrypt and show like any
 * other cell. The coordinates drive the tap → location-detail drawer.
 */
data class MapPin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val item: GalleryItem?,
)

/** The most pins a map plots at once: a large located library stays cheap, each pin being a decoded thumbnail. */
internal const val MARKER_CAP = 150

/**
 * The pins both maps plot: all of them up to [MARKER_CAP], else an evenly spaced subset so the spread still reads.
 * One rule for both maps, so a place shows the same pins on either.
 */
internal fun samplePins(pins: List<MapPin>): List<MapPin> {
    if (pins.size <= MARKER_CAP) return pins
    val step = pins.size.toDouble() / MARKER_CAP
    return (0 until MARKER_CAP).map { pins[(it * step).toInt()] }
}
