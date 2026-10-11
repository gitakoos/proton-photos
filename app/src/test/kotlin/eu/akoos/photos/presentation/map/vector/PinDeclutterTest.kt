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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pin declutter rule: the first of two overlapping cities keeps its pin, apart ones all stay. Pure. */
class PinDeclutterTest {

    private fun pin(x: Float, y: Float) = PinFootprint(x, y, halfWidth = 23f, above = 50f, below = 28f)

    @Test
    fun `cities far apart all keep their pin`() {
        val kept = keptPins(listOf(pin(100f, 300f), pin(400f, 300f), pin(100f, 700f)))
        assertArrayEquals(booleanArrayOf(true, true, true), kept)
    }

    @Test
    fun `the first of two overlapping cities keeps its pin and the second drops to a dot`() {
        val kept = keptPins(listOf(pin(100f, 300f), pin(110f, 310f)))
        assertArrayEquals(booleanArrayOf(true, false), kept)
    }

    @Test
    fun `a dropped city does not hide a later one that clears every kept pin`() {
        val kept = keptPins(listOf(pin(100f, 300f), pin(110f, 310f), pin(400f, 300f)))
        assertArrayEquals(booleanArrayOf(true, false, true), kept)
    }

    @Test
    fun `a city under another one's name is dropped, a city beside it is not`() {
        val first = pin(100f, 300f)
        val belowName = pin(100f, 300f + first.below + 10f + 50f)
        val beside = pin(100f + first.halfWidth * 2 + 20f, 300f)
        assertArrayEquals(booleanArrayOf(true, true, true), keptPins(listOf(first, belowName, beside)))
        assertArrayEquals(booleanArrayOf(true, false), keptPins(listOf(first, pin(100f, 330f))))
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertEquals(0, keptPins(emptyList()).size)
    }
}
