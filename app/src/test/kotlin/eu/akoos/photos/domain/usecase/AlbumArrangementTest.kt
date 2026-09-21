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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Coverage for [moveInArrangement], the one rule behind dragging a card around the Albums grid.
 *
 * Two things are worth pinning down. The album has to land on the index the drag asked for in both
 * directions, because the finger picks the target cell and anything else puts the card somewhere it
 * was not dropped. And a drag that ends nowhere useful — over the Memories cell, a device folder, or
 * the gap between two cards — has to leave the arrangement exactly as it was, since the result of
 * this call is what gets persisted as the user's order.
 *
 * No Android, no Compose: plain JVM assertions on values.
 */
class AlbumArrangementTest {

    private val order = listOf("a", "b", "c", "d")

    @Test
    fun `moving an album down the grid lands it on the index asked for`() {
        assertEquals(listOf("b", "c", "a", "d"), moveInArrangement(order, 0, 2))
    }

    @Test
    fun `moving an album up the grid lands it on the index asked for`() {
        assertEquals(listOf("a", "d", "b", "c"), moveInArrangement(order, 3, 1))
    }

    @Test
    fun `an album can be dragged onto either end`() {
        assertEquals(listOf("c", "a", "b", "d"), moveInArrangement(order, 2, 0))
        assertEquals(listOf("a", "c", "d", "b"), moveInArrangement(order, 1, 3))
    }

    @Test
    fun `a move onto its own slot is refused rather than performed`() {
        // Removing and reinserting at the same index would also come out equal, so identity is what
        // proves the guard fired and the caller's list was handed straight back.
        assertSame(order, moveInArrangement(order, 2, 2))
    }

    @Test
    fun `a target past either end leaves the arrangement alone`() {
        assertSame(order, moveInArrangement(order, 1, -1))
        assertSame(order, moveInArrangement(order, 1, order.size))
        assertSame(order, moveInArrangement(order, 1, 99))
    }

    @Test
    fun `a source past either end leaves the arrangement alone`() {
        // The dragged album is looked up by link id, so a card that has meanwhile left the list
        // reports -1 — the index that would otherwise throw on removal.
        assertSame(order, moveInArrangement(order, -1, 1))
        assertSame(order, moveInArrangement(order, order.size, 1))
        assertSame(order, moveInArrangement(order, 99, 1))
    }

    @Test
    fun `an empty or single-album grid has no move to make`() {
        assertSame(emptyList<String>(), moveInArrangement(emptyList(), 0, 0))
        val lone = listOf("a")
        assertSame(lone, moveInArrangement(lone, 0, 0))
        assertSame(lone, moveInArrangement(lone, 0, 1))
    }

    @Test
    fun `no move drops or duplicates an album`() {
        // The returned order is persisted outright, so an album lost here is an album that loses its
        // place in the grid for good.
        for (from in order.indices) {
            for (to in order.indices) {
                val moved = moveInArrangement(order, from, to)
                assertEquals("$from to $to must be a permutation", order.sorted(), moved.sorted())
                assertEquals("$from to $to must keep the grid's size", order.size, moved.size)
            }
        }
    }

    @Test
    fun `the album asked for is the one that ends up on the target index`() {
        for (from in order.indices) {
            for (to in order.indices) {
                assertEquals("$from to $to", order[from], moveInArrangement(order, from, to)[to])
            }
        }
    }

    @Test
    fun `the list handed in is never modified`() {
        // The grid keeps rendering the previous order until the new one is applied, so a move that
        // mutated its input would reorder the cards behind the composition's back.
        val source = order.toMutableList()
        moveInArrangement(source, 0, 3)
        assertEquals(listOf("a", "b", "c", "d"), source)
    }
}
