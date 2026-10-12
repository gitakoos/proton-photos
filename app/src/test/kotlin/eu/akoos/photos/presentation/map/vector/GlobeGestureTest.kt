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

package eu.akoos.photos.presentation.map.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how the globe follows the fingers: the point under a touch is found and put back under the touch after the
 * zoom changes, a drag moves the globe exactly as far as the finger, and a touch off the globe finds nothing.
 */
class GlobeGestureTest {

    private val cx = 540f
    private val cy = 1100f

    @Test
    fun the_point_under_the_centre_of_the_screen_is_the_centre_of_the_globe() {
        val geo = geoAt(cx, cy, cx, cy, 800.0, 47.5, 19.0)
        assertNotNull(geo)
        assertEquals(47.5, geo!!.first, 1e-6)
        assertEquals(19.0, geo.second, 1e-6)
    }

    @Test
    fun a_point_found_on_screen_projects_back_to_the_same_spot() {
        val radius = 5_000.0
        val (lat, lng) = geoAt(700f, 900f, cx, cy, radius, 47.5, 19.0)!!
        val back = GlobeProjection.project(lat, lng, 47.5, 19.0, radius)
        assertEquals(700f, cx + back.x, 0.05f)
        assertEquals(900f, cy + back.y, 0.05f)
    }

    @Test
    fun a_touch_outside_the_globe_finds_no_point() {
        assertNull(geoAt(cx + 900f, cy, cx, cy, 800.0, 47.5, 19.0))
    }

    @Test
    fun zooming_keeps_the_spot_between_the_fingers_between_the_fingers() {
        val fingersX = 800f
        val fingersY = 600f
        val before = 3_000.0
        val after = 12_000.0
        val (lat, lng) = geoAt(fingersX, fingersY, cx, cy, before, 47.5, 19.0)!!
        val (newLat, newLng) = centerKeepingPoint(lat, lng, fingersX, fingersY, cx, cy, after, 47.5, 19.0)!!
        val where = GlobeProjection.project(lat, lng, newLat, newLng, after)
        assertEquals(fingersX, cx + where.x, 0.3f)
        assertEquals(fingersY, cy + where.y, 0.3f)
    }

    @Test
    fun a_drag_turns_the_globe_exactly_as_far_as_the_finger_moves() {
        val radius = 8_000.0
        val (lat, lng) = geoAt(500f, 800f, cx, cy, radius, 47.5, 19.0)!!
        // The finger moves 120 pixels to the right and 60 up; the same spot has to follow it.
        val (newLat, newLng) = centerKeepingPoint(lat, lng, 620f, 740f, cx, cy, radius, 47.5, 19.0)!!
        val where = GlobeProjection.project(lat, lng, newLat, newLng, radius)
        assertEquals(620f, cx + where.x, 0.3f)
        assertEquals(740f, cy + where.y, 0.3f)
    }

    @Test
    fun a_spot_that_cannot_be_kept_under_the_finger_gives_no_answer() {
        // Asking for a spot to appear on the far side of the globe cannot be met.
        val answer = centerKeepingPoint(47.5, 19.0, cx + 5_000f, cy, cx, cy, 800.0, 47.5, 19.0)
        assertNull(answer)
    }

    @Test
    fun a_pixel_covers_fewer_degrees_the_larger_the_globe() {
        assertEquals(degreesPerPixel(1_000.0) / 2.0, degreesPerPixel(2_000.0), 1e-9)
        assertEquals(0.0573, degreesPerPixel(1_000.0), 0.0001)
    }
}
