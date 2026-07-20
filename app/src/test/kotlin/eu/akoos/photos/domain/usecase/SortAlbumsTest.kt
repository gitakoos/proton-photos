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
 * Coverage for [sortAlbums], the single rule deciding the Albums grid order.
 *
 * The grid is painted twice per visit, once from the cache and once from the network reply. If
 * those two paints disagree the list visibly rearranges itself, so what is asserted here is mostly
 * that the order is fully determined by the album values and never by the order they arrived in.
 *
 * No Android, no coroutines: plain JVM assertions on values.
 */
class SortAlbumsTest {

    private fun album(
        linkId: String,
        name: String = "Album $linkId",
        photoCount: Int = 0,
        lastActivityTimeMs: Long? = 0L,
    ) = Album(
        linkId = linkId,
        name = name,
        photoCount = photoCount,
        coverLinkId = null,
        lastActivityTimeMs = lastActivityTimeMs,
    )

    private fun List<Album>.ids() = map { it.linkId }

    @Test
    fun `last activity puts the most recently touched album first`() {
        val albums = listOf(
            album("a", lastActivityTimeMs = 100L),
            album("b", lastActivityTimeMs = 300L),
            album("c", lastActivityTimeMs = 200L),
        )
        assertEquals(listOf("b", "c", "a"), sortAlbums(albums, AlbumSortMode.LastActivity).ids())
    }

    @Test
    fun `an album with no recorded activity sinks to the bottom`() {
        val albums = listOf(
            album("a", lastActivityTimeMs = null),
            album("b", lastActivityTimeMs = 1L),
        )
        assertEquals(listOf("b", "a"), sortAlbums(albums, AlbumSortMode.LastActivity).ids())
    }

    @Test
    fun `photo count puts the largest album first`() {
        val albums = listOf(
            album("a", photoCount = 5),
            album("b", photoCount = 90),
            album("c", photoCount = 12),
        )
        assertEquals(listOf("b", "c", "a"), sortAlbums(albums, AlbumSortMode.PhotoCount).ids())
    }

    @Test
    fun `name sorts A to Z`() {
        val albums = listOf(
            album("a", name = "Zermatt"),
            album("b", name = "Athens"),
            album("c", name = "Munich"),
        )
        assertEquals(listOf("b", "c", "a"), sortAlbums(albums, AlbumSortMode.NameAsc).ids())
    }

    @Test
    fun `name sorting ignores case`() {
        // Without a case-insensitive comparison an ASCII sort puts every capitalised name ahead of
        // every lowercase one, so "apple" would land after "Banana".
        val albums = listOf(
            album("a", name = "Banana"),
            album("b", name = "apple"),
            album("c", name = "Cherry"),
        )
        assertEquals(listOf("b", "a", "c"), sortAlbums(albums, AlbumSortMode.NameAsc).ids())
    }

    @Test
    fun `names differing only in case fall back to the link id`() {
        val albums = listOf(
            album("z", name = "Holiday"),
            album("a", name = "holiday"),
        )
        assertEquals(listOf("a", "z"), sortAlbums(albums, AlbumSortMode.NameAsc).ids())
    }

    @Test
    fun `custom order matches last activity while no arrangement is stored`() {
        val albums = listOf(
            album("a", lastActivityTimeMs = 100L),
            album("b", lastActivityTimeMs = 300L),
            album("c", lastActivityTimeMs = 200L),
        )
        assertEquals(
            sortAlbums(albums, AlbumSortMode.LastActivity).ids(),
            sortAlbums(albums, AlbumSortMode.Custom).ids(),
        )
    }

    @Test
    fun `a stored arrangement leads, and albums it does not mention trail it`() {
        val albums = listOf(
            album("a", lastActivityTimeMs = 100L),
            album("b", lastActivityTimeMs = 300L),
            album("c", lastActivityTimeMs = 200L),
        )
        assertEquals(
            listOf("c", "a", "b"),
            sortAlbums(albums, AlbumSortMode.Custom, customOrder = listOf("c", "a")).ids(),
        )
    }

    @Test
    fun `albums tied on both activity time and photo count keep a fixed order`() {
        // The case that makes the grid flicker: two albums the sort key cannot separate. Feeding
        // them in opposite orders must still produce one answer, in every mode.
        val one = album("a1", name = "Trip", photoCount = 7, lastActivityTimeMs = 500L)
        val two = album("b2", name = "Trip", photoCount = 7, lastActivityTimeMs = 500L)
        for (mode in AlbumSortMode.entries) {
            assertEquals(
                "$mode must not depend on input order",
                sortAlbums(listOf(one, two), mode).ids(),
                sortAlbums(listOf(two, one), mode).ids(),
            )
            assertEquals("$mode must break the tie on link id", listOf("a1", "b2"), sortAlbums(listOf(two, one), mode).ids())
        }
    }

    @Test
    fun `sorting is idempotent in every mode`() {
        val albums = listOf(
            album("a", name = "Kite", photoCount = 3, lastActivityTimeMs = 10L),
            album("b", name = "kite", photoCount = 3, lastActivityTimeMs = 10L),
            album("c", name = "Anchor", photoCount = 9, lastActivityTimeMs = null),
        )
        for (mode in AlbumSortMode.entries) {
            val once = sortAlbums(albums, mode)
            assertEquals("$mode must be settled after one pass", once.ids(), sortAlbums(once, mode).ids())
        }
    }

    @Test
    fun `an empty list stays empty in every mode`() {
        for (mode in AlbumSortMode.entries) {
            assertTrue("$mode must tolerate an empty grid", sortAlbums(emptyList(), mode).isEmpty())
        }
    }

    @Test
    fun `no mode drops or duplicates an album`() {
        val albums = listOf(
            album("a", name = "One", photoCount = 1, lastActivityTimeMs = 3L),
            album("b", name = "Two", photoCount = 2, lastActivityTimeMs = null),
            album("c", name = "Three", photoCount = 2, lastActivityTimeMs = 3L),
        )
        for (mode in AlbumSortMode.entries) {
            assertEquals("$mode must be a permutation", albums.ids().sorted(), sortAlbums(albums, mode).ids().sorted())
        }
    }

    @Test
    fun `an absent or out-of-range stored ordinal reads as the default`() {
        // Guards the persisted value: a mode written by a newer build must not crash an older one.
        assertEquals(AlbumSortMode.LastActivity, AlbumSortMode.Default)
        assertEquals(AlbumSortMode.Default, AlbumSortMode.fromOrdinal(null))
        assertEquals(AlbumSortMode.Default, AlbumSortMode.fromOrdinal(99))
        assertEquals(AlbumSortMode.Default, AlbumSortMode.fromOrdinal(-1))
        assertEquals(AlbumSortMode.NameAsc, AlbumSortMode.fromOrdinal(AlbumSortMode.NameAsc.ordinal))
    }
}
