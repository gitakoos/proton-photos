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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pins where a face cover lands. Covers are found on the original, unrotated and uncropped photo, and
 * the render then turns, mirrors, straightens, keystones, crops and frames the pixels; a cover drawn
 * anywhere but on the pixels its face moved to leaves that face uncovered in the saved photo. These tests
 * check the mapping against plain pixel indexing (where a quarter turn or a flip stores each pixel), the
 * documented straighten and keystone geometry, the crop origin and the frame's photo box, and that the
 * region a cover is drawn in holds the whole carried-over oval.
 */
class FaceCoverMappingTest {

    private val eps = 1e-6

    private fun PlaneTransform.at(x: Double, y: Double): PlanePoint =
        map(x, y) ?: throw AssertionError("($x, $y) did not map")

    private fun assertPoint(message: String, ex: Double, ey: Double, p: PlanePoint, delta: Double = eps) {
        assertEquals("$message, x", ex, p.x, delta)
        assertEquals("$message, y", ey, p.y, delta)
    }

    private fun chain(
        w: Int,
        h: Int,
        rotation: Int = 0,
        flipH: Boolean = false,
        flipV: Boolean = false,
        straighten: Int = 0,
        perspectiveV: Int = 0,
        perspectiveH: Int = 0,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        framePlacement: PlaneTransform = PlaneTransform.IDENTITY,
    ): PlaneTransform = originalToRenderTransform(
        w, h, rotation, flipH, flipV, straighten, perspectiveV, perspectiveH, cropLeft, cropTop, framePlacement,
    )

    /**
     * Where a pixel of a [w] x [h] bitmap is stored after the render's quarter turns and then its flips
     * in the turned frame, by plain array indexing: a clockwise quarter turn stores pixel (i, j) at
     * (height - 1 - j, i).
     */
    private fun pixelAfterTurnAndFlip(
        i: Int,
        j: Int,
        w: Int,
        h: Int,
        rotation: Int,
        flipH: Boolean,
        flipV: Boolean,
    ): Pair<Int, Int> {
        var x = i
        var y = j
        var bw = w
        var bh = h
        repeat(((rotation / 90) % 4 + 4) % 4) {
            val nx = bh - 1 - y
            y = x
            x = nx
            bw = bh.also { bh = bw }
        }
        if (flipH) x = bw - 1 - x
        if (flipV) y = bh - 1 - y
        return x to y
    }

    /** The straighten angle and the grow that refills the frame, as documented for the render. */
    private fun straightenAngleAndGrow(straighten: Int, w: Int, h: Int): Pair<Double, Double> {
        val angle = straighten / 100.0 * 15.0
        val rad = Math.toRadians(kotlin.math.abs(angle))
        val grow = maxOf((w * cos(rad) + h * sin(rad)) / w, (w * sin(rad) + h * cos(rad)) / h)
        return angle to grow
    }

    private fun ovalBoundary(oval: OvalSpec, steps: Int = 360): List<Pair<Double, Double>> {
        val t = Math.toRadians(oval.rotationDegrees.toDouble())
        val ux = cos(t)
        val uy = sin(t)
        return (0 until steps).map { k ->
            val a = 2 * PI * k / steps
            val lx = oval.radiusX * cos(a)
            val ly = oval.radiusY * sin(a)
            (oval.centerX + lx * ux - ly * uy) to (oval.centerY + lx * uy + ly * ux)
        }
    }

