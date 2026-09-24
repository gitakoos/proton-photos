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

import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.fitImageInBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the Frame tool's controls reach its geometry: what a shape chip and a fill swatch do to the frame that is
 * on, how far a drag on the preview moves the photo, where the photo snaps onto the middle of the frame, and where
 * the system's back gesture has to give way to that drag.
 */
class FrameControlsTest {

    /** A 1920x1080 photo half size in a bare square: a 1920x1920 canvas with 960 px of room across and 1380 down. */
    private val half = FrameSpec(FrameRatio.OneToOne, scale = 0.5f)

    private val white = FrameFill.Solid(FRAME_WHITE)
    private val black = FrameFill.Solid(FRAME_BLACK)

    // ---- the shape chips ----

    @Test
    fun with_no_frame_on_a_shape_starts_white() {
        val none: FrameSpec? = null
        for (shape in FrameShape.entries.filter { it != FrameShape.Off }) {
            val picked = none.withShape(shape)
            assertEquals("$shape", shape.toSpec(white), picked)
            assertEquals("$shape", white, picked?.fill)
        }
        assertNull(none.withShape(FrameShape.Off))
    }

    @Test
    fun another_shape_keeps_the_fill_and_opens_centred_behind_its_even_border() {
        val placed = FrameShape.OneToOne.toSpec(black)!!.copy(scale = 0.5f, offsetX = 0.4f, offsetY = -0.6f)
        assertEquals(
            FrameSpec(
                ratio = FrameRatio.NineSixteen,
                fill = black,
                margin = FRAME_BORDER_MARGIN,
                bottomMargin = FRAME_BORDER_MARGIN,
            ),
            placed.withShape(FrameShape.NineSixteen),
        )
    }

    @Test
    fun polaroid_keeps_the_fill_and_another_shape_drops_its_card() {
        val blurred = FrameShape.FourFive.toSpec(FrameFill.Blur)!!.copy(scale = 0.7f, offsetY = 0.3f)
        val card = blurred.withShape(FrameShape.Polaroid)
        assertEquals(FrameShape.Polaroid.toSpec(FrameFill.Blur), card)
        assertEquals(FrameShape.Polaroid, frameShapeOf(card))
        val square = card.withShape(FrameShape.OneToOne)
        assertEquals(FrameShape.OneToOne.toSpec(FrameFill.Blur), square)
        assertEquals(FrameShape.OneToOne, frameShapeOf(square))
    }

    @Test
    fun picking_the_lit_shape_again_puts_the_photo_back_in_the_middle() {
        val opened = FrameShape.ThreeFour.toSpec(black)!!
        assertEquals(opened, opened.withShape(FrameShape.ThreeFour))
        val moved = opened.copy(scale = 0.7f, offsetX = -0.2f, offsetY = 0.9f)
        assertEquals(opened, moved.withShape(FrameShape.ThreeFour))
        assertEquals(FrameShape.ThreeFour, frameShapeOf(moved))
    }

    @Test
    fun off_takes_any_frame_away() {
        for (shape in FrameShape.entries) {
            assertNull("$shape", shape.toSpec(black).withShape(FrameShape.Off))
        }
        assertEquals(FrameShape.Off, frameShapeOf(null))
    }

    // ---- the fill swatches ----

    @Test
    fun the_fills_are_white_black_the_other_pen_colours_once_each_and_the_blur() {
        val red = 0xFFFF3B30.toInt()
        val blue = 0xFF007AFF.toInt()
        assertEquals(
            listOf(white, black, FrameFill.Solid(red), FrameFill.Solid(blue), FrameFill.Blur),
            frameFills(listOf(red, FRAME_WHITE, blue, FRAME_BLACK, red)),
        )
        assertEquals(listOf(white, black, FrameFill.Blur), frameFills(emptyList()))
    }

    @Test
    fun a_fill_keeps_the_shape_and_the_placement() {
        val placed = FrameShape.FourFive.toSpec()!!.copy(scale = 0.6f, offsetX = 0.3f, offsetY = -1f)
        assertEquals(placed.copy(fill = black), placed.withFill(black))
        assertEquals(placed.copy(fill = FrameFill.Blur), placed.withFill(black).withFill(FrameFill.Blur))
        val card = FrameShape.Polaroid.toSpec()!!.copy(scale = 0.8f)
        assertEquals(FrameShape.Polaroid, frameShapeOf(card.withFill(FrameFill.Blur)))
    }

    @Test
    fun a_fill_with_no_frame_on_changes_nothing() {
        val none: FrameSpec? = null
        assertNull(none.withFill(black))
        assertNull(none.withFill(FrameFill.Blur))
    }

    // ---- a drag on the preview ----

