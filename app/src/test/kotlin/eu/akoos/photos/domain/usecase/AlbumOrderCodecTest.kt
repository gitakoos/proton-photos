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

import eu.akoos.photos.domain.entity.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [encodeAlbumOrder] / [decodeAlbumOrder], the flattening of the user's own album
 * arrangement into one stored string.
 *
 * The value is a preference a user can carry across upgrades and restores, so decoding treats it as
 * untrusted: nothing it can contain may crash the grid or let one album hold two slots.
 *
 * No Android, no coroutines: plain JVM assertions on values.
 */
class AlbumOrderCodecTest {

    @Test
    fun `an absent value reads as no arrangement`() {
        assertTrue(decodeAlbumOrder(null).isEmpty())
    }

    @Test
    fun `an empty value reads as no arrangement`() {
        // Custom falls back to LastActivity on an empty list, so this is the "never arranged" state.
        assertTrue(decodeAlbumOrder("").isEmpty())
    }

    @Test
    fun `a value of only separators reads as no arrangement`() {
        assertTrue(decodeAlbumOrder("|||").isEmpty())
    }

    @Test
    fun `blank entries are dropped`() {
        assertEquals(listOf("a", "b"), decodeAlbumOrder("a||b"))
        assertEquals(listOf("a", "b"), decodeAlbumOrder("a| |b"))
    }

    @Test
    fun `leading and trailing separators are tolerated`() {
        assertEquals(listOf("a", "b"), decodeAlbumOrder("|a|b|"))
        assertEquals(listOf("a", "b"), decodeAlbumOrder("||a|b||"))
    }

    @Test
    fun `a repeated id keeps only its first slot`() {
        // Two slots for one album would make it appear twice in the grid.
        assertEquals(listOf("a", "b", "c"), decodeAlbumOrder("a|b|a|c|b"))
    }

    @Test
    fun `decoding is settled after one pass`() {
        val once = decodeAlbumOrder("|a||b|a|")
        assertEquals(once, decodeAlbumOrder(encodeAlbumOrder(once)))
    }

    @Test
    fun `a clean arrangement round-trips unchanged`() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(ids, decodeAlbumOrder(encodeAlbumOrder(ids)))
    }

    @Test
    fun `encoding normalises to what decoding can return`() {
        assertEquals(listOf("a", "b"), decodeAlbumOrder(encodeAlbumOrder(listOf("a", "", "b", "a"))))
        assertEquals("", encodeAlbumOrder(emptyList()))
        assertEquals("", encodeAlbumOrder(listOf("", " ")))
    }

    @Test
    fun `an arrangement survives base64 padding and slashes in a link id`() {
        // The reason the separator is a pipe: a Drive linkId is an opaque base64-ish token, and no
        // character of that alphabet may split an id in half.
        val ids = listOf("aB3k/J9x+Q2m==", "Zm9vYmFy", "cGhvdG9zLzEyMw==", "x_y-z")
        val encoded = encodeAlbumOrder(ids)
        assertEquals(ids, decodeAlbumOrder(encoded))
        assertEquals(ids.size, encoded.split("|").size)
    }

    @Test
    fun `a stored arrangement drives the grid order`() {
        // The pair only matters through sortAlbums, so the decoded value is checked where it lands.
        val albums = listOf(
            Album(linkId = "a", name = "A", photoCount = 0, coverLinkId = null, lastActivityTimeMs = 100L),
            Album(linkId = "b", name = "B", photoCount = 0, coverLinkId = null, lastActivityTimeMs = 300L),
            Album(linkId = "c", name = "C", photoCount = 0, coverLinkId = null, lastActivityTimeMs = 200L),
        )
        val order = decodeAlbumOrder("c||a|c")
        assertEquals(
            listOf("c", "a", "b"),
            sortAlbums(albums, AlbumSortMode.Custom, order).map { it.linkId },
        )
    }

    @Test
    fun `an id for an album that is not on screen keeps its slot`() {
        // Hidden albums are filtered out after ordering and shared albums render elsewhere, so an id
        // the grid cannot place must survive rather than be pruned away.
        val stored = encodeAlbumOrder(listOf("visible", "hidden", "shared"))
        assertEquals(listOf("visible", "hidden", "shared"), decodeAlbumOrder(stored))
    }
}
