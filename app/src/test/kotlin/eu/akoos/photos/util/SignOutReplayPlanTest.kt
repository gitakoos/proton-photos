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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutReplayPlanTest {

    @Test
    fun `an unfinished cleanup runs again while nobody is signed in`() {
        assertEquals(
            mapOf("a" to SignOutReplay.RUN),
            SignOutCleanup.replayPlan(pending = setOf("a"), presentUserId = null, running = emptySet()),
        )
    }

    @Test
    fun `the same account signed back in only loses the marker`() {
        assertEquals(
            mapOf("a" to SignOutReplay.DROP),
            SignOutCleanup.replayPlan(pending = setOf("a"), presentUserId = "a", running = emptySet()),
        )
    }

    @Test
    fun `another account signed in is never wiped for the one that left`() {
        assertEquals(
            mapOf("a" to SignOutReplay.DROP),
            SignOutCleanup.replayPlan(pending = setOf("a"), presentUserId = "b", running = emptySet()),
        )
    }

    @Test
    fun `a cleanup still running in this process is not started twice`() {
        assertEquals(
            mapOf("a" to SignOutReplay.SKIP, "b" to SignOutReplay.RUN),
            SignOutCleanup.replayPlan(pending = setOf("a", "b"), presentUserId = null, running = setOf("a")),
        )
    }

    @Test
    fun `nothing pending plans nothing`() {
        assertTrue(SignOutCleanup.replayPlan(emptySet(), presentUserId = null, running = emptySet()).isEmpty())
    }

    @Test
    fun `a claimed cleanup cannot be claimed again until it is released`() {
        assertTrue(SignOutCleanup.claim("claim-test"))
        assertFalse(SignOutCleanup.claim("claim-test"))
        assertTrue("claim-test" in SignOutCleanup.runningNow())
        SignOutCleanup.release("claim-test")
        assertTrue(SignOutCleanup.claim("claim-test"))
        SignOutCleanup.release("claim-test")
    }

    @Test
    fun `an unfinished sign-in is consumed once`() {
        SignOutCleanup.noteUnfinishedSignIn("gave-up")
        assertTrue(SignOutCleanup.consumeUnfinishedSignIn("gave-up"))
        assertFalse(SignOutCleanup.consumeUnfinishedSignIn("gave-up"))
    }
}
