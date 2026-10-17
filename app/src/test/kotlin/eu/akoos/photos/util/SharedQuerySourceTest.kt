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

package eu.akoos.photos.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sharing rules [SharedQuerySource] holds for the device-media list: one read and one change
 * listener however many collect, a burst of changes settled into one read, a steady stream still
 * read on a bounded interval, and the list dropped once no one collects.
 *
 * The store is a string the test changes at will, read in [READ_MS] of virtual time. A read keeps
 * the value it saw when it began, which is how a real scan can miss a change landing mid-read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedQuerySourceTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    private class FakeStore {
        var content = "v1"
        var failNextRead = false
        var outOfMemoryNextRead = false
        var reads = 0
            private set
        var listeners = 0
            private set
        var mostReadsAtOnce = 0
            private set
        val readStarts = mutableListOf<Long>()
        private var running = 0
        private var onChange: (() -> Unit)? = null

        fun listen(signal: () -> Unit): () -> Unit {
            listeners++
            onChange = signal
            return {
                listeners--
                onChange = null
            }
        }

        /** A change the store announces, as MediaStore does through its observer. */
        fun change() {
            onChange?.invoke()
        }

        suspend fun read(now: Long): String {
            reads++
            readStarts += now
            running++
            mostReadsAtOnce = maxOf(mostReadsAtOnce, running)
            val seen = content
            try {
                delay(READ_MS)
            } finally {
                running--
            }
            if (failNextRead) {
                failNextRead = false
                error("scan failed")
            }
            if (outOfMemoryNextRead) {
                outOfMemoryNextRead = false
                throw OutOfMemoryError("heap")
            }
            return seen
        }
    }

    private fun TestScope.source(
        store: FakeStore,
        isStale: () -> Boolean = { false },
        failures: MutableList<Throwable> = mutableListOf(),
    ) = SharedQuerySource(
        scope = appScope(),
        listen = store::listen,
        quietMs = QUIET_MS,
        maxWaitMs = MAX_WAIT_MS,
        stopTimeoutMs = STOP_MS,
        isStale = isStale,
        now = { testScheduler.currentTime },
        onReadFailure = { failures += it },
    ) { store.read(testScheduler.currentTime) }

    @Test
    fun `collectors share one read and one listener`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val seenA = mutableListOf<String>()
        val seenB = mutableListOf<String>()
        val a = launch { source.shared.collect { seenA += it } }
        val b = launch { source.shared.collect { seenB += it } }
        advanceTimeBy(READ_MS + 1)

        assertEquals(listOf("v1"), seenA)
        assertEquals(listOf("v1"), seenB)
        assertEquals("every collector must share the one read", 1, store.reads)
        assertEquals("and the one change listener", 1, store.listeners)
        a.cancel()
        b.cancel()
    }

    @Test
    fun `a change during the first read is not lost`() = runTest {
        // Listening starts before the first read, so a change the read began too early to see still
        // brings a read that does.
        val store = FakeStore()
        val source = source(store)
        val seen = mutableListOf<String>()
        val job = launch { source.shared.collect { seen += it } }
        runCurrent()
        assertEquals(1, store.reads)

        store.content = "v2"
        store.change()
        advanceTimeBy(READ_MS + QUIET_MS + READ_MS + 1)

        assertEquals(listOf("v1", "v2"), seen)
        job.cancel()
    }

    @Test
    fun `a burst of changes settles into one read`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val seen = mutableListOf<String>()
        val job = launch { source.shared.collect { seen += it } }
        advanceTimeBy(READ_MS + 1)
        assertEquals(1, store.reads)

        store.content = "v2"
        repeat(8) {
            store.change()
            advanceTimeBy(QUIET_MS / 2)
        }
        assertEquals("no read while the burst is still arriving", 1, store.reads)

        advanceTimeBy(QUIET_MS + READ_MS + 1)
        assertEquals("the whole burst must cost one read", 2, store.reads)
        assertEquals(listOf("v1", "v2"), seen)
        job.cancel()
    }

    @Test
    fun `a steady stream of changes is still read within the max wait`() = runTest {
        // An import lands file after file with no pause; a plain debounce would never read until
        // it ended, so the timeline would show nothing new for the whole import.
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)

        val streamStart = testScheduler.currentTime
        val spacing = QUIET_MS / 2
        repeat((3 * MAX_WAIT_MS / spacing).toInt()) {
            store.change()
            advanceTimeBy(spacing)
        }

        val duringStream = store.readStarts.filter { it >= streamStart }
        assertTrue("a stream that never pauses must still be read", duringStream.size >= 2)
        assertTrue("the first signal waits no longer than the max wait", duringStream.first() - streamStart <= MAX_WAIT_MS)
        // After a read ends the next burst opens at the next signal, which then waits the max wait.
        duringStream.zipWithNext().forEach { (earlier, later) ->
            assertTrue(
                "reads during a stream stay a bounded interval apart",
                later - earlier <= MAX_WAIT_MS + READ_MS + spacing,
            )
        }
        job.cancel()
    }

    @Test
    fun `an unchanged re-read is not published again`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val seen = mutableListOf<String>()
        val job = launch { source.shared.collect { seen += it } }
        advanceTimeBy(READ_MS + 1)

        store.change()
        advanceTimeBy(QUIET_MS + READ_MS + 1)

        assertEquals(2, store.reads)
        assertEquals("an equal re-read must not reach collectors again", listOf("v1"), seen)
        job.cancel()
    }

    @Test
    fun `a fresh read reaches every collector`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val seen = mutableListOf<String>()
        val job = launch { source.shared.collect { seen += it } }
        advanceTimeBy(READ_MS + 1)

        store.content = "v2"
        val read = async { source.fresh() }
        advanceTimeBy(READ_MS + 1)

        assertEquals("v2", read.await())
        assertEquals("a one-shot read must also refresh the shared list", listOf("v1", "v2"), seen)
        assertEquals(2, store.reads)
        job.cancel()
    }

    @Test
    fun `a fresh read never answers with a read that began before it`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)

        store.change()
        advanceTimeBy(QUIET_MS + READ_MS / 2)
        assertEquals("the change-driven read is now half way through", 2, store.reads)

        store.content = "v2"
        val read = async { source.fresh() }
        advanceTimeBy(2 * READ_MS + 1)

        assertEquals("v2", read.await())
        assertEquals(3, store.reads)
        assertEquals("a change-driven read and a fresh read must never overlap", 1, store.mostReadsAtOnce)
        job.cancel()
    }

    @Test
    fun `a collector arriving within the stop window reuses the held list`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)
        job.cancel()
        advanceTimeBy(1_000)

        val again = async { source.shared.first() }
        runCurrent()

        assertEquals("v1", again.await())
        assertEquals("a list still held must not be read again", 1, store.reads)
    }

    @Test
    fun `the list is dropped and listening stops once no one collects`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)
        job.cancel()

        advanceTimeBy(STOP_MS - 1)
        assertEquals("the source outlives its last collector for the stop window", 1, store.listeners)
        advanceTimeBy(2)
        assertEquals("then it stops listening", 0, store.listeners)

        store.content = "v2"
        val next = async { source.shared.first() }
        advanceTimeBy(READ_MS + 1)

        assertEquals("a reader after the drop must get a new read, not the dropped list", "v2", next.await())
        assertEquals(2, store.reads)
    }

    @Test
    fun `a failed read keeps the held list and is retried on its own`() = runTest {
        val store = FakeStore()
        val failures = mutableListOf<Throwable>()
        val source = source(store, failures = failures)
        val seen = mutableListOf<String>()
        val job = launch { source.shared.collect { seen += it } }
        advanceTimeBy(READ_MS + 1)

        store.content = "v2"
        store.failNextRead = true
        store.change()
        advanceTimeBy(QUIET_MS + READ_MS + 1)
        assertEquals(2, store.reads)
        assertEquals(1, failures.size)
        assertEquals("a failed read must leave the held list in place", listOf("v1"), seen)

        advanceTimeBy(retryBackoffMs(0) + QUIET_MS + READ_MS + 1)
        assertEquals("the failed read is retried without waiting for another change", 3, store.reads)
        assertEquals(listOf("v1", "v2"), seen)
        job.cancel()
    }

    @Test
    fun `a failed first read is retried so a waiting reader still gets a list`() = runTest {
        val store = FakeStore()
        store.failNextRead = true
        val source = source(store)

        val first = async { source.shared.first() }
        advanceTimeBy(READ_MS + retryBackoffMs(0) + QUIET_MS + READ_MS + 1)

        assertEquals("v1", first.await())
        assertEquals(2, store.reads)
    }

    @Test
    fun `a read that runs out of memory is not retried`() = runTest {
        // Another whole-library read would only run out again, on a loop with no end.
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e },
        ).also { scopes += it }
        val store = FakeStore()
        store.outOfMemoryNextRead = true
        val failures = mutableListOf<Throwable>()
        val source = SharedQuerySource(
            scope = scope,
            listen = store::listen,
            quietMs = QUIET_MS,
            maxWaitMs = MAX_WAIT_MS,
            stopTimeoutMs = STOP_MS,
            now = { testScheduler.currentTime },
            onReadFailure = { failures += it },
        ) { store.read(testScheduler.currentTime) }

        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 10 * (retryBackoffMs(9) + QUIET_MS + READ_MS))

        assertEquals("the read that ran out of memory must not run again", 1, store.reads)
        assertTrue("it is no ordinary read failure", failures.isEmpty())
        assertTrue(uncaught.isNotEmpty() && uncaught.all { it is OutOfMemoryError })
        job.cancel()
    }

    @Test
    fun `a collector finding the held list stale asks for a new read`() = runTest {
        // A permission grant or a volume change reaches no observer. A reader that would have scanned
        // for itself before the list was shared must still see the change.
        val store = FakeStore()
        var stale = false
        val source = source(store, isStale = { stale })
        val seenA = mutableListOf<String>()
        val a = launch { source.shared.collect { seenA += it } }
        advanceTimeBy(READ_MS + 1)

        stale = true
        store.content = "v2"
        val seenB = mutableListOf<String>()
        val b = launch { source.shared.collect { seenB += it } }
        advanceTimeBy(QUIET_MS + READ_MS + 1)

        assertEquals("the new collector gets the held list at once, then the re-read", listOf("v1", "v2"), seenB)
        assertEquals(listOf("v1", "v2"), seenA)
        assertEquals(2, store.reads)
        a.cancel()
        b.cancel()
    }

    @Test
    fun `a refresh request reads again while collected and does nothing otherwise`() = runTest {
        val store = FakeStore()
        val source = source(store)
        source.requestRefresh()
        advanceUntilIdle()
        assertEquals("with no collector there is no list to refresh", 0, store.reads)

        val seen = mutableListOf<String>()
        val job = launch { source.shared.collect { seen += it } }
        advanceTimeBy(READ_MS + 1)
        store.content = "v2"
        source.requestRefresh()
        advanceTimeBy(QUIET_MS + READ_MS + 1)

        assertEquals(2, store.reads)
        assertEquals(listOf("v1", "v2"), seen)
        job.cancel()
    }

    @Test
    fun `a recent read is answered from the held read while nothing has changed`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)
        assertEquals(1, store.reads)

        val first = async { source.recent(RECENT_MS) }
        val second = async { source.recent(RECENT_MS) }
        runCurrent()

        assertEquals("v1", first.await())
        assertEquals("v1", second.await())
        assertEquals("no new read for either caller", 1, store.reads)
        job.cancel()
    }

    @Test
    fun `a recent read runs a new read once a change was signalled`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)
        store.content = "v2"
        store.change()

        val answer = async { source.recent(RECENT_MS) }
        advanceTimeBy(QUIET_MS + READ_MS * 3)

        assertEquals("v2", answer.await())
        assertTrue(store.reads >= 2)
        job.cancel()
    }

    @Test
    fun `a recent read runs a new read when the held one is too old or not reusable`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)

        val notReusable = async { source.recent(RECENT_MS) { false } }
        advanceTimeBy(READ_MS * 2)
        assertEquals("v1", notReusable.await())
        assertEquals(2, store.reads)

        advanceTimeBy(RECENT_MS + 1)
        val tooOld = async { source.recent(RECENT_MS) }
        advanceTimeBy(READ_MS * 2)
        assertEquals("v1", tooOld.await())
        assertEquals(3, store.reads)
        job.cancel()
    }

    @Test
    fun `a recent read keeps no copy once no one collects`() = runTest {
        val store = FakeStore()
        val source = source(store)
        val job = launch { source.shared.collect { } }
        advanceTimeBy(READ_MS + 1)
        job.cancel()
        advanceTimeBy(STOP_MS + 1)

        val answer = async { source.recent(RECENT_MS) }
        advanceTimeBy(READ_MS * 2)

        assertEquals("v1", answer.await())
        assertEquals("the held read was dropped with the source, so this one reads", 2, store.reads)
    }

    private companion object {
        const val QUIET_MS = 250L
        const val MAX_WAIT_MS = 2_000L
        const val STOP_MS = 5_000L
        const val READ_MS = 100L
        const val RECENT_MS = 60_000L
    }
}
