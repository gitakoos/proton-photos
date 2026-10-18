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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The people list is built from a source that, while a sync runs, never stops changing: every build has to
 * finish and deliver even when the next input arrives before it is done, and rest after it in proportion
 * to its cost, so a source that never settles cannot keep the builds running back to back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapCompletingTest {

    /** A ticker that emits every 100 ms and never completes. */
    private fun endless(): Flow<Int> = flow {
        var i = 0
        while (true) {
            emit(i++)
            delay(100)
        }
    }

    @Test
    fun `a transform slower than an endless source still delivers`() = runTest {
        val first = withTimeoutOrNull(10_000) { endless().mapCompleting { delay(300); it }.take(1).toList() }
        assertEquals(listOf(0), first)
        val latestOnly = withTimeoutOrNull(10_000) { endless().mapLatest { delay(300); it }.take(1).toList() }
        assertNull(latestOnly)
    }

    @Test
    fun `later values skip what arrived during a run and keep climbing`() = runTest {
        val values = withTimeoutOrNull(10_000) { endless().mapCompleting { delay(300); it }.take(3).toList() }
        assertNotNull(values)
        values!!
        assertEquals(3, values.size)
        assertTrue(values.toString(), values.zipWithNext().all { (a, b) -> b > a + 1 })
    }

    /** When each of the first [count] results of [build] over the endless ticker arrived, on the virtual clock. */
    private suspend fun TestScope.arrivals(count: Int, build: suspend (Int) -> Int): List<Long> {
        val times = ArrayList<Long>()
        withTimeoutOrNull(600_000) {
            endless().mapCompleting(nowMs = { testScheduler.currentTime }, build = build)
                .take(count)
                .collect { times.add(testScheduler.currentTime) }
        }
        return times
    }

    @Test
    fun `results of a quick build are never closer than the least rest`() = runTest {
        val times = arrivals(6) { delay(10); it }
        assertEquals(6, times.size)
        assertTrue(times.toString(), times.zipWithNext().all { (a, b) -> b - a >= PEOPLE_BUILD_REST_MIN_MS })
    }

    @Test
    fun `a slow build is followed by a longer rest`() = runTest {
        val times = arrivals(3) { delay(1_000); it }
        assertEquals(3, times.size)
        // A second of building rests three seconds before the next build starts.
        assertTrue(times.toString(), times.zipWithNext().all { (a, b) -> b - a >= 4_000L })
    }

    @Test
    fun `the rest is three times the build within its bounds`() {
        assertEquals(PEOPLE_BUILD_REST_MIN_MS, peopleBuildRestMs(0L))
        assertEquals(PEOPLE_BUILD_REST_MIN_MS, peopleBuildRestMs(50L))
        assertEquals(300L, peopleBuildRestMs(100L))
        assertEquals(3_000L, peopleBuildRestMs(1_000L))
        assertEquals(PEOPLE_BUILD_REST_MAX_MS, peopleBuildRestMs(22_000L))
    }
}
