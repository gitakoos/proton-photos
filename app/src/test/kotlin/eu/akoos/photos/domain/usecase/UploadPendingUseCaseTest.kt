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
import android.content.ContentResolver
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.UploadAlbumTargetDao
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.StorageFullException
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.NetworkObserver
import eu.akoos.photos.util.StripResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadPendingUseCaseTest {

    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var networkObserver: NetworkObserver
    private lateinit var uploadAlbumTargetDao: UploadAlbumTargetDao
    private lateinit var context: Context
    private lateinit var useCase: UploadPendingUseCase
    // Hoisted so individual tests can flip a single pref (e.g. the strip-timestamp flags) after setUp.
    private lateinit var mockPrefsRef: Preferences
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        syncStateRepo = mockk(relaxed = true)
        // The upload claim succeeds by default so the base fixture uploads as before. uploadOne
        // atomically claims each LOCAL_ONLY row (flip to UPLOADING) before hashing/uploading and
        // skips on a 0 (another pass got it first); the relaxed mock would otherwise return 0 and
        // skip every item. A test modelling a lost claim can override this per URI.
        coEvery { syncStateRepo.claimForUpload(any()) } returns 1
        localRepo = mockk()
        cloudRepo = mockk(relaxed = true)
        networkObserver = mockk(relaxed = true)
        // No queued album targets in the base fixture: the drain and the per-upload album-add loop
        // both read from this DAO, so an empty result keeps those steps as no-ops. A test exercising
        // an album-add would stub getAll / getTargetsFor per URI.
        uploadAlbumTargetDao = mockk(relaxed = true)
        coEvery { uploadAlbumTargetDao.getAll() } returns emptyList()
        coEvery { uploadAlbumTargetDao.getTargetsFor(any()) } returns emptyList()
        context = mockk()

        // Upload-time folder filter maps pending URIs to bucket names via this flow. An empty
        // listing leaves every pending URI with a null bucket, which the allow-list filter treats
        // as backup-able while any folder is selected — so all LOCAL_ONLY items still upload.
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())

        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        // Hand back a readable stream so computeSha1 produces a real, non-null content hash and the
        // upload proceeds. A fresh stream per call because a single upload pass can hash more than one
        // item (and computeSha1 consumes the stream). A test that specifically models an unreadable
        // source can override this to return null per URI.
        every { contentResolver.openInputStream(any()) } answers {
            java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        }

        // Mock DataStore extension so settingsDataStore.data.first() and .edit{} work.
        val mockPrefs = mockk<Preferences>()
        mockPrefsRef = mockPrefs
        val mockDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        every { context.settingsDataStore } returns mockDataStore
        every { mockDataStore.data } returns flowOf(mockPrefs)
        // Auto-backup absent = ON, so the base fixture behaves as a normal running backup. The
        // tests that turn it off stub this key themselves.
        every { mockPrefs[SettingsKeys.AUTO_SYNC] } returns null
        // Non-empty folder set so the upload loop is not skipped.
        every { mockPrefs[SettingsKeys.SYNC_FOLDER_NAMES] } returns setOf("Camera")
        every { mockPrefs[SettingsKeys.BACKUP_EVERYTHING] } returns false
        every { mockPrefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] } returns emptySet()
        every { mockPrefs[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.RENAME_TO_CAPTURE_DATE] } returns false
        every { mockPrefs[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] } returns false
        every { mockPrefs[SettingsKeys.STRIP_GPS] } returns false
        every { mockPrefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] } returns emptySet()
        every { mockPrefs[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.STRIP_GPS] } returns false
        every { mockPrefs[SettingsKeys.STRIP_CAMERA_INFO] } returns false
        every { mockPrefs[SettingsKeys.STRIP_TIMESTAMP] } returns false
        every { mockPrefs[SettingsKeys.STRIP_SOFTWARE_INFO] } returns false
        every { mockPrefs[SettingsKeys.ALBUM_BUCKET_MAP] } returns emptySet()
        every { mockPrefs[SettingsKeys.MIRROR_STRIP_TO_LOCAL] } returns false
        // Upload compression off by default in the base fixture; the compress path is exercised
        // on-device by UploadImageCompressorTest.
        every { mockPrefs[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.COMPRESS_UPLOAD_TIER] } returns null
        every { mockPrefs[SettingsKeys.MIRROR_COMPRESS_TO_LOCAL] } returns false
        every { mockPrefs[SettingsKeys.PENDING_ALBUM_ADDS] } returns emptySet()
        every { mockPrefs[SettingsKeys.PENDING_DELETE_URIS] } returns emptySet()
        // Wi-Fi-only off so the network guard never short-circuits the upload loop.
        every { mockPrefs[SettingsKeys.SYNC_WIFI_ONLY] } returns false
        // Cloud listing settled: one ever-complete flag present + pairing settled, so the
        // post-reinstall duplicate-avoidance guard lets the bulk upload proceed.
        every { mockPrefs.asMap() } returns mapOf<Preferences.Key<*>, Any>(
            Pair(SettingsKeys.photoListingEverCompleteKey(userId.id, "vol1"), true),
        )
        every { mockPrefs[SettingsKeys.pairingSettledKey(userId.id)] } returns true

        useCase = UploadPendingUseCase(
            syncStateRepo, localRepo, cloudRepo, mockk(relaxed = true), networkObserver,
            mockk(relaxed = true), uploadAlbumTargetDao, context,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }),
        )
    }

    // Default a LOCAL_ONLY fixture row to queued=AUTO_FOLDER so it survives the queue-gated selector
    // (status==LOCAL_ONLY && queued). AUTO_FOLDER, not an explicit source, so these rows still respect
    // the Wi-Fi / folder guards; the base fixture just disables those guards via prefs. A test that
    // needs an explicit-action bypass overrides queueSource per row.
    private fun syncState(
        uri: String,
        status: SyncStatus,
        queued: Boolean = true,
        queueSource: String? = eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
    ) = SyncState(
        localUri = uri,
        cloudFileId = null,
        localHash = "",
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = null,
        sizeBytes = 1024L,
        queued = queued,
        queueSource = queueSource,
    )

    private fun localItem(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    private fun localItemAt(uri: String, dateTakenMs: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTakenMs,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    @Test
    fun `auto-backup off holds the rows the folder sweep queued`() = runTest {
        // The switch says off while the folder selection stays exactly as it was, which is what makes
        // this reachable at all: several triggers kick a run without consulting the switch, and the
        // folder filter alone cannot tell "off" from "Camera is selected".
        every { mockPrefsRef[SettingsKeys.AUTO_SYNC] } returns false
        val states = listOf(
            syncState("uri://1", SyncStatus.LOCAL_ONLY),
            syncState("uri://2", SyncStatus.LOCAL_ONLY),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)
        coEvery { localRepo.queryByUri(any()) } returns localItem("uri://1")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase(userId)

        coVerify(exactly = 0) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `auto-backup off still uploads what the user asked for by hand`() = runTest {
        // A manual "back up now", an album-add and an editor save are each an instruction about one
        // photo. The switch is a statement about the folder sweep, so it must not silently cancel
        // them: the user would press the button and watch nothing happen, with no error to explain it.
        every { mockPrefsRef[SettingsKeys.AUTO_SYNC] } returns false
        val states = listOf(
            syncState("uri://swept", SyncStatus.LOCAL_ONLY),
            syncState("uri://manual", SyncStatus.LOCAL_ONLY, queueSource = QueueSource.MANUAL),
            syncState("uri://album", SyncStatus.LOCAL_ONLY, queueSource = QueueSource.ALBUM_ADD),
            syncState("uri://edit", SyncStatus.LOCAL_ONLY, queueSource = QueueSource.EDITOR),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)
        coEvery { localRepo.queryByUri(any()) } answers { localItem(firstArg()) }
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase(userId)

        coVerify(exactly = 3) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { localRepo.queryByUri("uri://swept") }
    }

    @Test
    fun `Sync now uploads once even with auto-backup off, and only once`() = runTest {
        // Sync now is a one-tap action, not a setting, so it has to work while the switch is off.
        // The flag is consumed by the batch it was set for: a later automatic trigger must go back to
        // holding the sweep, or one tap would quietly re-enable backup for the rest of the session.
        every { mockPrefsRef[SettingsKeys.AUTO_SYNC] } returns false
        every { syncStateRepo.observeAll(userId) } returns
            flowOf(listOf(syncState("uri://1", SyncStatus.LOCAL_ONLY)))
        coEvery { localRepo.queryByUri(any()) } returns localItem("uri://1")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase.requestManualRun()
        useCase(userId)
        coVerify(exactly = 1) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }

        // Second pass, nothing asked for: back to held.
        useCase(userId)
        coVerify(exactly = 1) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a Sync now that defers for Wi-Fi still counts on the next run`() = runTest {
        // The flag is consumed past the early returns, not alongside the other prefs. A tap that lands
        // on a batch which defers (Wi-Fi-only on, no Wi-Fi) must not be spent on a run that uploaded
        // nothing: the rows would then be held by the switch on every later trigger, which is the same
        // silent nothing the flag exists to prevent.
        every { mockPrefsRef[SettingsKeys.AUTO_SYNC] } returns false
        every { mockPrefsRef[SettingsKeys.SYNC_WIFI_ONLY] } returns true
        coEvery { networkObserver.currentlyOnWifi() } returns false
        every { syncStateRepo.observeAll(userId) } returns
            flowOf(listOf(syncState("uri://1", SyncStatus.LOCAL_ONLY)))
        coEvery { localRepo.queryByUri(any()) } returns localItem("uri://1")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase.requestManualRun()
        useCase(userId)
        coVerify(exactly = 0) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }

        // Wi-Fi is back. The tap is still owed, so this run honours it.
        coEvery { networkObserver.currentlyOnWifi() } returns true
        useCase(userId)
        coVerify(exactly = 1) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `only LOCAL_ONLY items are uploaded`() = runTest {
        val states = listOf(
            syncState("uri://1", SyncStatus.LOCAL_ONLY),
            syncState("uri://2", SyncStatus.SYNCED),
            syncState("uri://3", SyncStatus.LOCAL_ONLY),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)
        coEvery { localRepo.queryByUri("uri://1") } returns localItem("uri://1")
        coEvery { localRepo.queryByUri("uri://3") } returns localItem("uri://3")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase(userId)

        coVerify(exactly = 2) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { localRepo.queryByUri("uri://2") }
    }

    @Test
    fun `a LOCAL_ONLY row that is not queued is not uploaded`() = runTest {
        // The queue switch: only LOCAL_ONLY rows carrying an explicit queued intent are selected.
        // uri://2 is LOCAL_ONLY but queued=false, so it must be skipped even though it is local-only.
        val states = listOf(
            syncState("uri://1", SyncStatus.LOCAL_ONLY),
            syncState("uri://2", SyncStatus.LOCAL_ONLY, queued = false, queueSource = null),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)
        coEvery { localRepo.queryByUri("uri://1") } returns localItem("uri://1")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase(userId)

        coVerify(exactly = 1) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { localRepo.queryByUri("uri://2") }
    }

    @Test
    fun `an explicit MANUAL row uploads even with no backup folders selected`() = runTest {
        // A manual "back up now" row (queued=MANUAL) must bypass the no-folders early-return: the user
        // asked for this one regardless of folder selection. With no folders configured, a plain
        // AUTO_FOLDER row would be skipped, but the MANUAL row still uploads.
        every { mockPrefsRef[SettingsKeys.SYNC_FOLDER_NAMES] } returns emptySet()
        val state = syncState(
            "uri://manual",
            SyncStatus.LOCAL_ONLY,
            queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL,
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(state))
        coEvery { localRepo.queryByUri("uri://manual") } returns localItem("uri://manual")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "cloud-id"

        useCase(userId)

        coVerify(exactly = 1) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `successful upload transitions status to SYNCED`() = runTest {
        val state = syncState("uri://1", SyncStatus.LOCAL_ONLY)
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(state))
        coEvery { localRepo.queryByUri("uri://1") } returns localItem("uri://1")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } returns "new-cloud-id"

        useCase(userId)

        coVerify {
            syncStateRepo.upsert(
                match { it.status == SyncStatus.SYNCED && it.cloudFileId == "new-cloud-id" },
                userId,
            )
        }
    }

    @Test
    fun `StorageFullException stops the upload loop immediately`() = runTest {
        val states = listOf(
            syncState("uri://1", SyncStatus.LOCAL_ONLY),
            syncState("uri://2", SyncStatus.LOCAL_ONLY),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)
        coEvery { localRepo.queryByUri("uri://1") } returns localItem("uri://1")
        coEvery { localRepo.queryByUri("uri://2") } returns localItem("uri://2")
        coEvery { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) } throws StorageFullException()

        useCase(userId)

        // After StorageFullException, the second item must NOT be attempted
        coVerify(exactly = 1) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `generic exception on one item does not stop remaining uploads`() = runTest {
        val states = listOf(
            syncState("uri://1", SyncStatus.LOCAL_ONLY),
            syncState("uri://2", SyncStatus.LOCAL_ONLY),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)
        coEvery { localRepo.queryByUri("uri://1") } returns localItem("uri://1")
        coEvery { localRepo.queryByUri("uri://2") } returns localItem("uri://2")
        coEvery { cloudRepo.uploadFile(userId, match { it.uri == "uri://1" }, any(), any(), any(), any()) } throws RuntimeException("network error")
        coEvery { cloudRepo.uploadFile(userId, match { it.uri == "uri://2" }, any(), any(), any(), any()) } returns "cloud-id-2"

        useCase(userId)

        // Both items were attempted; second one succeeded
        coVerify(exactly = 2) { cloudRepo.uploadFile(userId, any(), any(), any(), any(), any()) }
        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == "uri://2" && it.status == SyncStatus.SYNCED },
                userId,
            )
        }
    }

    @Test
    fun `item with no local file is silently skipped`() = runTest {
        val state = syncState("uri://missing", SyncStatus.LOCAL_ONLY)
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(state))
        coEvery { localRepo.queryByUri("uri://missing") } returns null

        useCase(userId)

        coVerify(exactly = 0) { cloudRepo.uploadFile(any(), any(), any(), any(), any(), any()) }
    }

    // ─── capture-time handling ────────────────────────────────────────────────

    @Test
    fun `the original capture time flows to the uploaded item and converts to whole seconds`() = runTest {
        // dateTaken stays in ms through the use-case; PhotoUploadService later sends
        // captureTime = dateTaken / 1000L (Proton wire format is seconds). Capture the
        // LocalMediaItem handed to uploadFile and assert that seconds conversion.
        val dateTakenMs = 1_700_000_123_000L // a clean second boundary
        val state = syncState("uri://1", SyncStatus.LOCAL_ONLY)
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(state))
        coEvery { localRepo.queryByUri("uri://1") } returns localItemAt("uri://1", dateTakenMs)
        val itemSlot = slot<LocalMediaItem>()
        coEvery {
            cloudRepo.uploadFile(userId, capture(itemSlot), any(), any(), any(), any())
        } returns "cloud-id"

        useCase(userId)

        assertEquals(dateTakenMs, itemSlot.captured.dateTaken)
        // The exact value PhotoUploadService puts on the wire.
        assertEquals(1_700_000_123L, itemSlot.captured.dateTaken / 1000L)
    }

    @Test
    fun `strip-timestamp floors the uploaded capture time to upload time, discarding the original`() = runTest {
        // STRIP_ON_UPLOAD + STRIP_TIMESTAMP rewrites the photo's capture time to "now" so the cloud
        // metadata can't reconstruct when the shot was taken. Assert the dateTaken handed to uploadFile
        // is the upload moment (≈ now), NOT the original 2019 timestamp.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns true
        every { mockPrefsRef[SettingsKeys.STRIP_TIMESTAMP] } returns true

        val originalMs = 1_550_000_000_000L // 2019-02-12, clearly not "now"
        val state = syncState("uri://1", SyncStatus.LOCAL_ONLY)
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(state))
        coEvery { localRepo.queryByUri("uri://1") } returns localItemAt("uri://1", originalMs)
        val itemSlot = slot<LocalMediaItem>()
        coEvery {
            cloudRepo.uploadFile(userId, capture(itemSlot), any(), any(), any(), any())
        } returns "cloud-id"

        val before = System.currentTimeMillis()
        // Neutralise the strip helpers (real Android file I/O this JVM fixture can't run) only for the
        // duration of this call; the block unmocks synchronously on exit so no object mock leaks to
        // another test. A null strip result routes the upload to the original bytes, which computeSha1
        // still hashes, so the capture-time flooring under test is exercised end-to-end.
        withStrippingNeutralised { useCase(userId) }
        val after = System.currentTimeMillis()

        val sent = itemSlot.captured.dateTaken
        assertNotEquals("strip-timestamp must discard the original capture time", originalMs, sent)
        // Floored to the upload moment — inside the [before, after] window the call spanned.
        assertTrue("sent=$sent should be >= $before", sent >= before)
        assertTrue("sent=$sent should be <= $after", sent <= after)
    }

    @Test
    fun `capture time is preserved when only GPS is stripped (strip-timestamp off)`() = runTest {
        // Stripping GPS but NOT timestamp must leave the capture time intact — the floor only applies
        // when STRIP_TIMESTAMP is on. Guards against an over-broad "any strip floors the date" bug.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns true
        every { mockPrefsRef[SettingsKeys.STRIP_GPS] } returns true
        every { mockPrefsRef[SettingsKeys.STRIP_TIMESTAMP] } returns false

        val originalMs = 1_550_000_000_000L
        val state = syncState("uri://1", SyncStatus.LOCAL_ONLY)
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(state))
        coEvery { localRepo.queryByUri("uri://1") } returns localItemAt("uri://1", originalMs)
        val itemSlot = slot<LocalMediaItem>()
        coEvery {
            cloudRepo.uploadFile(userId, capture(itemSlot), any(), any(), any(), any())
        } returns "cloud-id"

        withStrippingNeutralised { useCase(userId) }

        assertEquals(originalMs, itemSlot.captured.dateTaken)
    }

    /**
     * Runs [block] with [ExifHelper]'s strip entry points stubbed to a clean no-op, unmocking on exit
     * (the scoped [mockkObject] form). The strip helpers do real Android temp-file / EXIF / MIME work
     * that a plain JVM fixture can't run; the capture-time tests only need the flooring branch (which
     * runs before the strip fork), so a null / Failed strip result simply uploads the original bytes.
     */
    private inline fun withStrippingNeutralised(block: () -> Unit) {
        mockkObject(ExifHelper) {
            every { ExifHelper.stripToTempFile(any(), any(), any<MetadataStripConfig>()) } returns null
            every {
                ExifHelper.stripFieldsInPlace(any(), any(), any<MetadataStripConfig>())
            } returns StripResult.Failed
            block()
        }
    }
}
