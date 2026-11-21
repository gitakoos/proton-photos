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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumGridReloadsTest {

    private val listChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val trashMoves = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    private fun TestScope.reloads(): MutableList<Unit> {
        val seen = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            albumGridReloads(listChanges, trashMoves).collect { seen += it }
        }
        return seen
    }

    private fun TestScope.passMillis(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `photos moved to the trash reload the grid once the move settles`() = runTest {
        val seen = reloads()

        trashMoves.emit(3)
        passMillis(1_000)

        assertEquals(1, seen.size)
    }

    @Test
    fun `a trash move does not reload the grid before it settles`() = runTest {
        val seen = reloads()

        trashMoves.emit(3)
        passMillis(999)

        assertEquals(0, seen.size)
    }

    @Test
    fun `photos restored from the trash reload the grid`() = runTest {
        val seen = reloads()

        trashMoves.emit(-1)
        passMillis(1_000)

        assertEquals(1, seen.size)
    }

    @Test
    fun `a move of no photos leaves the grid alone`() = runTest {
        val seen = reloads()

        trashMoves.emit(0)
        passMillis(1_000)

        assertEquals(0, seen.size)
    }

    @Test
    fun `a burst of trash moves reloads the grid once`() = runTest {
        val seen = reloads()

        trashMoves.emit(1)
        passMillis(300)
        trashMoves.emit(1)
        passMillis(300)
        trashMoves.emit(1)
        passMillis(1_000)

        assertEquals(1, seen.size)
    }

    @Test
    fun `trash moves apart from each other reload the grid each time`() = runTest {
        val seen = reloads()

        trashMoves.emit(1)
        passMillis(1_000)
        trashMoves.emit(-1)
        passMillis(1_000)

        assertEquals(2, seen.size)
    }

    @Test
    fun `a change to the album list reloads the grid at once`() = runTest {
        val seen = reloads()

        listChanges.emit(Unit)

        assertEquals(1, seen.size)
    }
}