    @Test
    fun `with no edit every point stays where it is`() {
        val t = chain(400, 300)
        assertPoint("interior", 123.5, 45.5, t.at(123.5, 45.5))
        assertPoint("corner", 400.0, 300.0, t.at(400.0, 300.0))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), t.values(), 0f)
    }

    @Test
    fun `a turned or mirrored pixel and its cover land on the same render pixel`() {
        val w = 40
        val h = 30
        val cropLeft = 7
        val cropTop = 3
        val pixels = listOf(0 to 0, w - 1 to 0, w - 1 to h - 1, 0 to h - 1, 12 to 5, 31 to 22)
        for (rotation in listOf(0, 90, 180, 270)) {
            for (flipH in listOf(false, true)) {
                for (flipV in listOf(false, true)) {
                    val t = chain(w, h, rotation, flipH, flipV, cropLeft = cropLeft, cropTop = cropTop)
                    for ((i, j) in pixels) {
                        val (x, y) = pixelAfterTurnAndFlip(i, j, w, h, rotation, flipH, flipV)
                        // The pixel's centre must land on the centre of the render pixel storing it.
                        assertPoint(
                            "pixel ($i, $j) at $rotation degrees, flipH=$flipH, flipV=$flipV",
                            x + 0.5 - cropLeft, y + 0.5 - cropTop,
                            t.at(i + 0.5, j + 0.5),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a quarter turn clockwise moves the top-left corner to the top-right`() {
        val t = rotateFlipTransform(400, 300, 90, flipHorizontal = false, flipVertical = false)
        assertPoint("top-left", 300.0, 0.0, t.at(0.0, 0.0))
        assertPoint("top-right", 300.0, 400.0, t.at(400.0, 0.0))
        assertPoint("bottom-left", 0.0, 0.0, t.at(0.0, 300.0))
        assertEquals(300 to 400, rotatedSize(400, 300, 90))
        // The matrix Bitmap.createBitmap is handed: the turn, then the shift back onto the origin.
        assertArrayEquals(floatArrayOf(0f, -1f, 300f, 1f, 0f, 0f, 0f, 0f, 1f), t.values(), 0f)
    }

    @Test
    fun `half and three-quarter turns keep the size or swap it`() {
        assertEquals(400 to 300, rotatedSize(400, 300, 180))
        assertEquals(300 to 400, rotatedSize(400, 300, 270))
        assertEquals(400 to 300, rotatedSize(400, 300, 0))
        assertPoint("half turn", 390.0, 280.0, rotateFlipTransform(400, 300, 180, false, false).at(10.0, 20.0))
        assertPoint("three quarters", 20.0, 390.0, rotateFlipTransform(400, 300, 270, false, false).at(10.0, 20.0))
    }

    @Test
    fun `quarter turns move pixels by whole pixels with no rounding creep`() {
        val t = rotateFlipTransform(4000, 3000, 90, flipHorizontal = false, flipVertical = false)
        val p = t.at(3999.5, 2999.5)
        assertEquals(0.5, p.x, 0.0)
        assertEquals(3999.5, p.y, 0.0)
    }

    @Test
    fun `flips mirror across the frame they are applied in`() {
        assertPoint("horizontal", 390.0, 20.0, rotateFlipTransform(400, 300, 0, true, false).at(10.0, 20.0))
        assertPoint("vertical", 10.0, 280.0, rotateFlipTransform(400, 300, 0, false, true).at(10.0, 20.0))
        assertPoint("both", 390.0, 280.0, rotateFlipTransform(400, 300, 0, true, true).at(10.0, 20.0))
        // A quarter turn then a horizontal flip of the turned frame is a transpose.
        assertPoint("turn then flip", 20.0, 10.0, rotateFlipTransform(400, 300, 90, true, false).at(10.0, 20.0))
    }

    @Test
    fun `a crop shifts every point by the crop origin`() {
        assertPoint("plain crop", 73.5, 5.5, chain(400, 300, cropLeft = 50, cropTop = 40).at(123.5, 45.5))
        // A quarter turn first: (x, y) -> (h - y, x), then the crop origin comes off.
        assertPoint(
            "turn then crop", 300.0 - 45.5 - 50.0, 123.5 - 40.0,
            chain(400, 300, rotation = 90, cropLeft = 50, cropTop = 40).at(123.5, 45.5),
        )
    }

    @Test
    fun `straighten turns about the frame centre and grows to refill the frame`() {
        val w = 400
        val h = 300
        for (straighten in listOf(100, -100, 37)) {
            val t = geometryTransform(w, h, straighten, 0, 0)!!
            val (angle, grow) = straightenAngleAndGrow(straighten, w, h)
            assertPoint("centre stays put at $straighten", 200.0, 150.0, t.at(200.0, 150.0), 1e-4)
            // A point 100 px right of the centre turns by the angle (clockwise for a positive value, as y
            // points down) and moves out by the grow.
            val p = t.at(300.0, 150.0)
            assertEquals("distance at $straighten", 100.0 * grow, hypot(p.x - 200.0, p.y - 150.0), 1e-3)
            assertEquals("angle at $straighten", angle, Math.toDegrees(atan2(p.y - 150.0, p.x - 200.0)), 1e-3)
        }
    }

    @Test
    fun `straighten leaves no empty corner in the frame`() {
        for ((w, h) in listOf(400 to 300, 3000 to 4000)) {
            for (straighten in listOf(100, -100, 45)) {
                val t = geometryTransform(w, h, straighten, 0, 0)!!
                val frame = listOf(0.0 to 0.0, w.toDouble() to 0.0, w.toDouble() to h.toDouble(), 0.0 to h.toDouble())
                val quad = frame.map { (x, y) -> t.at(x, y) }
                for ((cx, cy) in frame) {
                    // Every frame corner sits inside the warped picture (on the inner side of each edge).
                    val sides = quad.indices.map { k ->
                        val a = quad[k]
                        val b = quad[(k + 1) % quad.size]
                        (b.x - a.x) * (cy - a.y) - (b.y - a.y) * (cx - a.x)
                    }
                    val tolerance = 1e-6 * w * h
                    assertTrue(
                        "frame corner ($cx, $cy) left uncovered at $straighten on $w x $h: $sides",
                        sides.all { it >= -tolerance } || sides.all { it <= tolerance },
                    )
                }
            }
        }
    }

    @Test
    fun `vertical keystone narrows the top and grows until that edge spans the frame`() {
        val w = 1000
        val h = 800
        val t = geometryTransform(w, h, 0, 100, 0)!!
        val inset = 0.28 * w / 2.0
        val grow = w / (w - 2.0 * inset)
        fun grown(x: Double, y: Double) = PlanePoint(500.0 + grow * (x - 500.0), 400.0 + grow * (y - 400.0))
        // The keystone trapezoid (top corners pulled in by the inset), grown about the frame centre.
        val expected = listOf(
            grown(inset, 0.0), grown(w - inset, 0.0), grown(w.toDouble(), h.toDouble()), grown(0.0, h.toDouble()),
        )
        val corners = listOf(0.0 to 0.0, w.toDouble() to 0.0, w.toDouble() to h.toDouble(), 0.0 to h.toDouble())
        corners.zip(expected).forEach { (c, e) -> assertPoint("corner $c", e.x, e.y, t.at(c.first, c.second), 1e-2) }
        // The narrowed top edge reaches exactly across the frame.
        assertEquals(0.0, t.at(0.0, 0.0).x, 1e-2)
        assertEquals(w.toDouble(), t.at(w.toDouble(), 0.0).x, 1e-2)
    }

    @Test
    fun `horizontal keystone narrows the named side and keeps straight edges straight`() {
        val w = 1000
        val h = 800
        // Negative narrows the right: its corners move toward the middle of that edge.
        val t = geometryTransform(w, h, 0, 0, -100)!!
        val topRight = t.at(w.toDouble(), 0.0)
        val bottomRight = t.at(w.toDouble(), h.toDouble())
        val topLeft = t.at(0.0, 0.0)
        val bottomLeft = t.at(0.0, h.toDouble())
        assertTrue("right edge shorter than left", bottomRight.y - topRight.y < bottomLeft.y - topLeft.y)
        // A point on the source's right edge stays on the warped right edge.
        val mid = t.at(w.toDouble(), 300.0)
        val cross = (bottomRight.x - topRight.x) * (mid.y - topRight.y) -
            (bottomRight.y - topRight.y) * (mid.x - topRight.x)
        assertEquals(0.0, cross, 1e-3)
    }

    @Test
    fun `no straighten or keystone leaves the frame alone`() {
        assertNull(geometryTransform(400, 300, 0, 0, 0))
    }

    @Test
    fun `a four-point warp sends each corner to its partner`() {
        val src = floatArrayOf(10f, 20f, 200f, 30f, 180f, 220f, 5f, 190f)
        val dst = floatArrayOf(0f, 0f, 100f, 10f, 90f, 120f, -10f, 100f)
        val t = PlaneTransform.quadToQuad(src, dst)!!
        for (k in 0 until 4) {
            assertPoint(
                "corner $k", dst[2 * k].toDouble(), dst[2 * k + 1].toDouble(),
                t.at(src[2 * k].toDouble(), src[2 * k + 1].toDouble()), 1e-6,
            )
        }
        // A collapsed quad has no warp.
        assertNull(PlaneTransform.quadToQuad(floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f), dst))
    }

    @Test
    fun `the whole chain turns, then warps the turned frame, then crops`() {
        val w = 640
        val h = 480
        val t = chain(w, h, rotation = 90, straighten = -40, perspectiveH = 30, cropLeft = 25, cropTop = 60)
        // The warp runs on the turned frame, so it gets the swapped size.
        val warp = geometryTransform(h, w, -40, 0, 30)!!
        for ((x, y) in listOf(100.0 to 50.0, 320.0 to 240.0, 600.0 to 400.0)) {
            val turned = PlanePoint(h - y, x)
            val warped = warp.at(turned.x, turned.y)
            assertPoint("($x, $y)", warped.x - 25.0, warped.y - 60.0, t.at(x, y), 1e-6)
        }
    }

    @Test
    fun `a frame carries every point into the photo box the picture is drawn in`() {
        // A quarter turn makes the 640 x 480 photo 480 x 640, and the crop keeps 400 x 300 of that from
        // (40, 30). Half size in a 9:16 frame, against the right edge and halfway from the centre to the
        // top: a 200 x 150 box on a 400 x 711 canvas.
        val cropW = 400
        val cropH = 300
        val box = frameCanvas(
            cropW, cropH, FrameSpec(FrameRatio.NineSixteen, scale = 0.5f, offsetX = 1f, offsetY = -0.5f),
        ).photo
        val t = chain(
            640, 480, rotation = 90, cropLeft = 40, cropTop = 30,
            framePlacement = frameTransform(cropW, cropH, box),
        )
        // The turn stores (x, y) at (480 - y, x) and the crop origin comes off, so these three original
        // points are the crop's top-left corner, its bottom-right corner and its centre.
        assertPoint("top-left", box.left.toDouble(), box.top.toDouble(), t.at(30.0, 440.0), 1e-3)
        assertPoint("bottom-right", box.right.toDouble(), box.bottom.toDouble(), t.at(330.0, 40.0), 1e-3)
        assertPoint(
            "centre", (box.left + box.right) / 2.0, (box.top + box.bottom) / 2.0, t.at(180.0, 240.0), 1e-3,
        )
        // The box keeps the picture's shape at half its size, so a face and its mosaic blocks halve too.
        assertEquals(30f, coverSpanInRender(OvalSpec(180f, 240f, 30f, 45f, 20f), t), 1e-3f)
    }

    @Test
    fun `the region a cover is drawn in holds the whole carried-over oval`() {
        val ovals = listOf(
            OvalSpec(300f, 200f, 60f, 80f, 0f),
            OvalSpec(120f, 90f, 40f, 55f, 27f),
            OvalSpec(500f, 350f, 70f, 90f, -64f),
        )
        val chains = listOf(
            chain(640, 480),
            chain(640, 480, rotation = 90, flipH = true, cropLeft = 30, cropTop = 12),
            chain(640, 480, straighten = 100),
            chain(640, 480, perspectiveV = 100),
            chain(640, 480, rotation = 270, straighten = -60, perspectiveH = -100, cropLeft = 40, cropTop = 25),
        )
        for (t in chains) {
            for (oval in ovals) {
                val box = coverBoundsInRender(oval, t)!!
                for ((x, y) in ovalBoundary(oval)) {
                    val p = t.at(x, y)
                    assertTrue(
                        "boundary point $p outside $box",
                        p.x >= box.left - 1e-3 && p.x <= box.right + 1e-3 &&
                            p.y >= box.top - 1e-3 && p.y <= box.bottom + 1e-3,
                    )
                }
            }
        }
    }

    @Test
    fun `with no edit the cover region is the square that holds the oval at any angle`() {
        val oval = OvalSpec(300f, 200f, 60f, 80f, 33f)
        assertEquals(RenderBox(220f, 120f, 380f, 280f), coverBoundsInRender(oval, PlaneTransform.IDENTITY))
    }

    @Test
    fun `the mosaic block size follows the face as it appears in the render`() {
        val oval = OvalSpec(200f, 150f, 30f, 45f, 20f)
        assertEquals(60f, coverSpanInRender(oval, PlaneTransform.IDENTITY), 1e-3f)
        // Turns, flips and crops move a face without resizing it.
        assertEquals(60f, coverSpanInRender(oval, chain(400, 300, rotation = 270, flipV = true, cropLeft = 9)), 1e-3f)
        // Straighten grows the picture, and the face with it.
        val (_, grow) = straightenAngleAndGrow(100, 400, 300)
        assertEquals((60.0 * grow).toFloat(), coverSpanInRender(oval, chain(400, 300, straighten = 100)), 1e-2f)
    }

    @Test
    fun `values come out in the row-major order the platform matrix reads`() {
        assertArrayEquals(
            floatArrayOf(1f, 0f, 5f, 0f, 1f, 7f, 0f, 0f, 1f),
            PlaneTransform.translate(5.0, 7.0).values(),
            0f,
        )
        // Translate, then scale by 2 about the origin: the offset doubles with the picture.
        assertArrayEquals(
            floatArrayOf(2f, 0f, 10f, 0f, 2f, 14f, 0f, 0f, 1f),
            PlaneTransform.translate(5.0, 7.0).then(PlaneTransform.scale(2.0, 2.0)).values(),
            0f,
        )
    }
}
