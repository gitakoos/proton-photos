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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write rule of the decrypted-thumbnail URL buffer: nothing is written while URLs keep arriving
 * inside the idle window, one batch goes out once they stop, a sustained stream is still flushed at the
 * longest wait, an explicit flush writes at once, and discarded URLs are never written. The writer is a
 * recorder and the clock is the test scheduler's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailUrlWriteBufferTest {

    private val batches = mutableListOf<Map<String, String>>()

    private fun TestScope.buffer() = ThumbnailUrlWriteBuffer(
        scope = backgroundScope,
        idleMs = 100,
        maxWaitMs = 500,
        write = { batches += it },
    )

    @Test
    fun `urls inside the idle window are written as one batch after it`() = runTest {
        val buffer = buffer()

        buffer.add("A", "ua")
        advanceTimeBy(60)
        buffer.add("B", "ub")
        advanceTimeBy(60)
        assertTrue("still inside the window of the last arrival", batches.isEmpty())

        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf(mapOf("A" to "ua", "B" to "ub")), batches)
    }

    @Test
    fun `a sustained stream is flushed at the longest wait`() = runTest {
        val writtenAt = mutableListOf<Long>()
        val buffer = ThumbnailUrlWriteBuffer(backgroundScope, 100, 500) { writtenAt += testScheduler.currentTime }

        // An arrival every 50 ms never leaves a quiet idle step.
        repeat(20) { i ->
            buffer.add("L$i", "u$i")
            advanceTimeBy(50)
        }
        runCurrent()

        assertEquals(listOf(500L), writtenAt)
    }

    @Test
    fun `an arrival during a write gets its own batch`() = runTest {
        val buffer = buffer()

        buffer.add("A", "ua")
        advanceTimeBy(100)
        runCurrent()
        buffer.add("B", "ub")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf(mapOf("A" to "ua"), mapOf("B" to "ub")), batches)
    }

    @Test
    fun `a later url for the same link replaces the pending one`() = runTest {
        val buffer = buffer()

        buffer.add("A", "old")
        buffer.add("A", "new")
        buffer.flush()

        assertEquals(listOf(mapOf("A" to "new")), batches)
    }

    @Test
    fun `flush writes at once and leaves nothing for the timer`() = runTest {
        val buffer = buffer()

        buffer.add("A", "ua")
        buffer.flush()
        advanceTimeBy(1000)
        runCurrent()

        assertEquals(listOf(mapOf("A" to "ua")), batches)
    }

    @Test
    fun `flush with nothing pending writes nothing`() = runTest {
        buffer().flush()

        assertTrue(batches.isEmpty())
    }

    @Test
    fun `discarded urls are not written`() = runTest {
        val buffer = buffer()

        buffer.add("A", "ua")
        buffer.add("B", "ub")
        buffer.discard(listOf("A"))
        buffer.flush()
        buffer.add("C", "uc")
        buffer.discardAll()
        buffer.flush()

        assertEquals(listOf(mapOf("B" to "ub")), batches)
    }

    @Test
    fun `a failed write drops its batch and later urls still go out`() = runTest {
        var fail = true
        val buffer = ThumbnailUrlWriteBuffer(backgroundScope, 100, 500) {
            if (fail) throw IllegalStateException("db closed")
            batches += it
        }

        buffer.add("A", "ua")
        buffer.flush()
        fail = false
        buffer.add("B", "ub")
        buffer.flush()

        assertEquals(listOf(mapOf("B" to "ub")), batches)
    }
}
