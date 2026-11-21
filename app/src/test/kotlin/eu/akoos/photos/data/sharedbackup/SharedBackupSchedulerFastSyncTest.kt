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

package eu.akoos.photos.data.sharedbackup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.akoos.photos.data.mlsync.AlbumOrderDriveNaming
import eu.akoos.photos.data.mlsync.LabelDriveNaming
import eu.akoos.photos.data.mlsync.MlDriveFile
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.mlsync.MlSyncSignOut
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.sdk.SdkGate
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.WorkManagerTestSetup
import eu.akoos.photos.worker.MlSyncWorker
import eu.akoos.photos.worker.SharedBackupTriggerWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The quicker shared backup as the scheduler runs it: when it lists the folders, when it asks for a round. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedBackupSchedulerFastSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val own = "22222222-2222-4222-8222-222222222222"
    private val pc = "11111111-1111-4111-8111-111111111111"
    private val user = UserId("user-1")

    private lateinit var context: Context
    private lateinit var store: SharedBackupStore
    private lateinit var scope: CoroutineScope
    private val primary = MutableStateFlow<UserId?>(user)
    private val online = MutableStateFlow(true)
    private val unmetered = MutableStateFlow(true)
    private val accountManager = mockk<AccountManager>()
    private val network = mockk<NetworkObserver>()
    private val sdkGate = mockk<SdkGate>()
    private val signOut = mockk<MlSyncSignOut>()
    private val runner = mockk<SharedBackupRunner>()
    private val driveStore = mockk<MlDriveStore>()
    private val enqueued = ArrayList<Boolean>()

    private var listing = SharedBackupListing(
        ml = emptyList(),
        labels = listOf(labels("r1")),
        hidden = emptyList(),
        settings = emptyList(),
        albumOrder = listOf(albumOrder("a1")),
    )

    private fun labels(revision: String) = MlDriveFile(
        nodeUid = "pc-labels", name = LabelDriveNaming.fileName(pc), sizeBytes = 10, activeRevisionUid = revision,
        serverModifiedAtMs = 1L, serverCreatedAtMs = 0L, claimedModifiedAtMs = null,
    )

    private fun albumOrder(revision: String) = MlDriveFile(
        nodeUid = "pc-album-order", name = AlbumOrderDriveNaming.fileName(pc), sizeBytes = 10,
        activeRevisionUid = revision, serverModifiedAtMs = 1L, serverCreatedAtMs = 0L, claimedModifiedAtMs = null,
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestSetup.initialize(context)
        context.settingsDataStore.edit { it.clear() }
        store = SharedBackupStore(context)
        scope = CoroutineScope(SupervisorJob())
        every { accountManager.getPrimaryUserId() } returns primary
        every { network.isOnline } returns online
        every { network.isUnmetered } returns unmetered
        every { sdkGate.isCapable } returns true
        coEvery { signOut.isSigningOut(any()) } returns false
        coEvery { runner.list(any()) } answers { listing }
        coEvery { driveStore.deviceId() } returns own
        mockkObject(MlSyncWorker.Companion)
        every { MlSyncWorker.enqueueRound(any(), any(), any(), any(), any(), any()) } answers {
            enqueued += arg<Boolean>(5)
            UUID.randomUUID()
        }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        unmockkObject(MlSyncWorker.Companion)
        scope.cancel()
        WorkManager.getInstance(context).cancelAllWork()
        context.settingsDataStore.edit { it.clear() }
        Unit
    }

    private fun scheduler() = SharedBackupScheduler(
        context = context,
        store = store,
        accountManager = accountManager,
        database = mockk(relaxed = true),
        autoSave = mockk(relaxed = true),
        autoSaveStore = mockk(relaxed = true),
        settingsSync = mockk(relaxed = true),
        network = network,
        sdkGate = { sdkGate },
        signOut = { signOut },
        runner = { runner },
        driveStore = { driveStore },
        appScope = scope,
    )

    private suspend fun turnOn() = store.setEnabled(true, System.currentTimeMillis())

    private suspend fun seenAll() {
        store.recordSeen(seenRevisions(listing))
        store.markOwnCopyTaken()
    }

    private fun planned(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }

    /** A round queued and waiting, as the ML sync job holds it. */
    private fun roundQueued() {
        val request = OneTimeWorkRequestBuilder<SharedBackupTriggerWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MlSyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
    }

    @Test
    fun `a pull with a change of the user's waiting sends the round at once, and pulling again starts no other`() =
        runBlocking {
            turnOn()
            val scheduler = scheduler()
            scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
            scheduler.refreshNow()
            assertEquals(listOf(true), enqueued)

            scheduler.refreshNow()
            scheduler.refreshNow()
            assertEquals(1, enqueued.size)
            coVerify(exactly = 0) { runner.list(any()) }
        }

    @Test
    fun `a pull finding another device's newer file starts a round, and pulls while it is queued start none`() =
        runBlocking {
            turnOn()
            val scheduler = scheduler()
            scheduler.refreshNow()
            scheduler.refreshNow()
            assertEquals(listOf(false), enqueued)
            coVerify(exactly = 1) { runner.list(user) }

            // Past the once-a-minute gap, the queued round still keeps another pull from listing or asking again.
            roundQueued()
            scheduler().refreshNow()
            assertEquals(1, enqueued.size)
            coVerify(exactly = 1) { runner.list(user) }
            assertTrue(planned(SharedBackupScheduler.NEXT_WORK).isEmpty())
        }

    @Test
    fun `a pull finding nothing new plans the next round as a round ending now would`() = runBlocking {
        turnOn()
        seenAll()
        // The next round as an earlier run of the app planned it.
        scheduler().scheduleNext()
        val before = planned(SharedBackupScheduler.NEXT_WORK).single().id
        val scheduler = scheduler()

        scheduler.refreshNow()
        assertTrue(enqueued.isEmpty())
        val after = planned(SharedBackupScheduler.NEXT_WORK).single().id
        assertNotEquals(before, after)

        // Pulling again within the minute lists nothing and leaves the plan as it is.
        scheduler.refreshNow()
        coVerify(exactly = 1) { runner.list(user) }
        assertEquals(after, planned(SharedBackupScheduler.NEXT_WORK).single().id)
    }

    @Test
    fun `a round that just ended moves the next look at the folders`() = runBlocking {
        turnOn()
        val scheduler = scheduler()
        scheduler.scheduleNext()
        scheduler.checkForNewer()
        scheduler.refreshNow()
        coVerify(exactly = 0) { runner.list(any()) }
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `a pull does nothing for a guest or with the backup off`() = runBlocking {
        val scheduler = scheduler()
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.refreshNow()
        turnOn()
        primary.value = null
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.refreshNow()
        coVerify(exactly = 0) { runner.list(any()) }
        assertTrue(enqueued.isEmpty())
        assertTrue(planned(SharedBackupScheduler.NEXT_WORK).isEmpty())
    }

    @Test
    fun `another device's newer file starts a round, and the same file again does not`() = runBlocking {
        turnOn()
        scheduler().checkForNewer()
        assertEquals(listOf(false), enqueued)

        // The round went through and saw the folders: the next look finds nothing new.
        store.recordSeen(seenRevisions(listing))
        store.markOwnCopyTaken()
        scheduler().checkForNewer()
        assertEquals(1, enqueued.size)

        listing = listing.copy(labels = listOf(labels("r2")))
        scheduler().checkForNewer()
        assertEquals(2, enqueued.size)
    }

    @Test
    fun `another device's newer album order file starts a round`() = runBlocking {
        turnOn()
        store.recordSeen(seenRevisions(listing))
        store.markOwnCopyTaken()
        scheduler().checkForNewer()
        assertTrue(enqueued.isEmpty())

        listing = listing.copy(albumOrder = listOf(albumOrder("a2")))
        scheduler().checkForNewer()
        assertEquals(listOf(false), enqueued)
    }

    @Test
    fun `the folders are listed at most once a minute`() = runBlocking {
        turnOn()
        store.recordSeen(seenRevisions(listing))
        store.markOwnCopyTaken()
        val scheduler = scheduler()
        scheduler.checkForNewer()
        scheduler.checkForNewer()
        coVerify(exactly = 1) { runner.list(user) }
    }

    @Test
    fun `nothing is listed with the backup off`() = runBlocking {
        scheduler().checkForNewer()
        coVerify(exactly = 0) { runner.list(any()) }
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `nothing is listed for a guest or when signed out`() = runBlocking {
        turnOn()
        primary.value = null
        scheduler().checkForNewer()
        coVerify(exactly = 0) { runner.list(any()) }
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `nothing is listed while the account signs out`() = runBlocking {
        turnOn()
        coEvery { signOut.isSigningOut(user) } returns true
        scheduler().checkForNewer()
        coVerify(exactly = 0) { runner.list(any()) }
    }

    @Test
    fun `nothing is listed offline, nor on a metered network when backups keep to unmetered ones`() = runBlocking {
        turnOn()
        online.value = false
        scheduler().checkForNewer()
        online.value = true
        unmetered.value = false
        scheduler().checkForNewer()
        coVerify(exactly = 0) { runner.list(any()) }

        context.settingsDataStore.edit { it[SettingsKeys.SYNC_WIFI_ONLY] = false }
        scheduler().checkForNewer()
        coVerify(exactly = 1) { runner.list(any()) }
    }

    @Test
    fun `nothing is listed after a failed round, whose next one is planned already`() = runBlocking {
        turnOn()
        store.recordRound(System.currentTimeMillis(), failed = true)
        scheduler().checkForNewer()
        coVerify(exactly = 0) { runner.list(any()) }
    }

    @Test
    fun `leaving the app sends the round at once only when a change of the user's waits`() = runBlocking {
        turnOn()
        val scheduler = scheduler()
        scheduler.appLeft()
        assertTrue(enqueued.isEmpty())

        // A scan's change waits for the half-hourly round.
        scheduler.changed(listOf(SharedBackupKind.SEARCH), byUser = false)
        scheduler.appLeft()
        assertTrue(enqueued.isEmpty())

        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.appLeft()
        assertEquals(listOf(true), enqueued)

        // Sent once: leaving again with nothing new waiting sends nothing more.
        scheduler.appLeft()
        assertEquals(1, enqueued.size)
    }

    @Test
    fun `leaving the app sends nothing with the backup off or for a guest`() = runBlocking {
        val scheduler = scheduler()
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.appLeft()
        turnOn()
        primary.value = null
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.appLeft()
        assertTrue(enqueued.isEmpty())
        verify(exactly = 0) { MlSyncWorker.enqueueRound(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sync now takes the waiting change in, so no other round follows for it`() = runBlocking {
        turnOn()
        val scheduler = scheduler()
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        assertEquals(1, planned(SharedBackupScheduler.SOON_WORK).size)

        scheduler.saveNow()
        assertEquals(1, enqueued.size)
        assertTrue(planned(SharedBackupScheduler.SOON_WORK).isEmpty())

        // Neither leaving the app nor a pull right after sends the change a second time, nor lists the folders.
        scheduler.appLeft()
        scheduler.refreshNow()
        assertEquals(1, enqueued.size)
        coVerify(exactly = 0) { runner.list(any()) }
    }

    @Test
    fun `every round asked for drops the planned check after the change it takes in`() = runBlocking {
        turnOn()
        val scheduler = scheduler()
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.syncNow()
        assertTrue(planned(SharedBackupScheduler.SOON_WORK).isEmpty())

        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler.loadNow()
        assertTrue(planned(SharedBackupScheduler.SOON_WORK).isEmpty())

        // The look at the folders that finds another device's newer file resets the same way.
        scheduler.changed(listOf(SharedBackupKind.LABELS), byUser = true)
        scheduler().checkForNewer()
        assertEquals(3, enqueued.size)
        assertTrue(planned(SharedBackupScheduler.SOON_WORK).isEmpty())
    }

    @Test
    fun `what a round saw is read back by a new store`() = runBlocking {
        val seen = mapOf("node-a" to "rev-1", "node-b" to "rev-7")
        store.recordSeen(seen)
        assertEquals(seen, SharedBackupStore(context).seen())
    }

    @Test
    fun `what a round saw survives a restart of the settings file`() = runBlocking {
        val seen = mapOf("vol~node-a" to "vol~node-a~rev-1", "vol~node-b" to "vol~node-b~rev-7")
        val file = temp.newFile("settings.preferences_pb").also { it.delete() }
        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = firstRun) { file }
            .edit { it[SettingsKeys.SHARED_BACKUP_SEEN] = encodeSeen(seen) }
        firstRun.coroutineContext[Job]!!.let { it.cancel(); it.join() }

        val secondRun = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val read = PreferenceDataStoreFactory.create(scope = secondRun) { file }.data.first()[SettingsKeys.SHARED_BACKUP_SEEN]
        secondRun.cancel()
        assertEquals(seen, decodeSeen(read.orEmpty()))
    }
}
