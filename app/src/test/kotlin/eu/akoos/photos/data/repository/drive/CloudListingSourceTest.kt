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

import eu.akoos.photos.util.retryBackoffMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the shared cloud listing holds for a large library while the table is written without
 * pause: the first read at once, one read and one change listener however many screens collect, a
 * burst of writes settled into one read, change-driven reads never sooner than the interval after
 * the previous read ended, the last write always read, and the copy dropped once no one collects.
 *
 * The table is a map the test edits at will, with a change signal shaped like Room's invalidation
 * flow (one signal as soon as it listens, then one per write). A read takes [READ_MS] of virtual
 * time unless a test sets otherwise, and keeps the rows it saw when it began, which is how a real
 * read can miss a write landing mid-read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudListingSourceTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    private class FakeTable(private val now: () -> Long, private val readMs: Long) {
        private val rowsByUser = mutableMapOf<String, List<String>>()
        private val writes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        private var running = 0
        var failNextRead = false
        var listeners = 0
            private set
        var mostReadsAtOnce = 0
            private set
        val readStarts = mutableListOf<Long>()
        val readEnds = mutableListOf<Long>()
        val readUsers = mutableListOf<String>()
        val reads: Int get() = readStarts.size

        fun changes(): Flow<Unit> = writes
            .onStart {
                listeners++
                emit(Unit)
            }
            .onCompletion { listeners-- }

        /** Rows the table holds from now on, with no change signalled. */
        fun seed(userId: String, vararg linkIds: String) {
            rowsByUser[userId] = linkIds.toList()
        }

        /** A write, signalled the way Room's invalidation tracker signals one. */
        fun write(userId: String, vararg linkIds: String) {
            seed(userId, *linkIds)
            writes.tryEmit(Unit)
        }

        suspend fun read(userId: String): List<String> {
            readStarts += now()
            readUsers += userId
            running++
            mostReadsAtOnce = maxOf(mostReadsAtOnce, running)
            val seen = rowsByUser[userId].orEmpty()
            try {
                delay(readMs)
            } finally {
                running--
                readEnds += now()
            }
            if (failNextRead) {
                failNextRead = false
                error("read failed")
            }
            return seen
        }
    }

    private fun TestScope.table(readMs: Long = READ_MS) = FakeTable({ testScheduler.currentTime }, readMs)

    private fun TestScope.source(
        table: FakeTable,
        failures: MutableList<Throwable> = mutableListOf(),
        changes: () -> Flow<Unit> = table::changes,
    ) = CloudListingSource(
        scope = appScope(),
        changes = changes,
        read = { table.read(it) },
        onReadFailure = { failures += it },
    )

    /** Every change-driven read started at least the interval after the read before it ended. */
    private fun assertIdleGapBetweenReads(table: FakeTable) {
        table.readStarts.drop(1).forEachIndexed { i, start ->
            assertTrue(
                "a read must start at least the interval after the previous read ended",
                start - table.readEnds[i] >= CLOUD_LISTING_MIN_INTERVAL_MS,
            )
        }
    }

    @Test
    fun `the first read runs at once and every collector shares it and the one listener`() = runTest {
        val table = table()
        table.seed(USER, "p1")
        val source = source(table)
        val seenA = mutableListOf<List<String>>()
        val seenB = mutableListOf<List<String>>()
        val a = launch { source.observe(USER).collect { seenA += it } }
        val b = launch { source.observe(USER).collect { seenB += it } }
        advanceTimeBy(READ_MS + 1)

        assertEquals(listOf(listOf("p1")), seenA)
        assertEquals(listOf(listOf("p1")), seenB)
        assertEquals("the first read runs as soon as the listener is in place", 0L, table.readStarts.single())
        assertEquals("and the one change listener serves every collector", 1, table.listeners)

        val seenLater = mutableListOf<List<String>>()
        val later = launch { source.observe(USER).collect { seenLater += it } }
        runCurrent()
        assertEquals("a later collector gets the held copy at once", listOf(listOf("p1")), seenLater)
        assertEquals("without a read of its own", 1, table.reads)
        a.cancel()
        b.cancel()
        later.cancel()
    }

    @Test
    fun `a burst of writes costs one read after the settle`() = runTest {
        val table = table()
        table.seed(USER, "p1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it } }
        advanceTimeBy(IDLE_MS)
        assertEquals(1, table.reads)

        val burstStart = testScheduler.currentTime
        repeat(10) { i ->
            table.write(USER, "p1", "p${i + 2}")
            advanceTimeBy(10)
        }
        assertEquals("no read while the burst is still settling", 1, table.reads)

        advanceTimeBy(CLOUD_LISTING_SETTLE_MS + READ_MS + 1)
        assertEquals("the whole burst must cost one read", 2, table.reads)
        assertEquals(
            "an isolated burst is read once the settle has passed",
            burstStart + CLOUD_LISTING_SETTLE_MS,
            table.readStarts.last(),
        )
        assertEquals("the read sees the last write of the burst", listOf(listOf("p1"), listOf("p1", "p11")), seen)
        job.cancel()
    }

    @Test
    fun `a steady stream of writes is read at a bounded rate and ends on the last write`() = runTest {
        // A backup and a thumbnail decrypt burst write the table many times a second for as long as
        // they run; a read per write would rebuild the whole library's copy for every one of them.
        val table = table()
        table.seed(USER, "start")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it } }
        advanceTimeBy(READ_MS + 1)

        val streamStart = testScheduler.currentTime
        repeat(100) { i ->
            table.write(USER, "p$i")
            advanceTimeBy(50)
        }
        val streamEnd = testScheduler.currentTime

        val duringStream = table.readStarts.filter { it in streamStart until streamEnd }
        assertTrue("a stream that never pauses must still be read", duringStream.size >= 3)
        assertTrue(
            "but far less often than it is written",
            duringStream.size <= (streamEnd - streamStart) / CLOUD_LISTING_MIN_INTERVAL_MS,
        )
        assertIdleGapBetweenReads(table)

        advanceTimeBy(CLOUD_LISTING_MIN_INTERVAL_MS + CLOUD_LISTING_SETTLE_MS + READ_MS + 1)
        assertEquals("the last write of the stream must be read", listOf("p99"), seen.last())
        assertEquals("reads must never overlap", 1, table.mostReadsAtOnce)
        job.cancel()
    }

    @Test
    fun `a read slower than the interval still leaves the interval idle before the next`() = runTest {
        // A whole large library can take longer to read than the interval itself. Counted from the
        // start of a read, the interval would already have passed when the read ends, and reads
        // would run back to back for as long as the writes continue.
        val table = table(readMs = SLOW_READ_MS)
        table.seed(USER, "start")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it } }
        advanceTimeBy(1)

        repeat(100) { i ->
            table.write(USER, "p$i")
            advanceTimeBy(50)
        }
        advanceTimeBy(CLOUD_LISTING_MIN_INTERVAL_MS + CLOUD_LISTING_SETTLE_MS + 2 * SLOW_READ_MS)

        assertTrue("the stream must still be read more than once", table.reads >= 3)
        assertIdleGapBetweenReads(table)
        assertEquals("the last write of the stream must be read", listOf("p99"), seen.last())
        job.cancel()
    }

    @Test
    fun `a write landing during a read is read again`() = runTest {
        val table = table()
        table.seed(USER, "p1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it } }
        advanceTimeBy(IDLE_MS)

        table.write(USER, "p2")
        advanceTimeBy(CLOUD_LISTING_SETTLE_MS + READ_MS / 2)
        assertEquals("the read of the first write is half way", 2, table.reads)

        table.write(USER, "p3")
        advanceTimeBy(CLOUD_LISTING_MIN_INTERVAL_MS + CLOUD_LISTING_SETTLE_MS + READ_MS + 1)

        assertEquals(3, table.reads)
        assertEquals(listOf(listOf("p1"), listOf("p2"), listOf("p3")), seen)
        assertIdleGapBetweenReads(table)
        job.cancel()
    }

    @Test
    fun `an unchanged re-read is not published again`() = runTest {
        // A decrypted thumbnail writes a column the listing does not select: the table changes, the
        // listing does not, and the timeline must not rebuild for it.
        val table = table()
        table.seed(USER, "p1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it } }
        advanceTimeBy(IDLE_MS)

        table.write(USER, "p1")
        advanceTimeBy(CLOUD_LISTING_SETTLE_MS + READ_MS + 1)

        assertEquals(2, table.reads)
        assertEquals("an equal re-read must not reach collectors again", listOf(listOf("p1")), seen)
        job.cancel()
    }

    @Test
    fun `the copy is dropped and listening stops once no one collects`() = runTest {
        val table = table()
        table.seed(USER, "p1")
        val source = source(table)
        val job = launch { source.observe(USER).collect { } }
        advanceTimeBy(READ_MS + 1)
        job.cancel()

        advanceTimeBy(CLOUD_LISTING_STOP_TIMEOUT_MS - 1)
        assertEquals("the copy outlives its last collector for the stop window", 1, table.listeners)
        advanceTimeBy(2)
        assertEquals("then it stops listening", 0, table.listeners)

        table.seed(USER, "p2")
        val next = async { source.observe(USER).first() }
        advanceTimeBy(READ_MS + 1)

        assertEquals("a reader after the drop must get a new read, not the dropped copy", listOf("p2"), next.await())
        assertEquals(2, table.reads)
    }

    @Test
    fun `each user gets a copy of their own listing`() = runTest {
        val table = table()
        table.seed("user-a", "a1")
        table.seed("user-b", "b1")
        val source = source(table)

        val a = async { source.observe("user-a").first() }
        val b = async { source.observe("user-b").first() }
        advanceTimeBy(READ_MS + 1)

        assertEquals(listOf("a1"), a.await())
        assertEquals(listOf("b1"), b.await())
        assertEquals(listOf("user-a", "user-b"), table.readUsers.sorted())
    }

    @Test
    fun `a failed read keeps the held copy and is retried on its own`() = runTest {
        val table = table()
        table.seed(USER, "p1")
        val failures = mutableListOf<Throwable>()
        val source = source(table, failures)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it } }
        advanceTimeBy(IDLE_MS)

        table.failNextRead = true
        table.write(USER, "p2")
        advanceTimeBy(CLOUD_LISTING_SETTLE_MS + READ_MS + 1)
        assertEquals(2, table.reads)
        assertEquals(1, failures.size)
        assertEquals("a failed read must leave the held copy in place", listOf(listOf("p1")), seen)

        advanceTimeBy(retryBackoffMs(0) + CLOUD_LISTING_SETTLE_MS + CLOUD_LISTING_MIN_INTERVAL_MS + READ_MS + 1)
        assertEquals("the failed read is retried without waiting for another write", 3, table.reads)
        assertEquals(listOf(listOf("p1"), listOf("p2")), seen)
        job.cancel()
    }

    @Test
    fun `a listener that fails to start still reads and listens again`() = runTest {
        val table = table()
        table.seed(USER, "p1")
        val failures = mutableListOf<Throwable>()
        var refuse = true
        val source = source(table, failures) {
            if (refuse) {
                refuse = false
                flow<Unit> { error("no tracker") }
            } else {
                table.changes()
            }
        }

        val first = async { source.observe(USER).first() }
        advanceTimeBy(READ_MS + 1)
        assertEquals("a reader must not wait on a listener that failed", listOf("p1"), first.await())
        assertEquals(1, failures.size)

        val job = launch { source.observe(USER).collect { } }
        advanceTimeBy(retryBackoffMs(0) + 1)
        assertEquals("the listener is set up again after a pause", 1, table.listeners)
        job.cancel()
    }

    private companion object {
        const val USER = "user1"
        const val READ_MS = 100L
        const val SLOW_READ_MS = 1_500L

        /** From the start of a test to past the interval that follows the first read. */
        const val IDLE_MS = READ_MS + CLOUD_LISTING_MIN_INTERVAL_MS + 1
    }
}
