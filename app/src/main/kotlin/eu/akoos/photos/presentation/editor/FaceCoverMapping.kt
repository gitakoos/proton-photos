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

package eu.akoos.photos.presentation.editor

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A point in pixel space. */
internal data class PlanePoint(val x: Double, val y: Double)

/** A box in render pixels. */
internal data class RenderBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * A plane projective transform as a row-major 3x3 matrix, the layout android.graphics.Matrix.setValues
 * reads: (x, y) maps to ((m0 x + m1 y + m2) / w, (m3 x + m4 y + m5) / w) with w = m6 x + m7 y + m8.
 * Plain Kotlin, so the geometry the photo render moves pixels with is verified off-device.
 */
internal class PlaneTransform private constructor(private val m: DoubleArray) {

    /** This transform, then [next]. */
    fun then(next: PlaneTransform): PlaneTransform = PlaneTransform(multiply(next.m, m))

    /** Where ([x], [y]) lands, or null for a point on or past a perspective warp's vanishing line. */
    fun map(x: Double, y: Double): PlanePoint? {
        val w = m[6] * x + m[7] * y + m[8]
        if (!(w > MIN_W)) return null
        return PlanePoint((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** The nine values in the order android.graphics.Matrix.setValues reads them. */
    fun values(): FloatArray = FloatArray(9) { m[it].toFloat() }

    companion object {
        private const val MIN_W = 1e-9

        val IDENTITY = PlaneTransform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translate(dx: Double, dy: Double): PlaneTransform =
            PlaneTransform(doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0))

        /** Scales by [sx] / [sy] about ([px], [py]); a factor of -1 mirrors across that point. */
        fun scale(sx: Double, sy: Double, px: Double = 0.0, py: Double = 0.0): PlaneTransform =
            PlaneTransform(doubleArrayOf(sx, 0.0, px - sx * px, 0.0, sy, py - sy * py, 0.0, 0.0, 1.0))

        /**
         * Turns by [degrees] about ([px], [py]), clockwise on screen where y points down, the way
         * android.graphics.Matrix.postRotate turns. Quarter turns are exact, so they move pixels by whole
         * pixels with no rounding creep.
         */
        fun rotate(degrees: Double, px: Double = 0.0, py: Double = 0.0): PlaneTransform {
            val (c, s) = when (((degrees % 360.0) + 360.0) % 360.0) {
                0.0 -> 1.0 to 0.0
                90.0 -> 0.0 to 1.0
                180.0 -> -1.0 to 0.0
                270.0 -> 0.0 to -1.0
                else -> Math.toRadians(degrees).let { cos(it) to sin(it) }
            }
            return PlaneTransform(doubleArrayOf(
                c, -s, px - c * px + s * py,
                s, c, py - s * px - c * py,
                0.0, 0.0, 1.0,
            ))
        }

        /**
         * The warp that moves the four [src] corners onto the four [dst] corners (x, y pairs, in the same
         * winding), the one android.graphics.Matrix.setPolyToPoly builds from four points. Four point
         * pairs fix a perspective warp uniquely, so both land every point in the same place. Null when a
         * quad is degenerate.
         */
        fun quadToQuad(src: FloatArray, dst: FloatArray): PlaneTransform? {
            val fromSquare = squareToQuad(src) ?: return null
            val toSquare = invert(fromSquare) ?: return null
            val toQuad = squareToQuad(dst) ?: return null
            return PlaneTransform(multiply(toQuad, toSquare))
        }

        /** The warp taking the unit square's corners (0,0), (1,0), (1,1), (0,1) onto quad [q]. */
        private fun squareToQuad(q: FloatArray): DoubleArray? {
            val x0 = q[0].toDouble(); val y0 = q[1].toDouble()
            val x1 = q[2].toDouble(); val y1 = q[3].toDouble()
            val x2 = q[4].toDouble(); val y2 = q[5].toDouble()
            val x3 = q[6].toDouble(); val y3 = q[7].toDouble()
            val sumX = x0 - x1 + x2 - x3
            val sumY = y0 - y1 + y2 - y3
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            if (det == 0.0 || !det.isFinite()) return null
            // A parallelogram gives g = h = 0, the affine case.
            val g = (sumX * dy2 - dx2 * sumY) / det
            val h = (dx1 * sumY - sumX * dy1) / det
            return doubleArrayOf(
                x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                g, h, 1.0,
            )
        }

        private fun invert(m: DoubleArray): DoubleArray? {
            val c00 = m[4] * m[8] - m[5] * m[7]
            val c01 = m[5] * m[6] - m[3] * m[8]
            val c02 = m[3] * m[7] - m[4] * m[6]
            val det = m[0] * c00 + m[1] * c01 + m[2] * c02
            if (det == 0.0 || !det.isFinite()) return null
            val adjugate = doubleArrayOf(
                c00, m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
                c01, m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
                c02, m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
            )
            return DoubleArray(9) { adjugate[it] / det }
        }

        /** Row-major [p] x [q]: [q] applies first. */
        private fun multiply(p: DoubleArray, q: DoubleArray): DoubleArray = DoubleArray(9) { k ->
            val r = (k / 3) * 3
            val c = k % 3
            p[r] * q[c] + p[r + 1] * q[3 + c] + p[r + 2] * q[6 + c]
        }
    }
}

/** The bounds of the [width] x [height] frame's four corners once [t] has moved them. */
private fun mappedFrameBounds(t: PlaneTransform, width: Int, height: Int): DoubleArray {
    val w = width.toDouble()
    val h = height.toDouble()
    val corners = listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h).map { (x, y) -> t.map(x, y)!! }
    return doubleArrayOf(
        corners.minOf { it.x }, corners.minOf { it.y },
        corners.maxOf { it.x }, corners.maxOf { it.y },
    )
}

/** The quarter turn and mirrors alone, before the shift back onto the bitmap's origin. */
private fun turnOnly(rotationDegrees: Int, flipHorizontal: Boolean, flipVertical: Boolean): PlaneTransform =
    PlaneTransform.rotate(rotationDegrees.toDouble())
        .then(PlaneTransform.scale(if (flipHorizontal) -1.0 else 1.0, if (flipVertical) -1.0 else 1.0))

/**
 * The render's rotate + flip step: [rotationDegrees] clockwise, then the flips in the turned frame's own
 * axes, then shifted so the turned picture starts at (0, 0), the layout Bitmap.createBitmap gives a
 * source drawn through a matrix. The pixel step and the face-cover mapping both use this one matrix.
 */
internal fun rotateFlipTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    flipHorizontal: Boolean,
    flipVertical: Boolean,
): PlaneTransform {
    val turn = turnOnly(rotationDegrees, flipHorizontal, flipVertical)
    val bounds = mappedFrameBounds(turn, sourceWidth, sourceHeight)
    return turn.then(PlaneTransform.translate(-bounds[0], -bounds[1]))
}

