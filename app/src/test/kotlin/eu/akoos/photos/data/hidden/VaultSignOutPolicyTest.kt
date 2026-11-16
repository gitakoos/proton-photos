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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session the server ended must not take the only copy of a hidden photo with it. These pin what
 * the sign-out may destroy afterwards: exactly what the rescue says is safe elsewhere, never what
 * stayed behind or what a hide was still moving, and nothing at all when the rescue cannot say.
 */
class VaultSignOutPolicyTest {

    private var rescues = 0
    private var lastBusy: Set<String>? = null
    private val notices = mutableListOf<RescueNotice>()

    private fun policy(rescue: suspend () -> HiddenRescue) = VaultSignOutPolicy(
        rescue = { _, busy -> rescues++; lastBusy = busy; rescue() },
        notify = { notices += it },
    )

    @Test
    fun `a sign-out the user asked for tells them when photos stayed in the vault`() {
        assertEquals(
            RescueNotice.KeptForPassword,
            VaultSignOutPolicy.userSignOutNotice(HiddenVaultDecisions.keptForPassword(listOf("file:///v/a")), 3),
        )
        assertEquals(
            RescueNotice.MovedPartly,
            VaultSignOutPolicy.userSignOutNotice(HiddenRescue(moved = 2, stillInVault = setOf("file:///v/b")), 3),
        )
        assertEquals(
            RescueNotice.NotMoved,
            VaultSignOutPolicy.userSignOutNotice(HiddenRescue(moved = 0, stillInVault = setOf("file:///v/b")), 3),
        )
        assertEquals(
            RescueNotice.ZipFailed,
            VaultSignOutPolicy.userSignOutNotice(HiddenVaultDecisions.zipFailed(listOf("file:///v/a")), 3),
        )
        assertEquals("a rescue that could not run leaves the whole vault", RescueNotice.NotMoved, VaultSignOutPolicy.userSignOutNotice(null, 3))
        assertNull(VaultSignOutPolicy.userSignOutNotice(HiddenRescue(moved = 3, stillInVault = emptySet()), 3))
        assertNull(VaultSignOutPolicy.userSignOutNotice(null, 0))
    }

    @Test
    fun `a password that cannot be read keeps the photos in the vault, never in the gallery`() = runTest {
        assertEquals(RescueRoute.KEEP_IN_VAULT, HiddenVaultDecisions.rescueRoute(StoredVaultPassword.Unreadable))
        assertEquals(RescueRoute.ZIP, HiddenVaultDecisions.rescueRoute(StoredVaultPassword.Readable(charArrayOf('p'))))
        assertEquals(RescueRoute.GALLERY, HiddenVaultDecisions.rescueRoute(StoredVaultPassword.None))
        val targets = listOf("file:///v/a", "file:///v/b")
        val policy = policy { HiddenVaultDecisions.keptForPassword(targets, wipeable = setOf("file:///v/drive")) }

        val wipe = policy.afterSignOut("u1")

        assertEquals("only the confirmed Drive photo goes", VaultWipe.Only(setOf("file:///v/drive")), wipe)
        assertEquals("the user is told where the photos are", listOf(RescueNotice.KeptForPassword), notices)
    }

