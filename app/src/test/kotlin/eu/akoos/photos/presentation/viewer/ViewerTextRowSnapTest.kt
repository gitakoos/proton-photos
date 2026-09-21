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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the row snap that keeps a selection reading left to right. The words of one line come off the
 * detector with slightly different tops, and the platform orders a spanning selection by position; a
 * strict top sort then hands the line back scrambled. Snapping every word of a line to the line's own
 * top makes the sort fall back to left to right, so this is checked on the exact tops the reader gave
 * for the failing image.
 */
class ViewerTextRowSnapTest {

    @Test
    fun `every word of one line takes the line's own top`() {
        // The product-box top line: "KK VDE Big Pack 1" came back as five words a few pixels apart in
        // height, which the platform then sorted by top into "Pack Big 1 VDE KK".
        val tops = listOf(1625f, 1622f, 1616f, 1613f, 1620f)
        val heights = listOf(106f, 104f, 129f, 113f, 91f)
        assertEquals(List(5) { 1613f }, snapRowTops(tops, heights))
    }

    @Test
    fun `distinct lines keep their own tops`() {
        val tops = listOf(1613f, 1720f)
        val heights = listOf(113f, 102f)
        assertEquals(listOf(1613f, 1720f), snapRowTops(tops, heights))
    }

    @Test
    fun `two lines of two words each snap per line`() {
        val tops = listOf(100f, 104f, 300f, 297f)
        val heights = listOf(40f, 40f, 40f, 40f)
        assertEquals(listOf(100f, 100f, 297f, 297f), snapRowTops(tops, heights))
    }

    @Test
    fun `a single run is left where it is`() {
        assertEquals(listOf(50f), snapRowTops(listOf(50f), listOf(30f)))
    }

    @Test
    fun `same line is judged on the shorter run`() {
        assertTrue(sameLine(100f, 40f, 110f, 40f))
        assertFalse(sameLine(100f, 40f, 200f, 40f))
    }
}