/** The size of the bitmap the rotate + flip step makes, rounded the way Bitmap.createBitmap rounds it. */
internal fun rotatedSize(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
    val bounds = mappedFrameBounds(turnOnly(rotationDegrees, false, false), sourceWidth, sourceHeight)
    return (bounds[2] - bounds[0]).roundToInt() to (bounds[3] - bounds[1]).roundToInt()
}

/**
 * The render's straighten + keystone warp over a [width] x [height] frame, or null when neither is set.
 * The keystone moves the frame's corners onto a trapezoid (positive [perspectiveV] narrows the top,
 * positive [perspectiveH] the left), then the picture turns by the straighten angle (about -15..+15
 * degrees) about the frame centre and grows about it until the frame is filled again. The pixel warp
 * and the face-cover mapping both use this one matrix.
 */
internal fun geometryTransform(
    width: Int,
    height: Int,
    straighten: Int,
    perspectiveV: Int,
    perspectiveH: Int,
): PlaneTransform? {
    if (straighten == 0 && perspectiveV == 0 && perspectiveH == 0) return null
    val w = width
    val h = height
    val angle = straighten / 100f * 15f
    val pv = perspectiveV / 100f * 0.28f * w / 2f
    val ph = perspectiveH / 100f * 0.28f * h / 2f
    val topInset = if (pv > 0) pv else 0f
    val botInset = if (pv < 0) -pv else 0f
    val leftInset = if (ph > 0) ph else 0f
    val rightInset = if (ph < 0) -ph else 0f
    val src = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
    val dst = floatArrayOf(
        topInset, leftInset,
        w - topInset, rightInset,
        w - botInset, h - rightInset,
        botInset, h - leftInset,
    )
    val keystone = PlaneTransform.quadToQuad(src, dst) ?: PlaneTransform.IDENTITY
    // Scale up so the narrowed keystone edge and the straightened corners still reach the frame.
    val maxInset = maxOf(topInset, botInset, leftInset, rightInset)
    val perspScale = if (maxInset > 0f) (w / (w - 2f * maxInset)).coerceIn(1f, 1.7f) else 1f
    val rad = Math.toRadians(Math.abs(angle.toDouble()))
    val cos = Math.cos(rad).toFloat()
    val sin = Math.sin(rad).toFloat()
    val rotScale = maxOf((w * cos + h * sin) / w, (w * sin + h * cos) / h)
    val grow = (perspScale * rotScale).toDouble()
    val cx = (w / 2f).toDouble()
    val cy = (h / 2f).toDouble()
    return keystone
        .then(PlaneTransform.rotate(angle.toDouble(), cx, cy))
        .then(PlaneTransform.scale(grow, grow, cx, cy))
}

