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

import eu.akoos.photos.domain.repository.VaultWipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session the server ended must not take the only copy of a device-only hidden photo with it.
 * These pin what the sign-out may destroy afterwards: nothing it could not move to the gallery,
 * and nothing at all when the rescue cannot say what is safe.
 */
class VaultSignOutPolicyTest {

    private var rescues = 0
    private var notices = 0
    private var lastZipName: String? = null

    private fun policy(rescue: suspend () -> HiddenRescue) = VaultSignOutPolicy(
        rescue = { rescues++; rescue() },
        notifyMoved = { zipName -> notices++; lastZipName = zipName },
    )

    @Test
    fun `a forced logout takes the device-only photos back, says so, and spares only what stayed`() = runTest {
        val policy = policy { HiddenRescue(moved = 2, stillInVault = setOf("file:///v/stuck")) }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Except(setOf("file:///v/stuck")), wipe)
        assertEquals(1, rescues)
        assertEquals("the user is told why photos appeared in the gallery", 1, notices)
    }

    @Test
    fun `a vault with nothing to take back posts no notice`() = runTest {
        val policy = policy { HiddenRescue(moved = 0, stillInVault = emptySet()) }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Except(emptySet()), wipe)
        assertEquals(0, notices)
    }

    @Test
    fun `a rescue that fails outright leaves the whole vault alone`() = runTest {
        val policy = policy { throw IllegalStateException("store unreadable") }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.None, wipe)
        assertEquals(0, notices)
    }

    @Test
    fun `a cancelled rescue is not mistaken for a failed one`() = runTest {
        val policy = policy { throw CancellationException("screen gone") }

        var cancelled = false
        try {
            policy.afterSignOut("u1")
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    @Test
    fun `a sign-in that gave up leaves the vault alone and never rescues`() = runTest {
        val policy = policy { HiddenRescue(moved = 3, stillInVault = emptySet()) }
        policy.markUnfinishedSignIn("u1")

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.None, wipe)
        assertEquals(0, rescues)
        assertFalse(notices > 0)
    }

    @Test
    fun `the unfinished mark applies to one disabling only`() = runTest {
        val policy = policy { HiddenRescue(moved = 1, stillInVault = emptySet()) }
        policy.markUnfinishedSignIn("u1")
        policy.afterSignOut("u1")

        val later = policy.afterSignOut("u1")

        assertEquals("a later forced logout of the same account is rescued as usual", VaultWipe.Except(emptySet()), later)
        assertEquals(1, rescues)
    }

    @Test
    fun `the mark of one account does not protect another`() = runTest {
        val policy = policy { HiddenRescue(moved = 1, stillInVault = emptySet()) }
        policy.markUnfinishedSignIn("u1")

        policy.afterSignOut("u2")

        assertEquals(1, rescues)
    }

    @Test
    fun `a chosen move out spares only the files that stayed in the vault`() {
        val wipe = VaultSignOutPolicy.wipeAfterRescue(
            HiddenRescue(moved = 4, stillInVault = setOf("file:///v/stuck")),
        )

        assertEquals(VaultWipe.Except(setOf("file:///v/stuck")), wipe)
    }

    @Test
    fun `a chosen move out that moved everything spares nothing`() {
        assertEquals(
            VaultWipe.Except(emptySet()),
            VaultSignOutPolicy.wipeAfterRescue(HiddenRescue(moved = 4, stillInVault = emptySet())),
        )
    }

    @Test
    fun `a move out that could not run at all destroys nothing`() {
        assertEquals(VaultWipe.None, VaultSignOutPolicy.wipeAfterRescue(null))
    }

    @Test
    fun `a sign-out the user asked for is left to the sign-out and never rescued again`() = runTest {
        val policy = policy { HiddenRescue(moved = 2, stillInVault = emptySet()) }
        UserSignOut.mark("user-asked")

        val wipe = policy.afterSignOut("user-asked")

        assertEquals(VaultWipe.None, wipe)
        assertEquals(0, rescues)
        assertEquals(0, notices)
    }

    @Test
    fun `the user mark applies to one disabling, so a later forced logout is rescued`() = runTest {
        val policy = policy { HiddenRescue(moved = 1, stillInVault = emptySet()) }
        UserSignOut.mark("user-twice")
        policy.afterSignOut("user-twice")

        val later = policy.afterSignOut("user-twice")

        assertEquals(VaultWipe.Except(emptySet()), later)
        assertEquals(1, rescues)
    }

    @Test
    fun `a zipped rescue tells the notice which zip holds the photos and wipes the whole vault`() = runTest {
        val policy = policy { HiddenRescue(moved = 2, stillInVault = emptySet(), zipName = "PFPVault_ForcedLogout_1.zip") }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Except(emptySet()), wipe)
        assertEquals(1, notices)
        assertEquals("PFPVault_ForcedLogout_1.zip", lastZipName)
    }

    @Test
    fun `the note for a zipped rescue says zipped and never names the zip`() {
        val note = VaultSignOutPolicy.vaultNote(
            HiddenRescue(moved = 4, stillInVault = emptySet(), zipName = "PFPVault_ForcedLogout_secret.zip"),
        )

        assertEquals("vault zipped 4, 0 left in vault", note)
        assertFalse(note.contains("secret"))
    }

    @Test
    fun `the diagnostics note carries counts and nothing that names a photo`() {
        val note = VaultSignOutPolicy.vaultNote(
            HiddenRescue(moved = 3, stillInVault = setOf("file:///data/user/0/app/files/hidden/secret-name.jpg")),
        )

        assertEquals("vault moved 3, 1 left in vault", note)
        assertFalse(note.contains("secret-name"))
        assertEquals("vault move failed, vault left alone", VaultSignOutPolicy.vaultNote(null))
    }
}
