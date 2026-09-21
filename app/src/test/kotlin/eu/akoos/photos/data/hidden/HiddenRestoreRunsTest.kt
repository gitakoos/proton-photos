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

package eu.akoos.photos.data.hidden

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two things standing between a reveal and a photo that exists twice or not at all: that the
 * screen which asked cannot abandon a reveal whose bytes are already moving, and that two asks for the
 * same photo move it once.
 *
 * The register runs on a scope handed to it, so the whole of it answers without a device.
 */
class HiddenRestoreRunsTest {

    private val vaultUri = "file:///vault/8f21c3__1700000000000.jpg"

    /** A register whose scope is as separate from the caller as the app scope is from a screen, and
     *  whose failures do not take the scope down — the shape the app provides. */
    private fun runsTest(body: suspend TestScope.(HiddenRestoreRuns) -> Unit) = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            body(HiddenRestoreRuns(owner))
        } finally {
            owner.cancel()
        }
    }

    // ── the caller may stop waiting, never the work ──────────────────────────────────────────────

    @Test
    fun `a caller that goes away mid-reveal does not take the reveal with it`() = runsTest { runs ->
        val landed = CompletableDeferred<Unit>()
        var recordsCleared = false
        val caller = async {
            runs.forPhoto(vaultUri) {
                landed.await()
                recordsCleared = true
                HiddenRestoreOutcome.RESTORED
            }
        }
        advanceUntilIdle()

        caller.cancel()
        advanceUntilIdle()
        assertFalse("the caller leaving must not end the run", recordsCleared)

        landed.complete(Unit)
        advanceUntilIdle()
        assertTrue("the bytes had moved, so the records naming them have to go", recordsCleared)
    }

    @Test
    fun `a caller that goes away does not take a detached run with it`() = runsTest { runs ->
        val landed = CompletableDeferred<Unit>()
        var finished = false
        val caller = async { runs.detached { landed.await(); finished = true } }
        advanceUntilIdle()

        caller.cancel()
        advanceUntilIdle()
        assertFalse(finished)

        landed.complete(Unit)
        advanceUntilIdle()
        assertTrue(finished)
    }

    // ── one photo, one run ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the same photo asked for twice at once comes back once`() = runsTest { runs ->
        val landed = CompletableDeferred<Unit>()
        var moves = 0
        val reveal: suspend () -> HiddenRestoreOutcome = {
            moves++
            landed.await()
            HiddenRestoreOutcome.RESTORED
        }
        val first = async { runs.forPhoto(vaultUri, reveal) }
        val second = async { runs.forPhoto(vaultUri, reveal) }
        advanceUntilIdle()
        assertEquals("the second ask joins the run already going", 1, moves)

        landed.complete(Unit)
        assertEquals(HiddenRestoreOutcome.RESTORED, first.await())
        assertEquals("both callers hear the one answer", HiddenRestoreOutcome.RESTORED, second.await())
        assertEquals(1, moves)
    }

    @Test
    fun `two photos asked for at once each get their own run`() = runsTest { runs ->
        val moved = mutableListOf<String>()
        val first = async { runs.forPhoto("$vaultUri.a") { moved += "a"; HiddenRestoreOutcome.RESTORED } }
        val second = async { runs.forPhoto("$vaultUri.b") { moved += "b"; HiddenRestoreOutcome.RESTORED } }
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), moved.sorted())
        assertEquals(HiddenRestoreOutcome.RESTORED, first.await())
        assertEquals(HiddenRestoreOutcome.RESTORED, second.await())
    }

    // ── a run that ended is not a run in flight ─────────────────────────────────────────────────

    @Test
    fun `a photo that did not come back can be asked for again`() = runsTest { runs ->
        var attempts = 0
        val first = runs.forPhoto(vaultUri) { attempts++; HiddenRestoreOutcome.FAILED }
        val second = runs.forPhoto(vaultUri) { attempts++; HiddenRestoreOutcome.RESTORED }

        assertEquals(HiddenRestoreOutcome.FAILED, first)
        assertEquals("a failed reveal must not wedge the photo shut", HiddenRestoreOutcome.RESTORED, second)
        assertEquals(2, attempts)
    }

    @Test
    fun `a reveal that throws reaches its caller and still leaves the register`() = runsTest { runs ->
        val outcome = runCatching { runs.forPhoto(vaultUri) { error("no room on the volume") } }
        assertTrue("the screen has to be able to say a reveal failed", outcome.isFailure)

        var retried = false
        val after = runs.forPhoto(vaultUri) { retried = true; HiddenRestoreOutcome.RESTORED }
        assertTrue(retried)
        assertEquals(HiddenRestoreOutcome.RESTORED, after)
    }

    @Test
    fun `a run cancelled where it stands leaves the register too`() = runTest {
        // Leaving the register is the one step the end of a run may not skip, and a cancelled run is
        // the case that could: it drops out at whatever suspension point it had reached, and taking
        // the register's lock is itself one. An entry left behind is permanent — every later ask for
        // that photo joins a run that already ended and is handed its cancellation instead of a
        // reveal, so the photo can never come back out of the vault while the app lives.
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val runs = HiddenRestoreRuns(owner)
            val landed = CompletableDeferred<Unit>()
            val caller = async {
                runCatching { runs.forPhoto(vaultUri) { landed.await(); HiddenRestoreOutcome.RESTORED } }
            }
            advanceUntilIdle()

            // The run itself rather than the caller: the runs are the owner's only children, and
            // cancelling one is what a torn-down owner does to every reveal still in flight.
            owner.coroutineContext.job.children.forEach { it.cancel() }
            advanceUntilIdle()
            assertTrue("the screen has to hear that its reveal ended", caller.await().isFailure)

            var restarted = false
            val second = runs.forPhoto(vaultUri) { restarted = true; HiddenRestoreOutcome.RESTORED }
            assertTrue("a cancelled run must not wedge the photo shut", restarted)
            assertEquals(HiddenRestoreOutcome.RESTORED, second)
        } finally {
            owner.cancel()
        }
    }
}
