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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pins the rule that a native session is closed only once no detached run still uses it: a close with a
 * run in flight waits for that run's release, an abandoned instance closes when its wedged run ends and
 * never earlier, the release fires exactly once however the run ends, and a caller cancelled mid-run no
 * longer frees the sessions its detached run is still inside.
 */
class MlSessionGuardTest {

    /** A stand-in for an ONNX-backed instance: records closes, and whether one landed during a run. */
    private class FakeSession : AutoCloseable {
        val closes = AtomicInteger(0)
        val closedLatch = CountDownLatch(1)
        private val running = AtomicInteger(0)
        val closedWhileRunning = AtomicBoolean(false)

        fun <T> infer(body: () -> T): T {
            running.incrementAndGet()
            try {
                return body()
            } finally {
                running.decrementAndGet()
            }
        }

        override fun close() {
            if (running.get() > 0) closedWhileRunning.set(true)
            closes.incrementAndGet()
            closedLatch.countDown()
        }
    }

    /** Queues every dispatch and runs nothing until asked, so a run can be cancelled before it starts. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val queued = CopyOnWriteArrayList<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
        }
    }

    @Test
    fun `close with nothing in flight closes at once`() {
        val closes = AtomicInteger(0)
        val guard = SessionRunGuard { closes.incrementAndGet() }
        guard.close()
        assertEquals(1, closes.get())
        assertTrue(guard.isClosed())
    }

    @Test
    fun `close while a run is in flight waits for its release`() {
        val closes = AtomicInteger(0)
        val guard = SessionRunGuard { closes.incrementAndGet() }
        val release = guard.acquire()
        guard.close()
        assertEquals(0, closes.get())
        assertFalse(guard.isClosed())
        release()
        assertEquals(1, closes.get())
        assertTrue(guard.isClosed())
    }

    @Test
    fun `close waits for the last of several runs`() {
        val closes = AtomicInteger(0)
        val guard = SessionRunGuard { closes.incrementAndGet() }
        val first = guard.acquire()
        val second = guard.acquire()
        guard.close()
        first()
        assertEquals(0, closes.get())
        assertEquals(1, guard.runsInFlight())
        second()
        assertEquals(1, closes.get())
    }

    @Test
    fun `a release counts once however often it is called and the close runs once`() {
        val closes = AtomicInteger(0)
        val guard = SessionRunGuard { closes.incrementAndGet() }
        val first = guard.acquire()
        val second = guard.acquire()
        guard.close()
        first()
        first()
        assertEquals(1, guard.runsInFlight())
        assertEquals(0, closes.get())
        second()
        second()
        guard.close()
        assertEquals(0, guard.runsInFlight())
        assertEquals(1, closes.get())
    }

    @Test
    fun `retiring an idle holder closes its instance at once and the next lease opens a fresh one`() {
        val opened = mutableListOf<FakeSession>()
        val holder = GuardedSessionHolder { FakeSession().also { opened += it } }
        val lease = holder.lease { it }
        lease.release()
        holder.retire()
        assertEquals(1, opened[0].closes.get())
        assertNull(holder.peek())
        val next = holder.lease { it }
        assertNotSame(opened[0], next.value)
    }

    @Test
    fun `a lease whose prepare throws counts nothing`() {
        val opened = mutableListOf<FakeSession>()
        val holder = GuardedSessionHolder { FakeSession().also { opened += it } }
        try {
            holder.lease<Unit> { throw IllegalStateException("model failed to open") }
            fail("prepare should have thrown")
        } catch (expected: IllegalStateException) {
        }
        holder.retire()
        assertEquals(1, opened[0].closes.get())
    }

    @Test
    fun `an abandoned instance closes when its run ends and never earlier`() {
        val opened = mutableListOf<FakeSession>()
        val holder = GuardedSessionHolder { FakeSession().also { opened += it } }
        val wedged = holder.lease { it }
        // The watchdog gives up: abandon, then the walk ends and closes whatever is live.
        holder.retire()
        holder.retire()
        assertEquals(0, opened[0].closes.get())
        wedged.release()
        assertEquals(1, opened[0].closes.get())
        wedged.release()
        assertEquals(1, opened[0].closes.get())
    }

    @Test
    fun `an instance opened after an abandon is unaffected by the old run`() {
        val opened = mutableListOf<FakeSession>()
        val holder = GuardedSessionHolder { FakeSession().also { opened += it } }
        val wedged = holder.lease { it }
        holder.retire()
        val fresh = holder.lease { it }
        assertNotSame(wedged.value, fresh.value)
        assertSame(fresh.value, holder.peek())
        fresh.release()
        // The fresh instance closes at once although the wedged run on the old one is still going.
        holder.retire()
        assertEquals(1, opened[1].closes.get())
        assertEquals(0, opened[0].closes.get())
        wedged.release()
        assertEquals(1, opened[0].closes.get())
    }

    @Test
    fun `a run cancelled before it starts still releases exactly once`() = runTest {
        val held = HeldDispatcher()
        val detached = CoroutineScope(SupervisorJob())
        val releases = AtomicInteger(0)
        val bodyRan = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val result = boundedDetachedRun(detached, held, timeoutMs = 1_000, timedOut = timedOut, onRunEnd = { releases.incrementAndGet() }) {
            bodyRan.set(true)
            1
        }
        assertNull(result)
        assertTrue(timedOut.get())
        // Cancelled but not yet over: the release waits for the run itself to finish.
        assertEquals(0, releases.get())
        held.runAll()
        assertFalse(bodyRan.get())
        assertEquals(1, releases.get())
        detached.cancel()
    }

    @Test
    fun `a caller already cancelled launches nothing and releases once`() = runTest {
        val detached = CoroutineScope(SupervisorJob())
        val releases = AtomicInteger(0)
        val bodyRan = AtomicBoolean(false)
        val threw = AtomicBoolean(false)
        val caller = launch {
            currentCoroutineContext().job.cancel()
            try {
                boundedDetachedRun(detached, EmptyCoroutineContext, timeoutMs = 1_000, onRunEnd = { releases.incrementAndGet() }) {
                    bodyRan.set(true)
                }
            } catch (e: CancellationException) {
                threw.set(true)
                throw e
            }
        }
        caller.join()
        assertTrue(threw.get())
        assertFalse(bodyRan.get())
        assertEquals(1, releases.get())
        detached.cancel()
    }

    @Test
    fun `a cancelled caller does not close the pair its detached run still uses`() = runBlocking {
        val detached = CoroutineScope(SupervisorJob())
        val runPool = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val opened = CopyOnWriteArrayList<FakeSession>()
        val holder = GuardedSessionHolder { FakeSession().also { opened += it } }
        val runStarted = CountDownLatch(1)
        val finishRun = CountDownLatch(1)
        try {
            val caller = launch(Dispatchers.Default) {
                try {
                    val lease = holder.lease { it }
                    boundedDetachedRun(detached, runPool, timeoutMs = 60_000, onRunEnd = lease.release) {
                        // A blocking call standing in for an uncancellable native inference.
                        lease.value.infer {
                            runStarted.countDown()
                            finishRun.await(10, TimeUnit.SECONDS)
                        }
                    }
                } finally {
                    // The sweep's own finally: close the pair on the way out.
                    holder.retire()
                }
            }
            assertTrue(runStarted.await(5, TimeUnit.SECONDS))
            // Leaving the screen cancels the caller; it returns while the run is still inside the pair.
            caller.cancelAndJoin()
            val session = opened.single()
            assertEquals(0, session.closes.get())
            finishRun.countDown()
            assertTrue(session.closedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, session.closes.get())
            assertFalse(session.closedWhileRunning.get())
        } finally {
            finishRun.countDown()
            detached.cancel()
            runPool.close()
        }
    }

    @Test
    fun `a timed-out run's abandoned pair closes when that run returns while the fresh pair runs on`() = runBlocking {
        val detached = CoroutineScope(SupervisorJob())
        val runPool = Executors.newCachedThreadPool().asCoroutineDispatcher()
        val opened = CopyOnWriteArrayList<FakeSession>()
        val holder = GuardedSessionHolder { FakeSession().also { opened += it } }
        val finishWedged = CountDownLatch(1)
        try {
            val wedged = holder.lease { it }
            val timedOut = AtomicBoolean(false)
            val first = boundedDetachedRun(detached, runPool, timeoutMs = 100, timedOut = timedOut, onRunEnd = wedged.release) {
                wedged.value.infer { finishWedged.await(10, TimeUnit.SECONDS) }
            }
            assertNull(first)
            assertTrue(timedOut.get())
            holder.retire()

            val fresh = holder.lease { it }
            val second = boundedDetachedRun(detached, runPool, timeoutMs = 5_000, onRunEnd = fresh.release) {
                fresh.value.infer { 42 }
            }
            assertEquals(42, second)
            holder.retire()
            assertTrue(opened[1].closedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(0, opened[0].closes.get())

            finishWedged.countDown()
            assertTrue(opened[0].closedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, opened[0].closes.get())
            assertFalse(opened[0].closedWhileRunning.get())
            assertFalse(opened[1].closedWhileRunning.get())
        } finally {
            finishWedged.countDown()
            detached.cancel()
            runPool.close()
        }
    }
}
