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

package eu.akoos.photos.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins which photo a pinch holds still while an album or folder grid swaps its date headers. Holding
 * the wrong one, or a header, looks exactly like the grid jumping.
 */
class GroupingPinchTest {

    /** The hero cover's key: the grids emit it without one, so it carries a default, non-string key. */
    private object HeroKey

    private val hero = VisibleGridItem(HeroKey, index = 0, offset = 0)

    private fun photo(key: String, index: Int, offset: Int) = VisibleGridItem(key, index, offset)

    private fun header(label: String, index: Int, offset: Int) = VisibleGridItem("hdr_$label", index, offset)

    private fun isPhoto(key: Any) = key is String && !key.startsWith("hdr_")

    private fun anchorOf(vararg visible: VisibleGridItem, leadingSlots: Int = 1) =
        regroupAnchor(visible.toList(), leadingSlots, ::isPhoto)

    @Test
    fun `a photo row at the top is held as far as it is scrolled`() {
        // Three columns, the top row 40 px past the top of the grid.
        val anchor = anchorOf(
            photo("a", 7, -40), photo("b", 8, -40), photo("c", 9, -40),
            header("June 2026", 10, 300),
            photo("d", 11, 390),
        )
        assertEquals(RegroupAnchor("a", 40), anchor)
    }

    @Test
    fun `a header at the top hands over to the first photo under it`() {
        val anchor = anchorOf(
            header("June 2026", 6, -30),
            photo("a", 7, 60), photo("b", 8, 60),
        )
        // The row sits below the top of the grid, so it is held at a negative scroll offset.
        assertEquals(RegroupAnchor("a", -60), anchor)
    }

    @Test
    fun `nothing is held while the hero cover is on screen`() {
        // The hero keeps its key and index through a regroup, so the grid stays at its top unaided.
        assertNull(anchorOf(hero.copy(offset = -500), header("June 2026", 1, 300), photo("a", 2, 390)))
        assertNull(anchorOf(hero))
    }

    @Test
    fun `nothing is held without a photo on screen`() {
        assertNull(anchorOf())
        assertNull(anchorOf(header("June 2026", 4, 0)))
    }

    @Test
    fun `the first photo is found by position rather than by list order`() {
        val anchor = anchorOf(
            photo("c", 9, 150), header("June 2026", 6, -30), photo("b", 8, 60), photo("a", 7, 60),
        )
        assertEquals(RegroupAnchor("a", -60), anchor)
    }

    @Test
    fun `a grid with no leading item holds its first photo from the very top`() {
        val anchor = anchorOf(header("June 2026", 0, 0), photo("a", 1, 90), leadingSlots = 0)
        assertEquals(RegroupAnchor("a", -90), anchor)
    }

    @Test
    fun `the photo check stops at the first photo`() {
        // Each check walks the whole album in the real grids, so the rest of the screen is not asked.
        val asked = mutableListOf<Any>()
        regroupAnchor(
            listOf(header("June 2026", 6, -30), photo("a", 7, 60), photo("b", 8, 60), header("May 2026", 9, 200)),
            leadingSlots = 1,
        ) { key -> asked += key; isPhoto(key) }
        assertEquals(listOf<Any>("hdr_June 2026", "a"), asked)
    }

    @Test
    fun `the held photo is looked up again under the new headers`() {
        // One month of four photos becomes two days of two, which moves the last photo down a slot.
        // The pinch scrolls that new slot's row 10 px past the top, where the photo's row was.
        val byMonth = listOf(listOf("a", "b", "c", "d"))
        val byDay = listOf(listOf("a", "b"), listOf("c", "d"))
        val anchor = anchorOf(photo("d", 5, -10))
        assertEquals(RegroupAnchor("d", 10), anchor)
        fun slotIn(groups: List<List<String>>) =
            photoSlotIndex(anchor!!.key as String, groups, headerPerGroup = true, leadingSlots = 1) { it }
        assertEquals(5, slotIn(byMonth))
        assertEquals(6, slotIn(byDay))
    }
}
