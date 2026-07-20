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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the crop overlay's resize math. The behaviour that matters to a user is that a chosen
 * aspect stays chosen: picking 4:3 and then dragging a corner inwards has to give a smaller
 * 4:3 crop, including when the drag runs off the edge of the photo.
 */
class CropGeometryTest {

    private val minPx = 32
    private val boundsW = 4000
    private val boundsH = 3000

    /** Rounding to whole pixels perturbs the ratio by up to half a pixel on the derived axis. */
    private fun assertRatio(expected: Float, box: CropBox) {
        val tolerance = expected / box.height + 0.001f
        assertEquals(
            "ratio of ${box.width}x${box.height}",
            expected,
            box.width.toFloat() / box.height,
            tolerance,
        )
    }

    private fun assertInBounds(box: CropBox) {
        assertTrue("left ${box.left}", box.left >= 0)
        assertTrue("top ${box.top}", box.top >= 0)
        assertTrue("right ${box.right} vs $boundsW", box.right <= boundsW)
        assertTrue("bottom ${box.bottom} vs $boundsH", box.bottom <= boundsH)
    }

    // ---- centeredCrop ----

    @Test
    fun a_square_out_of_a_landscape_source_is_pillarboxed() {
        val box = centeredCrop(4000, 3000, 1f)
        assertEquals(CropBox(500, 0, 3500, 3000), box)
    }

    @Test
    fun a_wide_shape_out_of_a_portrait_source_is_letterboxed() {
        val box = centeredCrop(3000, 4000, 16f / 9f)
        assertEquals(0, box.left)
        assertEquals(3000, box.right)
        assertRatio(16f / 9f, box)
        // Integer division puts the odd leftover pixel below the crop, so the two margins can
        // differ by one.
        assertTrue("centred vertically", kotlin.math.abs(box.top - (4000 - box.bottom)) <= 1)
    }

    @Test
    fun the_source_ratio_selects_the_whole_image() {
        val box = centeredCrop(4000, 3000, 4f / 3f)
        assertEquals(CropBox(0, 0, 4000, 3000), box)
    }

    // ---- free-form stays exactly as it was ----

    @Test
    fun a_free_corner_drag_moves_only_its_own_two_edges() {
        val box = CropBox(0, 0, 4000, 3000)
        val out = resizeCrop(box, CropHandle.TopLeft, 1000, 500, null, boundsW, boundsH, minPx)
        assertEquals(CropBox(1000, 500, 4000, 3000), out)
    }

    @Test
    fun a_free_edge_drag_moves_only_its_own_edge() {
        val box = CropBox(100, 200, 3000, 2000)
        val out = resizeCrop(box, CropHandle.Right, 2500, 1234, null, boundsW, boundsH, minPx)
        assertEquals(CropBox(100, 200, 2500, 2000), out)
    }

    @Test
    fun a_free_drag_cannot_cross_the_opposite_edge() {
        val box = CropBox(0, 0, 4000, 3000)
        val out = resizeCrop(box, CropHandle.TopLeft, 3999, 2999, null, boundsW, boundsH, minPx)
        assertEquals(4000 - minPx, out.left)
        assertEquals(3000 - minPx, out.top)
    }

    // ---- a locked corner ----

    @Test
    fun a_locked_corner_drag_keeps_the_ratio() {
        val box = CropBox(0, 0, 4000, 3000)
        val out = resizeCrop(box, CropHandle.TopLeft, 1500, 1900, 4f / 3f, boundsW, boundsH, minPx)
        assertRatio(4f / 3f, out)
        assertInBounds(out)
        assertTrue("must have shrunk", out.width < box.width)
    }

    @Test
    fun a_locked_corner_drag_leaves_the_opposite_corner_alone() {
        val box = CropBox(0, 0, 4000, 3000)
        val out = resizeCrop(box, CropHandle.TopLeft, 1500, 1900, 4f / 3f, boundsW, boundsH, minPx)
        assertEquals("right edge pinned", 4000, out.right)
        assertEquals("bottom edge pinned", 3000, out.bottom)
    }

