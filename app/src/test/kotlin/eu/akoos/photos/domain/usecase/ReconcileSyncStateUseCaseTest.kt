package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReconcileSyncStateUseCaseTest {

    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var context: Context
    private lateinit var useCase: ReconcileSyncStateUseCase
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        localRepo = mockk()
        cloudRepo = mockk(relaxed = true)
        syncStateRepo = mockk(relaxed = true)

        // Mock DataStore extension on Context
        val mockPrefs = mockk<Preferences>()
        val mockDataStore = mockk<DataStore<Preferences>>()
        mockkStatic("eu.akoos.photos.data.preferences.SettingsDataStoreKt")
        context = mockk()
        every { context.settingsDataStore } returns mockDataStore
        every { mockDataStore.data } returns flowOf(mockPrefs)
        every { mockPrefs[SettingsKeys.SYNC_FOLDER_NAMES] } returns setOf("Camera")  // back up Camera folder
        every { mockPrefs[SettingsKeys.AUTO_BACKUP_NEW_FOLDERS] } returns false

        useCase = ReconcileSyncStateUseCase(localRepo, cloudRepo, syncStateRepo, context)
    }

    private fun localItem(uri: String, name: String = "photo.jpg", size: Long = 1024L) = LocalMediaItem(
        uri = uri,
        dateTaken = 1000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        bucketName = "Camera",
    )

    private fun cloudPhoto(linkId: String, name: String = "photo.jpg", size: Long = 1024L) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun syncState(uri: String, cloudId: String?, status: SyncStatus = SyncStatus.SYNCED) =
        SyncState(
            localUri = uri,
            cloudFileId = cloudId,
            localHash = "",
            cloudHash = null,
            status = status,
            lastSyncAttemptMs = 0L,
            lastSyncSuccessMs = null,
            backedUpAtMs = null,
            sizeBytes = 1024L,
        )

    @Test
    fun `local items without matching cloud get LOCAL_ONLY status`() = runTest {
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(localItem("uri://1")))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
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
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
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
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(local))
        // Cloud no longer has "old-link-id"
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(listOf(existingSync))
        coEvery { syncStateRepo.getByUri("uri://1") } returns existingSync

        useCase(userId).toList()

        coVerify {
            syncStateRepo.upsert(
                match { it.localUri == "uri://1" && it.status == SyncStatus.LOCAL_ONLY },
                userId,
            )
        }
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
        every { localRepo.observeLocalMedia() } returns flowOf(listOf(downloadedFile))
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
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
        // gallery, "Free up space" ran, etc.). Pairs with the previous test.
        val ghostSync = syncState("content://media/external/images/media/99", cloudId = "linkGhost",
            status = SyncStatus.SYNCED)
        val cloud = cloudPhoto("linkGhost", name = "ghost.jpg")
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList()) // no local files at all
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(listOf(cloud))
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

    @Test
    fun `reconcile emits progress events and finishes with running=false`() = runTest {
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val progress = useCase(userId).toList()

        assertTrue("Should start with running=true", progress.first().running)
        assertFalse("Should end with running=false", progress.last().running)
    }

    @Test
    fun `reconcile reads cloud state from DB without triggering a network refresh`() = runTest {
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        useCase(userId).toList()

        // ReconcileSyncStateUseCase only reads from DB via observeCloudPhotos —
        // network refresh (incremental or full) is the caller's responsibility.
        coVerify(exactly = 0) { cloudRepo.refreshCloudPhotosIncremental(userId) }
        coVerify(exactly = 0) { cloudRepo.refreshCloudPhotos(userId) }
    }
}
