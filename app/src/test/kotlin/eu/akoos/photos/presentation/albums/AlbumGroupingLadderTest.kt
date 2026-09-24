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

import eu.akoos.photos.presentation.albums.AlbumGroupingLadder.atLevel
import eu.akoos.photos.presentation.albums.AlbumGroupingLadder.levelOf
import eu.akoos.photos.presentation.albums.AlbumPhotoGrouping.Day
import eu.akoos.photos.presentation.albums.AlbumPhotoGrouping.Month
import eu.akoos.photos.presentation.albums.AlbumPhotoGrouping.Year
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the steps a pinch moves an album or folder grid's headers through. The shared pinch detector
 * steps one level up on a spread and one level down on a pinch, so the order of this list is the
 * direction of the gesture.
 */
class AlbumGroupingLadderTest {

    @Test
    fun `the ladder runs from the widest span to the finest`() {
        assertEquals(listOf(Year, Month, Day), AlbumGroupingLadder.levels)
    }

    @Test
    fun `spreading the fingers steps finer towards Day`() {
        assertEquals(Month, atLevel(levelOf(Year) + 1))
        assertEquals(Day, atLevel(levelOf(Month) + 1))
    }

    @Test
    fun `pinching the fingers together steps wider towards Year`() {
        assertEquals(Month, atLevel(levelOf(Day) - 1))
        assertEquals(Year, atLevel(levelOf(Month) - 1))
    }

    @Test
    fun `a step past either end stays on that end`() {
        assertEquals(Day, atLevel(levelOf(Day) + 1))
        assertEquals(Year, atLevel(levelOf(Year) - 1))
        assertEquals(Year, atLevel(Int.MIN_VALUE))
        assertEquals(Day, atLevel(Int.MAX_VALUE))
    }

    @Test
    fun `every grouping is on the ladder once and round trips`() {
        assertEquals(AlbumPhotoGrouping.entries.size, AlbumGroupingLadder.levels.size)
        assertEquals(AlbumPhotoGrouping.entries.toSet(), AlbumGroupingLadder.levels.toSet())
        for (grouping in AlbumPhotoGrouping.entries) assertEquals(grouping, atLevel(levelOf(grouping)))
        for (level in AlbumGroupingLadder.levels.indices) assertEquals(level, levelOf(atLevel(level)))
    }

    @Test
    fun `the persisted ordinals stay as they are`() {
        // The saved grouping is stored as an ordinal, so the ladder has its own order rather than
        // this one, which a reorder would turn into a different saved choice on every install.
        assertEquals(0, Month.ordinal)
        assertEquals(1, Day.ordinal)
        assertEquals(2, Year.ordinal)
    }
}
