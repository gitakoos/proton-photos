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

import eu.akoos.photos.domain.usecase.AlbumSortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when a sort order sends the Albums page back to the top, the rule
 * [albumsSortChangeScrollsToTop] answers for the page's sort effect: a real change of order does, in
 * the grid and the list alike since both share the one grid state; a first sighting, a repeat, and
 * the switch to Custom an arrangement's own save marks as seen before it lands do not.
 *
 * Each step is one run of that effect, fed the order last acted on and the order the list is sorted
 * by now. Plain JVM assertions, no Compose and no Android.
 */
class AlbumsSortChangeTest {

    @Test
    fun `a first sighting keeps the restored scroll`() {
        for (mode in AlbumSortMode.entries) {
            assertFalse("$mode", albumsSortChangeScrollsToTop(seen = null, current = mode))
        }
    }

    @Test
    fun `the same order arriving again is no change`() {
        for (mode in AlbumSortMode.entries) {
            assertFalse("$mode", albumsSortChangeScrollsToTop(seen = mode, current = mode))
        }
    }

    @Test
    fun `every move to another order goes back to the top`() {
        for (seen in AlbumSortMode.entries) {
            for (current in AlbumSortMode.entries) {
                if (seen == current) continue
                assertTrue("$seen to $current", albumsSortChangeScrollsToTop(seen, current))
            }
        }
    }

    @Test
    fun `an arrangement's own switch to Custom leaves the grid where it is`() {
        // Arranging under the name sort. Unmarked, the Custom the save stores would read as a change.
        assertTrue(albumsSortChangeScrollsToTop(seen = AlbumSortMode.NameAsc, current = AlbumSortMode.Custom))
        // The save marks Custom as seen before the store delivers it, so the delivery is none.
        assertFalse(albumsSortChangeScrollsToTop(seen = AlbumSortMode.Custom, current = AlbumSortMode.Custom))
        // Picking another order afterwards is a change again.
        assertTrue(albumsSortChangeScrollsToTop(seen = AlbumSortMode.Custom, current = AlbumSortMode.PhotoCount))
    }

    @Test
    fun `a sequence of picks scrolls on each real change only`() {
        // The page effect's own loop: record what arrives, scroll when it differs from the last one.
        val arrivals = listOf(
            AlbumSortMode.LastActivity,
            AlbumSortMode.LastActivity,
            AlbumSortMode.NameAsc,
            AlbumSortMode.NameAsc,
            AlbumSortMode.PhotoCount,
        )
        var seen: AlbumSortMode? = null
        val scrolled = arrivals.map { current ->
            albumsSortChangeScrollsToTop(seen, current).also { seen = current }
        }
        assertEquals(listOf(false, false, true, false, true), scrolled)
    }
}
