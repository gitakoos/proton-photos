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

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where a redaction stroke drawn on the editor's preview lands on the photo ([previewToPhoto]). The render
 * redacts the photo before its frame, so a stroke kept on the photo stays on what it covers whatever frame goes
 * around it later. With no frame and a full-size preview the mapping is exactly the identity, which keeps an
 * unframed save the same pixels as a stroke stored in preview pixels.
 */
class RedactStrokeMappingTest {

    private val photoW = 1920
    private val photoH = 1080

    private val polaroid = FrameShape.Polaroid.toSpec()!!
    private val square = FrameSpec(ratio = FrameRatio.OneToOne, margin = FRAME_BORDER_MARGIN)
    private val squareMoved = square.copy(scale = 0.5f, offsetX = 0.7f, offsetY = -0.3f)
    private val tallBlur = FrameSpec(ratio = FrameRatio.NineSixteen, fill = FrameFill.Blur, scale = 0.8f, offsetY = 1f)

    /** Where photo point ([x], [y]) shows on a [previewW] x [previewH] render of [frame] around the photo. */
    private fun onPreview(
        x: Double,
        y: Double,
        frame: FrameSpec,
        previewW: Int,
        previewH: Int,
        w: Int = photoW,
        h: Int = photoH,
    ): Pair<Float, Float> {
        val canvas = frameCanvas(w, h, frame)
        val p = frameTransform(w, h, canvas.photo).map(x, y)!!
        return (p.x * previewW / canvas.width).toFloat() to (p.y * previewH / canvas.height).toFloat()
    }

    private fun photoPoints(w: Int = photoW, h: Int = photoH) = listOf(
        0.0 to 0.0,
        w.toDouble() to h.toDouble(),
        w / 2.0 to h / 2.0,
        w * 0.25 to h * 0.8,
        w * 0.9 to h * 0.1,
    )

    private fun assertRoundTrip(frame: FrameSpec, previewScale: Float, w: Int = photoW, h: Int = photoH) {
        val canvas = frameCanvas(w, h, frame)
        val previewW = (canvas.width * previewScale).roundToInt()
        val previewH = (canvas.height * previewScale).roundToInt()
        val toPhoto = previewToPhoto(previewW, previewH, w, h, frame)
        // A preview pixel spans 1 / previewScale canvas pixels, so float error grows with it.
        val tolerance = 0.05f / previewScale
        for ((x, y) in photoPoints(w, h)) {
            val (px, py) = onPreview(x, y, frame, previewW, previewH, w, h)
            assertEquals("x of ($x, $y) through $frame at $previewScale", x.toFloat(), toPhoto.x(px), tolerance)
            assertEquals("y of ($x, $y) through $frame at $previewScale", y.toFloat(), toPhoto.y(py), tolerance)
        }
    }

    @Test
    fun with_no_frame_a_full_size_preview_maps_every_point_to_itself_exactly() {
        val toPhoto = previewToPhoto(photoW, photoH, photoW, photoH, null)
        for ((x, y) in listOf(0f to 0f, 123.456f to 789.012f, 1919.99f to 1079.99f, 0.1f to 0.3f)) {
            assertEquals(x, toPhoto.x(x), 0f)
            assertEquals(y, toPhoto.y(y), 0f)
        }
        assertEquals(1f, toPhoto.scaleX, 0f)
        assertEquals(1f, toPhoto.scaleY, 0f)
    }

    @Test
    fun with_no_frame_a_downscaled_preview_scales_up_onto_the_photo() {
        val toPhoto = previewToPhoto(720, 405, photoW, photoH, null)
        assertEquals(960f, toPhoto.x(360f), 0.01f)
        assertEquals(540f, toPhoto.y(202.5f), 0.01f)
        // A brush drawn 10 preview pixels wide covers the same share of the photo.
        assertEquals(10f * photoW / 720f, 10f * toPhoto.scaleX, 0.01f)
    }

    @Test
    fun a_point_on_a_framed_preview_comes_back_to_the_photo_pixel_it_was_drawn_on() {
        for (frame in listOf(polaroid, square, squareMoved, tallBlur)) {
            assertRoundTrip(frame, previewScale = 1f)
            assertRoundTrip(frame, previewScale = 1f / 3f)
        }
    }

    @Test
    fun a_photo_over_the_frame_size_cap_still_maps_back_onto_its_pixels() {
        assertRoundTrip(polaroid, previewScale = 1f, w = 8000, h = 6000)
        assertRoundTrip(square, previewScale = 0.2f, w = 6000, h = 8000)
    }

    @Test
    fun a_stroke_on_the_photo_is_the_same_photo_pixel_under_any_frame() {
        // The same face drawn over under one frame and seen under another: both preview points name one pixel.
        val face = 1400.0 to 300.0
        val before = onPreview(face.first, face.second, square, 900, 900)
        val after = onPreview(face.first, face.second, squareMoved, 900, 900)
        assertTrue("the moved frame shows the face elsewhere", before != after)
        val fromBefore = previewToPhoto(900, 900, photoW, photoH, square)
        val fromAfter = previewToPhoto(900, 900, photoW, photoH, squareMoved)
        assertEquals(fromBefore.x(before.first), fromAfter.x(after.first), 0.1f)
        assertEquals(fromBefore.y(before.second), fromAfter.y(after.second), 0.1f)
        assertEquals(face.first.toFloat(), fromAfter.x(after.first), 0.1f)
        assertEquals(face.second.toFloat(), fromAfter.y(after.second), 0.1f)
    }

    @Test
    fun a_point_on_the_border_lands_off_the_photo() {
        val canvas = frameCanvas(photoW, photoH, polaroid)
        val toPhoto = previewToPhoto(canvas.width, canvas.height, photoW, photoH, polaroid)
        assertTrue(toPhoto.x(0f) < 0f)
        assertTrue(toPhoto.y(0f) < 0f)
        assertTrue(toPhoto.y(canvas.height.toFloat()) > photoH)
    }

    @Test
    fun a_brush_on_a_half_size_preview_of_a_full_scale_frame_doubles_on_the_photo() {
        val canvas = frameCanvas(photoW, photoH, square)
        val toPhoto = previewToPhoto(canvas.width / 2, canvas.height / 2, photoW, photoH, square)
        assertEquals(2f, toPhoto.scaleX, 0.01f)
        assertEquals(2f, toPhoto.scaleY, 0.01f)
    }

    @Test
    fun a_shrunk_photo_in_its_frame_spans_more_photo_pixels_per_preview_pixel() {
        val canvas = frameCanvas(photoW, photoH, squareMoved)
        val toPhoto = previewToPhoto(canvas.width, canvas.height, photoW, photoH, squareMoved)
        assertEquals(2f, toPhoto.scaleX, 0.01f)
    }
}