    @Test
    fun the_bottom_right_corner_pins_the_top_left() {
        val box = CropBox(400, 300, 3600, 2700)
        val out = resizeCrop(box, CropHandle.BottomRight, 2000, 1000, 1f, boundsW, boundsH, minPx)
        assertEquals(400, out.left)
        assertEquals(300, out.top)
        assertRatio(1f, out)
    }

    @Test
    fun a_locked_corner_follows_whichever_axis_the_finger_reached_furthest() {
        val box = CropBox(0, 0, 4000, 3000)
        // Finger far along y but barely along x: the height it implies must win.
        val out = resizeCrop(box, CropHandle.TopLeft, 3900, 100, 1f, boundsW, boundsH, minPx)
        assertTrue("height should follow the far axis", out.height > 2500)
        assertRatio(1f, out)
    }

    // ---- the edge of the photo ----

    @Test
    fun a_locked_drag_past_the_edge_stays_inside_and_keeps_the_ratio() {
        // Anchor near the right edge, so a wide lock cannot fit and has to shrink on both axes.
        val box = CropBox(3000, 1000, 3900, 2000)
        val out = resizeCrop(box, CropHandle.BottomRight, 999999, 999999, 16f / 9f, boundsW, boundsH, minPx)
        assertInBounds(out)
        assertRatio(16f / 9f, out)
        assertEquals("anchor held", 3000, out.left)
        assertEquals("anchor held", 1000, out.top)
    }

    @Test
    fun a_tall_lock_against_a_short_edge_shrinks_rather_than_clipping() {
        val box = CropBox(100, 2000, 500, 2900)
        val out = resizeCrop(box, CropHandle.BottomRight, 3500, 999999, 9f / 16f, boundsW, boundsH, minPx)
        assertInBounds(out)
        assertRatio(9f / 16f, out)
    }

    // ---- locked edges ----