/**
 * The render's frame step: the whole [imageWidth] x [imageHeight] picture drawn into [photo] on the
 * frame's canvas, the scale and shift Canvas.drawBitmap applies to a bitmap drawn into that rectangle. The
 * pixel step and the face-cover mapping both use this one matrix.
 */
internal fun frameTransform(imageWidth: Int, imageHeight: Int, photo: FrameRect): PlaneTransform =
    PlaneTransform.scale(
        photo.width.toDouble() / imageWidth.coerceAtLeast(1),
        photo.height.toDouble() / imageHeight.coerceAtLeast(1),
    ).then(PlaneTransform.translate(photo.left.toDouble(), photo.top.toDouble()))

/**
 * Where a point of the ORIGINAL image lands in the rendered photo: through the rotate + flip step, the
 * straighten + keystone warp, the crop, then onto a frame's canvas, the chain the render moves the pixels
 * through. Face covers stay in original-image pixels (where they were detected), so drawing each through
 * this puts it on its face however the photo was turned, mirrored, straightened, cropped or framed.
 * [cropLeft] / [cropTop] are the origin the render actually cut the crop at, zero when it did not crop,
 * and [framePlacement] is the [frameTransform] the frame step drew the picture with, identity with no frame.
 */
internal fun originalToRenderTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    flipHorizontal: Boolean,
    flipVertical: Boolean,
    straighten: Int,
    perspectiveV: Int,
    perspectiveH: Int,
    cropLeft: Int,
    cropTop: Int,
    framePlacement: PlaneTransform = PlaneTransform.IDENTITY,
): PlaneTransform {
    val turn = rotateFlipTransform(sourceWidth, sourceHeight, rotationDegrees, flipHorizontal, flipVertical)
    val (turnedWidth, turnedHeight) = rotatedSize(sourceWidth, sourceHeight, rotationDegrees)
    val warp = geometryTransform(turnedWidth, turnedHeight, straighten, perspectiveV, perspectiveH)
        ?: PlaneTransform.IDENTITY
    return turn.then(warp)
        .then(PlaneTransform.translate(-cropLeft.toDouble(), -cropTop.toDouble()))
        .then(framePlacement)
}

/**
 * The render-pixel box a face cover takes up once [toRender] has carried it over: the bounds of the four
 * mapped corners of the square that holds [oval] at any angle. The warp keeps straight edges straight,
 * so the mapped square still holds the mapped cover; with no edit the box is that square itself. Null
 * when the square reaches past a perspective warp's vanishing line.
 */
internal fun coverBoundsInRender(oval: OvalSpec, toRender: PlaneTransform): RenderBox? {
    val half = maxOf(oval.radiusX, oval.radiusY).toDouble()
    val cx = oval.centerX.toDouble()
    val cy = oval.centerY.toDouble()
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY
    val corners = listOf(cx - half to cy - half, cx + half to cy - half, cx + half to cy + half, cx - half to cy + half)
    for ((x, y) in corners) {
        val p = toRender.map(x, y) ?: return null
        left = minOf(left, p.x)
        top = minOf(top, p.y)
        right = maxOf(right, p.x)
        bottom = maxOf(bottom, p.y)
    }
    return RenderBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/**
 * How big [oval] reads once [toRender] has carried it over: twice its shorter semi-axis as mapped, which
 * sizes the mosaic blocks to the face as it appears in the render. With no edit it is the cover's own
 * shorter diameter.
 */
internal fun coverSpanInRender(oval: OvalSpec, toRender: PlaneTransform): Float {
    val cx = oval.centerX.toDouble()
    val cy = oval.centerY.toDouble()
    val centre = toRender.map(cx, cy) ?: return 2f * minOf(oval.radiusX, oval.radiusY)
    val theta = Math.toRadians(oval.rotationDegrees.toDouble())
    val ux = cos(theta)
    val uy = sin(theta)
    fun mappedRadius(r: Double, dx: Double, dy: Double): Double {
        val p = toRender.map(cx + r * dx, cy + r * dy) ?: return r
        return hypot(p.x - centre.x, p.y - centre.y)
    }
    val rx = mappedRadius(oval.radiusX.toDouble(), ux, uy)
    val ry = mappedRadius(oval.radiusY.toDouble(), -uy, ux)
    return (2.0 * minOf(rx, ry)).toFloat()
}
