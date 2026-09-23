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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules [SharedRunGate] holds for reconcile: every caller is answered by a run that began after
 * it asked, a burst of callers costs the run under way plus one more, runs never overlap, a failure
 * reaches exactly the callers of the run that failed, and a run lives exactly as long as some caller
 * still waits for it.
 *
 * Each run is a sequence number the test releases by hand, so an answer names the run it came from.
 * Timing is virtual, so "while a run is under way" is decided by the scheduler, not a sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRunGateTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /** Stands in for the app scope: the test's virtual clock, its own SupervisorJob, and a lifetime
     *  independent of the callers. */
    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    /** Runs that end only when the test says so. Each returns its own sequence number; the counters
     *  record how many began, how many were cancelled, and the most ever under way at once. */
    private class HeldRuns {
        private val releases = Channel<Result<Unit>>(Channel.UNLIMITED)
        private var running = 0
        var started = 0
            private set
        var cancelled = 0
            private set
        var mostAtOnce = 0
            private set

        suspend fun run(): Int {
            val n = ++started
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            try {
                releases.receive().getOrThrow()
            } catch (e: CancellationException) {
                cancelled++
                throw e
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

    private fun TestScope.gateFor(held: HeldRuns, turns: Mutex = Mutex()) =
        SharedRunGate(appScope(), turns) { held.run() }

    @Test
    fun `callers that ask before the run begins share it`() = runTest {
        val held = HeldRuns()
        val gate = gateFor(held)

        val callers = List(5) { async { gate.run() } }
        runCurrent()
        assertEquals("callers that all asked before the run began must share it", 1, held.started)

        held.finish()
        assertEquals(List(5) { 1 }, callers.awaitAll())
        assertEquals(1, held.started)
    }

    @Test
    fun `a caller arriving during a run gets the next run, not that one`() = runTest {
        // The run under way may have read the device or the table before the change this caller
        // reacts to, so answering it with that run would leave the change unreconciled.
        val held = HeldRuns()
        val gate = gateFor(held)

        val first = async { gate.run() }
        runCurrent()
        assertEquals(1, held.started)

        val second = async { gate.run() }
        runCurrent()
        assertEquals("no second run may begin while the first is under way", 1, held.started)

        held.finish()
        assertEquals(1, first.await())
        runCurrent()
        assertFalse("the late caller must not be answered by the run already under way", second.isCompleted)
        assertEquals("the late caller's run begins once the first has ended", 2, held.started)

        held.finish()
        assertEquals("the late caller must get the newer run", 2, second.await())
        assertEquals("runs must never overlap", 1, held.mostAtOnce)
    }

    @Test
    fun `callers arriving during one run all share the next`() = runTest {
        val held = HeldRuns()
        val gate = gateFor(held)

        val first = async { gate.run() }
        runCurrent()
        val late = List(4) { async { gate.run() } }
        runCurrent()

        held.finish()
        assertEquals(1, first.await())
        runCurrent()
        held.finish()
        assertEquals(List(4) { 2 }, late.awaitAll())
        assertEquals("one run for the first caller, one shared by every late caller", 2, held.started)
        assertEquals(1, held.mostAtOnce)
    }

    @Test
    fun `a failed run throws to exactly its callers and the next run answers the rest`() = runTest {
        val held = HeldRuns()
        val gate = gateFor(held)

        val early = List(2) { async { runCatching { gate.run() } } }
        runCurrent()
        val late = List(2) { async { runCatching { gate.run() } } }
        runCurrent()

        held.fail("store unavailable")
        val earlyOutcomes = early.awaitAll()
        assertTrue(
            "every caller of the failed run must see its failure",
            earlyOutcomes.all { it.exceptionOrNull()?.message == "store unavailable" },
        )

        runCurrent()
        held.finish()
        assertEquals("callers of the next run get its answer, not the failure", List(2) { 2 }, late.awaitAll().map { it.getOrThrow() })
    }

    @Test
    fun `a caller that stops waiting leaves the run to the callers still waiting`() = runTest {
        val held = HeldRuns()
        val gate = gateFor(held)

        val leaving = async { gate.run() }
        val staying = async { gate.run() }
        runCurrent()
        leaving.cancel()
        runCurrent()
        assertEquals("the shared run must not be cancelled while a caller waits", 0, held.cancelled)

        held.finish()
        assertEquals(1, staying.await())
        assertEquals(1, held.started)
    }

    @Test
    fun `a run no caller waits for any more is cancelled`() = runTest {
        // A run lives as long as someone still wants it, the way it lived with its one caller.
        val held = HeldRuns()
        val gate = gateFor(held)

        val only = async { gate.run() }
        runCurrent()
        assertEquals(1, held.started)
        only.cancel()
        runCurrent()
        assertEquals(1, held.cancelled)

        val after = async { gate.run() }
        runCurrent()
        assertEquals("the next call starts a new run", 2, held.started)
        held.finish()
        assertEquals(2, after.await())
    }

    @Test
    fun `a next run every caller has left never begins`() = runTest {
        val held = HeldRuns()
        val gate = gateFor(held)

        val first = async { gate.run() }
        runCurrent()
        val late = async { gate.run() }
        runCurrent()
        late.cancel()
        runCurrent()

        held.finish()
        assertEquals(1, first.await())
        runCurrent()
        assertEquals("a next run nobody waits for must not begin", 1, held.started)
    }

    @Test
    fun `a call after a finished run starts a new one`() = runTest {
        val held = HeldRuns()
        val gate = gateFor(held)

        val one = async { gate.run() }
        runCurrent()
        held.finish()
        assertEquals(1, one.await())

        val two = async { gate.run() }
        runCurrent()
        held.finish()
        assertEquals(2, two.await())
        assertEquals(2, held.started)
    }

    @Test
    fun `gates sharing a turn never run at once, and a run waiting for its turn can still be joined`() = runTest {
        val held = HeldRuns()
        val turns = Mutex()
        val scope = appScope()
        val gateA = SharedRunGate(scope, turns) { held.run() }
        val gateB = SharedRunGate(scope, turns) { held.run() }

        val a = async { gateA.run() }
        runCurrent()
        val b1 = async { gateB.run() }
        runCurrent()
        assertEquals("the other gate's run must wait for the turn", 1, held.started)
        val b2 = async { gateB.run() }
        runCurrent()

        held.finish()
        assertEquals(1, a.await())
        runCurrent()
        held.finish()
        assertEquals(
            "a run still waiting for its turn has read nothing, so a later caller shares it",
            listOf(2, 2),
            listOf(b1.await(), b2.await()),
        )
        assertEquals(2, held.started)
        assertEquals(1, held.mostAtOnce)
    }

    @Test
    fun `a caller arriving while an abandoned run winds down gets a new run, not its cancellation`() = runTest {
        val held = HeldRuns()
        val turns = Mutex()
        turns.lock()
        val gate = gateFor(held, turns)

        // The only caller's run waits for the turn, then that caller leaves. The next caller is queued
        // right behind it, so it asks after the run was abandoned but before its cancellation lands.
        val only = async { gate.run() }
        runCurrent()
        only.cancel()
        val next = async { runCatching { gate.run() } }
        runCurrent()
        assertEquals("the abandoned run must never begin", 0, held.started)

        turns.unlock()
        runCurrent()
        held.finish()
        assertEquals(1, next.await().getOrThrow())
    }

    @Test
    fun `callers are released when the scope running the runs is cancelled`() = runTest {
        // A caller left waiting on a run that can never happen would hang its worker for good.
        val held = HeldRuns()
        val scope = appScope()
        val gate = SharedRunGate(scope, Mutex()) { held.run() }

        val inFlight = async { runCatching { gate.run() } }
        runCurrent()
        val queued = async { runCatching { gate.run() } }
        runCurrent()

        scope.cancel()
        assertTrue(inFlight.await().isFailure)
        assertTrue("a caller waiting for the next run must not hang", queued.await().isFailure)
    }
}
