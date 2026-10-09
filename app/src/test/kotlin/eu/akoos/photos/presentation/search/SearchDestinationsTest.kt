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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.presentation.albums.LibraryTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDestinationsTest {

    private val names = listOf(
        LibraryTile.Collection to "collection",
        LibraryTile.Hidden to "hidden",
        LibraryTile.Shared to "shared",
        LibraryTile.Map to "map",
        LibraryTile.Calendar to "calendar",
        LibraryTile.People to "people",
        LibraryTile.Offline to "offline",
    )

    @Test
    fun `a whole name finds its tile`() {
        assertEquals(listOf(LibraryTile.Shared), SearchDestinations.match("shared", names))
        assertEquals(listOf(LibraryTile.Hidden), SearchDestinations.match("hidden", names))
    }

    @Test
    fun `the start of a name finds its tile`() {
        assertEquals(listOf(LibraryTile.Calendar), SearchDestinations.match("cal", names))
        assertEquals(listOf(LibraryTile.Map), SearchDestinations.match("ma", names))
    }

    @Test
    fun `a name that two tiles start with finds both in the given order`() {
        val both = listOf(LibraryTile.Offline to "offline", LibraryTile.Collection to "offline copies")
        assertEquals(listOf(LibraryTile.Offline, LibraryTile.Collection), SearchDestinations.match("off", both))
    }

    @Test
    fun `an exact name leads the ones that merely start with it`() {
        val tiles = listOf(LibraryTile.People to "people and pets", LibraryTile.Map to "people")
        assertEquals(listOf(LibraryTile.Map, LibraryTile.People), SearchDestinations.match("people", tiles))
    }

    @Test
    fun `a later word of a name finds its tile after the names that start with the query`() {
        val tiles = listOf(LibraryTile.Shared to "shared albums", LibraryTile.Collection to "albums")
        assertEquals(listOf(LibraryTile.Collection, LibraryTile.Shared), SearchDestinations.match("albums", tiles))
    }

    @Test
    fun `case and accents are ignored once the name and the query are folded`() {
        val hungarian = listOf(LibraryTile.Calendar to SearchFilter.fold("Naptár"), LibraryTile.Map to SearchFilter.fold("Térkép"))
        assertEquals(listOf(LibraryTile.Calendar), SearchDestinations.match("NAPTAR", hungarian))
        assertEquals(listOf(LibraryTile.Map), SearchDestinations.match("terk", hungarian))
    }

    @Test
    fun `one letter or nothing names no tile`() {
        for (query in listOf("", " ", "s", " m ")) {
            assertTrue("'$query'", SearchDestinations.match(query, names).isEmpty())
        }
    }

    @Test
    fun `a query that matches no name finds nothing`() {
        assertTrue(SearchDestinations.match("sunset", names).isEmpty())
    }

    @Test
    fun `a tile left out of the offered names is never found`() {
        val withoutHidden = names.filterNot { it.first == LibraryTile.Hidden }
        assertTrue(SearchDestinations.match("hidden", withoutHidden).isEmpty())
    }
}
