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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoPairingRow
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncProgress
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DeviceMediaScan
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.PerfDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReconcileSyncStateUseCaseTest {

    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var listingDao: PhotoListingDao
    private lateinit var context: Context
    private lateinit var useCase: ReconcileSyncStateUseCase

    // The scope a pass runs on. Unconfined, so a pass with stubbed inputs completes inside the call
    // that starts it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // Hoisted so individual tests can override a single pref (e.g. STRIP_ON_UPLOAD) after setUp.
    private lateinit var mockPrefsRef: Preferences
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        localRepo = mockk()
        // A lookup by id confirms every gone-file candidate unless a test says otherwise.
        coEvery { localRepo.confirmedGone(any()) } answers { firstArg<Collection<String>>().toSet() }
        cloudRepo = mockk(relaxed = true)
        syncStateRepo = mockk(relaxed = true)
        // The batched writes and the LOCAL_ONLY read answer as their per-row forms do, so the tests verify
        // one call per row whichever form a pass uses.
        coEvery { syncStateRepo.markQueuedAll(any(), any(), any()) } coAnswers {
            for (uri in firstArg<List<String>>()) syncStateRepo.markQueued(uri, secondArg(), thirdArg())
        }
        coEvery { syncStateRepo.clearQueuedForSyncedAll(any()) } coAnswers {
            for (uri in firstArg<List<String>>()) syncStateRepo.clearQueuedForSynced(uri)
        }
        coEvery { syncStateRepo.updateDomainColumnsIfNotSyncedWithCloudAll(any(), any()) } coAnswers {
            firstArg<List<SyncState>>().filter { syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(it, secondArg()) == 0 }
                .mapTo(HashSet()) { it.localUri }
        }
        coEvery { syncStateRepo.getLocalOnly(any()) } coAnswers {
            syncStateRepo.observeAll(firstArg()).first().filter { it.status == SyncStatus.LOCAL_ONLY }
        }
        // Strict: the pairing rows are the only listing read a pass may make.
        listingDao = mockk()

        // Mock DataStore extension on Context
        val mockPrefs = mockk<Preferences>(relaxed = true)
        mockPrefsRef = mockPrefs
        val mockDataStore = mockk<DataStore<Preferences>>()
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        context = mockk()
        every { context.settingsDataStore } returns mockDataStore
        every { mockDataStore.data } returns flowOf(mockPrefs)
        every { mockPrefs[SettingsKeys.SYNC_FOLDER_NAMES] } returns setOf("Camera")  // back up Camera folder
        every { mockPrefs[SettingsKeys.BACKUP_EVERYTHING] } returns false
        every { mockPrefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] } returns emptySet()
        every { mockPrefs[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        // Compression off in the base fixture; a boolean key must be stubbed explicitly because the
        // relaxed mock otherwise hands back a raw Object that fails the Boolean cast.
        every { mockPrefs[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] } returns false
        every { mockPrefs[SettingsKeys.PENDING_ALBUM_ADDS] } returns emptySet()
        // No ever-complete flag → the content-hash recompute path is skipped; the name/size and
        // cloud-linkId matchers still run, which is what these tests exercise.
        every { mockPrefs.asMap() } returns emptyMap()

        useCase = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, listingDao, context, appScope)
    }

    @After
    fun tearDown() = appScope.cancel()

    /** A device scan as the repository reports one that nothing kept from the whole device: complete
     *  whenever it found something, since a scan that found nothing proves nothing. */
    private fun completeScan(items: List<LocalMediaItem>) = DeviceMediaScan(items, complete = items.isNotEmpty())

    /** The listing rows a pass reads for [photos], in the order given. */
    private fun pairingRows(photos: List<CloudPhoto>) = photos.map {
        PhotoPairingRow(
            linkId = it.linkId,
            contentHash = it.contentHash,
            captureTime = it.captureTime,
            displayName = it.displayName,
            sizeBytes = it.sizeBytes,
        )
    }

    private fun localItem(uri: String, name: String = "photo.jpg", size: Long = 1024L) = LocalMediaItem(
        uri = uri,
        dateTaken = 1000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        bucketName = "Camera",
    )

    private fun cloudPhoto(
        linkId: String,
        name: String = "photo.jpg",
        size: Long = 1024L,
        captureTime: Long = 1L,
        contentHash: String? = null,
    ) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTime,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        thumbnailUrl = null,
        revisionId = "rev1",
        contentHash = contentHash,
    )

    private fun syncState(
        uri: String,
        cloudId: String?,
        status: SyncStatus = SyncStatus.SYNCED,
        localHash: String = "",
        queued: Boolean = false,
        queueSource: String? = null,
    ) =
        SyncState(
            localUri = uri,
            cloudFileId = cloudId,
            localHash = localHash,
            cloudHash = null,
            status = status,
            lastSyncAttemptMs = 0L,
            lastSyncSuccessMs = null,
            backedUpAtMs = null,
            sizeBytes = 1024L,
            queued = queued,
            queueSource = queueSource,
        )

    @Test
    fun `local items without matching cloud get LOCAL_ONLY status`() = runTest {
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("uri://1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY } },
                userId,
            )
        }
    }

    @Test
    fun `local item matching cloud by name+size gets SYNCED status`() = runTest {
        val local = localItem("uri://1", name = "vacation.jpg", size = 2048L)
        val cloud = cloudPhoto("link1", name = "vacation.jpg", size = 2048L)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri("uri://1") } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link1" }
                },
                userId,
            )
        }
    }

    @Test
    fun `previously synced item no longer in cloud is marked CLOUD_ONLY`() = runTest {
        val local = localItem("uri://1")
        val existingSync = syncState("uri://1", cloudId = "old-link-id", status = SyncStatus.SYNCED)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        // Cloud no longer has "old-link-id"
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existingSync))
        coEvery { syncStateRepo.getByUri("uri://1") } returns existingSync

        useCase(userId).toList()

        // The demote runs through the id-CAS guard now, keyed on the cloud id the snapshot saw.
        coVerify { syncStateRepo.demoteToLocalIfCloudIdMatches("uri://1", "old-link-id") }
    }

    @Test
    fun `SYNCED state for file outside backup selection is NOT demoted to CLOUD_ONLY`() = runTest {
        // Regression: photos downloaded via main gallery land in Pictures/Proton Photos/, which
        // typically isn't in the backup-folder selection (users don't want their downloads
        // loop-uploaded). The file IS still on the device though, so reconcile must NOT demote
        // the SYNCED state — otherwise album views (which only check SyncState, no contentHash
        // fallback) lose the green "downloaded" indicator.
        val downloadedFile = LocalMediaItem(
            uri = "content://media/external/images/media/42",
            dateTaken = 1000L,
            displayName = "downloaded.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Proton Photos", // NOT in selected folders (only "Camera" is)
        )
        val cloud = cloudPhoto("link1", name = "downloaded.jpg", size = 4096L)
        val existingSync = syncState(downloadedFile.uri, cloudId = "link1", status = SyncStatus.SYNCED)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(downloadedFile))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existingSync))
        coEvery { syncStateRepo.getByUri(any()) } returns existingSync

        useCase(userId).toList()

        // The SYNCED state must remain SYNCED — verify no demote-to-CLOUD_ONLY upsert happens.
        coVerify(exactly = 0) {
            syncStateRepo.upsert(
                match { it.localUri == downloadedFile.uri && it.status == SyncStatus.CLOUD_ONLY },
                userId,
            )
        }
    }

    @Test
    fun `SYNCED state IS demoted to CLOUD_ONLY when local file actually gone from device`() = runTest {
        // Sanity: the demote path still fires when the file truly disappeared (user deleted from
        // gallery, "Free up space" ran, etc.). Pairs with the previous test. The device still holds
        // another photo: a scan that found nothing proves nothing.
        val ghostSync = syncState("content://media/external/images/media/99", cloudId = "linkGhost",
            status = SyncStatus.SYNCED)
        val cloud = cloudPhoto("linkGhost", name = "ghost.jpg")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("content://media/external/images/media/1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(ghostSync))
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == ghostSync.localUri && it.status == SyncStatus.CLOUD_ONLY },
                userId,
            )
        }
    }

    // ─── gone-file steps only on a complete scan, only for rows older than it ────

    /** A SYNCED row and a queued MANUAL row, both for files the scan does not return. */
    private fun vanishedRows(lastSuccess: Long? = null, queuedAt: Long? = null) = listOf(
        syncState("content://media/external/images/media/90", cloudId = "linkGone", status = SyncStatus.SYNCED)
            .copy(lastSyncSuccessMs = lastSuccess),
        syncState("content://media/external/images/media/91", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
            .copy(queuedAt = queuedAt),
    )

    @Test
    fun `an incomplete scan demotes nothing and drops no queued row`() = runTest {
        // One collection failed mid-walk, or the read was torn: the files it missed are still there,
        // and a MANUAL intent dropped for one of them would never come back.
        val seen = localItem("content://media/external/images/media/1")
        coEvery { localRepo.freshScan() } returns DeviceMediaScan(listOf(seen), complete = false)
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloudPhoto("linkGone", name = "gone.jpg")))
        every { syncStateRepo.observeAll(userId) } returns flowOf(vanishedRows())

        useCase(userId).toList()

        coVerify(exactly = 0) { syncStateRepo.upsert(match { it.status == SyncStatus.CLOUD_ONLY }, userId) }
        coVerify(exactly = 0) { syncStateRepo.deleteLocalOnlyByUris(any()) }
        // What the scan did find is still paired and recorded.
        coVerify { syncStateRepo.upsertAll(match { states -> states.any { it.localUri == seen.uri } }, userId) }
    }

    @Test
    fun `the same rows on a complete scan are demoted and dropped`() = runTest {
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("content://media/external/images/media/1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloudPhoto("linkGone", name = "gone.jpg")))
        every { syncStateRepo.observeAll(userId) } returns flowOf(vanishedRows())

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == "content://media/external/images/media/90" && it.status == SyncStatus.CLOUD_ONLY },
                userId,
            )
        }
        coVerify { syncStateRepo.deleteLocalOnlyByUris(listOf("content://media/external/images/media/91")) }
    }

    @Test
    fun `a file the scan skipped but a lookup by id still finds is neither demoted nor dropped`() = runTest {
        // A torn read skipped both rows, or the files sit in the trash: the lookup by id is asked about
        // exactly the candidates and still finds them, so neither row is touched.
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("content://media/external/images/media/1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloudPhoto("linkGone", name = "gone.jpg")))
        every { syncStateRepo.observeAll(userId) } returns flowOf(vanishedRows())
        coEvery { localRepo.confirmedGone(any()) } returns emptySet()

        useCase(userId).toList()

        coVerify { localRepo.confirmedGone(listOf("content://media/external/images/media/90")) }
        coVerify { localRepo.confirmedGone(listOf("content://media/external/images/media/91")) }
        coVerify(exactly = 0) { syncStateRepo.upsert(match { it.status == SyncStatus.CLOUD_ONLY }, userId) }
        coVerify(exactly = 0) { syncStateRepo.deleteLocalOnlyByUris(any()) }
    }

    @Test
    fun `only the candidates the lookup by id confirms gone are demoted and dropped`() = runTest {
        val rows = vanishedRows() + listOf(
            syncState("content://media/external/images/media/92", cloudId = "linkKept", status = SyncStatus.SYNCED),
            syncState("content://media/external/images/media/93", cloudId = null, status = SyncStatus.LOCAL_ONLY,
                queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL),
        )
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("content://media/external/images/media/1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(
            listOf(cloudPhoto("linkGone", name = "gone.jpg"), cloudPhoto("linkKept", name = "kept.jpg")),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(rows)
        // 92 and 93 are still on the device, in the trash.
        coEvery { localRepo.confirmedGone(any()) } answers {
            firstArg<Collection<String>>().filterTo(HashSet()) { it.endsWith("/90") || it.endsWith("/91") }
        }

        useCase(userId).toList()

        coVerify(exactly = 1) { syncStateRepo.upsert(match { it.status == SyncStatus.CLOUD_ONLY }, userId) }
        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == "content://media/external/images/media/90" && it.status == SyncStatus.CLOUD_ONLY },
                userId,
            )
        }
        coVerify(exactly = 1) { syncStateRepo.deleteLocalOnlyByUris(listOf("content://media/external/images/media/91")) }
    }

    @Test
    fun `rows written after the scan began are left for the next pass`() = runTest {
        // A download marked SYNCED, and a "back up now" queued, while this pass ran: the scan began
        // before either file could be listed, so their absence from it says nothing.
        val later = System.currentTimeMillis() + 60 * 60_000L
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("content://media/external/images/media/1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloudPhoto("linkGone", name = "gone.jpg")))
        every { syncStateRepo.observeAll(userId) } returns flowOf(vanishedRows(lastSuccess = later, queuedAt = later))

        useCase(userId).toList()

        coVerify(exactly = 0) { syncStateRepo.upsert(match { it.status == SyncStatus.CLOUD_ONLY }, userId) }
        coVerify(exactly = 0) { syncStateRepo.deleteLocalOnlyByUris(any()) }
    }

    @Test
    fun `reconcile emits progress events and finishes with running=false`() = runTest {
        coEvery { localRepo.freshScan() } returns completeScan(emptyList())
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val progress = useCase(userId).toList()

        assertTrue("Should start with running=true", progress.first().running)
        assertFalse("Should end with running=false", progress.last().running)
    }

    @Test
    fun `reconcile reads cloud state from DB without triggering a network refresh`() = runTest {
        coEvery { localRepo.freshScan() } returns completeScan(emptyList())
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        useCase(userId).toList()

        // ReconcileSyncStateUseCase only reads the cloud listing from the DB, as its pairing rows;
        // a network refresh (incremental or full) is the caller's responsibility.
        coVerify(exactly = 1) { listingDao.getOwnStreamPairingRows(userId.id) }
        verify(exactly = 0) { cloudRepo.observeCloudPhotos(any()) }
        coVerify(exactly = 0) { cloudRepo.refreshCloudPhotosIncremental(userId) }
        coVerify(exactly = 0) { cloudRepo.refreshCloudPhotos(userId) }
    }

    @Test
    fun `the device list comes from a scan begun for this pass, never the held list`() = runTest {
        // The demotions and the gone-file drop act on what is missing from the device list, so a
        // held list that trails a change could demote a file that just arrived.
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("uri://1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        useCase(userId).toList()

        coVerify(exactly = 1) { localRepo.freshScan() }
        verify(exactly = 0) { localRepo.observeLocalMedia() }
    }

    @Test
    fun `the final progress carries the device and cloud totals`() = runTest {
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("uri://1"), localItem("uri://2", name = "b.jpg")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns
            pairingRows(listOf(cloudPhoto("link1", name = "x.jpg"), cloudPhoto("link2", name = "y.jpg"), cloudPhoto("link3", name = "z.jpg")))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val progress = useCase(userId).toList()

        assertEquals(SyncProgress(5, 5, false), progress.last())
    }

    @Test
    fun `a pass marks its start with the device count and its end with the rows it handled`() = runTest {
        // Two of the three device files sit in the selected Camera folder, so the loop handles two.
        val outside = localItem("uri://3", name = "c.jpg").copy(bucketName = "WhatsApp")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("uri://1"), localItem("uri://2", name = "b.jpg"), outside))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        mockkObject(PerfDiagnostics) {
            every { PerfDiagnostics.mark(any(), any()) } just Runs
            useCase(userId).toList()

            verifyOrder {
                PerfDiagnostics.mark("reconcile:start", 3)
                PerfDiagnostics.mark("reconcile:end", 2)
            }
            verify(exactly = 1) { PerfDiagnostics.mark("reconcile:start", any()) }
            verify(exactly = 1) { PerfDiagnostics.mark("reconcile:end", any()) }
        }
    }

    // ─── listing order: the last of several rows sharing a key wins ──────────

    @Test
    fun `two cloud rows sharing a content hash pair to the later one in listing order`() = runTest {
        // The listing arrives newest first and each lookup keeps the last row under a key, so of two
        // uploads with the same bytes the one listed later is the twin the device file pairs to.
        val local = localItem("uri://1", name = "IMG.jpg", size = 2048L)
        val existing = syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY, localHash = "sha")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(
            listOf(
                cloudPhoto("link-first", name = "a.jpg", captureTime = 900L, contentHash = "HMAC"),
                cloudPhoto("link-second", name = "b.jpg", captureTime = 800L, contentHash = "HMAC"),
            ),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        every { cloudRepo.cloudContentHash("sha") } returns "HMAC"

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-second" }
                },
                userId,
            )
        }
    }

    @Test
    fun `two cloud rows sharing a name and capture second pair to the later one in listing order`() = runTest {
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "IMG_0001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(
            listOf(
                cloudPhoto("link-first", name = "IMG_0001.jpg", size = 1L, captureTime = 2L),
                cloudPhoto("link-second", name = "IMG_0001.jpg", size = 2L, captureTime = 2L),
            ),
        )
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.cloudFileId == "link-second" } },
                userId,
            )
        }
    }

    // ─── listing read failures ───────────────────────────────────────────────

    @Test
    fun `a listing read that fails once is read again and the pass completes`() = runTest {
        // Virtual time, so the pause before the second read costs nothing.
        val passScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val retrying = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, listingDao, context, passScope)
        val local = localItem("uri://1", name = "vacation.jpg", size = 2048L)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } throws
            IllegalStateException("window refill") andThen pairingRows(listOf(cloudPhoto("link1", name = "vacation.jpg", size = 2048L)))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        retrying(userId).toList()

        coVerify(exactly = 2) { listingDao.getOwnStreamPairingRows(userId.id) }
        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.cloudFileId == "link1" } },
                userId,
            )
        }
        passScope.cancel()
    }

    @Test
    fun `a listing read that keeps failing fails the pass after the last retry and writes nothing`() = runTest {
        val passScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val failing = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, listingDao, context, passScope)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("uri://1")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } throws IllegalStateException("store gone")
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val outcome = runCatching { failing(userId).toList() }

        assertEquals("store gone", outcome.exceptionOrNull()?.message)
        // The first read and five retries, the same bound the listing stream kept.
        coVerify(exactly = 6) { listingDao.getOwnStreamPairingRows(userId.id) }
        coVerify(exactly = 0) { syncStateRepo.upsertAll(any(), any()) }
        passScope.cancel()
    }

    // ─── matcher priority: byId ───────────────────────────────────────────────

    @Test
    fun `existing cloudFileId pairs the row to SYNCED by id regardless of hash or name`() = runTest {
        // The existing SyncState already knows the cloud linkId. byId is the top-priority matcher,
        // so the row stays SYNCED even though the cloud photo's name/size differ from the local file
        // and no content hash is involved.
        val local = localItem("uri://1", name = "local-name.jpg", size = 111L)
        val cloud = cloudPhoto("link-known", name = "totally-different.jpg", size = 999L)
        val existing = syncState("uri://1", cloudId = "link-known", status = SyncStatus.SYNCED)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-known" }
                },
                userId,
            )
        }
    }

    // ─── matcher priority: byContentHash ──────────────────────────────────────

    @Test
    fun `identical bytes pair by content hash even when the cloud copy was renamed`() = runTest {
        // No cloudFileId on the row, but the stored localHash + a cloud photo carrying the matching
        // ContentHash pairs them by bytes — the authoritative matcher. The cloud name differs (a
        // renamed cloud copy), proving the pairing is hash-driven, not name-driven.
        val local = localItem("uri://1", name = "IMG_local.jpg", size = 2048L)
        val cloud = cloudPhoto("link-hash", name = "renamed-on-cloud.jpg", size = 2048L, contentHash = "CLOUDHMAC")
        val existing = syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY, localHash = "deadbeefsha1")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing
        // The local SHA-1 maps to the cloud HMAC ContentHash via the repo helper.
        every { cloudRepo.cloudContentHash("deadbeefsha1") } returns "CLOUDHMAC"

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-hash" }
                },
                userId,
            )
        }
    }

    @Test
    fun `a stored hash that maps to no cloud ContentHash stays LOCAL_ONLY`() = runTest {
        // The row has a stored hash but the cloud photo's ContentHash doesn't match (different bytes),
        // and there's no name/date fallback because the cloud photo HAS a hash. So: LOCAL_ONLY. The row
        // already existed in the snapshot, so its LOCAL_ONLY write goes through the clobber-guard.
        val local = localItem("uri://1", name = "shared.jpg", size = 2048L)
        val cloud = cloudPhoto("link-other", name = "shared.jpg", size = 2048L, contentHash = "OTHERHMAC", captureTime = 50L)
        val existing = syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY, localHash = "localsha1")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing
        every { cloudRepo.cloudContentHash("localsha1") } returns "MINE-NOT-THEIRS"

        useCase(userId).toList()

        coVerify {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(
                match { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
    }

    // ─── matcher priority: byNameAndDate gating ───────────────────────────────

    @Test
    fun `name and date pairs when the cloud photo has no content hash`() = runTest {
        // No cloudFileId, no stored hash, but displayName + captureTime line up and the cloud photo
        // carries NO ContentHash → the name/date fallback is trusted and the row is SYNCED.
        // local.dateTaken=2000ms → 2s; cloud.captureTime must equal 2.
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "vacation.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-nd", name = "vacation.jpg", size = 4096L, captureTime = 2L, contentHash = null)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-nd" }
                },
                userId,
            )
        }
    }

    @Test
    fun `name and date re-pairs an unclaimed hashed cloud twin but keeps it out of free-up-space`() = runTest {
        // A downloaded file rewrites its own bytes (date / GPS / mvhd), so it can no longer hash-match
        // its cloud copy, yet it shares the twin's name and EXACT capture second. When that twin is
        // unclaimed (e.g. a reinstall wiped the SyncState), reconcile re-pairs it to SYNCED so it does
        // not re-upload a duplicate. The match is NOT content-certain, so backedUpAtMs stays null and
        // Free-up-space never deletes the local on this looser proof.
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "IMG_0001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-hashed", name = "IMG_0001.jpg", size = 4096L, captureTime = 2L, contentHash = "HASHED")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any {
                        it.localUri == "uri://1" && it.status == SyncStatus.SYNCED &&
                            it.cloudFileId == "link-hashed" && it.backedUpAtMs == null
                    }
                },
                userId,
            )
        }
    }

    @Test
    fun `name and date DOES pair a hashed cloud photo when strip-on-upload is on`() = runTest {
        // strip-on-upload rewrites the bytes, so the local original can't hash-match its stripped
        // cloud copy. With STRIP_ON_UPLOAD on, the name/date match is trusted again even though the
        // cloud photo carries a ContentHash. Expect SYNCED.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns true
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "stripped.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-strip", name = "stripped.jpg", size = 1L, captureTime = 2L, contentHash = "STRIPPEDHASH")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-strip" }
                },
                userId,
            )
        }
    }

    @Test
    fun `name and date DOES pair a hashed cloud photo when compress-on-upload is on`() = runTest {
        // Twin of the strip case for the other byte-rewriting toggle. compress-on-upload re-encodes
        // the bytes, so the local original can't hash-match its compressed cloud copy. With
        // COMPRESS_ON_UPLOAD on and STRIP_ON_UPLOAD off, the name/date match is trusted again even
        // though the cloud photo carries a ContentHash. Expect SYNCED. Regressing this half of the
        // gate would make a compressed upload fail to re-pair its cloud copy after a reinstall and
        // trigger a duplicate re-upload.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_ON_UPLOAD] } returns true
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "compressed.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-compress", name = "compressed.jpg", size = 2L, captureTime = 2L, contentHash = "COMPRESSEDHASH")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://1" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-compress" }
                },
                userId,
            )
        }
    }

    @Test
    fun `name and date DOES pair a hashed cloud video when video compression is on`() = runTest {
        // A compressed video can't hash-match its cloud copy, so the video switch alone makes the
        // name/date match trusted.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] } returns true
        val local = LocalMediaItem(
            uri = "uri://v",
            dateTaken = 2000L,
            displayName = "VID_0001.mp4",
            mimeType = "video/mp4",
            sizeBytes = 40_960L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-video", name = "VID_0001.mp4", size = 2L, captureTime = 2L, contentHash = "SMALLERHASH")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://v" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-video" }
                },
                userId,
            )
        }
    }

    @Test
    fun `a compressed mov pairs with its mp4 copy on Drive after a reinstall`() = runTest {
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] } returns true
        val local = LocalMediaItem(
            uri = "uri://mov",
            dateTaken = 2000L,
            displayName = "IMG_0001.MOV",
            mimeType = "video/quicktime",
            sizeBytes = 40_960L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-mp4", name = "IMG_0001.MP4", size = 2L, captureTime = 2L, contentHash = "SMALLERHASH")
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states ->
                    states.any { it.localUri == "uri://mov" && it.status == SyncStatus.SYNCED && it.cloudFileId == "link-mp4" }
                },
                userId,
            )
        }
    }

    @Test
    fun `the byte-rewrite gate keeps photo and video compression apart`() {
        assertTrue(uploadRewritesBytes("video/mp4", stripOnUpload = false, compressPhotosOnUpload = false, compressVideosOnUpload = true))
        assertFalse(uploadRewritesBytes("video/mp4", stripOnUpload = false, compressPhotosOnUpload = true, compressVideosOnUpload = false))
        assertTrue(uploadRewritesBytes("image/jpeg", stripOnUpload = false, compressPhotosOnUpload = true, compressVideosOnUpload = false))
        assertFalse(uploadRewritesBytes("image/jpeg", stripOnUpload = false, compressPhotosOnUpload = false, compressVideosOnUpload = true))
        assertTrue(uploadRewritesBytes("image/jpeg", stripOnUpload = true, compressPhotosOnUpload = false, compressVideosOnUpload = false))
    }

    @Test
    fun `name and date does NOT re-pair a hashed cloud twin already claimed by another local`() = runTest {
        // The re-pair only ever rescues an UNCLAIMED twin. Here the same hashed cloud photo is already
        // paired to a different local (uri://other), so a second same-named + same-second local must
        // NOT steal it: without this guard a recurring camera name (IMG_0001.jpg across devices) could
        // be marked backed up when it isn't. Expect the second local stays LOCAL_ONLY and backs itself up.
        every { mockPrefsRef[SettingsKeys.STRIP_ON_UPLOAD] } returns false
        every { mockPrefsRef[SettingsKeys.COMPRESS_ON_UPLOAD] } returns false
        val local = LocalMediaItem(
            uri = "uri://1",
            dateTaken = 2000L,
            displayName = "IMG_0001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        val cloud = cloudPhoto("link-hashed", name = "IMG_0001.jpg", size = 4096L, captureTime = 2L, contentHash = "HASHED")
        val claimedByOther = syncState("uri://other", cloudId = "link-hashed", status = SyncStatus.SYNCED)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(claimedByOther))
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY } },
                userId,
            )
        }
    }

    // ─── UPLOADING rows are skipped ───────────────────────────────────────────

    @Test
    fun `an UPLOADING row is left untouched by reconcile`() = runTest {
        // The editor owns an UPLOADING row until its cloud-fanout finishes; reconcile must not write
        // a new SyncState for that URI (which would race the editor and duplicate the Drive entry).
        val local = localItem("uri://uploading")
        val cloud = cloudPhoto("link-x", name = "photo.jpg")
        val existing = syncState("uri://uploading", cloudId = null, status = SyncStatus.UPLOADING)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(listOf(cloud))
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existing))
        coEvery { syncStateRepo.getByUri(any()) } returns existing

        useCase(userId).toList()

        // The URI must NOT appear in the batch upsert (it was `continue`d over in the loop).
        coVerify(exactly = 0) {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://uploading" } },
                userId,
            )
        }
    }

    // ─── queue switch (piece 3) ───────────────────────────────────────────────

    @Test
    fun `an out-of-scope queued LOCAL_ONLY row is de-queued not deleted`() = runTest {
        // The user unchecked the folder, so this LOCAL_ONLY row is no longer in scope (its bucket is
        // not "Camera"). Under the queue switch reconcile clears its queued flag (LOCAL_ONLY-guarded)
        // instead of deleting the row, so the file survives as local-not-backed-up but leaves the
        // pending set.
        val orphan = LocalMediaItem(
            uri = "content://media/external/images/media/7",
            dateTaken = 1000L,
            displayName = "old.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT in the selected "Camera" folder
        )
        val orphanSync = syncState(orphan.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(orphan))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(orphanSync))
        coEvery { syncStateRepo.getByUri(any()) } returns orphanSync

        useCase(userId).toList()

        coVerify { syncStateRepo.clearQueued(orphan.uri) }
        coVerify(exactly = 0) { syncStateRepo.deleteLocalOnlyByUris(any()) }
    }

    @Test
    fun `a fresh in-scope LOCAL_ONLY row is stamped queued AUTO_FOLDER`() = runTest {
        // A brand-new unmatched in-scope local becomes LOCAL_ONLY and must be queued=AUTO_FOLDER so
        // the queue-gated upload selector picks it up.
        coEvery { localRepo.freshScan() } returns completeScan(listOf(localItem("uri://fresh")))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())
        coEvery { syncStateRepo.getByUri(any()) } returns null

        useCase(userId).toList()

        coVerify {
            syncStateRepo.markQueued(
                "uri://fresh",
                eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
                any(),
            )
        }
    }

    @Test
    fun `an already-LOCAL_ONLY MANUAL row is not relabelled AUTO_FOLDER`() = runTest {
        // A row the user manually queued must keep its MANUAL source across reconcile: the fresh
        // AUTO_FOLDER stamp only touches rows whose source is null or AUTO_FOLDER.
        val local = localItem("uri://manual")
        val manualSync = syncState("uri://manual", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(manualSync))
        coEvery { syncStateRepo.getByUri(any()) } returns manualSync

        useCase(userId).toList()

        coVerify(exactly = 0) {
            syncStateRepo.markQueued(
                "uri://manual",
                eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
                any(),
            )
        }
    }

    // ─── explicit-intent queue robustness (piece 5a) ──────────────────────────

    @Test
    fun `an out-of-scope MANUAL queued row is NOT de-queued`() = runTest {
        // The user manually asked to back this up from a folder that is not in the backup selection.
        // Scope cleanup only de-queues AUTO_FOLDER / null-source rows; an explicit MANUAL intent must
        // upload regardless of folder scope, so its queued flag must survive.
        val outOfScope = LocalMediaItem(
            uri = "content://media/external/images/media/11",
            dateTaken = 1000L,
            displayName = "manual-out.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT the selected "Camera" folder
        )
        val manualSync = syncState(outOfScope.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(outOfScope))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(manualSync))
        coEvery { syncStateRepo.getByUri(any()) } returns manualSync

        useCase(userId).toList()

        coVerify(exactly = 0) { syncStateRepo.clearQueued(outOfScope.uri) }
    }

    @Test
    fun `an out-of-scope null-source queued row IS de-queued`() = runTest {
        // A legacy queued row with no recorded source, out of scope. Null source means an ordinary
        // folder backup (or a pre-source row), so scope cleanup clears its queued flag.
        val outOfScope = LocalMediaItem(
            uri = "content://media/external/images/media/12",
            dateTaken = 1000L,
            displayName = "null-out.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT the selected "Camera" folder
        )
        val nullSourceSync = syncState(outOfScope.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = true, queueSource = null)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(outOfScope))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(nullSourceSync))
        coEvery { syncStateRepo.getByUri(any()) } returns nullSourceSync

        useCase(userId).toList()

        coVerify { syncStateRepo.clearQueued(outOfScope.uri) }
    }

    @Test
    fun `a stranded MANUAL LOCAL_ONLY row is re-queued under its original source`() = runTest {
        // A manual "back up now" row lost its queued flag but kept queueSource=MANUAL and has no cloud
        // copy, so it would otherwise never retry. Recovery re-queues it under MANUAL. Out of the folder
        // scope on purpose so the AUTO_FOLDER stamp path can't be what re-queues it.
        val stranded = LocalMediaItem(
            uri = "content://media/external/images/media/21",
            dateTaken = 1000L,
            displayName = "stranded.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // NOT the selected "Camera" folder
        )
        val strandedSync = syncState(stranded.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(stranded))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(strandedSync))
        coEvery { syncStateRepo.getByUri(any()) } returns strandedSync

        useCase(userId).toList()

        coVerify {
            syncStateRepo.markQueued(
                stranded.uri,
                eu.akoos.photos.domain.entity.QueueSource.MANUAL,
                any(),
            )
        }
    }

    @Test
    fun `a null-source un-queued LOCAL_ONLY row is NOT re-queued`() = runTest {
        // A row with no source and no queued flag: either a plain out-of-scope local that was never
        // queued, or a MANUAL upload the user cancelled (clearManualQueue nulls the source). Recovery
        // must leave it un-queued: no markQueued of any kind for this URI.
        val idle = LocalMediaItem(
            uri = "content://media/external/images/media/22",
            dateTaken = 1000L,
            displayName = "idle.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 512L,
            bucketName = "WhatsApp", // out of scope so the AUTO_FOLDER stamp can't fire either
        )
        val idleSync = syncState(idle.uri, cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = null)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(idle))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(idleSync))
        coEvery { syncStateRepo.getByUri(any()) } returns idleSync

        useCase(userId).toList()

        coVerify(exactly = 0) { syncStateRepo.markQueued(eq(idle.uri), any(), any()) }
    }

    @Test
    fun `a backed-up photo whose cloud copy is deleted is NOT re-queued for upload`() = runTest {
        // Regression: a photo that was backed up (SYNCED) but still carries a leftover
        // queueSource=MANUAL, then had its cloud copy deleted by the user, must NOT be re-uploaded.
        // The cloud-absent demotion flips it to LOCAL_ONLY+cloudFileId=null AND clears the stale
        // intent via clearQueuedForSynced, so the stranded-intent recovery does not re-queue it into
        // an endless re-upload of the deletion. The local file is still on the device (cloud-absent
        // branch, not the local-gone CLOUD_ONLY branch).
        val local = LocalMediaItem(
            uri = "content://media/external/images/media/50",
            dateTaken = 1000L,
            displayName = "restored.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4096L,
            bucketName = "Camera",
        )
        // lastSyncSuccessMs = null (default) makes the grace window elapsed. queueSource=MANUAL is the
        // spent intent left over from the original backup.
        val deletedCloudSync = syncState(local.uri, cloudId = "link-del", status = SyncStatus.SYNCED,
            queued = false, queueSource = eu.akoos.photos.domain.entity.QueueSource.MANUAL)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(local))
        // Cloud copy was deleted → the listing no longer contains "link-del".
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(deletedCloudSync))
        coEvery { syncStateRepo.getByUri(any()) } returns deletedCloudSync

        useCase(userId).toList()

        // The demotion clears the spent intent, and the recovery must NOT re-queue the row.
        coVerify { syncStateRepo.clearQueuedForSynced(local.uri) }
        coVerify(exactly = 0) { syncStateRepo.markQueued(eq(local.uri), any(), any()) }
    }

    // ─── reconcile-vs-upload clobber guard ────────────────────────────────────

    @Test
    fun `a snapshot LOCAL_ONLY row an upload promoted mid-pass is guarded and not re-queued`() = runTest {
        // The snapshot saw this in-scope row as LOCAL_ONLY and, with no cloud match, reconcile computes
        // LOCAL_ONLY again. But a concurrent upload finished mid-pass and flipped the DB row to
        // SYNCED+cloudFileId, so the guarded update reports 0 rows changed. Reconcile must route the
        // write through that guard (never the plain batch, which would clobber the fresh pairing) AND
        // must not re-queue the uri, since a finished upload is not queued for a duplicate.
        val racing = localItem("uri://racing")
        val snapshot = syncState("uri://racing", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = null)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(racing))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(snapshot))
        coEvery { syncStateRepo.getByUri(any()) } returns snapshot
        // The DB row is SYNCED now, so the guard no-ops (0 rows changed).
        coEvery {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(match { it.localUri == "uri://racing" }, userId)
        } returns 0

        useCase(userId).toList()

        // Routed through the guard, carrying the computed LOCAL_ONLY state ...
        coVerify {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(
                match { it.localUri == "uri://racing" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
        // ... never through the unguarded batch ...
        coVerify(exactly = 0) {
            syncStateRepo.upsertAll(
                match { states -> states.any { it.localUri == "uri://racing" } },
                userId,
            )
        }
        // ... and not re-queued, because the guard reported the row already finished uploading.
        coVerify(exactly = 0) { syncStateRepo.markQueued(eq("uri://racing"), any(), any()) }
    }

    @Test
    fun `a snapshot LOCAL_ONLY row still un-synced is guarded-updated and re-queued`() = runTest {
        // Same shape, but the DB row is genuinely still LOCAL_ONLY, so the guard reports 1 row changed.
        // Reconcile still routes through the guard, and because the row was not promoted it IS stamped
        // queued=AUTO_FOLDER so the upload selector picks it up.
        val pending = localItem("uri://pending")
        val snapshot = syncState("uri://pending", cloudId = null, status = SyncStatus.LOCAL_ONLY,
            queued = false, queueSource = null)
        coEvery { localRepo.freshScan() } returns completeScan(listOf(pending))
        coEvery { listingDao.getOwnStreamPairingRows(userId.id) } returns pairingRows(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(snapshot))
        coEvery { syncStateRepo.getByUri(any()) } returns snapshot
        coEvery {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(match { it.localUri == "uri://pending" }, userId)
        } returns 1

        useCase(userId).toList()

        coVerify {
            syncStateRepo.updateDomainColumnsIfNotSyncedWithCloud(
                match { it.localUri == "uri://pending" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
        coVerify {
            syncStateRepo.markQueued(
                "uri://pending",
                eu.akoos.photos.domain.entity.QueueSource.AUTO_FOLDER,
                any(),
            )
        }
    }
}
