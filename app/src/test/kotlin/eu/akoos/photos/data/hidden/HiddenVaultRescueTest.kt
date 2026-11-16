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
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.repository.VaultWipe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The sign-out rescue end to end, with the device and Drive stood in for: which photos come back,
 * which stay, and which vault files the wipe is then allowed to destroy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenVaultRescueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private val storage = mockk<HiddenStorageManager>()
    private val journal = mockk<HiddenVaultJournal>(relaxed = true)
    private val syncRepo = mockk<SyncStateRepository>(relaxed = true)
    private val accountManager = mockk<AccountManager>()
    private val password = mockk<VaultPasswordStore>()
    private val publisher = mockk<VaultZipPublisher>()
    private val drive = mockk<HiddenDriveCopyCheck>()
    private val user = UserId("u1")

    private val a = "file:///vault/a.jpg"
    private val b = "file:///vault/b.jpg"
    private val c = "file:///vault/c.jpg"
    private val d = "file:///vault/d.jpg"
    private val e = "file:///vault/e.jpg"

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        coEvery { syncRepo.getByUri(any()) } returns null
        coEvery { syncRepo.getByCloudId(any()) } returns null
        every { accountManager.getPrimaryUserId() } returns flowOf(null)
        every { storage.isHiddenUri(any()) } returns true
        every { storage.hasBlob(any()) } returns true
        Unit
    }

    private fun restorer() = HiddenVaultRestorer(
        context = context,
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        hiddenStorage = storage,
        journal = journal,
        syncStateRepo = syncRepo,
        accountManager = accountManager,
        localTagDao = mockk(relaxed = true),
        uploadAlbumTargetDao = mockk(relaxed = true),
        photoLocationDao = mockk(relaxed = true),
        vaultPassword = password,
        zipPublisher = publisher,
        driveCopies = drive,
    )

    private suspend fun record(index: Set<String>, pairings: Set<String>) {
        context.settingsDataStore.edit { p ->
            p[SettingsKeys.HIDDEN_PHOTO_URIS] = index
            p[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = pairings
        }
    }

    private fun restoresTo(uri: String, restoredUri: String) {
        coEvery { storage.restore(uri, any(), any(), any()) } coAnswers {
            val settle = arg<suspend (String) -> Boolean>(3)
            HiddenStorageManager.RestoredFile(restoredUri, viaOriginalPath = false, recordsSettled = settle(restoredUri))
        }
    }

    @Test
    fun `the gallery route moves device photos, keeps a photo whose write throws and an unconfirmed Drive photo, and offers only confirmed Drive files to the wipe`() = runBlocking {
        every { storage.vaultBlobUris() } returns setOf(a, b, c, d, e)
        record(index = setOf(a, b, c, d, e), pairings = setOf("$b|L-b", "$c|L-c"))
        coEvery { drive.liveOf(user, any()) } returns setOf("L-b")
        coEvery { password.readState() } returns StoredVaultPassword.None
        restoresTo(a, "content://media/external/images/media/1")
        // An Android 9 provider refusing the insert, surfacing from below the restore.
        coEvery { storage.restore(e, any(), any(), any()) } throws SecurityException("no storage permission")

        val rescued = restorer().rescueDeviceOnly(userId = user, busy = setOf(d))

        assertEquals(1, rescued.moved)
        assertEquals(setOf(e), rescued.stillInVault)
        assertEquals("only the photo Drive confirmed is safe to destroy", setOf(b), rescued.wipeable)
        assertEquals("the Drive photo Drive did not confirm stays in the vault", setOf(c), rescued.keptForDrive)
        assertEquals(VaultWipe.Only(setOf(b)), VaultSignOutPolicy.wipeAfterRescue(rescued, setOf(d)))
        assertEquals(RescueNotice.MovedPartly, VaultSignOutPolicy.forcedLogoutNotice(rescued))
        coVerify(exactly = 0) { storage.restore(b, any(), any(), any()) }
        coVerify(exactly = 0) { storage.restore(c, any(), any(), any()) }
        coVerify(exactly = 0) { storage.restore(d, any(), any(), any()) }
        coVerify { drive.liveOf(user, match { it.toSet() == setOf("L-b", "L-c") }) }
        val index = context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_PHOTO_URIS].orEmpty()
        assertTrue("the kept Drive photo keeps its record", c in index)
    }

    @Test
    fun `a session that cannot confirm Drive keeps the recorded Drive photo in the vault, out of the gallery`() = runBlocking {
        every { storage.vaultBlobUris() } returns setOf(b)
        record(index = setOf(b), pairings = setOf("$b|L-b"))
        coEvery { drive.liveOf(any(), any()) } returns emptySet()
        coEvery { password.readState() } returns StoredVaultPassword.None

        val notices = mutableListOf<RescueNotice>()
        val policy = VaultSignOutPolicy(
            rescue = { _, busy -> restorer().rescueDeviceOnly(userId = null, busy = busy) },
            notify = { notices += it },
        )
        val wipe = policy.afterSignOut("u-no-session")

        assertEquals(VaultWipe.Only(emptySet()), wipe)
        assertEquals(listOf(RescueNotice.KeptUnconfirmedInDrive), notices)
        coVerify(exactly = 0) { storage.restore(any(), any(), any(), any()) }
        assertEquals(setOf(b), context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_PHOTO_URIS])
    }

    @Test
    fun `a zip with an unconfirmed Drive photo zips only the device photos and keeps the Drive one`() = runBlocking {
        val files = vaultFiles("one.jpg")
        every { storage.vaultBlobUris() } returns files.toSet() + c
        record(index = files.toSet() + c, pairings = setOf("$c|L-c"))
        coEvery { drive.liveOf(user, any()) } returns emptySet()
        coEvery { password.readState() } returns StoredVaultPassword.Readable("pw".toCharArray())
        every { publisher.publish(any(), any()) } returns true

        val rescued = restorer().rescueDeviceOnly(userId = user)

        assertNotNull(rescued.zipName)
        assertEquals(1, rescued.moved)
        assertEquals(setOf(c), rescued.keptForDrive)
        assertEquals(VaultWipe.Only(files.toSet()), VaultSignOutPolicy.wipeAfterRescue(rescued))
        assertEquals(setOf(c), context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_PHOTO_URIS])
    }

    @Test
    fun `a zip that cannot be put in Downloads keeps every photo and says so`() = runBlocking {
        val files = vaultFiles("one.jpg", "two.jpg")
        every { storage.vaultBlobUris() } returns files.toSet() + b
        record(index = files.toSet() + b, pairings = setOf("$b|L-b"))
        coEvery { drive.liveOf(user, any()) } returns setOf("L-b")
        coEvery { password.readState() } returns StoredVaultPassword.Readable("pw".toCharArray())
        every { publisher.publish(any(), any()) } returns false

        val rescued = restorer().rescueDeviceOnly(userId = user)

        assertTrue(rescued.zipFailed)
        assertEquals(0, rescued.moved)
        assertEquals(files.toSet(), rescued.stillInVault)
        assertEquals(VaultWipe.Only(setOf(b)), VaultSignOutPolicy.wipeAfterRescue(rescued))
        assertEquals(RescueNotice.ZipFailed, VaultSignOutPolicy.forcedLogoutNotice(rescued))
        assertEquals("the records stay with the files", files.toSet() + b, context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_PHOTO_URIS])
    }

    @Test
    fun `a published zip drops the records and offers its photos to the wipe`() = runBlocking {
        val files = vaultFiles("one.jpg", "two.jpg")
        every { storage.vaultBlobUris() } returns files.toSet()
        record(index = files.toSet(), pairings = emptySet())
        coEvery { drive.liveOf(any(), any()) } returns emptySet()
        coEvery { password.readState() } returns StoredVaultPassword.Readable("pw".toCharArray())
        every { publisher.publish(any(), any()) } returns true

        val rescued = restorer().rescueDeviceOnly(userId = user)

        assertNotNull(rescued.zipName)
        assertEquals(2, rescued.moved)
        assertEquals(VaultWipe.Only(files.toSet()), VaultSignOutPolicy.wipeAfterRescue(rescued))
        assertEquals(RescueNotice.Moved(rescued.zipName), VaultSignOutPolicy.forcedLogoutNotice(rescued))
        assertTrue(context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_PHOTO_URIS].orEmpty().isEmpty())
    }

    @Test
    fun `a confirmed delete keeps the photo Drive no longer confirms`() = runBlocking {
        every { storage.vaultBlobUris() } returns setOf(a, b, c)
        record(index = setOf(a, b, c), pairings = setOf("$b|L-b", "$c|L-c"))
        coEvery { drive.liveOf(user, any()) } returns setOf("L-b")

        val plan = restorer().userDeletePlan(confirmed = setOf(a, b, c), userId = user, busy = emptySet())

        assertEquals(setOf(a, b), plan.delete)
        assertEquals(setOf(c), plan.unconfirmedInDrive)
    }

    /** Plain files standing in for vault files, as `file://` uris. */
    private fun vaultFiles(vararg names: String): List<String> = names.map { name ->
        val f = File(tmp.root, name).apply { writeBytes(ByteArray(2048) { (it * 31 + name.length).toByte() }) }
        Uri.fromFile(f).toString()
    }
}
