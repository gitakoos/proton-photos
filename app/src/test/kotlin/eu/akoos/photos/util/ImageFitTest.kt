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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the letterbox transform every overlay drawn on top of a fitted image depends on. The crop
 * frames and the redaction brush all live in the photo's own pixel coordinates while the finger
 * arrives in screen pixels, so a wrong scale or a wrong bar width puts the whole overlay somewhere
 * the user did not touch.
 *
 * Also pins the orientation decomposition, because a frame decoded without its EXIF turn is
 * rotated relative to what the user sees, and every coordinate derived from it inherits that turn.
 */
class ImageFitTest {

    private val eps = 0.0001f

    private fun assertFit(fit: ImageFit, scale: Float, offsetX: Float, offsetY: Float) {
        assertEquals("scale", scale, fit.scale, eps)
        assertEquals("offsetX", offsetX, fit.offsetX, eps)
        assertEquals("offsetY", offsetY, fit.offsetY, eps)
    }

    // ─── fit calculation ────────────────────────────────────────────────────

    @Test
    fun `an image wider than the box gets horizontal bars`() {
        // 400x200 into 200x200: width binds at 0.5, the 100pt-tall draw centres vertically.
        assertFit(fitImageInBox(400f, 200f, 200f, 200f), scale = 0.5f, offsetX = 0f, offsetY = 50f)
    }

    @Test
    fun `an image taller than the box gets vertical bars`() {
        // 200x400 into 200x200: height binds at 0.5, the 100pt-wide draw centres horizontally.
        assertFit(fitImageInBox(200f, 400f, 200f, 200f), scale = 0.5f, offsetX = 50f, offsetY = 0f)
    }

