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

import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The least importance (in degrees) a point needs to be drawn at detail level 0, 1, 2 and so on. */
internal val DETAIL_LEVEL_TOLERANCE_DEG = floatArrayOf(0.30f, 0.12f, 0.05f, 0.02f, 0.01f, 0.005f, 0f)

/**
 * The detail level for a globe of [radiusPx]: the coarsest one whose simplification stays under [pixels] pixels.
 * A thin line looks the same at a couple of pixels, and each level skipped is far fewer points to draw.
 */
internal fun detailLevelFor(radiusPx: Double, pixels: Double = SETTLED_TOLERANCE_PX): Int {
    val toleranceDeg = pixels * DEGREES_PER_RADIAN / radiusPx
    val level = DETAIL_LEVEL_TOLERANCE_DEG.indexOfFirst { it <= toleranceDeg }
    return if (level < 0) DETAIL_LEVEL_TOLERANCE_DEG.lastIndex else level
}

/** How much simplification, in pixels, a still picture and a moving one put up with. */
internal const val SETTLED_TOLERANCE_PX = 2.0
internal const val MOVING_TOLERANCE_PX = 5.0
private const val DEGREES_PER_RADIAN = 57.29578

/** The map zoom (a world 256 dp wide at zoom 0) the globe's size corresponds to, which place names are keyed to. */
internal fun labelZoomFor(radiusPx: Double, density: Float): Double =
    ln(2.0 * PI * radiusPx / density / 256.0) / ln(2.0)

/**
 * What one frame of the globe needs to know about the view, worked out once: where it is centred, how large the
 * globe is, how much of it the screen can show, and the screen rectangle (with a margin) that outlines are trimmed to.
 */
internal class GlobeView(
    val cx: Float,
    val cy: Float,
    val radius: Double,
    cLat: Double,
    cLng: Double,
    val width: Float,
    val height: Float,
) {
    val cLatDeg = cLat
    val cLngDeg = cLng
    val sinC: Double = sin(Math.toRadians(cLat))
    val cosC: Double = cos(Math.toRadians(cLat))
    val cLngRad: Double = Math.toRadians(cLng)
    val sinL: Double = sin(cLngRad)
    val cosL: Double = cos(cLngRad)

    /** Half the screen's diagonal in pixels. */
    val halfDiagonal: Double = hypot(width.toDouble(), height.toDouble()) / 2.0

    /** The angle from the centre of the globe to the farthest part the screen shows (a hemisphere at most). */
    val seenRad: Double = if (radius <= halfDiagonal) PI / 2.0 else min(PI / 2.0, asin(halfDiagonal / radius))
}

/**
 * Whether [ring] can reach the screen: its box is compared with the part of the globe the screen shows, so the
 * outlines far off to the side or round the back are skipped before any point is projected.
 */
internal fun ringMayShow(ring: DetailRing, view: GlobeView, minPixels: Double = 1.0): Boolean {
    val cosDistance = (view.sinC * ring.midLatSin +
        view.cosC * ring.midLatCos * cos(ring.midLngRad - view.cLngRad)).coerceIn(-1.0, 1.0)
    // Smaller than [minPixels]: not worth drawing.
    if (ring.reachRad * 2.0 * view.radius < minPixels) return false
    return acos(cosDistance) <= view.seenRad + ring.reachRad
}

private const val OUT_LEFT = 1
private const val OUT_RIGHT = 2
private const val OUT_TOP = 4
private const val OUT_BOTTOM = 8

private fun outsideCode(x: Float, y: Float, view: GlobeView, margin: Float): Int {
    var code = 0
    if (x < -margin) code = code or OUT_LEFT else if (x > view.width + margin) code = code or OUT_RIGHT
    if (y < -margin) code = code or OUT_TOP else if (y > view.height + margin) code = code or OUT_BOTTOM
    return code
}

/**
 * Appends [ring] at detail [maxLevel] to [path], as a filled outline when [fill] (far-side points are clamped to
 * the limb) or as a line broken at the far side. A run of points that all lie beyond the same side of the screen
 * is reduced to its first and last point: the straight line between them stays outside the screen too, so what
 * shows is unchanged, but a large outline zoomed far in costs only the points near the screen.
 */
