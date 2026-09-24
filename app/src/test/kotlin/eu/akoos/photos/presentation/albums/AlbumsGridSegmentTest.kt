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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the cloud albums start in the Albums grid, the offset every arrange-mode index goes
 * through: the grid cell a held card's slot is looked up at, and the slot a drop point's cell maps
 * back to. The pinned Collection card is the only cell ahead of the cloud albums and the device
 * folders follow them, so a wrong offset drags one album while another moves.
 *
 * Plain JVM assertions on pure functions, no Compose runtime and no Android.
 */
class AlbumsGridSegmentTest {

    // Three cloud albums, then device folders.
    private val cloudCount = 3

    @Test
    fun `card hidden puts the first album in cell 0`() {
        val start = albumsCloudSegmentStart(showCollection = false, searching = false)
        assertEquals(0, start)
        assertFalse(albumsCollectionCardShown(showCollection = false, searching = false))
        assertEquals(0, cloudSlotAt(0, start, cloudCount))
        assertEquals(2, cloudSlotAt(2, start, cloudCount))
        // The first device folder sits right behind the last album and is no drop target.
        assertNull(cloudSlotAt(3, start, cloudCount))
    }

    @Test
    fun `card shown makes cell 0 no drop target`() {
        val start = albumsCloudSegmentStart(showCollection = true, searching = false)
        assertEquals(1, start)
        assertTrue(albumsCollectionCardShown(showCollection = true, searching = false))
        assertNull(cloudSlotAt(0, start, cloudCount))
        assertEquals(0, cloudSlotAt(1, start, cloudCount))
        assertEquals(2, cloudSlotAt(3, start, cloudCount))
        assertNull(cloudSlotAt(4, start, cloudCount))
    }

    @Test
    fun `a search drops the card and the offset whichever way the switch is set`() {
        assertEquals(0, albumsCloudSegmentStart(showCollection = true, searching = true))
        assertEquals(0, albumsCloudSegmentStart(showCollection = false, searching = true))
        assertFalse(albumsCollectionCardShown(showCollection = true, searching = true))
        assertFalse(albumsCollectionCardShown(showCollection = false, searching = true))
        assertEquals(0, cloudSlotAt(0, albumsCloudSegmentStart(showCollection = true, searching = true), cloudCount))
    }

    @Test
    fun `the offset counts the card exactly when the grid emits it`() {
        for (showCollection in listOf(true, false)) {
            for (searching in listOf(true, false)) {
                val expected = if (albumsCollectionCardShown(showCollection, searching)) 1 else 0
                assertEquals(
                    "showCollection=$showCollection searching=$searching",
                    expected,
                    albumsCloudSegmentStart(showCollection, searching),
                )
            }
        }
    }

    @Test
    fun `a slot and the cell it is looked up at map back to each other`() {
        // The drag handlers find a slot's cell at start + slot, and a drop maps a cell back through
        // cloudSlotAt; the two have to agree with the card shown and hidden alike.
        for (start in listOf(0, 1)) {
            for (slot in 0 until cloudCount) {
                assertEquals("start=$start slot=$slot", slot, cloudSlotAt(start + slot, start, cloudCount))
            }
        }
    }

    @Test
    fun `no cloud albums leaves nothing to drop onto`() {
        for (start in listOf(0, 1)) {
            for (gridIndex in 0..3) {
                assertNull(cloudSlotAt(gridIndex, start, cloudCount = 0))
            }
        }
    }
}
