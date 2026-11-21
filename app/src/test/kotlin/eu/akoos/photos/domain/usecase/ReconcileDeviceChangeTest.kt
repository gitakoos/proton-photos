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
import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.QueueSource
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val shortCopies = InMemoryShortCloudCopyStore()
    private val claimed = FakeClaimedSizes()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val userId = UserId("test-user")
    private val written = slot<List<SyncState>>()
    private lateinit var prefs: Preferences
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        LocalRewrites.clear()
        // Strict: any hash the pass reads must be stubbed by the test.
        localRepo = mockk()
        coEvery { localRepo.confirmedGone(any()) } answers { firstArg<Collection<String>>().toSet() }
        coEvery { localRepo.fileStamp(any()) } returns null
        cloudRepo = mockk(relaxed = true)
        every { cloudRepo.cloudContentHash(any()) } answers { "hmac-${firstArg<String>()}" }
        syncStateRepo = mockk(relaxed = true)
        coEvery { syncStateRepo.upsertAll(capture(written), userId) } returns Unit
        syncStateRepo.answerReadsFromObserveAll()
        listingDao = mockk()

        prefs = mockk<Preferences>(relaxed = true)
        store = mockk<DataStore<Preferences>>()
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

        useCase = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, listingDao, context, appScope, shortCopies, claimed)
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
        PhotoPairingRow(linkId = linkId, contentHash = contentHash, captureTime = 99L, displayName = "cloud-$linkId.jpg", sizeBytes = 1_000_000L)

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
    fun `a rewrite this app recorded on the row keeps the stamp after a restart`() = runTest {
        // Nothing noted in this process: the row's own record of the bytes the app wrote is what tells.
        coEvery { localRepo.sha1("uri://own") } returns "metadata-written"
        coEvery { localRepo.sha1("uri://other") } returns "edited"

        val out = pass(
            listOf(device("uri://own", 2048L), device("uri://other", 2048L)),
            listOf(
                row("uri://own", "L1", "orig", 1024L).copy(originalHash = "METADATA-WRITTEN"),
                row("uri://other", "L2", "orig2", 1024L).copy(originalHash = "metadata-written"),
            ),
            listOf(cloud("L1", "hmac-orig"), cloud("L2", "hmac-orig2")),
        )

        assertEquals(STAMP, out.getValue("uri://own").backedUpAtMs)
        assertEquals("metadata-written", out.getValue("uri://own").localHash)
        assertNull("bytes other than the recorded ones lose the stamp", out.getValue("uri://other").backedUpAtMs)
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

    @Test
    fun `a stamped download row is never hashed and keeps the bytes it recorded once the listing is complete`() = runTest {
        // A complete listing is when a row missing its hash would have it recomputed and adopted.
        every { prefs.asMap() } returns mapOf<Preferences.Key<*>, Any>(
            kotlin.Pair(androidx.datastore.preferences.core.booleanPreferencesKey("photo_listing_ever_complete_${userId.id}_x"), true),
        )
        coEvery { store.updateData(any()) } returns prefs

        val out = pass(
            listOf(device("uri://dl", 1024L)),
            listOf(row("uri://dl", "L1", "", 1024L).copy(originalHash = "downloaded")),
            listOf(cloud("L1", "hmac-drive")),
        )

        coVerify(exactly = 0) { localRepo.sha1(any()) }
        val dl = out.getValue("uri://dl")
        assertEquals("", dl.localHash)
        assertEquals(STAMP, dl.backedUpAtMs)
    }

    // ── Drive copies shorter than the device file ────────────────────────────────────────────────────

    private fun stored(linkId: String, contentHash: String, size: Long) =
        PhotoPairingRow(linkId = linkId, contentHash = contentHash, captureTime = 99L, displayName = "cloud-$linkId.jpg", sizeBytes = size)

    private fun requeued(uri: String) {
        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == uri && it.status == SyncStatus.LOCAL_ONLY && it.cloudFileId == null && it.backedUpAtMs == null },
                userId,
            )
        }
        coVerify { syncStateRepo.markQueued(uri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `a backed-up file whose Drive copy stores fewer bytes than it holds is queued to be backed up again`() = runTest {
        val out = pass(
            listOf(device("uri://short", 2048L), device("uri://whole", 1024L)),
            listOf(
                row("uri://short", "L1", "orig", 2048L).copy(originalHash = "orig"),
                row("uri://whole", "L2", "other", 1024L).copy(originalHash = "other"),
            ),
            // L1's hash claims the whole 2048-byte file, yet Drive stores 1600 bytes for it.
            listOf(stored("L1", "hmac-orig", 1600L), stored("L2", "hmac-other", 1100L)),
        )

        requeued("uri://short")
        assertEquals(setOf("L1"), shortCopies.ids)
        assertNull(out["uri://short"])
        assertEquals(STAMP, out.getValue("uri://whole").backedUpAtMs)
        coVerify(exactly = 0) { syncStateRepo.markQueued("uri://whole", any(), any()) }
    }

    @Test
    fun `a smaller Drive copy of a stripped upload is not read as short, whatever the strip setting is now`() = runTest {
        for (stripNow in listOf(true, false)) {
            every { prefs[SettingsKeys.STRIP_ON_UPLOAD] } returns stripNow
            // The row's hash is the stripped upload's, recorded before device bytes were kept apart, so
            // its smaller Drive copy is the one expected.
            val legacy = pass(
                listOf(device("uri://1", 2048L)),
                listOf(row("uri://1", "L1", "stripped", 2048L)),
                listOf(stored("L1", "hmac-stripped", 1600L)),
            )
            // A row that recorded the device bytes apart from the stripped upload's.
            val recorded = pass(
                listOf(device("uri://2", 2048L)),
                listOf(row("uri://2", "L2", "stripped", 2048L).copy(originalHash = "device")),
                listOf(stored("L2", "hmac-stripped", 1600L)),
            )

            assertEquals(STAMP, legacy.getValue("uri://1").backedUpAtMs)
            assertEquals(STAMP, recorded.getValue("uri://2").backedUpAtMs)
        }
        assertEquals(emptySet<String>(), shortCopies.ids)
        coVerify(exactly = 0) { syncStateRepo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `a file queued again is never paired back to its short copy by hash`() = runTest {
        shortCopies.ids += "L1"
        val waiting = row("uri://1", "L1", "orig", 2048L).copy(
            status = SyncStatus.LOCAL_ONLY, cloudFileId = null, backedUpAtMs = null, lastSyncSuccessMs = null,
        )

        val out = pass(
            listOf(device("uri://1", 2048L), device("uri://2", 1024L)),
            listOf(waiting, row("uri://2", "L2", "other", 1024L)),
            // Drive's size reads whole and its hash names the file: only the record keeps them apart.
            listOf(stored("L1", "hmac-orig", 2100L), stored("L2", "hmac-other", 1100L)),
        )

        val state = out["uri://1"]
        assertTrue(state == null || (state.status != SyncStatus.SYNCED && state.cloudFileId == null && state.backedUpAtMs == null))
        assertEquals("L2", out.getValue("uri://2").cloudFileId)
    }

    @Test
    fun `the one-time look queues the editor's copies whose Drive copy declares fewer bytes`() = runTest {
        shortCopies.checked = false
        val since = EDITOR_SHORT_COPIES_SINCE_MS
        // The upload declared the size from before the EXIF write: 484 bytes short of the file.
        claimed.sizes["L1"] = 2048L - 484L
        coEvery { localRepo.sha1("uri://IMG_1_edit_20260801_101500") } returns "a"
        coEvery { localRepo.fileStamp("uri://IMG_1_edit_20260801_101500") } returns DeviceFileStamp(2048L, 0L)
        val out = pass(
            listOf(
                device("uri://IMG_1_edit_20260801_101500", 2048L),
                device("uri://IMG_2_edit_20260101_101500", 2048L),
                device("uri://IMG_3", 2048L),
            ),
            listOf(
                row("uri://IMG_1_edit_20260801_101500", "L1", "a", 2048L, backedUpAtMs = since + 1_000L),
                // Backed up before the version that cut the copies short.
                row("uri://IMG_2_edit_20260101_101500", "L2", "b", 2048L, backedUpAtMs = since - 1_000L),
                row("uri://IMG_3", "L3", "c", 2048L, backedUpAtMs = since + 1_000L),
            ),
            // Drive's sizes read whole: only the look by name and time finds the short copy.
            listOf(stored("L1", "hmac-a", 2100L), stored("L2", "hmac-b", 2100L), stored("L3", "hmac-c", 2100L)),
        )

        requeued("uri://IMG_1_edit_20260801_101500")
        assertEquals(setOf("L1"), shortCopies.ids)
        assertEquals("L1", shortCopies.replacements["uri://IMG_1_edit_20260801_101500"])
        assertTrue(shortCopies.checked)
        assertEquals(listOf("L1" to true), claimed.asked)
        assertEquals(since - 1_000L, out.getValue("uri://IMG_2_edit_20260101_101500").backedUpAtMs)
        assertEquals(since + 1_000L, out.getValue("uri://IMG_3").backedUpAtMs)
    }

    private fun editorRows(vararg links: String) = links.mapIndexed { i, link ->
        row("uri://IMG_${i}_edit_20260801_10150$i", link, "h$i", 2048L, backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
    }

    private fun editorDevices(vararg links: String) = links.indices.map { i -> device("uri://IMG_${i}_edit_20260801_10150$i", 2048L) }

    @Test
    fun `the one-time look leaves an editor copy that declares the whole file alone`() = runTest {
        shortCopies.checked = false
        claimed.sizes["L0"] = 2048L
        coEvery { localRepo.sha1("uri://IMG_0_edit_20260801_101500") } returns "h0"
        coEvery { localRepo.fileStamp("uri://IMG_0_edit_20260801_101500") } returns DeviceFileStamp(2048L, 0L)

        val out = pass(editorDevices("L0"), editorRows("L0"), listOf(stored("L0", "hmac-h0", 2100L)))

        assertEquals("L0", out.getValue("uri://IMG_0_edit_20260801_101500").cloudFileId)
        assertTrue(shortCopies.ids.isEmpty())
        assertTrue(shortCopies.checked)
        coVerify(exactly = 0) { syncStateRepo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `an editor copy whose declared size cannot be read is kept for a later look`() = runTest {
        shortCopies.checked = false

        val out = pass(editorDevices("L0"), editorRows("L0"), listOf(stored("L0", "hmac-h0", 2100L)))

        assertEquals("L0", out.getValue("uri://IMG_0_edit_20260801_101500").cloudFileId)
        assertTrue(shortCopies.ids.isEmpty())
        assertFalse(shortCopies.checked)
        coVerify(exactly = 0) { syncStateRepo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `an editor copy whose device file is not the uploaded bytes is recorded unverifiable and not uploaded again`() = runTest {
        shortCopies.checked = false
        claimed.sizes["L0"] = 1500L
        coEvery { localRepo.sha1("uri://IMG_0_edit_20260801_101500") } returns "stripped-apart"
        coEvery { localRepo.fileStamp("uri://IMG_0_edit_20260801_101500") } returns DeviceFileStamp(2048L, 0L)

        pass(editorDevices("L0"), editorRows("L0"), listOf(stored("L0", "hmac-h0", 2100L)))

        assertEquals(setOf("L0"), shortCopies.unverifiableIds)
        assertTrue(shortCopies.ids.isEmpty())
        assertTrue(shortCopies.checked)
        coVerify(exactly = 0) { syncStateRepo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `the one-time look measures the device file, not the size the scan reported`() = runTest {
        shortCopies.checked = false
        // The scan reports the size from before the EXIF write, the same size the upload declared.
        claimed.sizes["L0"] = 1564L
        coEvery { localRepo.sha1("uri://IMG_0_edit_20260801_101500") } returns "h0"
        coEvery { localRepo.fileStamp("uri://IMG_0_edit_20260801_101500") } returns DeviceFileStamp(2048L, 0L)

        pass(
            listOf(device("uri://IMG_0_edit_20260801_101500", 1564L)),
            listOf(row("uri://IMG_0_edit_20260801_101500", "L0", "h0", 1564L, backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)),
            listOf(stored("L0", "hmac-h0", 2100L)),
        )

        requeued("uri://IMG_0_edit_20260801_101500")
        assertEquals(setOf("L0"), shortCopies.ids)
    }

    private suspend fun partialPass(devices: List<LocalMediaItem>, rows: List<SyncState>, listing: List<PhotoPairingRow>) {
        coEvery { localRepo.freshScan() } returns DeviceMediaScan(devices, complete = false)
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns listing
        every { syncStateRepo.observeAll(userId) } returns flowOf(rows)
        useCase(userId).toList()
    }

    @Test
    fun `the one-time look is not marked done while a file backed up in the window was not seen`() = runTest {
        shortCopies.checked = false
        val inWindow = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L
        partialPass(
            listOf(device("uri://1", 1024L)),
            listOf(row("uri://1", "L1", "x", 1024L), row("uri://2", "L2", "y", 1024L, backedUpAtMs = inWindow)),
            listOf(stored("L1", "hmac-x", 1100L), stored("L2", "hmac-y", 1100L)),
        )

        assertFalse(shortCopies.checked)
    }

    @Test
    fun `the one-time look is marked done after a partial scan that saw every file backed up in the window`() = runTest {
        shortCopies.checked = false
        val inWindow = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L
        partialPass(
            listOf(device("uri://1", 1024L)),
            // uri://2 was backed up before the window, so its unseen file cannot be a short copy.
            listOf(row("uri://1", "L1", "x", 1024L, backedUpAtMs = inWindow), row("uri://2", "L2", "y", 1024L)),
            listOf(stored("L1", "hmac-x", 1100L), stored("L2", "hmac-y", 1100L)),
        )

        assertTrue(shortCopies.checked)
    }

    @Test
    fun `the one-time look waits for a pass that starts with backed-up rows`() = runTest {
        shortCopies.checked = false
        // A fresh install: nothing backed up yet, so the pairing below backs the copy up again first.
        pass(
            listOf(device("uri://IMG_1_edit_20260801_101500", 2048L), device("uri://2", 1024L)),
            emptyList(),
            listOf(stored("L1", "hmac-a", 2100L)),
        )

        assertFalse(shortCopies.checked)
        assertNull(shortCopies.startedMs)
    }

    private companion object {
        const val STAMP = 5_000L
    }
}
