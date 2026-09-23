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

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pool [LocalExifBackfillScheduler] reads its files on: every photo reached exactly once, never
 * more reads at once than workers, a failing photo skipped rather than ending the walk, and no photo
 * started once the walk is cancelled.
 *
 * The action stands in for one file read. Timing is virtual, so "at once" is decided by the
 * scheduler, not a sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalExifBackfillPoolTest {

    /** For walks where nothing may fail: a reported failure ends the test. */
    private val noFailure: (Int, Throwable) -> Unit = { photo, e ->
        throw AssertionError("photo $photo reported as failed", e)
    }

    @Test
    fun `every photo is processed exactly once`() = runTest {
        val photos = List(1_000) { it }
        val read = mutableListOf<Int>()
        val finished = AtomicInteger()

        forEachPooled(photos, workers = 4, finished = finished, onFailure = noFailure) { photo ->
            read += photo
            yield()
        }

        assertEquals("each photo must be read once, none skipped or repeated", photos, read.sorted())
        assertEquals(photos.size, finished.get())
    }

    @Test
    fun `the walk runs on its workers, not a coroutine per photo`() = runTest {
        val photos = List(500) { it }
        val coroutines = mutableSetOf<Job>()
        var running = 0
        var mostAtOnce = 0

        forEachPooled(photos, workers = 4, finished = AtomicInteger(), onFailure = noFailure) {
            coroutines += currentCoroutineContext()[Job]!!
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            delay(10)
            running--
        }

        assertEquals("every photo must run on one of the four workers", 4, coroutines.size)
        assertEquals("all four workers must read at once, and never more", 4, mostAtOnce)
    }

    @Test
    fun `a photo that fails does not stop the others`() = runTest {
        val photos = List(100) { it }
        val broken = listOf(0, 13, 57, 99)
        val read = mutableListOf<Int>()
        val failed = mutableListOf<Int>()
        val finished = AtomicInteger()

        forEachPooled(
            photos,
            workers = 4,
            finished = finished,
            onFailure = { photo, _ -> failed += photo },
        ) { photo ->
            yield()
            if (photo in broken) throw IllegalStateException("unreadable file")
            read += photo
        }

        assertEquals(photos - broken.toSet(), read.sorted())
        assertEquals(broken, failed.sorted())
        assertEquals("a failed photo still counts as finished", photos.size, finished.get())
    }

    @Test
    fun `a cancellation raised inside one read fails that photo, not the walk`() = runTest {
        // One worker, so a worker that gave up here would leave every later photo unread.
        val photos = List(20) { it }
        val read = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        forEachPooled(
            photos,
            workers = 1,
            finished = AtomicInteger(),
            onFailure = { photo, _ -> failed += photo },
        ) { photo ->
            if (photo == 3) throw CancellationException("a timeout inside the read")
            read += photo
        }

        assertEquals(listOf(3), failed)
        assertEquals(photos - 3, read)
    }

    @Test
    fun `cancelling the walk stops it starting photos`() = runTest {
        val photos = List(100) { it }
        val started = mutableListOf<Int>()
        val finished = AtomicInteger()

        val walk = launch {
            forEachPooled(photos, workers = 4, finished = finished, onFailure = noFailure) { photo ->
                started += photo
                delay(10)
            }
        }
        advanceTimeBy(25)
        walk.cancel()
        advanceUntilIdle()

        assertTrue(walk.isCancelled)
        assertEquals("three rounds of four reads had begun when the walk was cancelled", 12, started.size)
        assertEquals("only the reads that completed count as finished", 8, finished.get())
    }

    @Test
    fun `a walk of blocking reads stops at the next photo once cancelled`() = runTest {
        // The real reads block on the disk rather than suspend, so only the check between photos can
        // end a cancelled walk.
        val photos = List(100) { it }
        var reads = 0

        val walk = launch {
            forEachPooled(photos, workers = 4, finished = AtomicInteger(), onFailure = noFailure) {
                reads++
                if (reads == 10) this@launch.cancel()
            }
        }
        advanceUntilIdle()

        assertTrue(walk.isCancelled)
        assertEquals("no photo may be read after the cancel", 10, reads)
    }
}