    @Test
    fun a_locked_left_edge_drag_changes_both_axes() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val out = resizeCrop(box, CropHandle.Left, 2000, 0, 4f / 3f, boundsW, boundsH, minPx)
        assertEquals("right edge pinned", 3000, out.right)
        assertRatio(4f / 3f, out)
        assertTrue("height must follow the width", out.height != box.height)
    }

    @Test
    fun a_locked_top_edge_drag_changes_both_axes() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val out = resizeCrop(box, CropHandle.Top, 0, 1800, 4f / 3f, boundsW, boundsH, minPx)
        assertEquals("bottom edge pinned", 2500, out.bottom)
        assertRatio(4f / 3f, out)
        assertTrue("width must follow the height", out.width != box.width)
    }

    @Test
    fun a_locked_edge_drag_keeps_the_untouched_axis_centred() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val before = (box.top + box.bottom) / 2
        val out = resizeCrop(box, CropHandle.Left, 2200, 0, 1f, boundsW, boundsH, minPx)
        assertEquals("centre held", before, (out.top + out.bottom) / 2)
    }

    @Test
    fun a_locked_edge_drag_slides_off_the_bitmap_edge_instead_of_overflowing() {
        // Centring a tall shape on this box would put its top above 0.
        val box = CropBox(1000, 0, 3000, 200)
        val out = resizeCrop(box, CropHandle.Right, 3900, 0, 9f / 16f, boundsW, boundsH, minPx)
        assertInBounds(out)
        assertRatio(9f / 16f, out)
    }

    // ---- the minimum ----

    @Test
    fun shrinking_a_lock_to_nothing_still_leaves_both_axes_usable() {
        val box = CropBox(0, 0, 4000, 3000)
        val out = resizeCrop(box, CropHandle.TopLeft, 4000, 3000, 4f / 3f, boundsW, boundsH, minPx)
        assertTrue("width ${out.width}", out.width >= minPx)
        assertTrue("height ${out.height}", out.height >= minPx)
        assertRatio(4f / 3f, out)
    }

    @Test
    fun a_tall_lock_at_its_minimum_is_not_a_sliver() {
        val box = CropBox(0, 0, 4000, 3000)
        val out = resizeCrop(box, CropHandle.BottomRight, 0, 0, 9f / 16f, boundsW, boundsH, minPx)
        assertTrue("width ${out.width}", out.width >= minPx)
        assertTrue("height ${out.height}", out.height >= minPx)
    }

    @Test
    fun an_image_smaller_than_the_minimum_still_yields_a_rect_inside_it() {
        val tiny = 20
        val box = CropBox(0, 0, tiny, tiny)
        val out = resizeCrop(box, CropHandle.BottomRight, 999, 999, 1f, tiny, tiny, minPx)
        assertTrue(out.right <= tiny)
        assertTrue(out.bottom <= tiny)
        assertTrue(out.width > 0)
        assertTrue(out.height > 0)
    }

    // ---- translate ----

    @Test
    fun an_inside_drag_preserves_the_size() {
        val box = CropBox(100, 100, 1100, 850)
        val out = resizeCrop(box, CropHandle.Inside, 900, 700, 4f / 3f, boundsW, boundsH, minPx, 50, 40)
        assertEquals(box.width, out.width)
        assertEquals(box.height, out.height)
        assertEquals(850, out.left)
        assertEquals(660, out.top)
    }

    @Test
    fun an_inside_drag_clamps_to_the_bitmap() {
        val box = CropBox(100, 100, 1100, 850)
        val out = resizeCrop(box, CropHandle.Inside, 999999, 999999, 1f, boundsW, boundsH, minPx, 0, 0)
        assertInBounds(out)
        assertEquals(box.width, out.width)
        assertEquals(box.height, out.height)
    }

    // ---- the opposite anchors, so no handle is left unpinned ----

    @Test
    fun the_right_edge_pins_the_left() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val out = resizeCrop(box, CropHandle.Right, 2400, 0, 4f / 3f, boundsW, boundsH, minPx)
        assertEquals("left edge pinned", 1000, out.left)
        assertRatio(4f / 3f, out)
    }

    @Test
    fun the_bottom_edge_pins_the_top() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val out = resizeCrop(box, CropHandle.Bottom, 0, 2000, 4f / 3f, boundsW, boundsH, minPx)
        assertEquals("top edge pinned", 1000, out.top)
        assertRatio(4f / 3f, out)
    }

    @Test
    fun the_top_right_corner_pins_the_bottom_left() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val out = resizeCrop(box, CropHandle.TopRight, 2000, 2000, 1f, boundsW, boundsH, minPx)
        assertEquals("left edge pinned", 1000, out.left)
        assertEquals("bottom edge pinned", 2500, out.bottom)
        assertRatio(1f, out)
    }

    @Test
    fun the_bottom_left_corner_pins_the_top_right() {
        val box = CropBox(1000, 1000, 3000, 2500)
        val out = resizeCrop(box, CropHandle.BottomLeft, 2000, 2000, 1f, boundsW, boundsH, minPx)
        assertEquals("right edge pinned", 3000, out.right)
        assertEquals("top edge pinned", 1000, out.top)
        assertRatio(1f, out)
    }

    @Test
    fun every_locked_handle_respects_the_minimum_on_both_axes() {
        val ratios = listOf(1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f)
        val box = CropBox(1200, 900, 2600, 2000)
        for (ratio in ratios) {
            for (handle in CropHandle.entries.filter { it != CropHandle.Inside }) {
                // Aim the finger straight at the anchor, which asks for a zero-size crop.
                val (at, _) = anchorAndBeyond(box, handle)
                val out = resizeCrop(box, handle, at.first, at.second, ratio, boundsW, boundsH, minPx)
                assertTrue("$handle ratio=$ratio width ${out.width} of $out", out.width >= minPx)
                assertTrue("$handle ratio=$ratio height ${out.height} of $out", out.height >= minPx)
            }
        }
    }

    // ---- direction: a handle dragged past its own anchor must collapse, never re-grow ----

    @Test
    fun a_locked_corner_dragged_past_its_anchor_collapses_instead_of_mirroring() {
        val box = CropBox(1000, 500, 2000, 1500)
        // TopLeft anchors on (2000, 1500). The finger goes beyond it, into the opposite quadrant.
        val out = resizeCrop(box, CropHandle.TopLeft, 2500, 1800, 1f, boundsW, boundsH, minPx)
        assertEquals("anchor held", 2000, out.right)
        assertEquals("anchor held", 1500, out.bottom)
        assertTrue("must not re-grow away from the finger, got ${out.width}", out.width <= minPx + 1)
    }

    @Test
    fun dragging_further_past_the_anchor_does_not_grow_the_crop() {
        val box = CropBox(1000, 500, 2000, 1500)
        val justPast = resizeCrop(box, CropHandle.TopLeft, 2100, 1600, 1f, boundsW, boundsH, minPx)
        val farPast = resizeCrop(box, CropHandle.TopLeft, 4000, 3000, 1f, boundsW, boundsH, minPx)
        assertEquals("size must not depend on how far past the anchor the finger is",
            justPast.width, farPast.width)
    }

    @Test
    fun a_locked_left_edge_dragged_past_its_anchor_collapses() {
        val box = CropBox(1000, 500, 2000, 1500)
        val out = resizeCrop(box, CropHandle.Left, 3000, 0, 1f, boundsW, boundsH, minPx)
        assertEquals("anchor held", 2000, out.right)
        assertTrue("got ${out.width}", out.width <= minPx + 1)
    }

    @Test
    fun a_locked_bottom_edge_dragged_past_its_anchor_collapses() {
        val box = CropBox(1000, 500, 2000, 1500)
        val out = resizeCrop(box, CropHandle.Bottom, 0, 100, 1f, boundsW, boundsH, minPx)
        assertEquals("anchor held", 500, out.top)
        assertTrue("got ${out.height}", out.height <= minPx + 1)
    }

    /** The anchor a handle pivots on, and a finger placed well past it. */
    private fun anchorAndBeyond(box: CropBox, handle: CropHandle): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val d = 900
        return when (handle) {
            CropHandle.TopLeft -> (box.right to box.bottom) to (box.right + d to box.bottom + d)
            CropHandle.TopRight -> (box.left to box.bottom) to (box.left - d to box.bottom + d)
            CropHandle.BottomLeft -> (box.right to box.top) to (box.right + d to box.top - d)
            CropHandle.BottomRight -> (box.left to box.top) to (box.left - d to box.top - d)
            CropHandle.Left -> (box.right to 0) to (box.right + d to 0)
            CropHandle.Right -> (box.left to 0) to (box.left - d to 0)
            CropHandle.Top -> (0 to box.bottom) to (0 to box.bottom + d)
            CropHandle.Bottom -> (0 to box.top) to (0 to box.top - d)
            CropHandle.Inside -> (0 to 0) to (0 to 0)
        }
    }

    @Test
    fun no_locked_handle_grows_once_the_finger_is_past_its_anchor() {
        val ratios = listOf(1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f)
        val box = CropBox(1200, 900, 2600, 2000)
        for (ratio in ratios) {
            for (handle in CropHandle.entries.filter { it != CropHandle.Inside }) {
                val (at, beyond) = anchorAndBeyond(box, handle)
                val onAnchor = resizeCrop(box, handle, at.first, at.second, ratio, boundsW, boundsH, minPx)
                val pastIt = resizeCrop(box, handle, beyond.first, beyond.second, ratio, boundsW, boundsH, minPx)
                assertEquals(
                    "$handle ratio=$ratio: crossing the anchor must not resurrect the crop " +
                        "(on anchor $onAnchor, past it $pastIt)",
                    onAnchor,
                    pastIt,
                )
            }
        }
    }

    // ---- the chip model ----

    @Test
    fun free_locks_nothing_and_original_locks_the_photos_own_shape() {
        assertEquals(null, CropAspect.Free.lockRatio(4032, 3024))
        assertEquals(4f / 3f, CropAspect.Original.lockRatio(4032, 3024)!!, 0.0005f)
        assertEquals(3f / 4f, CropAspect.Original.lockRatio(3024, 4032)!!, 0.0005f)
        assertEquals(1f, CropAspect.OneToOne.lockRatio(4032, 3024)!!, 0.0005f)
    }

    @Test
    fun a_quarter_turn_turns_the_locked_shape_with_the_photo() {
        assertEquals(CropAspect.ThreeFour, CropAspect.FourThree.turned())
        assertEquals(CropAspect.FourThree, CropAspect.ThreeFour.turned())
        assertEquals(CropAspect.NineSixteen, CropAspect.SixteenNine.turned())
        assertEquals(CropAspect.SixteenNine, CropAspect.NineSixteen.turned())
    }

    @Test
    fun the_symmetric_shapes_survive_a_turn_unchanged() {
        assertEquals(CropAspect.Free, CropAspect.Free.turned())
        assertEquals(CropAspect.OneToOne, CropAspect.OneToOne.turned())
        // Original needs no mapping: its ratio is read from the already-turned bitmap.
        assertEquals(CropAspect.Original, CropAspect.Original.turned())
        assertEquals(3f / 4f, CropAspect.Original.turned().lockRatio(3024, 4032)!!, 0.0005f)
    }

    @Test
    fun turning_a_shape_twice_returns_it() {
        for (aspect in CropAspect.entries) {
            assertEquals("$aspect", aspect, aspect.turned().turned())
        }
    }

    @Test
    fun a_turned_shape_is_the_reciprocal_ratio() {
        val w = 4000
        val h = 2500
        for (aspect in CropAspect.entries.filter { it != CropAspect.Free }) {
            val before = aspect.lockRatio(w, h)!!
            // After a quarter turn the display's axes swap, so the bitmap is queried as h x w.
            val after = aspect.turned().lockRatio(h, w)!!
            assertEquals("$aspect", 1f / before, after, 0.0005f)
        }
    }

    // ---- the invariant, across the whole space ----

    @Test
    fun every_locked_handle_and_ratio_stays_in_bounds_and_on_ratio() {
        val ratios = listOf(1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f, boundsW.toFloat() / boundsH)
        val handles = CropHandle.entries.filter { it != CropHandle.Inside }
        val starts = listOf(
            CropBox(0, 0, boundsW, boundsH),
            CropBox(1000, 800, 3000, 2200),
            CropBox(3800, 2800, 4000, 3000),
            CropBox(0, 0, 60, 45),
        )
        val fingers = listOf(
            0 to 0, boundsW to boundsH, 2000 to 1500, 40 to 2900, 3990 to 10, -5000 to -5000, 99999 to 99999,
        )
        for (ratio in ratios) {
            for (handle in handles) {
                for (start in starts) {
                    for ((fx, fy) in fingers) {
                        val out = resizeCrop(start, handle, fx, fy, ratio, boundsW, boundsH, minPx)
                        val where = "$handle ratio=$ratio start=$start finger=$fx,$fy -> $out"
                        assertTrue("$where left", out.left >= 0)
                        assertTrue("$where top", out.top >= 0)
                        assertTrue("$where right", out.right <= boundsW)
                        assertTrue("$where bottom", out.bottom <= boundsH)
                        assertTrue("$where width", out.width > 0)
                        assertTrue("$where height", out.height > 0)
                        val tolerance = ratio / out.height + 0.001f
                        assertEquals(where, ratio, out.width.toFloat() / out.height, tolerance)
                    }
                }
            }
        }
    }
}
