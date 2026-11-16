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

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.domain.repository.VaultWipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A hide and a sign-out racing over one vault file.
 *
 * A hide deletes the original of every file it journals, so a sign-out that destroyed such a file
 * would lose the photo outright. These walk the race as the code runs it: the hide's copy on disk, its
 * admission at the journal, the sign-out shutting the gate, the plan and the wipe, and the hide's own
 * ending afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultHideGateTest {

    private lateinit var context: Context
    private lateinit var storage: HiddenStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = HiddenStorageManager(context)
        File(context.filesDir, "hidden").deleteRecursively()
    }

    private suspend fun vaultFile(bytes: ByteArray = byteArrayOf(1, 2, 3)): String =
        storage.create("jpg", 1_700_000_000_000L) { out -> out.write(bytes) }!!

    private fun exists(uri: String) = File(Uri.parse(uri).path!!).exists()

    @Test
    fun `a hide admitted before the sign-out keeps its file through the rescue and the wipe`() = runBlocking {
        val gate = VaultHideGate()
        val settled = vaultFile(byteArrayOf(1))
        // The hide has copied and journalled; its original goes once the user answers the system dialog.
        val moving = vaultFile(byteArrayOf(2))
        assertTrue(gate.admit(listOf(moving)))

        val busy = gate.beginSignOut()
        val plan = HiddenVaultDecisions.signOutPlan(storage.vaultBlobUris(), emptyMap(), emptySet(), busy)
        // The rescue takes the settled photo out (its vault copy then goes with the restore itself);
        // the confirmed delete of a user sign-out names the counted files less the busy ones.
        val delete = HiddenVaultDecisions.userDeletePlan(setOf(settled, moving), storage.vaultBlobUris(), emptyMap(), emptySet(), busy).delete
        storage.deleteVaultFiles(delete)
        // The original is deleted now and the hide publishes.
        gate.release(listOf(moving))
        gate.endSignOut()

        assertEquals(listOf(settled), plan.rescue)
        assertEquals(setOf(settled), delete)
        assertTrue("the only copy of the photo whose original just went", exists(moving))
        assertFalse(exists(settled))
    }

    @Test
    fun `a hide that reaches the journal after the sign-out began is refused and keeps its original`() = runBlocking {
        val gate = VaultHideGate()
        gate.beginSignOut()
        val copy = vaultFile()

        val admitted = gate.admit(listOf(copy))

        assertFalse("its caller drops the copy and deletes no original", admitted)
        assertTrue(gate.inFlight().isEmpty())
        gate.endSignOut()
        assertTrue("hides are admitted again once the sign-out ended", gate.admit(listOf(copy)))
    }

    @Test
    fun `a file that lands after the plan was taken survives the wipe`() = runBlocking {
        val gate = VaultHideGate()
        val settled = vaultFile(byteArrayOf(1))
        val busy = gate.beginSignOut()
        val rescue = HiddenRescue(moved = 1, stillInVault = emptySet(), wipeable = setOf(settled))
        val late = vaultFile(byteArrayOf(9))

        val wipe = VaultSignOutPolicy.wipeAfterRescue(rescue, busy)
        storage.deleteVaultFiles((wipe as VaultWipe.Only).uris)
        gate.endSignOut()

        assertTrue(exists(late))
        assertFalse(exists(settled))
    }

    @Test
    fun `the busy set learnt at the start covers a hide that publishes while the sign-out runs`() {
        val gate = VaultHideGate()
        assertTrue(gate.admit(listOf("file:///v/moving")))

        val busy = gate.beginSignOut()
        gate.release(listOf("file:///v/moving"))

        assertEquals("the sign-out still spares it after it left the gate", setOf("file:///v/moving"), busy)
        assertFalse(VaultSignOutPolicy.wipeAfterRescue(
            HiddenRescue(moved = 0, stillInVault = emptySet(), wipeable = setOf("file:///v/moving")),
            busy,
        ).let { (it as VaultWipe.Only).uris.contains("file:///v/moving") })
        gate.endSignOut()
    }

    @Test
    fun `two overlapping sign-outs each hold the gate shut`() {
        val gate = VaultHideGate()
        gate.beginSignOut()
        gate.beginSignOut()
        gate.endSignOut()

        assertTrue(gate.signingOut())
        assertFalse(gate.admit(listOf("file:///v/a")))
        gate.endSignOut()
        assertFalse(gate.signingOut())
        gate.endSignOut()
        assertTrue("an extra end never opens the gate below zero", gate.admit(listOf("file:///v/a")))
    }
}
