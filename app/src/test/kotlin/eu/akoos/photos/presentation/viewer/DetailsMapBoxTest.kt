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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.presentation.map.vector.GlobeProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins which fixes the details Map box places, and how far its World Map globe zooms in. Plain JVM
 * assertions, no Android.
 */
class DetailsMapBoxTest {

    // region which fixes the box places

    @Test
    fun `an ordinary fix is placed as given`() {
        val point = mappablePoint(47.4979, 19.0402)

        assertEquals(47.4979, point!!.latitude, 1e-9)
        assertEquals(19.0402, point.longitude, 1e-9)
    }

    @Test
    fun `the ends of both ranges are real places`() {
        assertNotNull(mappablePoint(90.0, 180.0))
        assertNotNull(mappablePoint(-90.0, -180.0))
    }

    @Test
    fun `a fix at zero zero is placed like any other`() {
        // The Location rows and the map both show it. Only a Takeout sidecar reads it as "no location",
        // and that import drops it before anything is stored.
        assertNotNull(mappablePoint(0.0, 0.0))
    }

    @Test
    fun `a missing half leaves the box out`() {
        assertNull(mappablePoint(null, 19.0402))
        assertNull(mappablePoint(47.4979, null))
        assertNull(mappablePoint(null, null))
    }

    @Test
    fun `a value that is not a finite number leaves the box out`() {
        assertNull(mappablePoint(Double.NaN, 19.0402))
        assertNull(mappablePoint(47.4979, Double.NaN))
        assertNull(mappablePoint(Double.POSITIVE_INFINITY, 19.0402))
        assertNull(mappablePoint(47.4979, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a value outside its range leaves the box out`() {
        assertNull(mappablePoint(90.0001, 19.0402))
        assertNull(mappablePoint(-90.0001, 19.0402))
        assertNull(mappablePoint(47.4979, 180.0001))
        assertNull(mappablePoint(47.4979, -180.0001))
    }

    // endregion
    // region how far the globe zooms in

    @Test
    fun `half the span from the centre lands on the edge of the short side`() {
        val shortSide = 150f
        val span = 20.0
        val radius = globeRadiusForSpan(shortSide, span)

        // Screen y grows downward, so a point due north sits above the centre.
        val north = GlobeProjection.project(10.0 + span / 2, 5.0, 10.0, 5.0, radius)

        assertEquals(0f, north.x, 1e-3f)
        assertEquals(-shortSide / 2, north.y, 1e-3f)
        assertTrue(north.visible)
    }

    @Test
    fun `a hemisphere span fits the whole globe to the short side`() {
        assertEquals(75.0, globeRadiusForSpan(150f, 180.0), 1e-9)
    }

    @Test
    fun `the card frames the country and its neighbours`() {
        // A photo in Budapest on the narrowest card the sheet lays out: Vienna and Belgrade are in frame,
        // while Paris and Rome are well outside it.
        val width = 320f
        val height = 150f
        val radius = globeRadiusForSpan(minOf(width, height), DETAILS_GLOBE_SPAN_DEG)

        fun inFrame(lat: Double, lng: Double): Boolean {
            val p = GlobeProjection.project(lat, lng, 47.4979, 19.0402, radius)
            return p.visible && abs(p.x) <= width / 2 && abs(p.y) <= height / 2
        }

        assertTrue(inFrame(48.2082, 16.3738))
        assertTrue(inFrame(44.7866, 20.4489))
        assertFalse(inFrame(48.8566, 2.3522))
        assertFalse(inFrame(41.9028, 12.4964))
    }

    // endregion
}
