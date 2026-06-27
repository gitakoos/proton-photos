package eu.akoos.photos.domain.usecase

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetGalleryItemsUseCaseTest {

    private lateinit var useCase: GetGalleryItemsUseCase

    @Before
    fun setUp() {
        // merge() is pure (it never touches the repos or scope for the inputs used here), so plain
        // mocks and a throwaway scope suffice. The tests call merge() directly rather than the flow
        // pipeline, which runs on Dispatchers.Default + sample() and cannot be driven by virtual time.
        useCase = GetGalleryItemsUseCase(
            mockk<LocalMediaRepository>(),
            mockk<DrivePhotoRepository>(),
            mockk<SyncStateRepository>(),
            CoroutineScope(Dispatchers.Unconfined),
        )
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
    fun `local only items when no cloud photos exist`() {
        val local = listOf(localItem("uri://1"), localItem("uri://2"))

        val result = useCase.merge(local, emptyList(), emptyList())

        assertEquals(2, result.size)
        assertTrue(result.all { it is GalleryItem.LocalOnly })
    }

    @Test
    fun `cloud only items when no local media exists`() {
        val cloud = listOf(cloudPhoto("link1"), cloudPhoto("link2"))

        val result = useCase.merge(emptyList(), cloud, emptyList())

        assertEquals(2, result.size)
        assertTrue(result.all { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `synced item when local matches cloud via cloudFileId`() {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = "link1"))

        val result = useCase.merge(local, cloud, states)

        assertEquals(1, result.size)
        val item = result.first()
        assertTrue("Expected Synced, got $item", item is GalleryItem.Synced)
        assertEquals("link1", (item as GalleryItem.Synced).cloud.linkId)
        assertEquals("uri://1", item.local.uri)
    }

    @Test
    fun `mixed items — synced, local only, and cloud only`() {
        val local = listOf(localItem("uri://synced"), localItem("uri://local"))
        val cloud = listOf(cloudPhoto("link-synced"), cloudPhoto("link-cloud-only"))
        val states = listOf(syncState("uri://synced", cloudId = "link-synced"))

        val result = useCase.merge(local, cloud, states)

        assertEquals(3, result.size)
        assertTrue(result.any { it is GalleryItem.Synced })
        assertTrue(result.any { it is GalleryItem.LocalOnly })
        assertTrue(result.any { it is GalleryItem.CloudOnly })
    }

    @Test
    fun `result is sorted by captureTime descending`() {
        val local = listOf(
            localItem("uri://old", dateTaken = 1_000L),
            localItem("uri://new", dateTaken = 9_000L),
        )

        val result = useCase.merge(local, emptyList(), emptyList())

        assertEquals("uri://new", (result[0] as GalleryItem.LocalOnly).local.uri)
        assertEquals("uri://old", (result[1] as GalleryItem.LocalOnly).local.uri)
    }

    @Test
    fun `cloud photo already matched is not duplicated as CloudOnly`() {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = "link1"))

        val result = useCase.merge(local, cloud, states)

        // Should be exactly 1 Synced item, NOT 1 Synced + 1 CloudOnly
        assertEquals(1, result.size)
        assertTrue(result.first() is GalleryItem.Synced)
    }

    @Test
    fun `sync state without cloudFileId keeps item as LocalOnly`() {
        val local = listOf(localItem("uri://1"))
        val cloud = listOf(cloudPhoto("link1"))
        val states = listOf(syncState("uri://1", cloudId = null, status = SyncStatus.LOCAL_ONLY))

        val result = useCase.merge(local, cloud, states)

        assertEquals(2, result.size)
        assertTrue(result.any { it is GalleryItem.LocalOnly })
        assertTrue(result.any { it is GalleryItem.CloudOnly })
    }
}
