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

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The radius, in pixels, of the globe at [zoom] on a screen [width] by [height]. */
internal fun globeRadius(width: Float, height: Float, zoom: Float): Float = min(width, height) / 2f * 0.92f * zoom

/** How many degrees of the globe one pixel covers at its centre. */
internal fun degreesPerPixel(radiusPx: Double): Double = DEGREES_PER_RADIAN_EXACT / radiusPx

private const val DEGREES_PER_RADIAN_EXACT = 57.29577951308232

/** The latitude and longitude under the screen point ([x], [y]), or null when it is off the globe. */
internal fun geoAt(
    x: Float, y: Float, cx: Float, cy: Float, radius: Double, cLat: Double, cLng: Double,
): Pair<Double, Double>? {
    val dx = (x - cx).toDouble()
    val dy = -(y - cy).toDouble()
    val rho = sqrt(dx * dx + dy * dy)
    if (rho > radius) return null
    if (rho == 0.0) return cLat to cLng
    val c = asin((rho / radius).coerceIn(-1.0, 1.0))
    val cLatR = Math.toRadians(cLat)
    val lat = Math.toDegrees(asin(cos(c) * sin(cLatR) + dy * sin(c) * cos(cLatR) / rho))
    val lng = cLng + Math.toDegrees(atan2(dx * sin(c), rho * cos(c) * cos(cLatR) - dy * sin(c) * sin(cLatR)))
    return lat to lng
}

/**
 * Where to put the centre of a globe of [radius] so that the point ([lat], [lng]) appears at the screen point
 * ([targetX], [targetY]). This is what keeps the spot under the fingers where it is while the globe is dragged or
 * pinched: the centre is moved a little at a time until the point lands within a pixel. Null when it does not get
 * there (a spot near the edge of the globe, where a small turn moves it a long way), so the caller turns the globe by
 * the finger's travel instead.
 */
internal fun centerKeepingPoint(
    lat: Double, lng: Double, targetX: Float, targetY: Float, cx: Float, cy: Float, radius: Double,
    startLat: Double, startLng: Double,
): Pair<Double, Double>? {
    var cLat = startLat
    var cLng = startLng
    val cosPoint = max(0.05, cos(Math.toRadians(lat)))
    repeat(CENTER_SOLVE_STEPS) {
        val projected = GlobeProjection.project(lat, lng, cLat, cLng, radius)
        if (!projected.visible) return null
        val errorX = targetX - (cx + projected.x)
        val errorY = targetY - (cy + projected.y)
        if (hypot(errorX.toDouble(), errorY.toDouble()) < CENTER_SOLVE_TOLERANCE_PX) return cLat to cLng
        cLat = (cLat + Math.toDegrees(errorY / radius)).coerceIn(-MAX_CENTER_LAT, MAX_CENTER_LAT)
        cLng -= Math.toDegrees(errorX / (radius * cosPoint))
    }
    val last = GlobeProjection.project(lat, lng, cLat, cLng, radius)
    val missed = hypot((targetX - (cx + last.x)).toDouble(), (targetY - (cy + last.y)).toDouble())
    return if (last.visible && missed < CENTER_ACCEPT_PX) cLat to cLng else null
}

private const val CENTER_SOLVE_STEPS = 10
private const val CENTER_SOLVE_TOLERANCE_PX = 0.25
private const val CENTER_ACCEPT_PX = 1.0
internal const val MAX_CENTER_LAT = 85.0
