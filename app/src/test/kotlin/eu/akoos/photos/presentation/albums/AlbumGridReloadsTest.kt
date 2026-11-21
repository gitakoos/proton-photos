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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumGridReloadsTest {

    private val listChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val trashMoves = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    private fun kotlinx.coroutines.test.TestScope.reloads(): MutableList<Unit> {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            albumGridReloads(listChanges, trashMoves).collect { seen += it }
        }
        return seen
    }

    @Test
    fun `photos moved to the trash reload the grid`() = runTest {
        val seen = reloads()

        trashMoves.emit(3)

        assertEquals(1, seen.size)
    }

    @Test
    fun `photos restored from the trash reload the grid`() = runTest {
        val seen = reloads()

        trashMoves.emit(-1)

        assertEquals(1, seen.size)
    }

    @Test
    fun `a move of no photos leaves the grid alone`() = runTest {
        val seen = reloads()

        trashMoves.emit(0)

        assertEquals(0, seen.size)
    }

    @Test
    fun `a change to the album list still reloads the grid`() = runTest {
        val seen = reloads()

        listChanges.emit(Unit)

        assertEquals(1, seen.size)
    }

    @Test
    fun `each trash and list change reloads once`() = runTest {
        val seen = reloads()

        trashMoves.emit(1)
        listChanges.emit(Unit)
        trashMoves.emit(-1)

        assertEquals(3, seen.size)
    }
}
