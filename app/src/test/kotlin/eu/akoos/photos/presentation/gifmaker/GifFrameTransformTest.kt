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

package eu.akoos.photos.presentation.gifmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure framing helper on a 16:9 source (1600 x 900). Everything here is plain arithmetic, so
 * the whole suite runs on the JVM with no Android types: the exporter and the preview both call
 * [gifFraming], so pinning its geometry keeps them pixel-identical.
 */
class GifFrameTransformTest {

    private val srcW = 1600
    private val srcH = 900
    private val maxEdge = 480
    private val eps = 0.01f

    // ---- canvas sizing ----------------------------------------------------

    @Test
    fun `original canvas keeps the source ratio scaled to the max edge, even`() {
        val (w, h) = gifCanvasFor(srcW, srcH, GifAspect.ORIGINAL, maxEdge)
        assertEquals("longest edge is the max edge", 480, w)
        assertEquals("shorter edge keeps 16 to 9", 270, h)
        assertEquals("width even", 0, w and 1)
        assertEquals("height even", 0, h and 1)
    }

    @Test
    fun `square canvas is a max-edge square`() {
        val (w, h) = gifCanvasFor(srcW, srcH, GifAspect.SQUARE, maxEdge)
        assertEquals(480, w)
        assertEquals(480, h)
    }

    // ---- ORIGINAL, zoom 1: source fills the canvas ------------------------

    @Test
    fun `original at zoom 1 samples the whole frame into the whole canvas`() {
        val (cw, ch) = gifCanvasFor(srcW, srcH, GifAspect.ORIGINAL, maxEdge)
        val f = gifFraming(srcW, srcH, cw, ch, zoom = 1f, panX = 0f, panY = 0f)

        assertEquals(0f, f.srcLeft, eps)
        assertEquals(0f, f.srcTop, eps)
        assertEquals(srcW.toFloat(), f.srcRight, eps)
        assertEquals(srcH.toFloat(), f.srcBottom, eps)

        assertEquals(0f, f.dstLeft, eps)
        assertEquals(0f, f.dstTop, eps)
        assertEquals(cw.toFloat(), f.dstRight, eps)
        assertEquals(ch.toFloat(), f.dstBottom, eps)
    }

    // ---- SQUARE, zoom 1: whole frame fits, bars remain --------------------

    @Test
    fun `square at zoom 1 fits the whole frame with black bars, centered`() {
        val (cw, ch) = gifCanvasFor(srcW, srcH, GifAspect.SQUARE, maxEdge)
        val f = gifFraming(srcW, srcH, cw, ch, zoom = 1f, panX = 0f, panY = 0f)

        // The whole frame is still sampled: nothing is cropped, it is only letterboxed.
        assertEquals(0f, f.srcLeft, eps)
        assertEquals(0f, f.srcTop, eps)
        assertEquals(srcW.toFloat(), f.srcRight, eps)
        assertEquals(srcH.toFloat(), f.srcBottom, eps)

        // A 16:9 frame in a square is full width, shorter than the canvas, and vertically centered.
        assertEquals(0f, f.dstLeft, eps)
        assertEquals(cw.toFloat(), f.dstRight, eps)
        val dstH = f.dstBottom - f.dstTop
        assertTrue("destination is letterboxed (bars top and bottom)", dstH < ch)
        assertEquals("top bar equals bottom bar", f.dstTop, ch - f.dstBottom, eps)
        assertEquals("expected fitted height", 270f, dstH, eps)
    }

    // ---- SQUARE, high zoom: source crops, canvas is filled ----------------

    @Test
    fun `square at high zoom crops a centered sub-rect and fills the whole canvas`() {
        val (cw, ch) = gifCanvasFor(srcW, srcH, GifAspect.SQUARE, maxEdge)
        val f = gifFraming(srcW, srcH, cw, ch, zoom = 4f, panX = 0f, panY = 0f)

        // No bars left: the destination is the whole square canvas.
        assertEquals(0f, f.dstLeft, eps)
        assertEquals(0f, f.dstTop, eps)
        assertEquals(cw.toFloat(), f.dstRight, eps)
        assertEquals(ch.toFloat(), f.dstBottom, eps)

        // The sampled source is a strict, centered sub-rectangle of the frame.
        assertTrue("cropped from the left", f.srcLeft > 0f)
        assertTrue("cropped from the top", f.srcTop > 0f)
        assertTrue("cropped from the right", f.srcRight < srcW)
        assertTrue("cropped from the bottom", f.srcBottom < srcH)
        assertEquals("horizontally centered", f.srcLeft, srcW - f.srcRight, eps)
        assertEquals("vertically centered", f.srcTop, srcH - f.srcBottom, eps)
        // A square canvas crops a square region of the source.
        assertEquals("square crop", f.srcRight - f.srcLeft, f.srcBottom - f.srcTop, eps)
    }

    // ---- pan is clamped so the crop never leaves the frame ----------------

    @Test
    fun `pan beyond range is clamped so the source rect stays inside the frame`() {
        val (cw, ch) = gifCanvasFor(srcW, srcH, GifAspect.SQUARE, maxEdge)
        // Extreme, out-of-range pan on both axes at a zoom where the content covers the canvas.
        val f = gifFraming(srcW, srcH, cw, ch, zoom = 4f, panX = 5f, panY = -5f)

        assertTrue("srcLeft >= 0", f.srcLeft >= -eps)
        assertTrue("srcTop >= 0", f.srcTop >= -eps)
        assertTrue("srcRight <= srcW", f.srcRight <= srcW + eps)
        assertTrue("srcBottom <= srcH", f.srcBottom <= srcH + eps)

        // Content still covers the canvas after clamping (no black edge pulled in).
        assertEquals(0f, f.dstLeft, eps)
        assertEquals(0f, f.dstTop, eps)
        assertEquals(cw.toFloat(), f.dstRight, eps)
        assertEquals(ch.toFloat(), f.dstBottom, eps)

        // A pushed-right pan pins the crop against the source's left edge; a pushed-up pan against
        // its bottom edge.
        assertEquals("clamped to left edge", 0f, f.srcLeft, eps)
        assertEquals("clamped to bottom edge", srcH.toFloat(), f.srcBottom, eps)
    }
}
