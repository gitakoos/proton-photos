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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import eu.akoos.photos.util.ImageFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins how the photo crop overlay reads a touch: a lone finger drags a handle once it is past the
 * slop, a second finger turns the gesture into a pinch until every finger is up, and the pinch
 * scales about the point between the fingers in the photo's own pixels.
 */
class PhotoEditorCropGestureTest {

    private val slop = 24f

    private fun after(mode: CropGestureMode, fingers: Int, travelled: Float = 0f) =
        nextCropGestureMode(mode, fingers, travelled, slop)

    @Test
    fun a_lone_finger_drags_only_once_it_is_past_the_slop() {
        assertEquals(CropGestureMode.Undecided, after(CropGestureMode.Undecided, 1, 0f))
        assertEquals(CropGestureMode.Undecided, after(CropGestureMode.Undecided, 1, 24f))
        assertEquals(CropGestureMode.Drag, after(CropGestureMode.Undecided, 1, 24.5f))
    }

    @Test
    fun a_tap_that_lifts_inside_the_slop_never_becomes_a_drag() {
        assertEquals(CropGestureMode.Undecided, after(CropGestureMode.Undecided, 0, 10f))
    }

    @Test
    fun a_second_finger_makes_a_pinch_before_or_during_a_drag() {
        assertEquals(CropGestureMode.Pinch, after(CropGestureMode.Undecided, 2))
        assertEquals(CropGestureMode.Pinch, after(CropGestureMode.Undecided, 2, 500f))
        assertEquals(CropGestureMode.Pinch, after(CropGestureMode.Drag, 2, 500f))
        assertEquals(CropGestureMode.Pinch, after(CropGestureMode.Drag, 3, 500f))
    }

    @Test
    fun a_pinch_never_falls_back_to_a_drag_while_a_finger_is_down() {
        assertEquals(CropGestureMode.Pinch, after(CropGestureMode.Pinch, 1, 500f))
        assertEquals(CropGestureMode.Pinch, after(CropGestureMode.Pinch, 0))
    }

    @Test
    fun a_drag_stays_a_drag_while_its_finger_is_down() {
        assertEquals(CropGestureMode.Drag, after(CropGestureMode.Drag, 1, 0f))
        assertEquals(CropGestureMode.Drag, after(CropGestureMode.Drag, 1, 900f))
    }

    // ---- where a pinch scales about ----

    /** The photo drawn at half size inside a 20 x 40 letterbox. */
    private val fit = ImageFit(scale = 0.5f, offsetX = 20f, offsetY = 40f)

    @Test
    fun the_focus_is_the_midpoint_of_two_fingers_in_photo_pixels() {
        // Midpoint (200, 400) on screen, less the letterbox, at twice the drawn size.
        val focus = cropPinchFocus(listOf(Offset(100f, 300f), Offset(300f, 500f)), fit)
        assertEquals(360f, focus.x, 1e-3f)
        assertEquals(720f, focus.y, 1e-3f)
    }

    @Test
    fun more_fingers_average_to_their_centre() {
        // Centre (50, 70) on screen.
        val focus = cropPinchFocus(listOf(Offset(20f, 40f), Offset(80f, 40f), Offset(50f, 130f)), fit)
        assertEquals(60f, focus.x, 1e-3f)
        assertEquals(60f, focus.y, 1e-3f)
    }

    @Test
    fun a_lone_finger_left_after_a_pinch_is_its_own_focus() {
        val focus = cropPinchFocus(listOf(Offset(220f, 140f)), fit)
        assertEquals(400f, focus.x, 1e-3f)
        assertEquals(200f, focus.y, 1e-3f)
    }

    @Test
    fun no_fingers_give_no_focus() {
        assertFalse(cropPinchFocus(emptyList(), fit).isSpecified)
    }
}