    @Test
    fun `an exact aspect match fills the box with no bars`() {
        assertFit(fitImageInBox(1200f, 900f, 400f, 300f), scale = 1f / 3f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `a square in a wide box is pillarboxed`() {
        assertFit(fitImageInBox(500f, 500f, 1000f, 400f), scale = 0.8f, offsetX = 300f, offsetY = 0f)
    }

    @Test
    fun `an upscale is allowed`() {
        // The fit never clamps at 1, so a small source is blown up to touch the box.
        assertFit(fitImageInBox(100f, 100f, 400f, 400f), scale = 4f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `a zero box collapses to a zero scale`() {
        // A container reports zero size on its first measure pass; call sites coerce to 1, but the
        // maths itself stays unclamped.
        assertFit(fitImageInBox(400f, 300f, 0f, 0f), scale = 0f, offsetX = 0f, offsetY = 0f)
    }

    @Test
    fun `one zero image axis still fits on the other`() {
        // 0x100 into 200x200: the height binds, and the zero-wide draw centres.
        assertFit(fitImageInBox(0f, 100f, 200f, 200f), scale = 2f, offsetX = 100f, offsetY = 0f)
    }

    @Test
    fun `a fully zero image produces no usable fit`() {
        // Documents that no clamp was bolted on during the hoist: both axes divide by zero, so the
        // scale is infinite and the offsets are not numbers. No real bitmap reaches this.
        val fit = fitImageInBox(0f, 0f, 200f, 200f)
        assertFalse("scale should not be finite", fit.scale.isFinite())
        assertTrue("offsetX should be NaN", fit.offsetX.isNaN())
        assertTrue("offsetY should be NaN", fit.offsetY.isNaN())
    }

    // ─── point and rect mapping ─────────────────────────────────────────────

    @Test
    fun `at scale one with no bars image space is screen space`() {
        val fit = fitImageInBox(300f, 300f, 300f, 300f)
        assertEquals(1f, fit.scale, eps)
        val p = FitPoint(120f, 45f)
        assertEquals(p, fit.toScreen(p))
        assertEquals(p, fit.toImage(p))
    }

    @Test
    fun `a point round-trips through a letterboxed fit`() {
        val fit = fitImageInBox(400f, 200f, 200f, 200f)
        val image = FitPoint(300f, 150f)
        val screen = fit.toScreen(image)
        // 300 * 0.5 + 0 horizontally, 150 * 0.5 + 50 vertically.
        assertEquals(150f, screen.x, eps)
        assertEquals(125f, screen.y, eps)
        val back = fit.toImage(screen)
        assertEquals(image.x, back.x, eps)
        assertEquals(image.y, back.y, eps)
    }

    @Test
    fun `a point round-trips when zoomed up and offset`() {
        val fit = ImageFit(scale = 3.75f, offsetX = -220.5f, offsetY = 87.25f)
        val image = FitPoint(1234.5f, 987.25f)
        val back = fit.toImage(fit.toScreen(image))
        assertEquals(image.x, back.x, 0.01f)
        assertEquals(image.y, back.y, 0.01f)
    }

    @Test
    fun `the image origin lands on the top-left of the drawn area`() {
        val fit = fitImageInBox(200f, 400f, 200f, 200f)
        val origin = fit.toScreen(FitPoint(0f, 0f))
        assertEquals(50f, origin.x, eps)
        assertEquals(0f, origin.y, eps)
        val corner = fit.toScreen(FitPoint(200f, 400f))
        assertEquals(150f, corner.x, eps)
        assertEquals(200f, corner.y, eps)
    }

    @Test
    fun `a rect round-trips through a letterboxed fit`() {
        val fit = fitImageInBox(1000f, 500f, 400f, 400f)
        val image = FitBox(100f, 50f, 900f, 450f)
        val screen = fit.toScreen(image)
        // scale 0.4, a 200pt-tall draw centred in 400 gives a 100pt top bar.
        assertEquals(40f, screen.left, eps)
        assertEquals(120f, screen.top, eps)
        assertEquals(360f, screen.right, eps)
        assertEquals(280f, screen.bottom, eps)
        val back = fit.toImage(screen)
        assertEquals(image.left, back.left, eps)
        assertEquals(image.top, back.top, eps)
        assertEquals(image.right, back.right, eps)
        assertEquals(image.bottom, back.bottom, eps)
    }

    @Test
    fun `a screen rect round-trips back to screen space`() {
        val fit = fitImageInBox(3000f, 4000f, 1080f, 1920f)
        val screen = FitBox(120f, 300f, 900f, 1500f)
        val back = fit.toScreen(fit.toImage(screen))
        assertEquals(screen.left, back.left, 0.01f)
        assertEquals(screen.top, back.top, 0.01f)
        assertEquals(screen.right, back.right, 0.01f)
        assertEquals(screen.bottom, back.bottom, 0.01f)
    }

    // ─── orientation ────────────────────────────────────────────────────────

    private val srcW = 400f
    private val srcH = 300f
    private val topLeft = FitPoint(0f, 0f)

    @Test
    fun `no orientation leaves a point alone`() {
        assertEquals(FitPoint(37f, 91f), DisplayOrientation.None.orient(FitPoint(37f, 91f), srcW, srcH))
        assertFalse(DisplayOrientation.None.swapsAxes)
    }

    @Test
    fun `a 90 degree turn sends the top-left corner to the top-right`() {
        val o = DisplayOrientation(90, mirrored = false)
        // The turned frame is 300x400, so its top-right corner is at x = 300.
        assertEquals(FitPoint(srcH, 0f), o.orient(topLeft, srcW, srcH))
        assertEquals(FitPoint(srcH, srcW), o.orient(FitPoint(srcW, 0f), srcW, srcH))
        assertTrue(o.swapsAxes)
    }

    @Test
    fun `a 180 degree turn sends the top-left corner to the bottom-right`() {
        val o = DisplayOrientation(180, mirrored = false)
        assertEquals(FitPoint(srcW, srcH), o.orient(topLeft, srcW, srcH))
        assertEquals(topLeft, o.orient(FitPoint(srcW, srcH), srcW, srcH))
        assertFalse(o.swapsAxes)
    }

    @Test
    fun `a 270 degree turn sends the top-left corner to the bottom-left`() {
        val o = DisplayOrientation(270, mirrored = false)
        // The turned frame is 300x400, so its bottom-left corner is at y = 400.
        assertEquals(FitPoint(0f, srcW), o.orient(topLeft, srcW, srcH))
        assertEquals(topLeft, o.orient(FitPoint(srcW, 0f), srcW, srcH))
        assertTrue(o.swapsAxes)
    }

    @Test
    fun `a mirror flips across the vertical centre line`() {
        val o = DisplayOrientation(0, mirrored = true)
        assertEquals(FitPoint(srcW, 0f), o.orient(topLeft, srcW, srcH))
        assertEquals(FitPoint(srcW / 2f, 10f), o.orient(FitPoint(srcW / 2f, 10f), srcW, srcH))
    }

    @Test
    fun `a mirrored 90 degree turn is the transverse flip`() {
        // Mirror first, then turn: the top-left corner ends up at the far corner of the turned frame.
        val o = DisplayOrientation(90, mirrored = true)
        assertEquals(FitPoint(srcH, srcW), o.orient(topLeft, srcW, srcH))
    }

    @Test
    fun `a mirrored 270 degree turn is the transpose`() {
        // Transpose is the reflection in the main diagonal, so a corner stays on that diagonal.
        val o = DisplayOrientation(270, mirrored = true)
        assertEquals(topLeft, o.orient(topLeft, srcW, srcH))
        assertEquals(FitPoint(srcH, srcW), o.orient(FitPoint(srcW, srcH), srcW, srcH))
        assertEquals(FitPoint(90f, 12f), o.orient(FitPoint(12f, 90f), srcW, srcH))
    }

    @Test
    fun `four quarter turns return a point to itself`() {
        val o = DisplayOrientation(90, mirrored = false)
        // Each turn swaps the frame's axes, so the dimensions alternate on the way round.
        val a = o.orient(FitPoint(120f, 55f), srcW, srcH)
        val b = o.orient(a, srcH, srcW)
        val c = o.orient(b, srcW, srcH)
        val d = o.orient(c, srcH, srcW)
        assertEquals(120f, d.x, eps)
        assertEquals(55f, d.y, eps)
    }
}