    @Test
    fun `a zip that could not be made is reported as such, not as an unreadable password`() = runTest {
        val targets = listOf("file:///v/a", "file:///v/b")
        val policy = policy { HiddenVaultDecisions.zipFailed(targets) }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Only(emptySet()), wipe)
        assertEquals(listOf(RescueNotice.ZipFailed), notices)
        assertEquals("vault kept 2, zip not made", VaultSignOutPolicy.vaultNote(HiddenVaultDecisions.zipFailed(targets)))
    }

    @Test
    fun `a forced logout takes the device-only photos back, says so, and destroys only what is safe`() = runTest {
        val policy = policy {
            HiddenRescue(moved = 2, stillInVault = emptySet(), wipeable = setOf("file:///v/drive"))
        }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Only(setOf("file:///v/drive")), wipe)
        assertEquals(1, rescues)
        assertEquals("the user is told why photos appeared in the gallery", listOf(RescueNotice.Moved(null)), notices)
    }

    @Test
    fun `a gallery move that only partly worked says the rest stayed, never that none is lost`() = runTest {
        val policy = policy { HiddenRescue(moved = 2, stillInVault = setOf("file:///v/stuck")) }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Only(emptySet()), wipe)
        assertEquals(listOf(RescueNotice.MovedPartly), notices)
    }

    @Test
    fun `a gallery move that failed for every photo still posts a notice`() = runTest {
        val policy = policy { HiddenRescue(moved = 0, stillInVault = setOf("file:///v/a", "file:///v/b")) }

        policy.afterSignOut("u1")

        assertEquals(listOf(RescueNotice.NotMoved), notices)
    }

    @Test
    fun `a file that stayed or that a hide is moving is never wiped, even if offered`() = runTest {
        val policy = policy {
            HiddenRescue(
                moved = 1,
                stillInVault = setOf("file:///v/stuck"),
                wipeable = setOf("file:///v/drive", "file:///v/stuck", "file:///v/moving"),
            )
        }

        val wipe = policy.afterSignOut("u1", busy = setOf("file:///v/moving"))

        assertEquals(VaultWipe.Only(setOf("file:///v/drive")), wipe)
        assertEquals("the rescue learns the files to leave alone", setOf("file:///v/moving"), lastBusy)
    }

    @Test
    fun `a forced logout keeps the Drive photos Drive did not confirm and says they come back`() = runTest {
        val policy = policy {
            HiddenRescue(
                moved = 2,
                stillInVault = emptySet(),
                wipeable = setOf("file:///v/drive", "file:///v/unconfirmed"),
                keptForDrive = setOf("file:///v/unconfirmed"),
            )
        }

        val wipe = policy.afterSignOut("u1")

        assertEquals("a Drive photo Drive did not confirm is never wiped", VaultWipe.Only(setOf("file:///v/drive")), wipe)
        assertEquals(listOf(RescueNotice.Moved(null), RescueNotice.KeptUnconfirmedInDrive), notices)
    }

    @Test
    fun `a vault of only unconfirmed Drive photos keeps them all and posts only the kept notice`() = runTest {
        val policy = policy {
            HiddenRescue(moved = 0, stillInVault = emptySet(), keptForDrive = setOf("file:///v/a", "file:///v/b"))
        }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Only(emptySet()), wipe)
        assertEquals(listOf(RescueNotice.KeptUnconfirmedInDrive), notices)
        assertEquals(
            "vault moved 0, 0 left in vault, 2 kept, not confirmed in Drive",
            VaultSignOutPolicy.vaultNote(HiddenRescue(moved = 0, stillInVault = emptySet(), keptForDrive = setOf("file:///v/a", "file:///v/b"))),
        )
    }

    @Test
    fun `a vault with nothing to take back posts no notice`() = runTest {
        val policy = policy { HiddenRescue(moved = 0, stillInVault = emptySet()) }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Only(emptySet()), wipe)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a rescue that fails outright leaves the whole vault alone`() = runTest {
        val policy = policy { throw IllegalStateException("store unreadable") }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.None, wipe)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a forced logout whose marker was not saved neither rescues nor wipes anything`() = runTest {
        val policy = policy { HiddenRescue(moved = 2, stillInVault = emptySet(), wipeable = setOf("file:///v/a")) }

        val wipe = policy.afterSignOut("u-unmarked", markerSaved = false)

        assertEquals(VaultWipe.None, wipe)
        assertEquals("no rescue runs, so no photo leaves the vault", 0, rescues)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `an unsaved marker still takes the user mark, so a later forced logout is rescued`() = runTest {
        val policy = policy { HiddenRescue(moved = 1, stillInVault = emptySet()) }
        UserSignOut.mark("user-unmarked")

        assertEquals(VaultWipe.None, policy.afterSignOut("user-unmarked", markerSaved = false))
        assertFalse(UserSignOut.isMarked("user-unmarked"))
        assertEquals(VaultWipe.Only(emptySet()), policy.afterSignOut("user-unmarked"))
        assertEquals(1, rescues)
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
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `the unfinished mark applies to one disabling only`() = runTest {
        val policy = policy { HiddenRescue(moved = 1, stillInVault = emptySet()) }
        policy.markUnfinishedSignIn("u1")
        policy.afterSignOut("u1")

        val later = policy.afterSignOut("u1")

        assertEquals("a later forced logout of the same account is rescued as usual", VaultWipe.Only(emptySet()), later)
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
    fun `a chosen move out destroys only what the rescue says is safe`() {
        val wipe = VaultSignOutPolicy.wipeAfterRescue(
            HiddenRescue(moved = 4, stillInVault = setOf("file:///v/stuck"), wipeable = setOf("file:///v/zipped")),
        )

        assertEquals(VaultWipe.Only(setOf("file:///v/zipped")), wipe)
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
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `the user mark applies to one disabling, so a later forced logout is rescued`() = runTest {
        val policy = policy { HiddenRescue(moved = 1, stillInVault = emptySet()) }
        UserSignOut.mark("user-twice")
        policy.afterSignOut("user-twice")

        val later = policy.afterSignOut("user-twice")

        assertEquals(VaultWipe.Only(emptySet()), later)
        assertEquals(1, rescues)
    }

    @Test
    fun `a zipped rescue tells the notice which zip holds the photos and wipes what went into it`() = runTest {
        val policy = policy {
            HiddenRescue(
                moved = 2,
                stillInVault = emptySet(),
                zipName = "PFPVault_ForcedLogout_1.zip",
                wipeable = setOf("file:///v/a", "file:///v/b"),
            )
        }

        val wipe = policy.afterSignOut("u1")

        assertEquals(VaultWipe.Only(setOf("file:///v/a", "file:///v/b")), wipe)
        assertEquals(listOf(RescueNotice.Moved("PFPVault_ForcedLogout_1.zip")), notices)
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
