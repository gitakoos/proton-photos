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

class PhotoArrangementTest {

    private data class Photo(val id: String, val time: Long)

    private fun ordered(items: List<Photo>, arrangement: List<String>) =
        PhotoArrangement.ordered(items, arrangement, { it.id }, { it.time }).map { it.id }

    private val abcde = listOf("a", "b", "c", "d", "e")

    @Test
    fun `arranged photos keep their hand-made order`() {
        val items = listOf(Photo("a", 1), Photo("b", 2), Photo("c", 3))

        assertEquals(listOf("c", "a", "b"), ordered(items, listOf("c", "a", "b")))
    }

    @Test
    fun `photos added since the save go to the end, oldest first, ties on the id`() {
        val items = listOf(Photo("new2", 50), Photo("a", 1), Photo("new1", 40), Photo("b", 2), Photo("new0", 40))

        assertEquals(listOf("b", "a", "new0", "new1", "new2"), ordered(items, listOf("b", "a")))
    }

    @Test
    fun `a removed photo is skipped and the rest close up`() {
        val items = listOf(Photo("a", 1), Photo("c", 3))

        assertEquals(listOf("c", "a"), ordered(items, listOf("c", "b", "a")))
    }

    @Test
    fun `no arrangement at all lists every photo oldest first`() {
        val items = listOf(Photo("b", 2), Photo("a", 1))

        assertEquals(listOf("a", "b"), ordered(items, emptyList()))
    }

    @Test
    fun `an empty album is returned as it is`() {
        val empty = emptyList<Photo>()

        assertSame(empty, PhotoArrangement.ordered(empty, listOf("a"), { it.id }, { it.time }))
    }

    @Test
    fun `move to start and end carry the selection as one block in its own order`() {
        assertEquals(listOf("b", "d", "a", "c", "e"), PhotoArrangement.moveToStart(abcde, setOf("d", "b")))
        assertEquals(listOf("a", "c", "e", "b", "d"), PhotoArrangement.moveToEnd(abcde, setOf("d", "b")))
        assertSame(abcde, PhotoArrangement.moveToStart(abcde, emptySet()))
    }

    @Test
    fun `move before and after a tapped photo`() {
        assertEquals(listOf("b", "c", "d", "a", "e"), PhotoArrangement.moveBefore(abcde, setOf("a"), "e"))
        assertEquals(listOf("b", "c", "d", "e", "a"), PhotoArrangement.moveAfter(abcde, setOf("a"), "e"))
        assertEquals(listOf("a", "d", "e", "b", "c"), PhotoArrangement.moveAfter(abcde, setOf("b", "c"), "e"))
        assertEquals(listOf("d", "a", "b", "c", "e"), PhotoArrangement.moveBefore(abcde, setOf("d"), "a"))
    }

    @Test
    fun `a target inside the selection or unknown changes nothing`() {
        assertEquals(abcde, PhotoArrangement.moveBefore(abcde, setOf("b", "c"), "c"))
        assertEquals(abcde, PhotoArrangement.moveAfter(abcde, setOf("b"), "zzz"))
    }

    @Test
    fun `gather pulls a scattered selection together where the held photo stands`() {
        assertEquals(listOf("a", "c", "b", "d", "e"), PhotoArrangement.gather(abcde, setOf("b", "d"), "d"))
        assertEquals(listOf("a", "b", "d", "c", "e"), PhotoArrangement.gather(abcde, setOf("b", "d"), "b"))
        assertSame(abcde, PhotoArrangement.gather(abcde, setOf("b"), "b"))
        assertSame(abcde, PhotoArrangement.gather(abcde, setOf("b", "d"), "a"))
    }

    @Test
    fun `dragging lands before a photo behind the block and after one ahead of it`() {
        assertEquals(listOf("a", "d", "b", "c", "e"), PhotoArrangement.dragTo(abcde, setOf("d"), "b"))
        assertEquals(listOf("a", "c", "d", "b", "e"), PhotoArrangement.dragTo(abcde, setOf("b"), "d"))
        assertEquals(listOf("c", "a", "b", "d", "e"), PhotoArrangement.dragTo(abcde, setOf("a", "b"), "c"))
    }

    @Test
    fun `dragging over the block itself or an unknown key changes nothing`() {
        assertSame(abcde, PhotoArrangement.dragTo(abcde, setOf("b", "c"), "c"))
        assertSame(abcde, PhotoArrangement.dragTo(abcde, setOf("b"), "zzz"))
        assertSame(abcde, PhotoArrangement.dragTo(abcde, emptySet(), "a"))
    }

    @Test
    fun `a save keeps the slot of a photo the session did not show`() {
        // "h" is hidden on this device: it keeps its third slot while the shown photos take theirs in the new order.
        val stored = listOf("a", "b", "h", "c")

        assertEquals(listOf("c", "a", "h", "b"), PhotoArrangement.forSave(stored, listOf("c", "a", "b")))
    }

    @Test
    fun `a save adds a photo the stored order never had`() {
        assertEquals(listOf("b", "n", "a"), PhotoArrangement.forSave(listOf("a", "b"), listOf("b", "n", "a")))
    }

    @Test
    fun `a save drops a photo that left the album and keeps one this device has not listed`() {
        val stored = listOf("a", "left", "unlisted", "b")
        val members = setOf("a", "b", "unlisted")

        assertEquals(listOf("b", "unlisted", "a"), PhotoArrangement.forSave(stored, listOf("b", "a"), members))
    }

    @Test
    fun `the first save is the shown order`() {
        assertEquals(listOf("c", "b", "a"), PhotoArrangement.forSave(emptyList(), listOf("c", "b", "a"), emptySet()))
    }
}
