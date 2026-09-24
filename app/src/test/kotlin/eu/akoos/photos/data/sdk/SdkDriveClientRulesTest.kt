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

package eu.akoos.photos.data.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Coverage for the rules behind the Drive SDK client's lifetime: the client id each account gets on an
 * install, how long a client closed for a sign-out stays closed, and when a retired client is closed
 * around the calls still running on it.
 */
class SdkDriveClientRulesTest {

    private val install = "5b6a1f0e-2c3d-4e5f-8a9b-0c1d2e3f4a5b"

    // ---- Client id ---------------------------------------------------------------------------------------

    @Test
    fun `an account's client id is the same on every start`() {
        assertEquals(sdkClientUid(install, "user-a"), sdkClientUid(install, "user-a"))
    }

    @Test
    fun `two accounts on one install never share a client id, nor show the install's own`() {
        val first = sdkClientUid(install, "user-a")
        val second = sdkClientUid(install, "user-b")
        assertNotEquals(first, second)
        assertNotEquals(install, first)
        assertNotEquals(install, second)
    }

    @Test
    fun `the same account on another install gets another client id`() {
        val other = "9e8d7c6b-5a4f-4e3d-9c2b-1a0f9e8d7c6b"
        assertNotEquals(sdkClientUid(install, "user-a"), sdkClientUid(other, "user-a"))
    }

    @Test
    fun `a client id is a UUID`() {
        val uid = sdkClientUid(install, "user-a")
        assertEquals(uid, UUID.fromString(uid).toString())
    }

    // ---- Closed for a sign-out ---------------------------------------------------------------------------

    @Test
    fun `a closed client stays closed through the sign-out and after it`() {
        // Still the session that was signing out, then none once the account is disabled.
        assertTrue(clientStaysClosed(closedSession = "s1", currentSession = "s1"))
        assertTrue(clientStaysClosed(closedSession = "s1", currentSession = null))
        // Closed when the server had already ended the session, and still without one.
        assertTrue(clientStaysClosed(closedSession = null, currentSession = null))
    }

    @Test
    fun `a new sign-in opens the client again`() {
        assertFalse(clientStaysClosed(closedSession = "s1", currentSession = "s2"))
        assertFalse(clientStaysClosed(closedSession = null, currentSession = "s2"))
    }

    // ---- Calls in flight -----------------------------------------------------------------------------------

    @Test
    fun `a client with no call in flight closes as it is retired, and takes no call after`() {
        val lease = SdkClientLease()
        assertTrue(lease.retire())
        assertFalse(lease.enter())
        assertFalse(lease.retire())
    }

    @Test
    fun `a client in use closes only when its last call is out, and only once`() {
        val lease = SdkClientLease()
        assertTrue(lease.enter())
        assertTrue(lease.enter())
        assertFalse(lease.idle.value)
        // Retired under two calls: nothing closes it under them.
        assertFalse(lease.retire())
        assertFalse(lease.enter())
        assertFalse(lease.exit())
        assertFalse(lease.idle.value)
        // The last call out closes it.
        assertTrue(lease.exit())
        assertTrue(lease.idle.value)
        assertFalse(lease.retire())
    }

    @Test
    fun `a client that is not retired never closes`() {
        val lease = SdkClientLease()
        repeat(3) {
            assertTrue(lease.enter())
            assertFalse(lease.exit())
        }
        assertTrue(lease.idle.value)
    }
}
