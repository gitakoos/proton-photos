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
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoPairingRow
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.upload.LocalRewrites
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DeviceMediaScan
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A backed-up photo another app edits in place keeps its uri, so the pass pairs it by its stored cloud id
 * as before. Free-up space deletes on the backup stamp alone, so the pass must take the stamp off a file
 * whose new bytes Drive does not hold, keep it on everything else, never re-upload, and never hash a file
 * whose size did not change.
 */
class ReconcileDeviceChangeTest {

    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var listingDao: PhotoListingDao
    private lateinit var useCase: ReconcileSyncStateUseCase
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val userId = UserId("test-user")
    private val written = slot<List<SyncState>>()

    @Before
    fun setUp() {
        LocalRewrites.clear()
        // Strict: any hash the pass reads must be stubbed by the test.
        localRepo = mockk()
        coEvery { localRepo.confirmedGone(any()) } answers { firstArg<Collection<String>>().toSet() }
        cloudRepo = mockk(relaxed = true)
        every { cloudRepo.cloudContentHash(any()) } answers { "hmac-${firstArg<String>()}" }
        syncStateRepo = mockk(relaxed = true)
        coEvery { syncStateRepo.upsertAll(capture(written), userId) } returns Unit
        listingDao = mockk()

        val prefs = mockk<Preferences>(relaxed = true)
        val store = mockk<DataStore<Preferences>>()
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        val context = mockk<Context>()
        every { context.settingsDataStore } returns store
        every { store.data } returns flowOf(prefs)
        every { prefs[SettingsKeys.SYNC_FOLDER_NAMES] } returns setOf("Camera")
        every { prefs[SettingsKeys.BACKUP_EVERYTHING] } returns false
        every { prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] } returns emptySet()
        every { prefs[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { prefs[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        every { prefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] } returns false
        every { prefs.asMap() } returns emptyMap()

        useCase = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, listingDao, context, appScope)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        LocalRewrites.clear()
    }

    private fun device(uri: String, size: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = 1000L,
        displayName = "${uri.substringAfterLast('/')}.jpg",
        mimeType = "image/jpeg",
        sizeBytes = size,
        bucketName = "Camera",
    )

    private fun row(
        uri: String,
        cloudId: String,
        localHash: String,
        size: Long,
        backedUpAtMs: Long? = STAMP,
    ) = SyncState(
        localUri = uri,
        cloudFileId = cloudId,
        localHash = localHash,
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 0L,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = size,
    )

    private fun cloud(linkId: String, contentHash: String?) =
        PhotoPairingRow(linkId = linkId, contentHash = contentHash, captureTime = 99L, displayName = "cloud-$linkId.jpg", sizeBytes = 1L)

    private suspend fun pass(devices: List<LocalMediaItem>, rows: List<SyncState>, listing: List<PhotoPairingRow>): Map<String, SyncState> {
        coEvery { localRepo.freshScan() } returns DeviceMediaScan(devices, complete = true)
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns listing
        every { syncStateRepo.observeAll(userId) } returns flowOf(rows)
        useCase(userId).toList()
        return written.captured.associateBy { it.localUri }
    }

    @Test
    fun `a file edited by another app loses its stamp and keeps its pairing`() = runTest {
        coEvery { localRepo.sha1("uri://1") } returns "edited"

        val out = pass(listOf(device("uri://1", 2048L)), listOf(row("uri://1", "L1", "orig", 1024L)), listOf(cloud("L1", "hmac-orig")))

        val state = out.getValue("uri://1")
        assertEquals(SyncStatus.SYNCED, state.status)
        assertEquals("L1", state.cloudFileId)
        assertEquals("edited", state.localHash)
        assertNull(state.backedUpAtMs)
        assertEquals(2048L, state.sizeBytes)
        coVerify(exactly = 0) { syncStateRepo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `a file whose size did not change is never hashed`() = runTest {
        val out = pass(listOf(device("uri://1", 1024L)), listOf(row("uri://1", "L1", "orig", 1024L)), listOf(cloud("L1", "hmac-orig")))

        coVerify(exactly = 0) { localRepo.sha1(any()) }
        assertEquals(STAMP, out.getValue("uri://1").backedUpAtMs)
    }

    @Test
    fun `the same bytes at a new size keep the stamp`() = runTest {
        coEvery { localRepo.sha1("uri://1") } returns "orig"

        val state = pass(listOf(device("uri://1", 2048L)), listOf(row("uri://1", "L1", "orig", 1024L)), listOf(cloud("L1", "hmac-x"))).getValue("uri://1")

        assertEquals(STAMP, state.backedUpAtMs)
        assertEquals("orig", state.localHash)
        assertEquals(2048L, state.sizeBytes)
    }

    @Test
    fun `bytes the cloud copy holds keep the stamp`() = runTest {
        coEvery { localRepo.sha1("uri://1") } returns "uploaded"

        val state = pass(listOf(device("uri://1", 2048L)), listOf(row("uri://1", "L1", "stale", 1024L)), listOf(cloud("L1", "hmac-uploaded"))).getValue("uri://1")

        assertEquals(STAMP, state.backedUpAtMs)
        assertEquals("uploaded", state.localHash)
    }

    @Test
    fun `a cloud copy with no content hash proves nothing`() = runTest {
        coEvery { localRepo.sha1("uri://1") } returns "edited"
        every { cloudRepo.cloudContentHash(any()) } returns null

        val state = pass(listOf(device("uri://1", 2048L)), listOf(row("uri://1", "L1", "orig", 1024L)), listOf(cloud("L1", null))).getValue("uri://1")

        assertNull(state.backedUpAtMs)
    }

    @Test
    fun `an unreadable file keeps its old size and stamp for the next pass`() = runTest {
        coEvery { localRepo.sha1("uri://1") } returns null

        val state = pass(listOf(device("uri://1", 2048L)), listOf(row("uri://1", "L1", "orig", 1024L)), listOf(cloud("L1", "hmac-orig"))).getValue("uri://1")

        assertEquals(1024L, state.sizeBytes)
        assertEquals("orig", state.localHash)
        assertEquals(STAMP, state.backedUpAtMs)
    }

    @Test
    fun `a file that lost its stamp is not stamped again on the next pass`() = runTest {
        val out = pass(
            listOf(device("uri://1", 2048L)),
            listOf(row("uri://1", "L1", "edited", 2048L, backedUpAtMs = null)),
            listOf(cloud("L1", "hmac-orig")),
        )

        coVerify(exactly = 0) { localRepo.sha1(any()) }
        assertNull(out.getValue("uri://1").backedUpAtMs)
        assertEquals("L1", out.getValue("uri://1").cloudFileId)
    }

    @Test
    fun `a row with no stored hash still earns its stamp through its cloud id`() = runTest {
        val out = pass(
            listOf(device("uri://1", 1024L)),
            listOf(row("uri://1", "L1", "", 1024L, backedUpAtMs = null)),
            listOf(cloud("L1", "hmac-orig")),
        )

        assertNotNull(out.getValue("uri://1").backedUpAtMs)
    }

    @Test
    fun `a download or an editor placeholder is never hashed`() = runTest {
        val out = pass(
            listOf(device("uri://dl", 2048L), device("uri://ed", 2048L)),
            listOf(row("uri://dl", "L1", "", 1024L), row("uri://ed", "L2", "sha", 0L)),
            listOf(cloud("L1", "hmac-a"), cloud("L2", "hmac-sha")),
        )

        coVerify(exactly = 0) { localRepo.sha1(any()) }
        assertEquals(STAMP, out.getValue("uri://dl").backedUpAtMs)
        assertEquals(STAMP, out.getValue("uri://ed").backedUpAtMs)
    }

    @Test
    fun `a file this process rewrote keeps its stamp, only at the size the rewrite left`() = runTest {
        coEvery { localRepo.sha1(any()) } returns "rewritten"
        LocalRewrites.note("uri://own", 2048L)
        LocalRewrites.note("uri://later", 1500L)

        val out = pass(
            listOf(device("uri://own", 2048L), device("uri://later", 2048L)),
            listOf(row("uri://own", "L1", "orig", 1024L), row("uri://later", "L2", "orig", 1024L)),
            listOf(cloud("L1", "hmac-orig"), cloud("L2", "hmac-orig2")),
        )

        assertEquals(STAMP, out.getValue("uri://own").backedUpAtMs)
        assertEquals("rewritten", out.getValue("uri://own").localHash)
        assertNull(out.getValue("uri://later").backedUpAtMs)
    }

    @Test
    fun `a pass hashes at most its cap and leaves the rest for the next one`() = runTest {
        val count = DEVICE_CHANGE_HASHES_PER_PASS + 1
        val devices = List(count) { device("uri://$it", 2048L) }
        val rows = List(count) { row("uri://$it", "L$it", "orig", 1024L) }
        val listing = List(count) { cloud("L$it", "hmac-orig") }
        coEvery { localRepo.sha1(any()) } returns "edited"

        val out = pass(devices, rows, listing)

        coVerify(exactly = DEVICE_CHANGE_HASHES_PER_PASS) { localRepo.sha1(any()) }
        val last = out.getValue("uri://${count - 1}")
        assertEquals(1024L, last.sizeBytes)
        assertEquals(STAMP, last.backedUpAtMs)
        assertNull(out.getValue("uri://0").backedUpAtMs)
    }

    @Test
    fun `a pass stops hashing once it has read its byte budget`() = runTest {
        val big = DEVICE_CHANGE_HASH_BYTES_PER_PASS / 2 + 1
        coEvery { localRepo.sha1(any()) } returns "edited"

        val out = pass(
            listOf(device("uri://a", big), device("uri://b", big)),
            listOf(row("uri://a", "La", "orig", 1024L), row("uri://b", "Lb", "orig", 1024L)),
            listOf(cloud("La", "hmac-orig"), cloud("Lb", "hmac-orig")),
        )

        coVerify(exactly = 1) { localRepo.sha1(any()) }
        assertNull(out.getValue("uri://a").backedUpAtMs)
        assertEquals(1024L, out.getValue("uri://b").sizeBytes)
        assertEquals(STAMP, out.getValue("uri://b").backedUpAtMs)
    }

    private companion object {
        const val STAMP = 5_000L
    }
}
