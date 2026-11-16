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

package eu.akoos.photos.data.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ItemCancels]: cancelling one item ends only that item, an item cancelled before its turn never
 * starts, a cancel after an item ended changes nothing, an item past its commit point finishes, and a
 * cancel of the whole batch still reaches the caller.
 */
class ItemCancelsTest {

    @Test
    fun `cancelling one running item stops only it and the others finish`() = runTest {
        val cancels = ItemCancels()
        val finished = mutableListOf<String>()
        val results = listOf("a", "b", "c").map { key ->
            async {
                key to cancels.run(key) {
                    delay(100)
                    finished += key
                }
            }
        }
        launch {
            delay(50)
            cancels.cancel("b")
        }
        val outcome = results.awaitAll().toMap()
        assertEquals(listOf("a", "c"), finished)
        assertEquals(mapOf("a" to false, "b" to true, "c" to false), outcome)
    }

    @Test
    fun `an item cancelled before its turn never starts`() = runTest {
        val cancels = ItemCancels()
        cancels.cancel("queued")
        var started = false
        val stopped = cancels.run("queued") { started = true }
        assertTrue(stopped)
        assertFalse(started)
    }

    @Test
    fun `a cancel after the item ended does not mark it cancelled`() = runTest {
        val cancels = ItemCancels()
        val stopped = cancels.run("done") { delay(10) }
        cancels.cancel("done")
        assertFalse(stopped)
    }

    @Test
    fun `clear forgets earlier requests for the next batch`() = runTest {
        val cancels = ItemCancels()
        cancels.cancel("x")
        cancels.clear()
        var ran = false
        assertFalse(cancels.run("x") { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `cancelling the caller still propagates`() = runTest {
        val cancels = ItemCancels()
        var caught: Throwable? = null
        val job = launch {
            try {
                cancels.run("k") { delay(1_000) }
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }
        delay(10)
        job.cancel()
        job.join()
        assertTrue(caught is CancellationException)
        assertFalse(cancels.isCancelled("k"))
    }
    @Test
    fun `an item past its commit point finishes despite a later cancel`() = runTest {
        val cancels = ItemCancels()
        var committed: Boolean? = null
        var finished = false
        val stopped = cancels.run("k") {
            committed = ItemCommitPoint.reach()
            cancels.cancel("k")
            delay(50)
            finished = true
        }
        assertEquals(true, committed)
        assertTrue(finished)
        assertFalse(stopped)
    }

    @Test
    fun `a cancel before the commit point makes the item stop there`() = runTest {
        val cancels = ItemCancels()
        var committed: Boolean? = null
        val stopped = cancels.run("k") {
            cancels.cancel("k")
            committed = ItemCommitPoint.reach()
            if (committed == false) throw CancellationException("stopped before the commit")
        }
        assertEquals(false, committed)
        assertTrue(stopped)
    }

    @Test
    fun `a committed item stays committed at a later commit point after a cancel`() = runTest {
        val cancels = ItemCancels()
        var second: Boolean? = null
        val stopped = cancels.run("k") {
            ItemCommitPoint.reach()
            cancels.cancel("k")
            second = ItemCommitPoint.reach()
        }
        assertEquals(true, second)
        assertFalse(stopped)
    }

    @Test
    fun `outside an item the commit point always lets the work go on`() = runTest {
        assertTrue(ItemCommitPoint.reach())
    }
    @Test
    fun `a cancel during a stoppable step goes to its stop and a step that still completes is kept`() = runTest {
        val cancels = ItemCancels()
        var stopAsked = false
        var afterCommit = false
        val stopped = cancels.run("k") {
            ItemCommitPoint.stopVia(onStop = { stopAsked = true }) {
                cancels.cancel("k")
                delay(10)
                "committed anyway"
            }
            // The coroutine was never cancelled, so the steps after the commit still run.
            delay(10)
            afterCommit = true
        }
        assertTrue(stopAsked)
        assertTrue(afterCommit)
        assertFalse(stopped)
    }

    @Test
    fun `a stoppable step that gives up after its stop ends the item as cancelled`() = runTest {
        val cancels = ItemCancels()
        val stopped = cancels.run("k") {
            ItemCommitPoint.stopVia(onStop = {}) {
                cancels.cancel("k")
                throw CancellationException("the step stopped")
            }
        }
        assertTrue(stopped)
    }

    @Test
    fun `a cancel before a stoppable step stops the item without running the step`() = runTest {
        val cancels = ItemCancels()
        var ran = false
        val stopped = cancels.run("k") {
            cancels.cancel("k")
            ItemCommitPoint.stopVia(onStop = {}) { ran = true }
        }
        assertFalse(ran)
        assertTrue(stopped)
    }

    @Test
    fun `a cancel after a completed stoppable step lets the item finish`() = runTest {
        val cancels = ItemCancels()
        var reachedEnd = false
        val stopped = cancels.run("k") {
            ItemCommitPoint.stopVia(onStop = {}) { delay(1) }
            // Committed by the step: a cancel now lets the item finish.
            cancels.cancel("k")
            delay(10)
            reachedEnd = true
        }
        assertTrue(reachedEnd)
        assertFalse(stopped)
    }
}
