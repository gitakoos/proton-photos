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

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A geographic point projected onto the globe: pixel offsets from the globe centre, and whether it
 *  sits on the near hemisphere (a far-side point is behind the sphere and should be culled). */
data class GlobePoint(val x: Float, val y: Float, val visible: Boolean)

/**
 * Orthographic projection of geographic coordinates onto a sphere seen from infinitely far away, the
 * projection a rotating globe draws with. The sphere is turned so ([centerLatDeg], [centerLngDeg]) faces
 * the viewer; a point whose angular distance from that centre exceeds 90 degrees is on the far
 * hemisphere and returns [GlobePoint.visible] = false so the caller can drop it. Offsets are in pixels
 * from the globe centre for a sphere of [radiusPx]. Screen y grows downward, so the returned y is
 * already negated from the mathematical convention. Pure and deterministic.
 */
object GlobeProjection {

    fun project(
        latDeg: Double,
        lngDeg: Double,
        centerLatDeg: Double,
        centerLngDeg: Double,
        radiusPx: Double,
    ): GlobePoint {
        val lat = Math.toRadians(latDeg)
        val lng = Math.toRadians(lngDeg)
        val cLat = Math.toRadians(centerLatDeg)
        val cLng = Math.toRadians(centerLngDeg)
        val dLng = lng - cLng
        // cos of the angular distance between the point and the globe centre; negative = far side.
        val cosC = sin(cLat) * sin(lat) + cos(cLat) * cos(lat) * cos(dLng)
        val x = radiusPx * cos(lat) * sin(dLng)
        val y = radiusPx * (cos(cLat) * sin(lat) - sin(cLat) * cos(lat) * cos(dLng))
        return GlobePoint(x.toFloat(), (-y).toFloat(), cosC >= 0.0)
    }

    /**
     * Compass bearing (radians, 0 = north, clockwise) from the globe centre to a point. A far-side
     * vertex has no honest position on the near disc, so a fill clamps it to the limb at this bearing:
     * (radiusPx * sin(bearing), -radiusPx * cos(bearing)) from the centre, which meets the visible edge
     * exactly where a limb point of the same bearing projects. Pure.
     */
    fun bearing(
        latDeg: Double,
        lngDeg: Double,
        centerLatDeg: Double,
        centerLngDeg: Double,
    ): Double {
        val lat = Math.toRadians(latDeg)
        val cLat = Math.toRadians(centerLatDeg)
        val dLng = Math.toRadians(lngDeg) - Math.toRadians(centerLngDeg)
        return atan2(cos(lat) * sin(dLng), cos(cLat) * sin(lat) - sin(cLat) * cos(lat) * cos(dLng))
    }
}
