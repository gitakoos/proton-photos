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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins [runUploadWorkers], the pool the upload batch walks its queue with: a fixed number of workers
 * takes the items in list order and runs each once, a failure the item contains leaves the rest
 * running, a stop or a storage-full abort ends the batch once the running items finish, and a
 * cancellation or an escaping failure tears every worker down.
 *
 * Timing is virtual: each fake item takes a set number of milliseconds on [runTest]'s scheduler, so
 * which items overlap, and when the batch ends, is exact rather than a race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadWorkerPoolTest {

    private val tenItems = (0 until 10).toList()

    /** What the fake item processor saw. The scheduler is single-threaded, so plain lists do. */
    private class Trace {
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val workers = mutableSetOf<Job>()
        var running = 0
        var maxRunning = 0

        /** Runs [item] for [durationMs], then [atEnd], recording each step. */
        suspend fun process(item: Int, durationMs: Long, atEnd: () -> Unit = {}) {
            started += item
            workers += currentCoroutineContext()[Job]!!
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                delay(durationMs)
                atEnd()
                completed += item
            } catch (e: CancellationException) {
                cancelled += item
                throw e
            } finally {
                running--
            }
        }
    }

    @Test
    fun `every item runs once, in list order, three at a time on three coroutines`() = runTest {
        val trace = Trace()

        runUploadWorkers(tenItems, workers = 3, shouldStop = { false }) { trace.process(it, 100) }

        assertEquals(tenItems, trace.started)
        assertEquals(tenItems, trace.completed.sorted())
        assertEquals(3, trace.maxRunning)
        // Ten items share the three worker coroutines instead of holding one each.
        assertEquals(3, trace.workers.size)
        // Four rounds of 100 ms: three, three, three, then the last one.
        assertEquals(400L, currentTime)
    }

    @Test
    fun `a failure the item contains leaves the other items running`() = runTest {
        val trace = Trace()
        val failed = mutableListOf<Int>()

        runUploadWorkers(tenItems, workers = 3, shouldStop = { false }) { item ->
            // The upload path catches a per-photo failure inside the item, records it, and returns.
            try {
                trace.process(item, 100) { if (item == 1) throw IOException("upload failed") }
            } catch (e: IOException) {
                failed += item
            }
        }

        assertEquals(tenItems, trace.started)
        assertEquals(listOf(1), failed)
        assertEquals(tenItems - 1, trace.completed.sorted())
        assertTrue(trace.cancelled.isEmpty())
    }

    @Test
    fun `a stop lets the running items finish and starts no more`() = runTest {
        val trace = Trace()
        val stop = AtomicBoolean(false)
        val batch = launch {
            runUploadWorkers(tenItems, workers = 3, shouldStop = { stop.get() }) { trace.process(it, 100) }
        }

        // Items 3, 4 and 5 are halfway through when the stop lands.
        advanceTimeBy(150)
        stop.set(true)
        advanceUntilIdle()

        assertTrue(batch.isCompleted && !batch.isCancelled)
        assertEquals((0..5).toList(), trace.started)
        assertEquals((0..5).toList(), trace.completed.sorted())
        assertTrue(trace.cancelled.isEmpty())
    }

    @Test
    fun `a storage-full abort from one item ends the batch once the running items finish`() = runTest {
        val trace = Trace()
        val storageFull = AtomicBoolean(false)

        runUploadWorkers(tenItems, workers = 3, shouldStop = { storageFull.get() }) { item ->
            // The upload path's storage-full branch: the item fails early, raises the batch flag and
            // returns normally.
            if (item == 4) trace.process(item, 50) { storageFull.set(true) } else trace.process(item, 100)
        }

        assertEquals((0..5).toList(), trace.started)
        assertEquals((0..5).toList(), trace.completed.sorted())
        assertTrue(trace.cancelled.isEmpty())
        // Items 3 and 5 were already running when the flag went up: the batch ends when they finish
        // at 200 ms instead of starting item 6.
        assertEquals(200L, currentTime)
    }

    @Test
    fun `cancelling the batch cancels the running items and starts no more`() = runTest {
        val trace = Trace()
        val batch = launch {
            runUploadWorkers(tenItems, workers = 3, shouldStop = { false }) { trace.process(it, 100) }
        }

        advanceTimeBy(150)
        batch.cancel()
        advanceUntilIdle()

        assertTrue(batch.isCancelled)
        assertEquals((0..5).toList(), trace.started)
        assertEquals(listOf(0, 1, 2), trace.completed.sorted())
        assertEquals(setOf(3, 4, 5), trace.cancelled.toSet())
        assertEquals(0, trace.running)
    }

    @Test
    fun `a failure escaping an item cancels the running items and reaches the caller`() = runTest {
        val trace = Trace()

        val thrown = runWithEscapingFailure(trace, IllegalStateException("hard failure"))

        assertTrue("got $thrown", thrown is IllegalStateException)
        assertEquals((0..5).toList(), trace.started)
        assertEquals(setOf(3, 5), trace.cancelled.toSet())
    }

    @Test
    fun `a cancellation escaping one item tears the whole batch down`() = runTest {
        val trace = Trace()

        val thrown = runWithEscapingFailure(trace, CancellationException("item timed out"))

        assertTrue("got $thrown", thrown is CancellationException)
        assertEquals((0..5).toList(), trace.started)
        assertEquals(setOf(3, 5), trace.cancelled.toSet())
    }

    /** Ten 100 ms items on three workers where item 4 runs 50 ms and then throws [failure]. */
    private suspend fun runWithEscapingFailure(trace: Trace, failure: Throwable): Throwable? = try {
        runUploadWorkers(tenItems, workers = 3, shouldStop = { false }) { item ->
            if (item == 4) {
                trace.process(item, 50)
                throw failure
            }
            trace.process(item, 100)
        }
        null
    } catch (e: Throwable) {
        e
    }
}
