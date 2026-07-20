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
import org.junit.Test

/**
 * Pins the photo-to-grid-slot mapping every photo grid uses to land back on the photo the viewer
 * closed on. Off-by-one here sends the user to the wrong row, which looks exactly like the position
 * not being kept at all.
 */
class ViewerReturnTest {

    /** Stands in for a photo: the grids differ in item type but all resolve to a stable key. */
    private fun slotOf(
        key: String,
        groups: List<List<String>>,
        headerPerGroup: Boolean = false,
        leadingSlots: Int = 0,
    ) = photoSlotIndex(key, groups, headerPerGroup, leadingSlots) { it }

    @Test
    fun `a flat grid maps one to one`() {
        val photos = listOf(listOf("a", "b", "c", "d"))
        assertEquals(0, slotOf("a", photos))
        assertEquals(3, slotOf("d", photos))
    }

    @Test
    fun `leading full-width items push every photo down`() {
        // A cover, a banner, a hint row: whatever a grid emits before its first photo.
        val photos = listOf(listOf("a", "b", "c"))
        assertEquals(2, slotOf("a", photos, leadingSlots = 2))
        assertEquals(4, slotOf("c", photos, leadingSlots = 2))
    }

    @Test
    fun `each group header before the photo costs a slot`() {
        val groups = listOf(listOf("a", "b", "c"), listOf("d", "e"), listOf("f", "g", "h", "i"))
        // header, 3 photos, header, 2 photos, header, 4 photos
        assertEquals(1, slotOf("a", groups, headerPerGroup = true))
        assertEquals(3, slotOf("c", groups, headerPerGroup = true))
        assertEquals(5, slotOf("d", groups, headerPerGroup = true))
        assertEquals(6, slotOf("e", groups, headerPerGroup = true))
        assertEquals(8, slotOf("f", groups, headerPerGroup = true))
        assertEquals(11, slotOf("i", groups, headerPerGroup = true))
    }

    @Test
    fun `leading items and group headers add up`() {
        // The timeline's worst case: a permission banner, an On-this-day row, then month headers.
        val groups = listOf(listOf("a", "b", "c"), listOf("d", "e"))
        assertEquals(3, slotOf("a", groups, headerPerGroup = true, leadingSlots = 2))
        assertEquals(7, slotOf("d", groups, headerPerGroup = true, leadingSlots = 2))
    }

    @Test
    fun `a grouped grid with headers switched off is a flat run`() {
        // Timeline grouping None buckets every photo together but emits no header for it, so the
        // photos sit directly after the leading items.
        val groups = listOf(listOf("a", "b", "c"))
        assertEquals(1, slotOf("a", groups, headerPerGroup = false, leadingSlots = 1))
        assertEquals(3, slotOf("c", groups, headerPerGroup = false, leadingSlots = 1))
    }

    @Test
    fun `a photo the grid does not list is refused rather than guessed`() {
        val groups = listOf(listOf("a", "b"), listOf("c"))
        assertEquals(-1, slotOf("gone", groups, headerPerGroup = true))
        assertEquals(-1, slotOf("gone", emptyList()))
        assertEquals(-1, slotOf("a", emptyList()))
    }

    @Test
    fun `render order wins over source order`() {
        // The point of counting through the groups: a screen whose source list is sorted one way
        // but rendered in another must still land on the row the user is actually looking at.
        val groups = listOf(listOf("c"), listOf("a", "b"))
        assertEquals(0, slotOf("c", groups))
        assertEquals(1, slotOf("a", groups))
        assertEquals(2, slotOf("b", groups))
    }

    @Test
    fun `empty groups are stepped over`() {
        // A bucket can empty out under a filter while its header is still emitted.
        val groups = listOf(emptyList(), emptyList(), listOf("a", "b"))
        assertEquals(3, slotOf("a", groups, headerPerGroup = true))
        assertEquals(4, slotOf("b", groups, headerPerGroup = true))
    }

    @Test
    fun `every photo lands on its own slot`() {
        // Walking the whole grid must produce strictly increasing, never-colliding slots: the same
        // property the emission order itself guarantees.
        val groups = listOf(listOf("a", "b"), listOf("c"), listOf("d", "e", "f"))
        val slots = groups.flatten().map { slotOf(it, groups, headerPerGroup = true, leadingSlots = 1) }
        assertEquals(slots.size, slots.toSet().size)
        assertEquals(slots.sorted(), slots)
        // Leading item + 3 headers + 6 photos = 10 slots, last photo at index 9.
        assertEquals(9, slots.last())
    }
}
