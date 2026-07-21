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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lifetime rules [DetachedWalk] exists to hold, which the full cloud refresh got wrong while
 * it ran in its caller's job.
 *
 * A full walk of a large library runs for tens of minutes and every scope that starts one — the
 * Settings screen, the Photos tab, the Activity — dies long before that, so the walk has to
 * belong to the app scope while the caller merely waits alongside it. Two things then have to
 * stay true that structured concurrency was giving for free: the walk must still be stoppable
 * (sign-out), and concurrent callers must not each add a walk to a backlog nothing cancels.
 *
 * The walk body here is a signal rather than real work, which is the point — these tests pin the
 * lifetime rules, not the listing. Timing is virtual, so "still running" and "stopped" are
 * decided by the scheduler rather than by a sleep that could pass on a slow machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetachedWalkTest {

    private val userA = UserId("user-a")
    private val userB = UserId("user-b")

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /**
     * Stands in for the injected `@AppScope`: same virtual clock as the test, its own SupervisorJob,
     * and a lifetime independent of the callers.
     *
     * The SupervisorJob is not decoration — the real scope has one, and without it a walk that
     * throws would cancel the scope itself instead of handing the failure to whoever awaited.
     *
     * Built from the scheduler rather than from `backgroundScope`, whose context marks everything
     * launched in it as background work that [advanceUntilIdle] is documented not to wait for. A
     * walk parked there looks indistinguishable from a walk that was cancelled, which is precisely
     * the distinction these tests exist to make.
     */
    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @Test
    fun `cancelling the caller does not cancel the walk`() = runTest {
        // The whole reason for the class. Leaving the screen used to abandon the listing partway,
        // so it never reached the end — and the stale-entry sweep only runs on a pass that does.
        val walk = DetachedWalk(appScope())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var walkFinished = false

        val caller = launch {
            walk.run(userA) { _ ->
                started.complete(Unit)
                release.await()
                walkFinished = true
            }
        }
        started.await()

        caller.cancelAndJoin()
        advanceUntilIdle()
        assertFalse("the walk must not finish just because its caller went away", walkFinished)

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue("the walk must run to its end with no caller left", walkFinished)
    }

    @Test
    fun `two callers arriving at once produce one walk`() = runTest {
        // Single-flight was load-bearing before this change (it bounds the gopenpgp decrypt burst)
        // and is more so after it: an unshared walk per caller would pile whole library walks onto
        // a scope no screen teardown can drain.
        val walk = DetachedWalk(appScope())
        val walksStarted = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            walk.run(userA) { _ ->
                walksStarted.incrementAndGet()
                release.await()
            }
        }
        advanceUntilIdle()

        val second = launch {
            walk.run(userA) { _ ->
                walksStarted.incrementAndGet()
                release.await()
            }
        }
        advanceUntilIdle()
        assertEquals("the second caller must join the walk, not start one", 1, walksStarted.get())

        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, walksStarted.get())
    }

    @Test
    fun `both callers get the result of the shared walk`() = runTest {
        // Joining is only acceptable while it still answers the caller: the free-up sweep refuses
        // to delete a device copy when its refresh failed, so a joined failure has to arrive too.
        val walk = DetachedWalk(appScope())
        val release = CompletableDeferred<Unit>()
        val failures = AtomicInteger()

        val callers = List(2) {
            launch {
                runCatching {
                    walk.run(userA) { _ ->
                        release.await()
                        error("listing refused")
                    }
                }.onFailure { failures.incrementAndGet() }
            }
        }
        advanceUntilIdle()
        release.complete(Unit)
        callers.forEach { it.join() }

        assertEquals("a shared walk's failure must reach every caller", 2, failures.get())
    }

    @Test
    fun `a signed-out account's walk does not continue`() = runTest {
        // Sign-out wipes this account's rows and key material right after. A walk still paginating
        // would write the signed-out user's photos back into the table that was just emptied.
        val walk = DetachedWalk(appScope())
        val started = CompletableDeferred<Unit>()
        var reachedEnd = false

        launch {
            runCatching {
                walk.run(userA) { _ ->
                    started.complete(Unit)
                    delay(10 * 60_000L)
                    reachedEnd = true
                }
            }
        }
        started.await()

        walk.cancelFor(userA)
        advanceUntilIdle()
        assertFalse("the walk must stop when its account signs out", reachedEnd)
    }

    @Test
    fun `cancelling for one account leaves another account's walk alone`() = runTest {
        // Sign-out is per-user; a second signed-in account keeps its walk.
        val walk = DetachedWalk(appScope())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var walkFinished = false

        launch {
            walk.run(userA) { _ ->
                started.complete(Unit)
                release.await()
                walkFinished = true
            }
        }
        started.await()

        walk.cancelFor(userB)
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue("only the named account's walk may be stopped", walkFinished)
    }

    @Test
    fun `a walk for a new account supersedes the outgoing one`() = runTest {
        // The app holds one primary user at a time, so a second id means the account was switched
        // and the outgoing walk has nothing left to write for.
        val walk = DetachedWalk(appScope())
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstReachedEnd = false
        var secondStarted = false

        launch {
            runCatching {
                walk.run(userA) { _ ->
                    firstStarted.complete(Unit)
                    delay(10 * 60_000L)
                    firstReachedEnd = true
                }
            }
        }
        firstStarted.await()

        val switched = launch {
            walk.run(userB) { _ ->
                secondStarted = true
                release.await()
            }
        }
        advanceUntilIdle()
        assertFalse("the outgoing account's walk must not run on", firstReachedEnd)
        assertTrue("the new account's walk must start", secondStarted)

        release.complete(Unit)
        switched.join()
    }

    @Test
    fun `a finished walk is not reused by the next caller`() = runTest {
        // Single-flight shares an ACTIVE walk only: once one ends, the next refresh is a real one.
        val walk = DetachedWalk(appScope())
        val walksStarted = AtomicInteger()

        walk.run(userA) { _ -> walksStarted.incrementAndGet() }
        walk.run(userA) { _ -> walksStarted.incrementAndGet() }

        assertEquals(2, walksStarted.get())
    }

    @Test
    fun `an idle forced caller runs one pass and it is forced`() = runTest {
        // A forced press must actually list. With no walk to join it simply starts one, and the
        // pass it starts is marked forced.
        val walk = DetachedWalk(appScope())
        val passes = mutableListOf<Boolean>()

        walk.run(userA, forced = true) { passForced -> passes.add(passForced) }

        assertEquals("a forced press with nothing in flight runs exactly one forced pass", listOf(true), passes)
    }

    @Test
    fun `a forced caller mid-walk adds exactly one more pass and it is forced`() = runTest {
        // A forced press landing during a walk must not ride the pass in flight, which began before
        // the press. It queues one more pass that starts once this one ends, run forced.
        val walk = DetachedWalk(appScope())
        val passes = mutableListOf<Boolean>()
        val release = CompletableDeferred<Unit>()

        val gentle = launch {
            walk.run(userA) { passForced ->
                passes.add(passForced)
                release.await()
            }
        }
        advanceUntilIdle()

        val forced = launch { walk.run(userA, forced = true) { _ -> } }
        advanceUntilIdle()
        assertEquals("the forced caller must not start a concurrent pass", listOf(false), passes)

        release.complete(Unit)
        gentle.join()
        forced.join()
        assertEquals("the forced press must add exactly one more pass, itself forced", listOf(false, true), passes)
    }

    @Test
    fun `several forced callers during one walk collapse to a single extra pass`() = runTest {
        // The rerun latch holds one bit, not a queue. A burst of forced presses on a busy walk earns
        // exactly one extra pass between them, never one apiece.
        val walk = DetachedWalk(appScope())
        val passes = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val gentle = launch {
            walk.run(userA) { _ ->
                passes.incrementAndGet()
                release.await()
            }
        }
        advanceUntilIdle()

        val forcedCallers = List(3) {
            launch { walk.run(userA, forced = true) { _ -> } }
        }
        advanceUntilIdle()

        release.complete(Unit)
        gentle.join()
        forcedCallers.forEach { it.join() }
        assertEquals("three forced presses on a busy walk add one pass, not three", 2, passes.get())
    }

    @Test
    fun `a gentle caller mid-walk adds no extra pass`() = runTest {
        // Only a forced press earns a rerun. A gentle second caller just waits on the pass in flight
        // and adds nothing.
        val walk = DetachedWalk(appScope())
        val passes = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            walk.run(userA) { _ ->
                passes.incrementAndGet()
                release.await()
            }
        }
        advanceUntilIdle()

        val second = launch { walk.run(userA) { _ -> passes.incrementAndGet() } }
        advanceUntilIdle()

        release.complete(Unit)
        first.join()
        second.join()
        assertEquals("a gentle second caller must not add a pass", 1, passes.get())
    }

    @Test
    fun `cancelling for the account drops a queued forced rerun`() = runTest {
        // Sign-out clears the latch. A forced follow-up must not fire once the account's rows and
        // keys are gone.
        val walk = DetachedWalk(appScope())
        val passes = mutableListOf<Boolean>()
        val release = CompletableDeferred<Unit>()

        val gentle = launch {
            runCatching {
                walk.run(userA) { passForced ->
                    passes.add(passForced)
                    release.await()
                }
            }
        }
        advanceUntilIdle()

        val forced = launch { runCatching { walk.run(userA, forced = true) { _ -> } } }
        advanceUntilIdle()

        walk.cancelFor(userA)
        release.complete(Unit)
        advanceUntilIdle()
        gentle.join()
        forced.join()

        assertEquals("a forced rerun queued at sign-out must never run", listOf(false), passes)
    }
}
