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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules [FreshQueryGate] exists to hold for a whole-library read: callers that ask together
 * share one read, a caller never gets a read that began before it asked, and two reads never run
 * at once.
 *
 * The read here is a sequence number the test releases by hand, so each answer names the read it
 * came from. Timing is virtual, so "while a read runs" is decided by the scheduler, not a sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshQueryGateTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /** Stands in for the app scope: the test's virtual clock, its own SupervisorJob, and a lifetime
     *  independent of the callers. */
    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    /** A read that ends only when the test says so. Returns its own sequence number and records the
     *  most reads that were ever running at once. */
    private class HeldRead {
        private val releases = Channel<Result<Unit>>(Channel.UNLIMITED)
        private var running = 0
        var started = 0
            private set
        var mostAtOnce = 0
            private set

        suspend fun read(): Int {
            val n = ++started
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            try {
                releases.receive().getOrThrow()
            } finally {
                running--
            }
            return n
        }

        fun finish() {
            releases.trySend(Result.success(Unit))
        }

        fun fail(message: String) {
            releases.trySend(Result.failure(IllegalStateException(message)))
        }
    }

    @Test
    fun `callers that ask together share one read`() = runTest {
        val held = HeldRead()
        val gate = FreshQueryGate(appScope()) { held.read() }

        val callers = List(5) { async { gate.fresh() } }
        runCurrent()
        assertEquals("callers that all asked before the read began must share it", 1, held.started)

        held.finish()
        assertEquals(List(5) { 1 }, callers.awaitAll())
        assertEquals(1, held.started)
    }

    @Test
    fun `a caller arriving during a read gets the next read, not that one`() = runTest {
        // The read under way may have passed the change this caller reacts to, so answering with it
        // would hand back exactly the stale list the call exists to avoid.
        val held = HeldRead()
        val gate = FreshQueryGate(appScope()) { held.read() }

        val first = async { gate.fresh() }
        runCurrent()
        assertEquals(1, held.started)

        val second = async { gate.fresh() }
        runCurrent()
        assertEquals("no second read may start while the first runs", 1, held.started)

        held.finish()
        assertEquals(1, first.await())
        runCurrent()
        assertEquals("the late caller's read starts once the first has finished", 2, held.started)

        held.finish()
        assertEquals("the late caller must get the newer read", 2, second.await())
        assertEquals("reads must never overlap", 1, held.mostAtOnce)
    }

    @Test
    fun `callers arriving during one read all share the next`() = runTest {
        val held = HeldRead()
        val gate = FreshQueryGate(appScope()) { held.read() }

        val first = async { gate.fresh() }
        runCurrent()
        val late = List(4) { async { gate.fresh() } }
        runCurrent()

        held.finish()
        assertEquals(1, first.await())
        runCurrent()
        held.finish()
        assertEquals(List(4) { 2 }, late.awaitAll())
        assertEquals("one read for the first caller, one shared by every late caller", 2, held.started)
        assertEquals(1, held.mostAtOnce)
    }

    @Test
    fun `a failed read reaches every caller that shared it and the next call reads again`() = runTest {
        val held = HeldRead()
        val gate = FreshQueryGate(appScope()) { held.read() }

        val callers = List(3) { async { runCatching { gate.fresh() } } }
        runCurrent()
        held.fail("store unavailable")
        val outcomes = callers.awaitAll()
        assertTrue("every caller that shared the read must see its failure", outcomes.all { it.isFailure })
        assertEquals("store unavailable", outcomes.first().exceptionOrNull()?.message)

        val retry = async { gate.fresh() }
        runCurrent()
        assertEquals("a call after the failure must start a new read", 2, held.started)
        held.finish()
        assertEquals(2, retry.await())
    }

    @Test
    fun `a caller that is cancelled does not cancel the read the others share`() = runTest {
        val held = HeldRead()
        val gate = FreshQueryGate(appScope()) { held.read() }

        val leaving = async { gate.fresh() }
        val staying = async { gate.fresh() }
        runCurrent()
        leaving.cancel()
        runCurrent()

        held.finish()
        assertEquals(1, staying.await())
        assertEquals(1, held.started)
    }

    @Test
    fun `callers are released when the scope running the reads is cancelled`() = runTest {
        // A caller left waiting on a read that can never run would hang its worker for good.
        val scope = appScope()
        val held = HeldRead()
        val gate = FreshQueryGate(scope) { held.read() }

        val inFlight = async { runCatching { gate.fresh() } }
        runCurrent()
        val queued = async { runCatching { gate.fresh() } }
        runCurrent()

        scope.cancel()
        assertTrue(inFlight.await().isFailure)
        assertTrue("a caller waiting for the next read must not hang", queued.await().isFailure)
    }

    @Test
    fun `results carries every successful read in order`() = runTest {
        val held = HeldRead()
        val gate = FreshQueryGate(appScope()) { held.read() }
        val seen = mutableListOf<Int>()
        val watcher = launch { gate.results.collect { seen += it } }
        runCurrent()

        val a = async { gate.fresh() }
        runCurrent()
        held.finish()
        a.await()
        val b = async { runCatching { gate.fresh() } }
        runCurrent()
        held.fail("gone")
        b.await()
        val c = async { gate.fresh() }
        runCurrent()
        held.finish()
        c.await()
        runCurrent()

        assertEquals("a failed read publishes nothing", listOf(1, 3), seen)
        watcher.cancel()
    }

    @Test
    fun `a read that runs out of memory is not passed off as an ordinary failure`() = runTest {
        // Wrapped as a failed read, the error would only reach callers that retry past failures; left
        // to end the read as it is, it also reaches the scope's handler like any other uncaught error.
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e },
        ).also { scopes += it }
        val gate = FreshQueryGate<Int>(scope) { throw OutOfMemoryError("heap") }

        val caller = async { runCatching { gate.fresh() } }

        assertTrue("the caller still hears of it", caller.await().exceptionOrNull() is OutOfMemoryError)
        assertTrue(uncaught.single() is OutOfMemoryError)
    }
}
