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

package eu.akoos.photos.presentation.duplicates

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how a copy is enlarged in the duplicate review: the spot under the fingers stays under them, the
 * picture can never slide off the view, the scale stays between fitted and the maximum, and a double tap
 * toggles between fitted and enlarged.
 */
class PhotoZoomTest {

    private val view = Size(1000f, 2000f)

    @Test
    fun the_point_under_the_fingers_stays_put_while_zooming() {
        val centroid = Offset(750f, 500f)
        val zoomed = applyPinchPan(PhotoZoom(), zoomChange = 2f, panChange = Offset.Zero, centroid = centroid, view = view)
        assertEquals(2f, zoomed.scale, 0.0001f)
        // A point drawn at the centroid when fitted must still be drawn there once enlarged:
        // centre + scale * (point - centre) + offset == point.
        val cx = view.width / 2f
        val cy = view.height / 2f
        assertEquals(centroid.x, cx + zoomed.scale * (centroid.x - cx) + zoomed.offset.x, 0.001f)
        assertEquals(centroid.y, cy + zoomed.scale * (centroid.y - cy) + zoomed.offset.y, 0.001f)
    }

    @Test
    fun the_picture_cannot_be_dragged_past_its_edges() {
        val twice = PhotoZoom(scale = 2f)
        val dragged = applyPinchPan(twice, 1f, Offset(10_000f, -10_000f), Offset(500f, 1000f), view)
        assertEquals(500f, dragged.offset.x, 0.001f)
        assertEquals(-1000f, dragged.offset.y, 0.001f)
    }

    @Test
    fun the_scale_stops_at_the_maximum_and_returns_to_fitted_at_one() {
        val capped = applyPinchPan(PhotoZoom(scale = 5f), 4f, Offset.Zero, Offset(500f, 1000f), view)
        assertEquals(MAX_REVIEW_ZOOM, capped.scale, 0.0001f)
        val back = applyPinchPan(PhotoZoom(scale = 1.5f, offset = Offset(30f, 40f)), 0.1f, Offset.Zero, Offset(500f, 1000f), view)
        assertEquals(PhotoZoom(), back)
        assertFalse(back.isZoomed)
    }

    @Test
    fun a_double_tap_enlarges_around_the_tap_and_a_second_one_fits_again() {
        val tap = Offset(250f, 400f)
        val enlarged = applyDoubleTap(PhotoZoom(), tap, view)
        assertEquals(DOUBLE_TAP_ZOOM, enlarged.scale, 0.0001f)
        assertTrue(enlarged.isZoomed)
        val cx = view.width / 2f
        assertEquals(tap.x, cx + enlarged.scale * (tap.x - cx) + enlarged.offset.x, 0.001f)
        assertEquals(PhotoZoom(), applyDoubleTap(enlarged, tap, view))
    }
}
