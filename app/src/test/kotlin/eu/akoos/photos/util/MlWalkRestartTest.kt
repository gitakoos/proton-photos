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

import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the restart both background model walks owe their tail: a start asked for mid-pass is held and taken
 * once, a sign-out or a rescan drops it with the account, and a tail starts a pass only for the account last
 * asked for while it is still the signed-in one, or for the guest while none is.
 */
class MlWalkRestartTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    @Test
    fun a_held_restart_is_taken_once() {
        val restart = WalkRestart()
        restart.request()
        assertTrue(restart.isRequested())
        assertTrue(restart.take())
        assertFalse(restart.take())
        assertFalse(restart.isRequested())
    }

    @Test
    fun reset_clears_the_restart_flag_and_the_account() {
        val restart = WalkRestart()
        restart.lastUserId = alice
        restart.request()
        restart.reset()
        assertFalse("a tail landing after sign-out owes no restart", restart.take())
        assertNull(restart.lastUserId)
        // Nor may the leaving account's walk continue, even if it is still reported signed in.
        assertFalse(restart.mayRestart(alice, primaryUserId = alice))
    }

    @Test
    fun a_tail_restarts_only_the_account_last_asked_for_while_it_is_signed_in() {
        val restart = WalkRestart()
        restart.lastUserId = alice
        assertTrue(restart.mayRestart(alice, primaryUserId = alice))
        // Signed out, or another account now the primary one.
        assertFalse(restart.mayRestart(alice, primaryUserId = null))
        assertFalse(restart.mayRestart(alice, primaryUserId = bob))
        // A walk for another account than the one last asked for.
        restart.lastUserId = bob
        assertFalse(restart.mayRestart(alice, primaryUserId = alice))
        assertTrue(restart.mayRestart(bob, primaryUserId = bob))
    }

    @Test
    fun a_guest_tail_restarts_only_while_no_account_is_signed_in() {
        assertTrue(walkTailMayRestart(nextUserId = null, lastUserId = null, primaryUserId = null))
        assertFalse(walkTailMayRestart(nextUserId = null, lastUserId = null, primaryUserId = alice))
        // A sign-in asked for its own walk while the guest pass ran: the guest pass does not continue.
        assertFalse(walkTailMayRestart(nextUserId = null, lastUserId = alice, primaryUserId = alice))
    }
}
