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
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.StorageFullException
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.NetworkObserver
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UploadPendingUseCaseTest {

    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var networkObserver: NetworkObserver
    private lateinit var context: Context
    private lateinit var useCase: UploadPendingUseCase
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        syncStateRepo = mockk(relaxed = true)
        localRepo = mockk()
        cloudRepo = mockk(relaxed = true)
        networkObserver = mockk(relaxed = true)
        context = mockk()

        // Upload-time folder filter maps pending URIs to bucket names via this flow. An empty
        // listing leaves every pending URI with a null bucket, which the allow-list filter treats
        // as backup-able while any folder is selected — so all LOCAL_ONLY items still upload.
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())

        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        // Return empty stream so SHA-256 produces empty string
        every { contentResolver.openInputStream(any()) } returns null

        // Mock DataStore extension so settingsDataStore.data.first() and .edit{} work.
        val mockPrefs = mockk<Preferences>()
        val mockDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        every { context.settingsDataStore } returns mockDataStore
        every { mockDataStore.data } returns flowOf(mockPrefs)
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

        useCase = UploadPendingUseCase(syncStateRepo, localRepo, cloudRepo, mockk(relaxed = true), networkObserver, context)
    }

    private fun syncState(uri: String, status: SyncStatus) = SyncState(
        localUri = uri,
        cloudFileId = null,
        localHash = "",
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = null,
        sizeBytes = 1024L,
    )

    private fun localItem(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

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
}
