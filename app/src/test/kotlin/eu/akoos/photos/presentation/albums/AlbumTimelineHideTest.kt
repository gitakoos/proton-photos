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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the stored set an album's timeline exclusion is, and above all that it is reversible and
 * local: three surfaces write it, so a flip that dropped a neighbour's id would silently un-hide an
 * album the user never touched. Runs WITHOUT a device — the decision is plain set work.
 */
class AlbumTimelineHideTest {

    private val trip = "album-trip"
    private val pets = "album-pets"

    @Test
    fun `an album no one excluded is in the feed`() {
        assertFalse(AlbumTimelineHide.isExcluded(emptySet(), trip))
        assertFalse(AlbumTimelineHide.isExcluded(setOf(pets), trip))
    }

    @Test
    fun `an excluded album reads as excluded`() {
        assertTrue(AlbumTimelineHide.isExcluded(setOf(pets, trip), trip))
    }

    @Test
    fun `a first flip excludes and a second puts it back`() {
        val on = AlbumTimelineHide.toggled(emptySet(), trip)
        assertEquals(setOf(trip), on)
        assertTrue(AlbumTimelineHide.isExcluded(on, trip))

        val off = AlbumTimelineHide.toggled(on, trip)
        assertTrue(off.isEmpty())
        assertFalse(AlbumTimelineHide.isExcluded(off, trip))
    }

    @Test
    fun `flipping one album leaves every other id where it was`() {
        val stored = setOf(pets, "album-work", "an album that no longer exists")
        val on = AlbumTimelineHide.toggled(stored, trip)
        assertEquals(stored + trip, on)
        assertEquals(stored, AlbumTimelineHide.toggled(on, trip))
    }

    @Test
    fun `an album with no id yet stores nothing`() {
        // The drawer can open before a load has resolved the album, and a blank id in the set would
        // match no album and never clear.
        val stored = setOf(pets)
        assertSame(stored, AlbumTimelineHide.toggled(stored, ""))
        assertFalse(AlbumTimelineHide.isExcluded(setOf(""), ""))
    }

    @Test
    fun `a stored id matching no album excludes nothing else`() {
        // Deleting an album leaves its id behind in the set; it must not take another album's photos
        // out of the feed with it.
        assertFalse(AlbumTimelineHide.isExcluded(setOf("album-deleted"), trip))
    }

    @Test
    fun `every album is on exactly one side of the stored set`() {
        // The property that makes the choice reversible from either drawer: whatever the set holds,
        // an album either reads as excluded or does not, and one more flip always returns it.
        val albums = listOf(trip, pets, "album-work")
        for (stored in listOf(emptySet(), setOf(trip), setOf(trip, pets), setOf("album-deleted"))) {
            val excluded = albums.filter { AlbumTimelineHide.isExcluded(stored, it) }
            val shown = albums.filterNot { AlbumTimelineHide.isExcluded(stored, it) }
            assertEquals(albums.size, excluded.size + shown.size)
            for (album in albums) {
                val flipped = AlbumTimelineHide.toggled(stored, album)
                assertEquals(
                    !AlbumTimelineHide.isExcluded(stored, album),
                    AlbumTimelineHide.isExcluded(flipped, album),
                )
                assertEquals(stored, AlbumTimelineHide.toggled(flipped, album))
            }
        }
    }
}