    /** Where the photo shows in a [boxW] x [boxH] preview, which fits the full-size canvas into it. */
    private fun onScreen(spec: FrameSpec, boxW: Int, boxH: Int): FitBox {
        val c = frameCanvas(1920, 1080, spec)
        val fit = fitImageInBox(c.width.toFloat(), c.height.toFloat(), boxW.toFloat(), boxH.toFloat())
        return fit.toScreen(FitBox(c.photo.left, c.photo.top, c.photo.right, c.photo.bottom))
    }

    @Test
    fun a_drag_moves_the_photo_on_screen_exactly_as_far_as_the_finger() {
        for ((boxW, boxH) in listOf(400 to 800, 1080 to 1500, 900 to 300)) {
            val before = onScreen(half, boxW, boxH)
            val after = onScreen(half.afterPreviewGesture(1f, 30f, -20f, 1920, 1080, boxW, boxH), boxW, boxH)
            assertEquals("${boxW}x$boxH across", 30f, after.left - before.left, 0.01f)
            assertEquals("${boxW}x$boxH down", -20f, after.top - before.top, 0.01f)
        }
    }

    @Test
    fun the_smaller_the_preview_draws_the_canvas_the_further_a_drag_reaches_on_it() {
        // Drawn 480 px wide, the 1920 px canvas has four of its pixels under each screen pixel.
        assertEquals(
            half.afterGesture(1f, 240f, -120f, 1920, 1080),
            half.afterPreviewGesture(1f, 60f, -30f, 1920, 1080, 480, 960),
        )
        // Drawn 960 px wide, two.
        assertEquals(
            half.afterGesture(1f, 120f, -60f, 1920, 1080),
            half.afterPreviewGesture(1f, 60f, -30f, 1920, 1080, 960, 1920),
        )
    }

    @Test
    fun the_side_the_box_holds_the_canvas_by_sets_the_measure() {
        // 4000x3000 in 9:16 lays out on 2304x4096, drawn 540x960 in a 1080x960 box: the height holds it, and
        // 67.5 screen pixels are 288 canvas pixels, half the 576 from the centre to the edge at half size.
        val start = FrameSpec(FrameRatio.NineSixteen, scale = 0.5f)
        assertEquals(0.5f, start.afterPreviewGesture(1f, 67.5f, 0f, 4000, 3000, 1080, 960).offsetX, 1e-5f)
    }

    @Test
    fun a_pinch_passes_straight_through() {
        assertEquals(
            half.afterGesture(0.6f, 0f, 0f, 1920, 1080),
            half.afterPreviewGesture(0.6f, 0f, 0f, 1920, 1080, 400, 800),
        )
    }

    @Test
    fun a_preview_not_measured_yet_still_gives_a_frame_in_range() {
        val out = half.afterPreviewGesture(0.8f, 50f, -50f, 1920, 1080, 0, 0)
        assertTrue("${out.scale}", out.scale in FRAME_MIN_SCALE..1f)
        assertTrue("${out.offsetX}", out.offsetX in -1f..1f)
        assertTrue("${out.offsetY}", out.offsetY in -1f..1f)
        val snapped = half.copy(offsetX = 0.4f, offsetY = -0.7f).snappedToCentre(1920, 1080, 0, 0, 12f).spec
        assertTrue("${snapped.offsetX}", snapped.offsetX in -1f..1f)
        assertTrue("${snapped.offsetY}", snapped.offsetY in -1f..1f)
    }

    // ---- the snap to the middle ----

    /** [this] on a 480x960 preview of a 1920x1080 photo, with four canvas pixels under each screen pixel. */
    private fun FrameSpec.snapOn480(thresholdPx: Float = 12f): FrameSnap =
        snappedToCentre(1920, 1080, 480, 960, thresholdPx)

    @Test
    fun a_photo_near_the_middle_snaps_onto_it_on_that_axis_alone() {
        // 12 screen px reach 48 canvas px: an offset of 0.1 across, with 960 px of room, and about 0.07 down, 1380.
        val nearAcross = half.copy(offsetX = 0.09f, offsetY = 0.5f)
        assertEquals(FrameSnap(nearAcross.copy(offsetX = 0f), true, false), nearAcross.snapOn480())
        val nearDown = half.copy(offsetX = 0.11f, offsetY = -0.06f)
        assertEquals(FrameSnap(nearDown.copy(offsetY = 0f), false, true), nearDown.snapOn480())
        val nearBoth = half.copy(offsetX = -0.05f, offsetY = 0.03f)
        assertEquals(FrameSnap(half, true, true), nearBoth.snapOn480())
    }

    @Test
    fun a_photo_away_from_the_middle_keeps_its_place() {
        val away = half.copy(offsetX = 0.11f, offsetY = -0.08f)
        assertEquals(FrameSnap(away, snappedX = false, snappedY = false), away.snapOn480())
        val edge = half.copy(offsetX = -1f, offsetY = 1f)
        assertEquals(FrameSnap(edge, snappedX = false, snappedY = false), edge.snapOn480())
    }

