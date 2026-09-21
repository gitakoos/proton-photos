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

package eu.akoos.photos.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate's job in one word is ORDER: it admits at most `permits` decrypts at once and, when a
 * permit frees, lets an interactive (foreground) waiter overtake background indexing without ever
 * raising the in-flight cap. These tests pin the cap, the foreground-first admission, FIFO within a
 * priority, and that a cancelled waiter frees its slot rather than leaking a permit.
 *
 * Timing is virtual: [runTest]'s default dispatcher runs launched coroutines only when the scheduler
 * is pumped, and in dispatch order, so "who holds" and "who is admitted next" are decided
 * deterministically by [advanceUntilIdle] rather than by a sleep that could pass on a slow machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriorityGateTest {

    @Test
    fun `never more than permits holders at once`() = runTest {
        val gate = PriorityGate(permits = 3)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val callers = List(6) {
            launch {
                gate.withPermit(DecryptPriority.FOREGROUND) {
                    val now = active.incrementAndGet()
                    maxActive.getAndUpdate { m -> maxOf(m, now) }
                    release.await()
                    active.decrementAndGet()
                }
            }
        }
        advanceUntilIdle()

        // Three hold the permits, the other three are parked in acquire.
        assertEquals("only `permits` callers may hold at once", 3, active.get())

        release.complete(Unit)
        advanceUntilIdle()
        callers.forEach { it.join() }

        assertEquals("every holder released", 0, active.get())
        assertEquals("the cap was never exceeded across the whole drain", 3, maxActive.get())
    }

    @Test
    fun `a foreground waiter is admitted ahead of queued background waiters`() = runTest {
        val gate = PriorityGate(permits = 1)
        val holderHasPermit = CompletableDeferred<Unit>()
        val holderRelease = CompletableDeferred<Unit>()
        val admitted = mutableListOf<String>()

        val holder = launch {
            gate.withPermit(DecryptPriority.BACKGROUND) {
                holderHasPermit.complete(Unit)
                holderRelease.await()
            }
        }
        holderHasPermit.await()

        // Queue two background waiters, THEN one foreground waiter, in that dispatch order.
        val bg1 = launch { gate.withPermit(DecryptPriority.BACKGROUND) { admitted.add("bg1") } }
        advanceUntilIdle()
        val bg2 = launch { gate.withPermit(DecryptPriority.BACKGROUND) { admitted.add("bg2") } }
        advanceUntilIdle()
        val fg = launch { gate.withPermit(DecryptPriority.FOREGROUND) { admitted.add("fg") } }
        advanceUntilIdle()

        // The single permit frees; the foreground waiter overtakes both background ones.
        holderRelease.complete(Unit)
        advanceUntilIdle()
        holder.join(); bg1.join(); bg2.join(); fg.join()

        assertEquals(listOf("fg", "bg1", "bg2"), admitted)
    }

    @Test
    fun `waiters of the same priority are admitted first in first out`() = runTest {
        val gate = PriorityGate(permits = 1)
        val holderHasPermit = CompletableDeferred<Unit>()
        val holderRelease = CompletableDeferred<Unit>()
        val admitted = mutableListOf<String>()

        val holder = launch {
            gate.withPermit(DecryptPriority.BACKGROUND) {
                holderHasPermit.complete(Unit)
                holderRelease.await()
            }
        }
        holderHasPermit.await()

        val waiters = mutableListOf<Job>()
        listOf("a", "b", "c").forEach { name ->
            waiters += launch { gate.withPermit(DecryptPriority.BACKGROUND) { admitted.add(name) } }
            advanceUntilIdle()
        }

        holderRelease.complete(Unit)
        advanceUntilIdle()
        holder.join(); waiters.forEach { it.join() }

        assertEquals("same-priority waiters keep arrival order", listOf("a", "b", "c"), admitted)
    }

    @Test
    fun `a cancelled waiter frees its slot and never leaks a permit`() = runTest {
        val gate = PriorityGate(permits = 1)
        val holderHasPermit = CompletableDeferred<Unit>()
        val holderRelease = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val holder = launch {
            gate.withPermit(DecryptPriority.BACKGROUND) {
                holderHasPermit.complete(Unit)
                holderRelease.await()
                order.add("holder")
            }
        }
        holderHasPermit.await()

        // Two foreground waiters queue behind the holder; the first is cancelled while still queued.
        val doomed = launch { gate.withPermit(DecryptPriority.FOREGROUND) { order.add("doomed") } }
        advanceUntilIdle()
        val survivor = launch { gate.withPermit(DecryptPriority.FOREGROUND) { order.add("survivor") } }
        advanceUntilIdle()

        doomed.cancelAndJoin()
        advanceUntilIdle()

        // The freed permit skips the cancelled waiter and reaches the one still queued.
        holderRelease.complete(Unit)
        advanceUntilIdle()
        holder.join(); survivor.join()
        assertEquals(listOf("holder", "survivor"), order)

        // No permit leaked: a fresh caller still gets in (would block forever if the cancel had lost one).
        var laterRan = false
        val later = launch { gate.withPermit(DecryptPriority.BACKGROUND) { laterRan = true } }
        advanceUntilIdle()
        later.join()
        assertTrue("a cancelled waiter must not leak its permit", laterRan)
    }
}
