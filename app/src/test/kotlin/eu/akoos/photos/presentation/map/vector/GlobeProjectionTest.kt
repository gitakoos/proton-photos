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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeProjectionTest {

    private val r = 100.0

    @Test
    fun `the centre projects to the origin and is visible`() {
        val p = GlobeProjection.project(0.0, 0.0, 0.0, 0.0, r)
        assertEquals(0f, p.x, 1e-3f)
        assertEquals(0f, p.y, 1e-3f)
        assertTrue(p.visible)
    }

    @Test
    fun `a point ninety degrees east sits on the right rim`() {
        val p = GlobeProjection.project(0.0, 90.0, 0.0, 0.0, r)
        assertEquals(100f, p.x, 1e-3f)
        assertEquals(0f, p.y, 1e-3f)
        assertTrue(p.visible)
    }

    @Test
    fun `a point ninety degrees west sits on the left rim`() {
        val p = GlobeProjection.project(0.0, -90.0, 0.0, 0.0, r)
        assertEquals(-100f, p.x, 1e-3f)
        assertTrue(p.visible)
    }

    @Test
    fun `the north pole sits at the top when centred on the equator`() {
        // Screen y grows downward, so "up" is negative.
        val p = GlobeProjection.project(90.0, 0.0, 0.0, 0.0, r)
        assertEquals(0f, p.x, 1e-3f)
        assertEquals(-100f, p.y, 1e-3f)
        assertTrue(p.visible)
    }

    @Test
    fun `the far side is culled`() {
        val behind = GlobeProjection.project(0.0, 180.0, 0.0, 0.0, r)
        assertFalse(behind.visible)
        val antipode = GlobeProjection.project(-10.0, 200.0, 10.0, 20.0, r)
        assertFalse(antipode.visible)
    }

    @Test
    fun `rotating the centre brings a point to the middle`() {
        // Centre the globe on Budapest; Budapest then lands at the origin.
        val p = GlobeProjection.project(47.5, 19.0, 47.5, 19.0, r)
        assertEquals(0f, p.x, 1e-3f)
        assertEquals(0f, p.y, 1e-3f)
        assertTrue(p.visible)
    }
}
