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

package eu.akoos.photos.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Trailing-debounce contract for MediaStore observer bursts ahead of queryAllMedia: Immediate
 * never waits, Observer collapses to one emit after settle, and the final post-burst signal is
 * always delivered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRefreshCoalescingTest {

    private val settleMs = 300L

    @Test
    fun `immediate emits without waiting for settle`() = runTest {
        val emissions = mutableListOf<Int>()
        val job = launch {
            flow {
                emit(MediaRefreshTrigger.Immediate)
            }.coalesceMediaRefresh(settleMs).collect {
                emissions += 1
            }
        }
        runCurrent()
        assertEquals(listOf(1), emissions)
        advanceTimeBy(settleMs)
        runCurrent()
        assertEquals(listOf(1), emissions)
        job.join()
    }

    @Test
    fun `observer burst collapses to one trailing emit after settle`() = runTest {
        val emissions = mutableListOf<Int>()
        val upstream = MutableSharedFlow<MediaRefreshTrigger>(extraBufferCapacity = 16)
        val job = launch {
            upstream.coalesceMediaRefresh(settleMs).collect {
                emissions += emissions.size + 1
            }
        }
        runCurrent()

        repeat(10) { upstream.emit(MediaRefreshTrigger.Observer) }
        runCurrent()
        assertEquals(emptyList<Int>(), emissions)

        advanceTimeBy(settleMs - 1)
        runCurrent()
        assertEquals(emptyList<Int>(), emissions)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1), emissions)

        job.cancel()
    }

    @Test
    fun `final post-burst emit is delivered after upstream completes`() = runTest {
        val emissions = mutableListOf<Int>()
        val job = launch {
            flow {
                emit(MediaRefreshTrigger.Observer)
                emit(MediaRefreshTrigger.Observer)
                emit(MediaRefreshTrigger.Observer)
            }.coalesceMediaRefresh(settleMs).collect {
                emissions += 1
            }
        }
        runCurrent()
        assertEquals(emptyList<Int>(), emissions)

        advanceTimeBy(settleMs)
        advanceUntilIdle()
        assertEquals(listOf(1), emissions)
        job.join()
    }

    @Test
    fun `immediate during a burst flushes now and a later observer still settles`() = runTest {
        val emissions = mutableListOf<Int>()
        val upstream = MutableSharedFlow<MediaRefreshTrigger>(extraBufferCapacity = 16)
        val job = launch {
            upstream.coalesceMediaRefresh(settleMs).collect {
                emissions += emissions.size + 1
            }
        }
        runCurrent()

        upstream.emit(MediaRefreshTrigger.Observer)
        upstream.emit(MediaRefreshTrigger.Observer)
        runCurrent()
        assertEquals(emptyList<Int>(), emissions)

        // Permission / notifyMediaChanged must not wait out the settle window.
        upstream.emit(MediaRefreshTrigger.Immediate)
        runCurrent()
        assertEquals(listOf(1), emissions)

        // Burst continues after the manual refresh — trailing Observer still fires once.
        upstream.emit(MediaRefreshTrigger.Observer)
        upstream.emit(MediaRefreshTrigger.Observer)
        runCurrent()
        assertEquals(listOf(1), emissions)

        advanceTimeBy(settleMs)
        runCurrent()
        assertEquals(listOf(1, 2), emissions)

        job.cancel()
    }
}
