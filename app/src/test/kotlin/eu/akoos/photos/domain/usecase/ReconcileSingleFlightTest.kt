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

package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncProgress
import eu.akoos.photos.domain.repository.DeviceMediaScan
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reconcile as its callers meet it: one instance, several callers at once. The device scan every pass
 * begins with is held until the test releases it, so the number of scans is the number of passes and
 * "a pass is under way" is decided by the scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileSingleFlightTest {

    private val userId = UserId("test-user")
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var context: Context
    private val scopes = mutableListOf<CoroutineScope>()
    private val scans = HeldScans()

    /** Device scans that end only when the test says so. */
    private class HeldScans {
        private val releases = Channel<Result<Unit>>(Channel.UNLIMITED)
        var started = 0
            private set

        suspend fun scan(): List<LocalMediaItem> {
            started++
            releases.receive().getOrThrow()
            return listOf(
                LocalMediaItem(
                    uri = "uri://1",
                    dateTaken = 1000L,
                    displayName = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 1024L,
                    bucketName = "Camera",
                ),
            )
        }

        fun finish() {
            releases.trySend(Result.success(Unit))
        }

        fun fail(message: String) {
            releases.trySend(Result.failure(IllegalStateException(message)))
        }
    }

    @Before
    fun setUp() {
        localRepo = mockk()
        coEvery { localRepo.freshScan() } coAnswers { DeviceMediaScan(scans.scan(), complete = true) }
        coEvery { localRepo.confirmedGone(any()) } answers { firstArg<Collection<String>>().toSet() }
        syncStateRepo = mockk(relaxed = true)
        every { syncStateRepo.observeAll(any()) } returns flowOf(emptyList())

        val prefs = mockk<Preferences>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>()
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        context = mockk()
        every { context.settingsDataStore } returns dataStore
        every { dataStore.data } returns flowOf(prefs)
        every { prefs[SettingsKeys.SYNC_FOLDER_NAMES] } returns setOf("Camera")
        every { prefs[SettingsKeys.BACKUP_EVERYTHING] } returns false
        every { prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] } returns emptySet()
        every { prefs[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { prefs[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        every { prefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] } returns false
        every { prefs.asMap() } returns emptyMap()
    }

    @After
    fun cancelScopes() = scopes.forEach { it.cancel() }

    /** The one instance every caller shares, its passes on the test's virtual clock. */
    private fun TestScope.sharedInstance(): ReconcileSyncStateUseCase {
        val listing = mockk<PhotoListingDao>()
        coEvery { listing.getOwnStreamPairingRows(any()) } returns emptyList()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        return ReconcileSyncStateUseCase(localRepo, mockk<DrivePhotoRepository>(relaxed = true), syncStateRepo, listing, context, scope)
    }

    @Test
    fun `callers that fire together share one pass`() = runTest {
        val reconcile = sharedInstance()

        val callers = List(4) { async { reconcile(userId).toList() } }
        runCurrent()
        assertEquals("callers that asked before the pass began must share it", 1, scans.started)

        scans.finish()
        callers.awaitAll().forEach { progress -> assertEquals(SyncProgress(1, 1, false), progress.last()) }
        assertEquals(1, scans.started)
        coVerify(exactly = 1) { syncStateRepo.upsertAll(any(), userId) }
    }

    @Test
    fun `callers arriving during a pass cost one more pass between them, never one each`() = runTest {
        val reconcile = sharedInstance()

        val first = async { reconcile(userId).toList() }
        runCurrent()
        val late = List(3) { async { reconcile(userId).toList() } }
        runCurrent()
        assertEquals("no second pass may begin while one is under way", 1, scans.started)

        scans.finish()
        first.await()
        runCurrent()
        assertEquals(2, scans.started)
        scans.finish()
        late.awaitAll()
        assertEquals("the pass under way plus one pass shared by every late caller", 2, scans.started)
        coVerify(exactly = 2) { syncStateRepo.upsertAll(any(), userId) }
    }

    @Test
    fun `a caller arriving mid-pass returns only after a pass that began after it asked`() = runTest {
        val reconcile = sharedInstance()

        val first = async { reconcile(userId).toList() }
        runCurrent()
        val late = async { reconcile(userId).toList() }
        runCurrent()

        scans.finish()
        first.await()
        runCurrent()
        assertFalse("the late caller must not be answered by the pass that was already under way", late.isCompleted)

        scans.finish()
        late.await()
        assertEquals(2, scans.started)
    }

    @Test
    fun `a failed pass fails exactly its callers and the next pass answers the rest`() = runTest {
        val reconcile = sharedInstance()

        val early = async { runCatching { reconcile(userId).toList() } }
        runCurrent()
        val late = async { runCatching { reconcile(userId).toList() } }
        runCurrent()

        scans.fail("scan failed")
        assertEquals("scan failed", early.await().exceptionOrNull()?.message)
        runCurrent()
        scans.finish()
        assertTrue("the next pass must answer the late caller", late.await().isSuccess)
        assertEquals(2, scans.started)
    }

    @Test
    fun `a caller that stops waiting does not stop the pass another caller waits on`() = runTest {
        val reconcile = sharedInstance()

        val leaving = async { reconcile(userId).toList() }
        val staying = async { reconcile(userId).toList() }
        runCurrent()
        leaving.cancel()
        runCurrent()

        scans.finish()
        staying.await()
        coVerify(exactly = 1) { syncStateRepo.upsertAll(any(), userId) }
        assertEquals(1, scans.started)
    }

    @Test
    fun `passes for two accounts take turns`() = runTest {
        val reconcile = sharedInstance()
        val other = UserId("other-user")

        val mine = async { reconcile(userId).toList() }
        runCurrent()
        val theirs = async { reconcile(other).toList() }
        runCurrent()
        assertEquals("the other account's pass must wait for this one to end", 1, scans.started)

        scans.finish()
        mine.await()
        runCurrent()
        assertEquals(2, scans.started)
        scans.finish()
        theirs.await()
        coVerify(exactly = 1) { syncStateRepo.upsertAll(any(), userId) }
        coVerify(exactly = 1) { syncStateRepo.upsertAll(any(), other) }
    }

    @Test
    fun `a call after a finished pass runs a new pass`() = runTest {
        val reconcile = sharedInstance()

        val one = async { reconcile(userId).toList() }
        runCurrent()
        scans.finish()
        one.await()

        val two = async { reconcile(userId).toList() }
        runCurrent()
        scans.finish()
        two.await()
        assertEquals(2, scans.started)
    }
}
