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

package eu.akoos.photos.presentation.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [pacedBy] holds the Settings backup totals to one read per pause. The source here counts how often
 * it is asked for a value, standing in for a Room query flow, which runs its query each time it is
 * asked. Timing is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacedFlowTest {

    @Test
    fun `the first value passes at once and the next is not asked for until the pause ends`() = runTest {
        var reads = 0
        val source = flow {
            while (true) {
                reads++
                emit(reads)
            }
        }
        val seen = mutableListOf<Pair<Long, Int>>()
        val collector = launch {
            source.pacedBy(PAUSE_MS).collect { seen += testScheduler.currentTime to it }
        }

        runCurrent()
        assertEquals(listOf(0L to 1), seen)

        advanceTimeBy(PAUSE_MS - 1)
        assertEquals("no read may run during the pause", 1, reads)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf(0L to 1, PAUSE_MS to 2), seen)

        collector.cancel()
    }

    @Test
    fun `every value still arrives, one pause apart`() = runTest {
        val seen = mutableListOf<Pair<Long, Int>>()

        flowOf(1, 2, 3).pacedBy(PAUSE_MS).collect { seen += testScheduler.currentTime to it }

        assertEquals(listOf(0L to 1, PAUSE_MS to 2, 2 * PAUSE_MS to 3), seen)
    }

    private companion object {
        const val PAUSE_MS = 1_000L
    }
}