private fun appendDetail(path: Path, ring: DetailRing, maxLevel: Int, view: GlobeView, fill: Boolean) {
    val margin = TRIM_MARGIN_PX
    val radius = view.radius
    val trig = ring.trig()
    var started = false
    var runMask = 0
    var hasPending = false
    var pendingX = 0f
    var pendingY = 0f
    for (i in ring.lat.indices) {
        if (ring.level[i] > maxLevel) continue
        val sinLat = trig.sinLat[i].toDouble()
        val cosLat = trig.cosLat[i].toDouble()
        val sinD = trig.sinLng[i] * view.cosL - trig.cosLng[i] * view.sinL
        val cosD = trig.cosLng[i] * view.cosL + trig.sinLng[i] * view.sinL
        val east = cosLat * sinD
        val north = view.cosC * sinLat - view.sinC * cosLat * cosD
        val visible = view.sinC * sinLat + view.cosC * cosLat * cosD >= 0.0
        val x: Float
        val y: Float
        if (visible) {
            x = view.cx + (radius * east).toFloat()
            y = view.cy - (radius * north).toFloat()
        } else if (fill) {
            // A point round the back sits on the rim, in its own direction from the centre.
            val length = hypot(east, north)
            if (length == 0.0) continue
            x = view.cx + (radius * east / length).toFloat()
            y = view.cy - (radius * north / length).toFloat()
        } else {
            if (hasPending) {
                path.lineTo(pendingX, pendingY)
                hasPending = false
            }
            started = false
            runMask = 0
            continue
        }
        val code = outsideCode(x, y, view, margin)
        if (code != 0 && runMask != 0 && (code and runMask) != 0) {
            runMask = runMask and code
            pendingX = x
            pendingY = y
            hasPending = true
            continue
        }
        if (hasPending) {
            path.lineTo(pendingX, pendingY)
            hasPending = false
        }
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
        runMask = code
    }
    if (hasPending) path.lineTo(pendingX, pendingY)
    if (fill && started) path.close()
}

// How far beyond the screen an outline is still drawn in full before its off-screen stretches are trimmed.
private const val TRIM_MARGIN_PX = 40f

/** Append [ring]'s fill to [path] at detail [maxLevel]. */
internal fun appendDetailFill(path: Path, ring: DetailRing, maxLevel: Int, view: GlobeView) =
    appendDetail(path, ring, maxLevel, view, fill = true)

/** Append [ring]'s outline to [path] at detail [maxLevel]. */
internal fun appendDetailBorder(path: Path, ring: DetailRing, maxLevel: Int, view: GlobeView) =
    appendDetail(path, ring, maxLevel, view, fill = false)

/**
 * The fewest inhabitants a place needs for its name to show in the middle of the screen at map zoom [zoom]: only
 * the biggest cities from far out, ever smaller towns as the globe is zoomed in.
 */
internal fun populationForLabel(zoom: Double): Double = 10.0.pow(PLACE_POPULATION_BASE - PLACE_POPULATION_SLOPE * zoom)

/**
 * The same threshold [fromCentre] of the way to the screen's corner (0 in the middle, 1 at the screen's edge
 * along the diagonal): it rises towards the edge, so the middle shows the most names and the edge only the big ones.
 */
internal fun populationForLabelAt(zoom: Double, fromCentre: Double): Double =
    populationForLabel(zoom) * (1.0 + PLACE_EDGE_FACTOR * fromCentre * fromCentre)

private const val PLACE_POPULATION_BASE = 7.6
private const val PLACE_POPULATION_SLOPE = 0.55
private const val PLACE_EDGE_FACTOR = 14.0

/** The ISO code of the highlighted country a tap lands in, found by turning the tap back into a coordinate. */
internal fun countryAtDetail(
    tapX: Float, tapY: Float, cx: Float, cy: Float, radius: Float,
    cLat: Double, cLng: Double, detail: WorldDetail, hotOwner: BooleanArray,
): String? {
    val dx = (tapX - cx).toDouble()
    val dy = -(tapY - cy).toDouble()
    val rho = sqrt(dx * dx + dy * dy)
    if (rho > radius || rho == 0.0) return null
    val c = asin((rho / radius).coerceIn(-1.0, 1.0))
    val cLatR = Math.toRadians(cLat)
    val lat = Math.toDegrees(asin(cos(c) * sin(cLatR) + dy * sin(c) * cos(cLatR) / rho))
    val lng = cLng + Math.toDegrees(atan2(dx * sin(c), rho * cos(c) * cos(cLatR) - dy * sin(c) * sin(cLatR)))
    for (ring in detail.countryRings) {
        if (!hotOwner[ring.owner]) continue
        if (lat < ring.minLat || lat > ring.maxLat || lng < ring.minLng || lng > ring.maxLng) continue
        if (pointInDetailRing(lat, lng, ring, TAP_LEVEL)) return detail.countries[ring.owner].iso2
    }
    return null
}

// The detail level a tap is tested against: smooth enough to follow a border, light enough for a single tap.
private const val TAP_LEVEL = 3

internal fun pointInDetailRing(lat: Double, lng: Double, ring: DetailRing, maxLevel: Int): Boolean {
    var inside = false
    var previous = -1
    for (i in ring.lat.indices) {
        if (ring.level[i] > maxLevel) continue
        if (previous >= 0) {
            val aLat = ring.lat[i].toDouble(); val aLng = ring.lng[i].toDouble()
            val bLat = ring.lat[previous].toDouble(); val bLng = ring.lng[previous].toDouble()
            if ((aLat > lat) != (bLat > lat)) {
                val x = (bLng - aLng) * (lat - aLat) / (bLat - aLat) + aLng
                if (lng < x) inside = !inside
            }
        }
        previous = i
    }
    return inside
}
