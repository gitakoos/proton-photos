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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Frame tool's geometry: the canvas a frame needs, where the photo lands on it, how a pinch or a drag moves
 * it there, and the small copy a blurred fill is made from. What a user sees rides on it: the chosen shape comes out
 * exact, every shape opens on an even border, the photo is never cropped or pushed off the canvas, a Polaroid keeps
 * its card on any photo, and the preview blurs the fill as the save does.
 */
class FrameGeometryTest {

    /** The layout is float maths, so a hundredth of a pixel counts as exact. */
    private val px = 0.01f

    private val polaroid = FrameShape.Polaroid.toSpec()!!

    private fun spec(
        ratio: FrameRatio = FrameRatio.Original,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) = FrameSpec(ratio = ratio, scale = scale, offsetX = offsetX, offsetY = offsetY)

    /** A pinch and a drag on a 1920x1080 photo. */
    private fun FrameSpec.gesture(zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): FrameSpec =
        afterGesture(zoom, panX, panY, 1920, 1080)

    private fun assertRect(expected: FrameRect, actual: FrameRect) {
        assertEquals("left of $actual", expected.left, actual.left, px)
        assertEquals("top of $actual", expected.top, actual.top, px)
        assertEquals("right of $actual", expected.right, actual.right, px)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, px)
    }

    /** The photo is a real rectangle, and all of it is on the canvas. */
    private fun assertOnCanvas(where: String, canvas: FrameCanvas) {
        val p = canvas.photo
        assertTrue("$where finite", listOf(p.left, p.top, p.right, p.bottom).all { it.isFinite() })
        assertTrue("$where left", p.left >= 0f)
        assertTrue("$where top", p.top >= 0f)
        assertTrue("$where right", p.right <= canvas.width)
        assertTrue("$where bottom", p.bottom <= canvas.height)
        assertTrue("$where width", p.width >= 0f)
        assertTrue("$where height", p.height >= 0f)
    }

    // ---- where the photo lands ----

    @Test
    fun a_landscape_photo_in_a_square_frame_spans_the_width_with_bars_above_and_below() {
        val c = frameCanvas(1920, 1080, spec(FrameRatio.OneToOne))
        assertEquals(1920, c.width)
        assertEquals(1920, c.height)
        assertRect(FrameRect(0f, 420f, 1920f, 1500f), c.photo)
    }

    @Test
    fun a_portrait_photo_in_a_square_frame_spans_the_height_with_bars_beside_it() {
        val c = frameCanvas(1080, 1920, spec(FrameRatio.OneToOne))
        assertEquals(1920, c.width)
        assertEquals(1920, c.height)
        assertRect(FrameRect(420f, 0f, 1500f, 1920f), c.photo)
    }

    @Test
    fun original_at_full_size_is_the_photo_itself() {
        for ((w, h) in listOf(1920 to 1080, 3024 to 4032, 2000 to 2000, 4000 to 1000)) {
            val c = frameCanvas(w, h, spec())
            assertEquals("${w}x$h", w, c.width)
            assertEquals("${w}x$h", h, c.height)
            assertRect(FrameRect(0f, 0f, w.toFloat(), h.toFloat()), c.photo)
        }
    }

    @Test
    fun original_shows_a_border_only_once_the_photo_shrinks() {
        val c = frameCanvas(1920, 1080, spec(scale = 0.8f))
        assertEquals(1920, c.width)
        assertEquals(1080, c.height)
        assertRect(FrameRect(192f, 108f, 1728f, 972f), c.photo)
    }

    @Test
    fun every_fixed_ratio_comes_out_exact_with_the_photo_touching_two_opposite_edges() {
        val photos = listOf(4000 to 3000, 3000 to 4000, 1920 to 1080, 1080 to 1920, 2000 to 2000, 3000 to 1000)
        for (ratio in FrameRatio.entries.filter { it.ratio != null }) {
            val expected = ratio.ratio!!
            for ((w, h) in photos) {
                val c = frameCanvas(w, h, spec(ratio))
                val p = c.photo
                val where = "$ratio ${w}x$h -> ${c.width}x${c.height} $p"
                assertEquals(where, expected, c.width.toFloat() / c.height, expected / c.height + 0.001f)
                assertTrue(where, abs(p.width - c.width) < 0.5f || abs(p.height - c.height) < 0.5f)
                assertEquals(where, w.toFloat() / h, p.width / p.height, 0.001f * w / h)
            }
        }
    }

    @Test
    fun a_frame_under_the_cap_keeps_every_pixel_of_the_photo() {
        val c = frameCanvas(1000, 800, spec(FrameRatio.OneToOne))
        assertEquals(1000, c.width)
        assertEquals(1000, c.height)
        assertRect(FrameRect(0f, 100f, 1000f, 900f), c.photo)
    }

    // ---- the long-side cap ----

    @Test
    fun the_long_side_cap_scales_the_canvas_and_the_photo_together() {
        // 4000x3000 in 9:16 wants a 4000x7111 canvas; the cap takes all of it down by 0.576.
        val full = frameCanvas(4000, 3000, spec(FrameRatio.NineSixteen))
        assertEquals(2304, full.width)
        assertEquals(4096, full.height)
        assertEquals(9f / 16f, full.width.toFloat() / full.height, 0.0001f)
        assertRect(FrameRect(0f, 1184f, 2304f, 2912f), full.photo)
        assertEquals(4f / 3f, full.photo.width / full.photo.height, 0.0001f)
        // A shrunk photo shrinks from the capped size.
        val half = frameCanvas(4000, 3000, spec(FrameRatio.NineSixteen, scale = 0.5f))
        assertRect(FrameRect(576f, 1616f, 1728f, 2480f), half.photo)
    }

    @Test
    fun a_smaller_cap_shrinks_the_same_layout() {
        val c = frameCanvas(1920, 1080, spec(FrameRatio.OneToOne), maxLongSide = 1000)
        assertEquals(1000, c.width)
        assertEquals(1000, c.height)
        assertRect(FrameRect(0f, 218.75f, 1000f, 781.25f), c.photo)
    }

    // ---- scale and offsets stay in range ----

    @Test
    fun the_photo_shrinks_no_further_than_the_minimum() {
        val floor = frameCanvas(1920, 1080, spec(FrameRatio.OneToOne, scale = FRAME_MIN_SCALE))
        assertEquals(1920f * FRAME_MIN_SCALE, floor.photo.width, px)
        for (scale in listOf(0.1f, 0f, -2f, Float.NEGATIVE_INFINITY)) {
            assertEquals("$scale", floor, frameCanvas(1920, 1080, spec(FrameRatio.OneToOne, scale = scale)))
        }
    }

    @Test
    fun the_photo_never_grows_past_the_fit() {
        val fit = frameCanvas(1920, 1080, spec(FrameRatio.OneToOne))
        for (scale in listOf(1.01f, 1.7f, 40f, Float.POSITIVE_INFINITY, Float.NaN)) {
            assertEquals("$scale", fit, frameCanvas(1920, 1080, spec(FrameRatio.OneToOne, scale = scale)))
        }
    }

    /** Half size in a square: a 960x540 photo with 960 px of room across and 1380 down. */
    private fun halfInSquare(offsetX: Float, offsetY: Float): FrameCanvas =
        frameCanvas(1920, 1080, spec(FrameRatio.OneToOne, 0.5f, offsetX, offsetY))

    @Test
    fun an_offset_of_one_puts_the_photo_against_that_edge() {
        assertRect(FrameRect(480f, 690f, 1440f, 1230f), halfInSquare(0f, 0f).photo)
        assertRect(FrameRect(0f, 0f, 960f, 540f), halfInSquare(-1f, -1f).photo)
        assertRect(FrameRect(960f, 1380f, 1920f, 1920f), halfInSquare(1f, 1f).photo)
        assertRect(FrameRect(720f, 345f, 1680f, 885f), halfInSquare(0.5f, -0.5f).photo)
    }

    @Test
    fun an_offset_past_an_edge_stops_there_and_one_that_is_not_a_number_centres() {
        assertEquals(halfInSquare(1f, -1f), halfInSquare(5f, -7f))
        assertEquals(halfInSquare(1f, -1f), halfInSquare(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
        assertEquals(halfInSquare(0f, 0f), halfInSquare(Float.NaN, Float.NaN))
    }

    // ---- a pinch and a drag ----

    @Test
    fun a_pinch_multiplies_the_scale() {
        assertEquals(0.5f, spec(FrameRatio.OneToOne).gesture(zoom = 0.5f).scale, 1e-6f)
        assertEquals(0.75f, spec(FrameRatio.OneToOne, scale = 0.5f).gesture(zoom = 1.5f).scale, 1e-6f)
    }

    @Test
    fun a_pinch_stops_at_the_minimum_and_at_the_fit() {
        val start = spec(FrameRatio.OneToOne, scale = 0.6f)
        for (zoom in listOf(0.01f, 0f, -3f)) {
            assertEquals("$zoom", FRAME_MIN_SCALE, start.gesture(zoom = zoom).scale, 0f)
        }
        assertEquals(1f, start.gesture(zoom = 10f).scale, 0f)
    }

    @Test
    fun a_drag_moves_the_photo_by_exactly_that_many_canvas_pixels() {
        val start = spec(FrameRatio.OneToOne, scale = 0.5f)
        val moved = start.gesture(panX = 240f, panY = -345f)
        assertEquals(0.5f, moved.offsetX, 1e-6f)
        assertEquals(-0.5f, moved.offsetY, 1e-6f)
        val before = frameCanvas(1920, 1080, start).photo
        val after = frameCanvas(1920, 1080, moved).photo
        assertEquals(240f, after.left - before.left, px)
        assertEquals(-345f, after.top - before.top, px)
    }

    @Test
    fun an_axis_with_no_room_keeps_its_offset_at_zero() {
        // At full size a landscape photo spans the square's width, so it has nowhere to go sideways.
        val moved = spec(FrameRatio.OneToOne).gesture(panX = 500f, panY = 210f)
        assertEquals(0f, moved.offsetX, 0f)
        assertEquals(0.5f, moved.offsetY, 1e-6f)
        // Original at full size has no room on either axis, which also clears an offset left over there.
        val still = spec(offsetX = 0.4f, offsetY = -0.9f).gesture(panX = 80f, panY = -80f)
        assertEquals(0f, still.offsetX, 0f)
        assertEquals(0f, still.offsetY, 0f)
    }

    @Test
    fun a_drag_past_the_edge_stops_the_photo_against_it() {
        val far = spec(FrameRatio.OneToOne, scale = 0.5f).gesture(panX = 99999f, panY = -99999f)
        assertEquals(1f, far.offsetX, 0f)
        assertEquals(-1f, far.offsetY, 0f)
    }

    @Test
    fun the_drag_is_measured_against_the_room_left_after_the_pinch() {
        // No room across at full size; halved there is 960 px, and 240 px is half the way to the edge.
        val moved = spec(FrameRatio.OneToOne).gesture(zoom = 0.5f, panX = 240f)
        assertEquals(0.5f, moved.scale, 1e-6f)
        assertEquals(0.5f, moved.offsetX, 1e-6f)
    }

    @Test
    fun a_drag_on_a_capped_canvas_counts_that_canvas_pixels() {
        // 4000x3000 in 9:16 lays out on 2304x4096, where the half-size photo has 1152 px of room across.
        val start = spec(FrameRatio.NineSixteen, scale = 0.5f)
        val moved = start.afterGesture(1f, 288f, 0f, 4000, 3000)
        assertEquals(0.5f, moved.offsetX, 1e-5f)
        val before = frameCanvas(4000, 3000, start).photo
        val after = frameCanvas(4000, 3000, moved).photo
        assertEquals(288f, after.left - before.left, px)
    }

    @Test
    fun a_gesture_that_is_not_a_number_changes_nothing() {
        val start = spec(FrameRatio.OneToOne, scale = 0.5f, offsetX = 0.25f, offsetY = -0.4f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals("zoom $bad", start, start.gesture(zoom = bad))
            assertEquals("pan $bad", start, start.gesture(panX = bad, panY = bad))
        }
    }

    // ---- Polaroid ----

    @Test
    fun a_landscape_polaroid_has_even_top_and_side_borders_and_a_bottom_three_and_a_half_times_that() {
        // 6% of the 1500 px short side is a 90 px border, and 315 px below.
        val c = frameCanvas(2000, 1500, polaroid)
        assertEquals(2180, c.width)
        assertEquals(1905, c.height)
        assertRect(FrameRect(90f, 90f, 2090f, 1590f), c.photo)
    }

    @Test
    fun a_portrait_polaroid_gets_the_same_card_standing_up() {
        val c = frameCanvas(1500, 2000, polaroid)
        assertEquals(1680, c.width)
        assertEquals(2405, c.height)
        assertRect(FrameRect(90f, 90f, 1590f, 2090f), c.photo)
    }

    @Test
    fun a_polaroid_keeps_its_proportions_on_any_photo_shape() {
        val photos = listOf(3000 to 3000, 4032 to 3024, 3024 to 4032, 6000 to 1000, 1000 to 5000, 641 to 479)
        for ((w, h) in photos) {
            val c = frameCanvas(w, h, polaroid)
            val p = c.photo
            val side = p.left
            val where = "${w}x$h -> ${c.width}x${c.height} $p"
            assertTrue("$where has a border", side > 0f)
            assertEquals("$where top", side, p.top, 0.75f)
            assertEquals("$where right", side, c.width - p.right, 0.75f)
            assertEquals("$where bottom", p.top * FRAME_POLAROID_BOTTOM_FACTOR, c.height - p.bottom, 1f)
            assertEquals("$where shape", w.toFloat() / h, p.width / p.height, 0.001f * w / h)
            // Under the cap the photo keeps every pixel, even where the canvas rounds.
            if (max(c.width, c.height) < FRAME_MAX_LONG_SIDE) assertEquals("$where size", w.toFloat(), p.width, px)
        }
    }

    @Test
    fun a_polaroid_in_a_fixed_ratio_keeps_its_borders_as_the_least_it_shows() {
        val landscape = frameCanvas(2000, 1500, polaroid.copy(ratio = FrameRatio.OneToOne))
        assertEquals(2180, landscape.width)
        assertEquals(2180, landscape.height)
        assertRect(FrameRect(90f, 227.5f, 2090f, 1727.5f), landscape.photo)
        val portrait = frameCanvas(1500, 2000, polaroid.copy(ratio = FrameRatio.OneToOne))
        assertEquals(2405, portrait.width)
        assertEquals(2405, portrait.height)
        assertRect(FrameRect(452.5f, 90f, 1952.5f, 2090f), portrait.photo)
    }

    @Test
    fun a_shrunk_polaroid_moves_only_inside_its_card() {
        fun card(x: Float, y: Float) = frameCanvas(2000, 1500, polaroid.copy(scale = 0.5f, offsetX = x, offsetY = y))
        assertEquals(2180, card(0f, 0f).width)
        assertEquals(1905, card(0f, 0f).height)
        assertRect(FrameRect(590f, 465f, 1590f, 1215f), card(0f, 0f).photo)
        assertRect(FrameRect(90f, 90f, 1090f, 840f), card(-1f, -1f).photo)
        assertRect(FrameRect(1090f, 840f, 2090f, 1590f), card(1f, 1f).photo)
    }

    // ---- shapes ----

    @Test
    fun the_shape_chips_come_in_the_tools_order() {
        assertEquals(
            listOf(
                null, FrameRatio.Original, FrameRatio.OneToOne, FrameRatio.FourFive, FrameRatio.ThreeFour,
                FrameRatio.NineSixteen, FrameRatio.SixteenNine, FrameRatio.Original,
            ),
            FrameShape.entries.map { it.ratio },
        )
        assertEquals(FrameShape.Off, FrameShape.entries.first())
        assertEquals(FrameShape.Polaroid, FrameShape.entries.last())
        assertEquals(listOf("Original", "1:1", "4:5", "3:4", "9:16", "16:9"), FrameRatio.entries.map { it.label })
        assertNull(FrameRatio.Original.ratio)
    }

    @Test
    fun off_is_no_frame_at_all() {
        assertNull(FrameShape.Off.toSpec())
        assertNull(FrameShape.Off.toSpec(FrameFill.Blur))
        assertEquals(FrameShape.Off, frameShapeOf(null))
    }

    @Test
    fun every_shape_opens_at_full_size_in_the_middle_in_the_fill_it_is_given() {
        for (shape in FrameShape.entries.filter { it != FrameShape.Off }) {
            val spec = shape.toSpec(FrameFill.Blur)!!
            assertEquals("$shape", FrameFill.Blur, spec.fill)
            assertEquals("$shape", 1f, spec.scale, 0f)
            assertEquals("$shape", 0f, spec.offsetX, 0f)
            assertEquals("$shape", 0f, spec.offsetY, 0f)
            assertEquals("$shape", shape.ratio, spec.ratio)
        }
        assertEquals(FrameFill.Solid(FRAME_WHITE), FrameShape.OneToOne.toSpec()!!.fill)
    }

    @Test
    fun every_shape_reads_back_as_itself_in_every_fill() {
        val fills = listOf(
            FrameFill.Solid(FRAME_WHITE),
            FrameFill.Solid(FRAME_BLACK),
            FrameFill.Solid(0xFF336699.toInt()),
            FrameFill.Blur,
        )
        for (fill in fills) {
            for (shape in FrameShape.entries) {
                assertEquals("$shape in $fill", shape, frameShapeOf(shape.toSpec(fill)))
            }
        }
        // A pinch or a drag leaves the same chip lit, and a bare spec lights the chip of its ratio.
        val moved = FrameShape.Polaroid.toSpec()!!.afterGesture(0.7f, 50f, -20f, 4000, 3000)
        assertEquals(FrameShape.Polaroid, frameShapeOf(moved))
        assertEquals(FrameShape.FourFive, frameShapeOf(FrameSpec(FrameRatio.FourFive, scale = 0.5f)))
    }

    // ---- the even border every shape opens with ----

    @Test
    fun original_opens_with_the_same_border_on_all_four_sides() {
        // 5% of the 1500 px short side is 75 px all round.
        val landscape = frameCanvas(2000, 1500, FrameShape.Original.toSpec()!!)
        assertEquals(2150, landscape.width)
        assertEquals(1650, landscape.height)
        assertRect(FrameRect(75f, 75f, 2075f, 1575f), landscape.photo)
        val portrait = frameCanvas(1500, 2000, FrameShape.Original.toSpec()!!)
        assertEquals(1650, portrait.width)
        assertEquals(2150, portrait.height)
        assertRect(FrameRect(75f, 75f, 1575f, 2075f), portrait.photo)
    }

    @Test
    fun a_fixed_ratio_opens_with_that_border_on_the_sides_that_hold_the_photo() {
        val square = frameCanvas(2000, 1500, FrameShape.OneToOne.toSpec()!!)
        assertEquals(2150, square.width)
        assertEquals(2150, square.height)
        assertRect(FrameRect(75f, 325f, 2075f, 1825f), square.photo)
        // 5% of 1080 is 54 px beside a 1920x1080 photo in 9:16, with equal bars above and below.
        val story = frameCanvas(1920, 1080, FrameShape.NineSixteen.toSpec()!!)
        assertEquals(2028, story.width)
        assertEquals(3605, story.height)
        assertRect(FrameRect(54f, 1262.5f, 1974f, 2342.5f), story.photo)
    }

    @Test
    fun every_shape_opens_centred_behind_an_even_border() {
        val photos = listOf(
            4000 to 3000, 3000 to 4000, 1920 to 1080, 1080 to 1920, 2000 to 2000, 6000 to 1000, 1000 to 6000,
            641 to 479,
        )
        for (shape in FrameShape.entries.filter { it != FrameShape.Off && it != FrameShape.Polaroid }) {
            for ((w, h) in photos) {
                val c = frameCanvas(w, h, shape.toSpec()!!)
                val p = c.photo
                val left = p.left
                val right = c.width - p.right
                val top = p.top
                val bottom = c.height - p.bottom
                // The border shrinks with the photo where the long-side cap takes the canvas down.
                val border = FRAME_BORDER_MARGIN * min(w, h) * p.width / w
                val where = "$shape ${w}x$h -> ${c.width}x${c.height} $p"
                assertEquals("$where across", left, right, 0.75f)
                assertEquals("$where down", top, bottom, 0.75f)
                assertEquals("$where border", border, min(left, top), 0.75f)
                if (shape == FrameShape.Original) assertEquals("$where all round", left, top, 0.75f)
                assertEquals("$where shape", w.toFloat() / h, p.width / p.height, 0.001f * w / h)
            }
        }
    }

    // ---- the blurred fill ----

    @Test
    fun a_blurred_fill_puts_a_fixed_count_of_pixels_along_the_canvas_long_side() {
        assertEquals(64 to 48, frameBlurSize(4000, 3000, 4000, 3000))
        assertEquals(64 to 48, frameBlurSize(720, 540, 720, 540))
        // A 16:9 picture covering a 9:16 canvas: its height spans the canvas's long side, and its width runs past.
        assertEquals(114 to 64, frameBlurSize(1920, 1080, 1080, 1920))
    }

    @Test
    fun the_preview_and_the_save_blur_the_same_share_of_the_frame() {
        // The preview renders from a copy 720 px long, the save from the full photo.
        val photos = listOf(
            Triple(4000, 3000, 720 to 540),
            Triple(3000, 4000, 540 to 720),
            Triple(4000, 2250, 720 to 405),
        )
        val specs = FrameShape.entries.mapNotNull { it.toSpec(FrameFill.Blur) } +
            FrameSpec(FrameRatio.NineSixteen, FrameFill.Blur, scale = 0.5f)
        for ((w, h, preview) in photos) {
            val (pw, ph) = preview
            for (spec in specs) {
                val full = frameCanvas(w, h, spec)
                val small = frameCanvas(pw, ph, spec)
                val save = frameBlurSize(w, h, full.width, full.height)
                val shown = frameBlurSize(pw, ph, small.width, small.height)
                val where = "$spec ${w}x$h: $save against $shown"
                assertEquals("$where across", save.first.toFloat(), shown.first.toFloat(), 1f)
                assertEquals("$where down", save.second.toFloat(), shown.second.toFloat(), 1f)
            }
        }
    }

    @Test
    fun a_blurred_fill_never_asks_for_a_huge_or_an_empty_copy() {
        val sizes = listOf(
            listOf(1, 5000, 4096, 2304), listOf(10000, 1000, 2304, 4096), listOf(Int.MAX_VALUE, 1, 4096, 4096),
            listOf(0, 0, 0, 0), listOf(-3, 7, 1, -1),
        )
        for ((pw, ph, cw, ch) in sizes) {
            val (w, h) = frameBlurSize(pw, ph, cw, ch)
            val where = "${pw}x$ph over ${cw}x$ch -> ${w}x$h"
            assertTrue(where, w in 1..FRAME_BLUR_MAX_SIDE && h in 1..FRAME_BLUR_MAX_SIDE)
        }
    }

    @Test
    fun softening_a_flat_colour_changes_nothing() {
        val flat = IntArray(20 * 10) { 0xFF336699.toInt() }
        assertArrayEquals(flat, softenArgb(flat, 20, 10, FRAME_BLUR_RADIUS, FRAME_BLUR_PASSES))
    }

    @Test
    fun a_dot_spreads_evenly_over_the_blur_box() {
        // One box blur of radius 2 shares the dot's 255 out over a 5x5 square: 51 across, then 10 down.
        val w = 9
        val dot = IntArray(w * w) { if (it == 4 * w + 4) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val out = softenArgb(dot, w, w, radius = 2, passes = 1)
        for (y in 0 until w) {
            for (x in 0 until w) {
                val v = if (x in 2..6 && y in 2..6) 10 else 0
                assertEquals("($x, $y)", 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v, out[y * w + x])
            }
        }
    }

    @Test
    fun softening_an_edge_makes_a_ramp_mirrored_about_it() {
        // Black on the left half and white on the right: the blur climbs across the edge, the same way on both sides.
        val w = 16
        val h = 3
        val edge = IntArray(w * h) { if (it % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val out = softenArgb(edge, w, h, FRAME_BLUR_RADIUS, FRAME_BLUR_PASSES)
        for (y in 0 until h) {
            val row = (0 until w).map { out[y * w + it] and 0xFF }
            assertEquals("row $y climbs", row.sorted(), row)
            assertTrue("row $y softened: $row", row[w / 2] in 1..254)
            for (x in 0 until w) assertEquals("row $y at $x: $row", 255, row[x] + row[w - 1 - x])
        }
        assertTrue(out.all { it ushr 24 == 0xFF })
    }

    @Test
    fun softening_leaves_its_input_alone_and_needs_a_radius_and_a_pass() {
        val w = 8
        val h = 4
        val edge = IntArray(w * h) { if (it % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val before = edge.copyOf()
        softenArgb(edge, w, h, 2, 3)
        assertArrayEquals(before, edge)
        assertArrayEquals(edge, softenArgb(edge, w, h, 0, 3))
        assertArrayEquals(edge, softenArgb(edge, w, h, 2, 0))
        assertArrayEquals(IntArray(0), softenArgb(IntArray(0), 0, 0, 2, 3))
        assertArrayEquals(edge, softenArgb(edge, w, h + 1, 2, 3))
    }

    // ---- inputs with no sensible answer still get a safe one ----

    @Test
    fun a_photo_with_no_size_or_a_huge_one_still_lays_out() {
        val sizes = listOf(
            0 to 0, -5 to 100, 100 to 0, Int.MIN_VALUE to -1, Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1,
        )
        for ((w, h) in sizes) {
            for (s in listOf(spec(), spec(FrameRatio.NineSixteen, 0.5f, 1f, -1f), polaroid)) {
                val c = frameCanvas(w, h, s)
                val where = "${w}x$h $s -> ${c.width}x${c.height} ${c.photo}"
                assertTrue(where, c.width in 1..FRAME_MAX_LONG_SIDE && c.height in 1..FRAME_MAX_LONG_SIDE)
                assertOnCanvas(where, c)
            }
        }
    }

    @Test
    fun a_cap_of_zero_or_less_still_gives_one_pixel() {
        for (cap in listOf(0, -1, Int.MIN_VALUE)) {
            val c = frameCanvas(4000, 3000, spec(FrameRatio.OneToOne), cap)
            assertEquals("cap $cap", 1, c.width)
            assertEquals("cap $cap", 1, c.height)
            assertOnCanvas("cap $cap", c)
        }
    }

    @Test
    fun a_spec_with_numbers_out_of_range_lays_out_like_the_nearest_valid_one() {
        assertEquals(
            frameCanvas(2000, 1500, spec()),
            frameCanvas(2000, 1500, FrameSpec(margin = Float.NaN, bottomMargin = -3f)),
        )
        assertEquals(
            frameCanvas(2000, 1500, FrameSpec(margin = 1f, bottomMargin = 1f)),
            frameCanvas(2000, 1500, FrameSpec(margin = 50f, bottomMargin = Float.POSITIVE_INFINITY)),
        )
    }

    @Test
    fun a_gesture_on_a_photo_with_no_size_stays_in_range() {
        val out = spec(FrameRatio.OneToOne, scale = 0.5f).afterGesture(0.5f, 40f, -40f, 0, -3, 0)
        assertTrue("${out.scale}", out.scale in FRAME_MIN_SCALE..1f)
        assertTrue("${out.offsetX}", out.offsetX in -1f..1f)
        assertTrue("${out.offsetY}", out.offsetY in -1f..1f)
    }

    @Test
    fun every_layout_keeps_the_photo_whole_on_the_canvas_and_the_ratio_exact() {
        val photos = listOf(
            4000 to 3000, 3000 to 4000, 641 to 479, 4096 to 4096, 12000 to 9000, 1 to 1, 1 to 5000, 5000 to 1,
        )
        val looks = listOf(
            spec(), FrameShape.Original.toSpec(FrameFill.Blur)!!, polaroid, FrameSpec(margin = 1f, bottomMargin = 1f),
        )
        val placements = listOf(FRAME_MIN_SCALE, 0.5f, 0.92f, 1f).flatMap { scale ->
            listOf(0f to 0f, 1f to 1f, -1f to 1f, 0.37f to -0.8f).map { (x, y) -> Triple(scale, x, y) }
        }
        val caps = listOf(FRAME_MAX_LONG_SIDE, 1000, 7, 1)
        for (ratio in FrameRatio.entries) {
            for (look in looks) {
                for ((w, h) in photos) {
                    for ((scale, x, y) in placements) {
                        for (cap in caps) {
                            val s = look.copy(ratio = ratio, scale = scale, offsetX = x, offsetY = y)
                            val c = frameCanvas(w, h, s, cap)
                            val p = c.photo
                            val where = "${w}x$h $s cap $cap -> ${c.width}x${c.height} $p"
                            assertTrue("$where canvas", c.width in 1..cap && c.height in 1..cap)
                            assertOnCanvas(where, c)
                            val expected = ratio.ratio
                            if (expected != null && min(c.width, c.height) >= 100) {
                                val tolerance = expected / c.height + 0.001f
                                assertEquals("$where ratio", expected, c.width.toFloat() / c.height, tolerance)
                            }
                            if (p.width >= 1f && p.height >= 1f) {
                                assertEquals("$where shape", w.toFloat() / h, p.width / p.height, 0.01f * w / h)
                            }
                        }
                    }
                }
            }
        }
    }
}
