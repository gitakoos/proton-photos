package me.proton.photos.domain.usecase

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.domain.entity.SyncState
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.LocalMediaRepository
import me.proton.photos.domain.repository.SyncStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetGalleryItemsUseCaseTest {

    private lateinit var localRepo: LocalMediaRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var useCase: GetGalleryItemsUseCase
    private val userId = UserId("test-user")

    @Before
    fun setUp() {
        localRepo = mockk()
        cloudRepo = mockk()
        syncStateRepo = mockk()
        useCase = GetGalleryItemsUseCase(localRepo, cloudRepo, syncStateRepo)
    }

    private fun localItem(uri: String, dateTaken: Long = 1000L) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = "photo_$uri.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    private fun cloudPhoto(linkId: String, captureTime: Long = 1L) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTime,
        displayName = "photo_$linkId.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
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
    fun `local only items when no cloud photos exist`() = runTest {
        val local = listOf(localItem("uri://1"), localItem("uri://2"))
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val result = useCase.invoke(userId).toList().last()

        assertEquals(2, result.size)
        assertTrue(result.all { it is GalleryItem.LocalOnly })
    }

    @Test
    fun `cloud only items when no local media exists`() = runTest {
        val cloud = listOf(cloudPhoto("link1"), cloudPhoto("link2"))
        every { localRepo.observeLocalMedia() } returns flowOf(emptyList())
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(cloud)
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val result = useCase.invoke(userId).toList().last()

        assertEquals(2, result.size)
        assertTrue(result.all { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `synced item when local matches cloud via cloudFileId`() = runTest {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = "link1"))
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(cloud)
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)

        val result = useCase.invoke(userId).toList().last()

        assertEquals(1, result.size)
        val item = result.first()
        assertTrue("Expected Synced, got $item", item is GalleryItem.Synced)
        assertEquals("link1", (item as GalleryItem.Synced).cloud.linkId)
        assertEquals("uri://1", item.local.uri)
    }

    @Test
    fun `mixed items — synced, local only, and cloud only`() = runTest {
        val local = listOf(localItem("uri://synced"), localItem("uri://local"))
        val cloud = listOf(cloudPhoto("link-synced"), cloudPhoto("link-cloud-only"))
        val states = listOf(syncState("uri://synced", cloudId = "link-synced"))
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(cloud)
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)

        val result = useCase.invoke(userId).toList().last()

        assertEquals(3, result.size)
        assertTrue(result.any { it is GalleryItem.Synced })
        assertTrue(result.any { it is GalleryItem.LocalOnly })
        assertTrue(result.any { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `result is sorted by captureTime descending`() = runTest {
        val local = listOf(
            localItem("uri://old", dateTaken = 1_000L),
            localItem("uri://new", dateTaken = 9_000L),
        )
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(emptyList())
        every { syncStateRepo.observeAll(userId) } returns flowOf(emptyList())

        val result = useCase.invoke(userId).toList().last()

        assertEquals("uri://new", (result[0] as GalleryItem.LocalOnly).local.uri)
        assertEquals("uri://old", (result[1] as GalleryItem.LocalOnly).local.uri)
    }

    @Test
    fun `cloud photo already matched is not duplicated as CloudOnly`() = runTest {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = "link1"))
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(cloud)
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)

        val result = useCase.invoke(userId).toList().last()

        // Should be exactly 1 Synced item, NOT 1 Synced + 1 CloudOnly
        assertEquals(1, result.size)
        assertTrue(result.first() is GalleryItem.Synced)
    }

    @Test
    fun `sync state without cloudFileId keeps item as LocalOnly`() = runTest {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY))
        every { localRepo.observeLocalMedia() } returns flowOf(local)
        every { cloudRepo.observeCloudPhotos(userId) } returns flowOf(cloud)
        every { syncStateRepo.observeAll(userId) } returns flowOf(states)

        val result = useCase.invoke(userId).toList().last()

        assertEquals(2, result.size)
        assertTrue(result.any { it is GalleryItem.LocalOnly })
        assertTrue(result.any { it is GalleryItem.CloudOnly })
    }
}