    @Test
    fun the_reach_is_measured_on_the_preview() {
        // 43.2 canvas px off the middle: 10.8 px on a preview a quarter of the canvas's size, 43.2 on one its own size.
        val near = half.copy(offsetX = 0.09f)
        assertTrue(near.snappedToCentre(1920, 1080, 480, 960, 12f).snappedX)
        assertFalse(near.snappedToCentre(1920, 1080, 1920, 3840, 12f).snappedX)
        // A wider reach takes it there too.
        assertTrue(near.snappedToCentre(1920, 1080, 1920, 3840, 44f).snappedX)
    }

    @Test
    fun an_axis_with_no_room_never_snaps() {
        // At full size a landscape photo spans the bare square's width: centred across, with nowhere to go.
        val full = FrameSpec(FrameRatio.OneToOne).snapOn480()
        assertFalse(full.snappedX)
        assertTrue(full.snappedY)
        // Every shape opens with the photo filling the room between its borders on at least one axis.
        val opened = FrameShape.Original.toSpec()!!.snapOn480()
        assertFalse(opened.snappedX || opened.snappedY)
    }

    @Test
    fun a_polaroid_snaps_to_the_middle_of_the_window_in_its_card() {
        // 2000x1500 at half size: the window between the borders runs from 90 to 1590 down, so its middle is at
        // 840, above the card's own middle at 952.5. The preview draws the 2180x1905 card at its own size.
        val card = FrameShape.Polaroid.toSpec()!!.copy(scale = 0.5f, offsetY = 0.02f)
        val snap = card.snappedToCentre(2000, 1500, 2180, 1905, 12f)
        assertTrue(snap.snappedY)
        val p = frameCanvas(2000, 1500, snap.spec).photo
        assertEquals(840f, (p.top + p.bottom) / 2f, 0.01f)
    }

    @Test
    fun a_drag_that_ends_near_the_middle_lands_on_it() {
        // 10 screen px across on the 480 px preview are 40 canvas px, inside the 48 the snap reaches.
        val dragged = half.afterPreviewGesture(1f, 10f, 0f, 1920, 1080, 480, 960)
        assertEquals(1f / 12f, dragged.offsetX, 1e-5f)
        assertEquals(0f, dragged.snapOn480().spec.offsetX, 0f)
        // 15 are 60, past it, and the photo stays under the finger.
        val further = half.afterPreviewGesture(1f, 15f, 0f, 1920, 1080, 480, 960)
        assertEquals(further, further.snapOn480().spec)
    }

    // ---- where the back gesture gives way ----

    /** A photo drawn edge to edge across a 1000 px wide preview, 600 px tall around y = 500. */
    private val wide = FitBox(0f, 200f, 1000f, 800f)

    private fun strips(photo: FitBox, left: Float = 60f, right: Float = 60f) = frameGestureExclusions(
        photo = photo,
        containerWidthPx = 1000f,
        leftInsetPx = left,
        rightInsetPx = right,
        maxHeightPx = 400f,
    )

    @Test
    fun a_photo_at_both_edges_gets_a_strip_down_the_middle_of_each_side() {
        assertEquals(listOf(FitBox(0f, 300f, 60f, 700f), FitBox(940f, 300f, 1000f, 700f)), strips(wide))
    }

    @Test
    fun a_photo_shorter_than_the_budget_is_covered_along_its_whole_side() {
        val short = FitBox(10f, 450f, 990f, 550f)
        assertEquals(listOf(FitBox(0f, 450f, 60f, 550f), FitBox(940f, 450f, 1000f, 550f)), strips(short))
    }

    @Test
    fun only_a_side_that_reaches_into_its_strip_asks() {
        assertEquals(listOf(FitBox(0f, 300f, 60f, 700f)), strips(FitBox(20f, 200f, 700f, 800f)))
        assertEquals(listOf(FitBox(940f, 300f, 1000f, 700f)), strips(FitBox(300f, 200f, 980f, 800f)))
        // A photo that stops at the strips leaves back reachable all the way down both edges.
        assertEquals(emptyList<FitBox>(), strips(FitBox(60f, 200f, 940f, 800f)))
    }

    @Test
    fun no_edge_gesture_or_no_photo_asks_for_nothing() {
        assertEquals(emptyList<FitBox>(), strips(wide, left = 0f, right = 0f))
        assertEquals(emptyList<FitBox>(), strips(FitBox(0f, 500f, 1000f, 500f)))
        assertEquals(emptyList<FitBox>(), strips(FitBox(0f, Float.NaN, 1000f, 800f)))
    }
}
