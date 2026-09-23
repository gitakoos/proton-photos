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

import eu.akoos.photos.data.db.dao.SyncStateLite
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.util.retryBackoffMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * The rules the shared sync-row copy holds for a large library under a running backup: one read
 * and one change listener however many screens collect, a burst of writes settled into one read,
 * change-driven reads at most once per interval with the last write always read, one-shot readers
 * sharing a read that began after they asked, and the copy dropped once no one collects.
 *
 * The table is a map the test edits at will, with a change signal shaped like Room's invalidation
 * flow (one signal as soon as it listens, then one per write). A read takes [READ_MS] of virtual
 * time and keeps the rows it saw when it began, which is how a real read can miss a write landing
 * mid-read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStateSnapshotTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    private class FakeTable(private val now: () -> Long) {
        private val rowsByUser = mutableMapOf<String, List<SyncStateLite>>()
        private val writes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        private var running = 0
        var failNextRead = false
        var listeners = 0
            private set
        var mostReadsAtOnce = 0
            private set
        val readStarts = mutableListOf<Long>()
        val readUsers = mutableListOf<String>()
        val reads: Int get() = readStarts.size

        fun changes(): Flow<Unit> = writes
            .onStart {
                listeners++
                emit(Unit)
            }
            .onCompletion { listeners-- }

        /** Rows the table holds from now on, with no change signalled. */
        fun seed(userId: String, vararg uris: String) {
            rowsByUser[userId] = uris.map { row(it) }
        }

        /** A write, signalled the way Room's invalidation tracker signals one. */
        fun write(userId: String, vararg uris: String) {
            seed(userId, *uris)
            writes.tryEmit(Unit)
        }

        suspend fun read(userId: String): List<SyncStateLite> {
            readStarts += now()
            readUsers += userId
            running++
            mostReadsAtOnce = maxOf(mostReadsAtOnce, running)
            val seen = rowsByUser[userId].orEmpty()
            try {
                delay(READ_MS)
            } finally {
                running--
            }
            if (failNextRead) {
                failNextRead = false
                error("read failed")
            }
            return seen
        }
    }

    private fun TestScope.source(
        table: FakeTable,
        failures: MutableList<Throwable> = mutableListOf(),
        changes: () -> Flow<Unit> = table::changes,
    ) = SyncStateSnapshot.Source(
        scope = appScope(),
        changes = changes,
        read = { table.read(it) },
        onReadFailure = { failures += it },
    )

    private fun TestScope.table() = FakeTable { testScheduler.currentTime }

    @Test
    fun `collectors share one read and one listener`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)
        val seenA = mutableListOf<List<String>>()
        val seenB = mutableListOf<List<String>>()
        val a = launch { source.observe(USER).collect { seenA += it.uris() } }
        val b = launch { source.observe(USER).collect { seenB += it.uris() } }
        advanceTimeBy(READ_MS + 1)

        assertEquals(listOf(listOf("uri://1")), seenA)
        assertEquals(listOf(listOf("uri://1")), seenB)
        assertEquals("every collector must share the one read", 1, table.reads)
        assertEquals("and the one change listener", 1, table.listeners)
        assertEquals("the first read runs as soon as the listener is in place", 0L, table.readStarts.single())
        a.cancel()
        b.cancel()
    }

    @Test
    fun `a burst of writes costs one read after the settle`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it.uris() } }
        advanceTimeBy(SYNC_SNAPSHOT_MIN_INTERVAL_MS + 1)
        assertEquals(1, table.reads)

        val burstStart = testScheduler.currentTime
        repeat(10) { i ->
            table.write(USER, "uri://1", "uri://${i + 2}")
            advanceTimeBy(10)
        }
        assertEquals("no read while the burst is still settling", 1, table.reads)

        advanceTimeBy(SYNC_SNAPSHOT_SETTLE_MS + READ_MS + 1)
        assertEquals("the whole burst must cost one read", 2, table.reads)
        assertEquals("an isolated burst is read once the settle has passed", burstStart + SYNC_SNAPSHOT_SETTLE_MS, table.readStarts.last())
        assertEquals(listOf(listOf("uri://1"), listOf("uri://1", "uri://11")), seen)
        job.cancel()
    }

    @Test
    fun `a steady stream of writes is read at most once per interval and ends on the last write`() = runTest {
        // A backup writes the table several times per photo for as long as it runs; a read per write
        // would rebuild the whole table's copy for every one of them.
        val table = table()
        table.seed(USER, "uri://start")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it.uris() } }
        advanceTimeBy(READ_MS + 1)

        val streamStart = testScheduler.currentTime
        repeat(100) { i ->
            table.write(USER, "uri://$i")
            advanceTimeBy(50)
        }

        val duringStream = table.readStarts.filter { it >= streamStart }
        assertTrue("a stream that never pauses must still be read", duringStream.size >= 3)
        table.readStarts.zipWithNext().forEach { (earlier, later) ->
            assertTrue("reads must start at least the interval apart", later - earlier >= SYNC_SNAPSHOT_MIN_INTERVAL_MS)
        }

        advanceTimeBy(SYNC_SNAPSHOT_MIN_INTERVAL_MS + SYNC_SNAPSHOT_SETTLE_MS + READ_MS + 1)
        assertEquals("the last write of the stream must be read", listOf("uri://99"), seen.last())
        job.cancel()
    }

    @Test
    fun `a write landing during a read is read again`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it.uris() } }
        advanceTimeBy(SYNC_SNAPSHOT_MIN_INTERVAL_MS + 1)

        table.write(USER, "uri://2")
        advanceTimeBy(SYNC_SNAPSHOT_SETTLE_MS + READ_MS / 2)
        assertEquals("the read of the first write is half way", 2, table.reads)

        table.write(USER, "uri://3")
        advanceTimeBy(SYNC_SNAPSHOT_MIN_INTERVAL_MS + SYNC_SNAPSHOT_SETTLE_MS + READ_MS + 1)

        assertEquals(3, table.reads)
        assertEquals(listOf(listOf("uri://1"), listOf("uri://2"), listOf("uri://3")), seen)
        job.cancel()
    }

    @Test
    fun `an unchanged re-read is not published again`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it.uris() } }
        advanceTimeBy(SYNC_SNAPSHOT_MIN_INTERVAL_MS + 1)

        table.write(USER, "uri://1")
        advanceTimeBy(SYNC_SNAPSHOT_SETTLE_MS + READ_MS + 1)

        assertEquals(2, table.reads)
        assertEquals("an equal re-read must not reach collectors again", listOf(listOf("uri://1")), seen)
        job.cancel()
    }

    @Test
    fun `one-shot readers that ask together share one read`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)

        val callers = List(5) { async { source.current(USER) } }
        runCurrent()
        assertEquals("callers that all asked before the read began must share it", 1, table.reads)

        advanceTimeBy(READ_MS + 1)
        callers.awaitAll().forEach { assertEquals(listOf("uri://1"), it.uris()) }
        assertEquals(1, table.reads)
    }

    @Test
    fun `a one-shot read never answers with a read that began before it`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)

        val first = async { source.current(USER) }
        runCurrent()
        assertEquals(1, table.reads)

        table.seed(USER, "uri://2")
        val second = async { source.current(USER) }
        advanceTimeBy(2 * READ_MS + 1)

        assertEquals(listOf("uri://1"), first.await().uris())
        assertEquals("the late caller must get a read that saw its change", listOf("uri://2"), second.await().uris())
        assertEquals(2, table.reads)
        assertEquals("reads must never overlap", 1, table.mostReadsAtOnce)
    }

    @Test
    fun `a one-shot read also reaches collectors`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it.uris() } }
        advanceTimeBy(READ_MS + 1)

        table.seed(USER, "uri://2")
        val read = async { source.current(USER) }
        advanceTimeBy(READ_MS + 1)

        assertEquals(listOf("uri://2"), read.await().uris())
        assertEquals(listOf(listOf("uri://1"), listOf("uri://2")), seen)
        job.cancel()
    }

    @Test
    fun `the copy is dropped and listening stops once no one collects`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val source = source(table)
        val job = launch { source.observe(USER).collect { } }
        advanceTimeBy(READ_MS + 1)
        job.cancel()

        advanceTimeBy(SYNC_SNAPSHOT_STOP_TIMEOUT_MS - 1)
        assertEquals("the copy outlives its last collector for the stop window", 1, table.listeners)
        advanceTimeBy(2)
        assertEquals("then it stops listening", 0, table.listeners)

        table.seed(USER, "uri://2")
        val next = async { source.observe(USER).first() }
        advanceTimeBy(READ_MS + 1)

        assertEquals("a reader after the drop must get a new read, not the dropped copy", listOf("uri://2"), next.await().uris())
        assertEquals(2, table.reads)
    }

    @Test
    fun `each user gets a copy of their own rows`() = runTest {
        val table = table()
        table.seed("user-a", "uri://a")
        table.seed("user-b", "uri://b")
        val source = source(table)

        val a = async { source.observe("user-a").first() }
        val b = async { source.observe("user-b").first() }
        advanceTimeBy(READ_MS + 1)

        assertEquals(listOf("uri://a"), a.await().uris())
        assertEquals(listOf("uri://b"), b.await().uris())
        assertEquals(listOf("user-a", "user-b"), table.readUsers.sorted())
    }

    @Test
    fun `a failed read keeps the held copy and is retried on its own`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
        val failures = mutableListOf<Throwable>()
        val source = source(table, failures)
        val seen = mutableListOf<List<String>>()
        val job = launch { source.observe(USER).collect { seen += it.uris() } }
        advanceTimeBy(SYNC_SNAPSHOT_MIN_INTERVAL_MS + 1)

        table.failNextRead = true
        table.write(USER, "uri://2")
        advanceTimeBy(SYNC_SNAPSHOT_SETTLE_MS + READ_MS + 1)
        assertEquals(2, table.reads)
        assertEquals(1, failures.size)
        assertEquals("a failed read must leave the held copy in place", listOf(listOf("uri://1")), seen)

        advanceTimeBy(retryBackoffMs(0) + SYNC_SNAPSHOT_SETTLE_MS + SYNC_SNAPSHOT_MIN_INTERVAL_MS + READ_MS + 1)
        assertEquals("the failed read is retried without waiting for another write", 3, table.reads)
        assertEquals(listOf(listOf("uri://1"), listOf("uri://2")), seen)
        job.cancel()
    }

    @Test
    fun `a listener that fails to start still reads and listens again`() = runTest {
        val table = table()
        table.seed(USER, "uri://1")
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
        assertEquals("a reader must not wait on a listener that failed", listOf("uri://1"), first.await().uris())
        assertEquals(1, failures.size)

        val job = launch { source.observe(USER).collect { } }
        advanceTimeBy(retryBackoffMs(0) + 1)
        assertEquals("the listener is set up again after a pause", 1, table.listeners)
        job.cancel()
    }

    @Test
    fun `the lookups readers share are built from the rows`() {
        val lite = listOf(
            SyncStateLite("uri://s", "c-s", "", SyncStatus.SYNCED, queued = false),
            SyncStateLite("uri://h", "c-h", "", SyncStatus.HIDDEN, queued = false),
            SyncStateLite("uri://h-unpaired", null, "", SyncStatus.HIDDEN, queued = false),
            SyncStateLite("uri://l", null, "sha-l", SyncStatus.LOCAL_ONLY, queued = true),
        )
        val rows = SyncStateSnapshot.Rows(lite)

        assertEquals(setOf("c-h"), rows.hiddenCloudIds)
        assertEquals(setOf("c-s"), rows.syncedCloudIds)
        assertEquals("sha-l", rows.byUri.getValue("uri://l").localHash)
        assertEquals(4, rows.byUri.size)
        assertEquals("reads holding the same rows are equal", SyncStateSnapshot.Rows(lite.toList()), rows)
    }

    private fun SyncStateSnapshot.Rows.uris(): List<String> = rows.map { it.localUri }

    private companion object {
        const val USER = "user1"
        const val READ_MS = 100L

        fun row(uri: String) = SyncStateLite(
            localUri = uri,
            cloudFileId = null,
            localHash = "",
            status = SyncStatus.LOCAL_ONLY,
            queued = false,
        )
    }
}
