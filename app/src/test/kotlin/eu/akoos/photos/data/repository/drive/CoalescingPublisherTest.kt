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

package eu.akoos.photos.data.repository.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The rate limit on the thumbnail store's change signal. Every decrypt marks a change; the grid only
 * has to hear about them often enough to repaint, and it must always hear about the last one. So: the
 * first mark after a quiet spell goes out at once, marks inside the window share one trailing publish,
 * two publishes are never closer than the window, and no burst ends without a publish that follows its
 * last mark. Timing is virtual except in the thread race, where only a lost final publish can fail it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingPublisherTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /** A publisher on the test's background scope: the virtual clock, and cancelled when the test body
     *  ends, so a window that never closes fails an assertion rather than spinning the scheduler. */
    private fun TestScope.publisher(onPublish: () -> Unit) =
        CoalescingPublisher(backgroundScope, WINDOW_MS, onPublish)

    @Test
    fun `the first mark publishes at once`() = runTest {
        val publishedAt = mutableListOf<Long>()
        val publisher = publisher { publishedAt += testScheduler.currentTime }

        publisher.mark()
        assertEquals(listOf(0L), publishedAt)

        advanceTimeBy(WINDOW_MS * 3)
        assertEquals("a window with nothing owed closes without a publish", listOf(0L), publishedAt)
    }

    @Test
    fun `marks inside the window share one publish when it ends`() = runTest {
        val publishedAt = mutableListOf<Long>()
        val publisher = publisher { publishedAt += testScheduler.currentTime }

        publisher.mark()
        repeat(9) {
            advanceTimeBy(10)
            publisher.mark()
        }
        advanceTimeBy(WINDOW_MS - 90 - 1)
        assertEquals("nothing more goes out before the window ends", listOf(0L), publishedAt)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, WINDOW_MS), publishedAt)

        advanceTimeBy(WINDOW_MS * 3)
        assertEquals("the absorbed marks cost no further publish", listOf(0L, WINDOW_MS), publishedAt)
    }

    @Test
    fun `a mark after a quiet spell publishes at once`() = runTest {
        val publishedAt = mutableListOf<Long>()
        val publisher = publisher { publishedAt += testScheduler.currentTime }

        publisher.mark()
        advanceTimeBy(WINDOW_MS * 3)
        publisher.mark()

        assertEquals(listOf(0L, WINDOW_MS * 3), publishedAt)
    }

    @Test
    fun `a sustained burst publishes at most once per window and ends on its last change`() = runTest {
        var changes = 0
        val publishedAt = mutableListOf<Long>()
        val seenChanges = mutableListOf<Int>()
        val publisher = publisher {
            publishedAt += testScheduler.currentTime
            seenChanges += changes
        }

        repeat(1_000) {
            changes++
            publisher.mark()
            advanceTimeBy(1)
        }
        advanceTimeBy(WINDOW_MS * 3)

        assertTrue("${publishedAt.size} publishes", publishedAt.size <= 1_000 / WINDOW_MS + 2)
        assertTrue(
            "publishes closer than the window: $publishedAt",
            publishedAt.zipWithNext().all { (a, b) -> b - a >= WINDOW_MS },
        )
        assertEquals("the final publish sees every change", 1_000, seenChanges.last())
    }

    @Test
    fun `marks racing on many threads never lose the final publish`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val written = AtomicInteger(0)
        val seen = AtomicInteger(0)
        val publisher = CoalescingPublisher(scope, windowMs = 2L) {
            seen.accumulateAndGet(written.get()) { a, b -> maxOf(a, b) }
        }

        val writers = List(4) {
            thread {
                repeat(2_000) {
                    written.incrementAndGet()
                    publisher.mark()
                }
            }
        }
        writers.forEach { it.join() }

        withTimeout(10_000L) {
            while (seen.get() != 8_000) delay(5)
        }
        assertEquals(8_000, seen.get())
    }

    private companion object {
        const val WINDOW_MS = 100L
    }
}
