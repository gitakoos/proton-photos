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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.repository.drive.CloudTrashOutcome
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the device side of [DeletePhotoUseCase] against a stand-in for MediaStore: the direct delete
 * of Android 10 and older, checked file by file, and the Android 11+ system request, asked in chunks
 * one consent at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletePhotoUseCaseDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val userId = UserId("user1")

    /** MediaStore as Android 10 ([asksConsent] false) or Android 11+ answers it. [refused] device
     *  files stay on a direct delete; a request from the [failFromRequest]th one on cannot be built. */
    private inner class FakeDevice(
        override val asksConsent: Boolean,
        private val refused: Set<String> = emptySet(),
        private val failFromRequest: Int = Int.MAX_VALUE,
    ) : DeviceMediaDeletes {
        val requests = mutableListOf<List<Uri>>()
        val deleted = mutableListOf<String>()

        override fun request(uris: List<Uri>, permanent: Boolean): PendingIntent {
            if (requests.size + 1 >= failFromRequest) throw IllegalStateException("request refused")
            requests += uris
            return PendingIntent.getActivity(
                context, requests.size, Intent("delete-${requests.size}"), PendingIntent.FLAG_IMMUTABLE,
            )
        }

        override fun delete(uri: Uri): Boolean {
            if (uri.toString() in refused) return false
            deleted += uri.toString()
            return true
        }
    }

    private val syncRepo = mockk<SyncStateRepository>(relaxed = true)
    private val cloudRepo = mockk<DrivePhotoRepository> {
        coEvery { deleteFiles(any(), any()) } answers {
            CloudTrashOutcome(trashedLinkIds = secondArg<List<String>>().toSet(), failedLinkIds = emptySet())
        }
    }

    private fun local(uri: String) = LocalMediaItem(
        uri = uri, dateTaken = 1_000L, displayName = "p.jpg", mimeType = "image/jpeg", sizeBytes = 10L, bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId, shareId = "s", volumeId = "v", captureTime = 1L, displayName = "p.jpg",
        mimeType = "image/jpeg", sizeBytes = 10L, thumbnailUrl = null, revisionId = "r",
    )

    private fun localOnly(uri: String) = GalleryItem.LocalOnly(local(uri))
    private fun synced(uri: String, linkId: String) = GalleryItem.Synced(cloud(linkId), local(uri))

    // ── Android 10 and older: one direct delete per file ─────────────────────────────

    @Test
    fun `a refused device file keeps its row and is reported back`() = runTest {
        val refusedItem = synced("content://media/3", "link-3")
        val items = listOf(localOnly("content://media/1"), synced("content://media/2", "link-2"), refusedItem)
        val useCase = DeletePhotoUseCase(syncRepo, cloudRepo, FakeDevice(asksConsent = false, refused = setOf("content://media/3")))

        val result = useCase(userId, items, freeUpSpace = true, deleteFromCloud = false)

        assertEquals(DeletePhotoUseCase.Result.Success(keptOnDevice = listOf(refusedItem)), result)
        coVerify(exactly = 1) { syncRepo.updateStatusAndDeleteLocal("content://media/1", SyncStatus.LOCAL_ONLY) }
        coVerify(exactly = 1) { syncRepo.updateStatusAndDeleteLocal("content://media/2", SyncStatus.CLOUD_ONLY) }
        coVerify(exactly = 0) { syncRepo.updateStatusAndDeleteLocal("content://media/3", any()) }
        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }
    }

    @Test
    fun `a photo whose device file was refused keeps its Drive copy too`() = runTest {
        val items = listOf(
            synced("content://media/2", "link-2"),
            synced("content://media/3", "link-3"),
            GalleryItem.CloudOnly(cloud("link-4")),
        )
        val useCase = DeletePhotoUseCase(syncRepo, cloudRepo, FakeDevice(asksConsent = false, refused = setOf("content://media/3")))

        val result = useCase(userId, items, freeUpSpace = true, deleteFromCloud = true)

        assertEquals(listOf(items[1]), (result as DeletePhotoUseCase.Result.Success).keptOnDevice)
        coVerify(exactly = 1) { cloudRepo.deleteFiles(userId, listOf("link-2", "link-4")) }
        coVerify(exactly = 0) { syncRepo.updateStatusAndDeleteLocal("content://media/3", any()) }
    }

    @Test
    fun `every file deleted reports nothing kept and settles every row`() = runTest {
        val items = listOf(localOnly("content://media/1"), synced("content://media/2", "link-2"))
        val device = FakeDevice(asksConsent = false)
        val result = DeletePhotoUseCase(syncRepo, cloudRepo, device)(userId, items, freeUpSpace = true, deleteFromCloud = false)

        assertEquals(DeletePhotoUseCase.Result.Success(), result)
        assertEquals(listOf("content://media/1", "content://media/2"), device.deleted)
        coVerify(exactly = 2) { syncRepo.updateStatusAndDeleteLocal(any(), any()) }
    }

    // ── Android 11+: system requests, one chunk at a time ────────────────────────────

    @Test
    fun `a large delete asks in chunks and settles each chunk as it is granted`() = runTest {
        val onDevice = (1..2_500).map { localOnly("content://media/$it") }
        val cloudOnly = GalleryItem.CloudOnly(cloud("link-cloud"))
        val device = FakeDevice(asksConsent = true)
        val useCase = DeletePhotoUseCase(syncRepo, cloudRepo, device)

        val first = useCase(userId, onDevice + cloudOnly, freeUpSpace = true, deleteFromCloud = true)
            as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        assertEquals(listOf(1_000), device.requests.map { it.size })
        // The photo with no device file rides with the first chunk, so its Drive half waits on that dialog.
        assertEquals(listOf("link-cloud"), first.cloudLinkIds)
        assertEquals(1_001, first.itemsBeingDeleted.size)
        assertEquals(1_500, first.remaining.size)
        assertTrue(first.done.isEmpty())
        coVerify(exactly = 0) { syncRepo.updateStatusAndDeleteLocal(any(), any()) }
        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }

        val second = useCase.completeAfterPermissionGranted(userId, first)
            as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        assertEquals(listOf(1_000, 1_000), device.requests.map { it.size })
        assertEquals(1_001, second.done.size)
        assertEquals(500, second.remaining.size)
        assertTrue(second.cloudLinkIds.isEmpty())
        assertTrue("the whole delete still takes the Drive copies", second.deleteFromCloud)
        assertNotEquals(first.pendingIntent, second.pendingIntent)
        coVerify(exactly = 1_000) { syncRepo.updateStatusAndDeleteLocal(any(), SyncStatus.LOCAL_ONLY) }
        coVerify(exactly = 1) { cloudRepo.deleteFiles(userId, listOf("link-cloud")) }

        val third = useCase.completeAfterPermissionGranted(userId, second)
            as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        assertEquals(listOf(1_000, 1_000, 500), device.requests.map { it.size })
        assertTrue(third.remaining.isEmpty())
        assertEquals((onDevice + cloudOnly).toSet(), third.confirmedItems.toSet())
        assertEquals(onDevice.size + 1, third.confirmedItems.size)

        val last = useCase.completeAfterPermissionGranted(userId, third)
        assertEquals(DeletePhotoUseCase.Result.Success(), last)
        assertEquals(3, device.requests.size)
        coVerify(exactly = 2_500) { syncRepo.updateStatusAndDeleteLocal(any(), SyncStatus.LOCAL_ONLY) }
    }

    @Test
    fun `a confirmed removal leaves the device list at once, and only the files it removed`() = runTest {
        val localRepo = mockk<eu.akoos.photos.domain.repository.LocalMediaRepository>(relaxed = true)
        val useCase = DeletePhotoUseCase(syncRepo, cloudRepo, FakeDevice(asksConsent = true), localRepo)
        val items = listOf(synced("content://media/1", "link-1"), GalleryItem.CloudOnly(cloud("link-2")))

        val asked = useCase(userId, items, freeUpSpace = true, deleteFromCloud = false)
            as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        coVerify(exactly = 0) { localRepo.forgetRemoved(any()) }
        useCase.completeAfterPermissionGranted(userId, asked)

        coVerify(exactly = 1) { localRepo.forgetRemoved(listOf("content://media/1")) }
    }

    @Test
    fun `a direct delete forgets only the files that went`() = runTest {
        val localRepo = mockk<eu.akoos.photos.domain.repository.LocalMediaRepository>(relaxed = true)
        val device = FakeDevice(asksConsent = false, refused = setOf("content://media/2"))
        val items = listOf(localOnly("content://media/1"), localOnly("content://media/2"))

        DeletePhotoUseCase(syncRepo, cloudRepo, device, localRepo)(userId, items, freeUpSpace = true, deleteFromCloud = false)

        coVerify(exactly = 1) { localRepo.forgetRemoved(setOf("content://media/1")) }
    }

    @Test
    fun `a delete that fits one request asks once`() = runTest {
        val device = FakeDevice(asksConsent = true)
        val useCase = DeletePhotoUseCase(syncRepo, cloudRepo, device)
        val items = (1..DEVICE_REQUEST_CHUNK).map { localOnly("content://media/$it") }

        val only = useCase(userId, items, freeUpSpace = true, deleteFromCloud = false)
            as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        assertTrue(only.remaining.isEmpty())
        assertEquals(DeletePhotoUseCase.Result.Success(), useCase.completeAfterPermissionGranted(userId, only))
        assertEquals(1, device.requests.size)
    }

    @Test
    fun `a next request that cannot be built ends the delete and names what it left`() = runTest {
        val items = (1..1_200).map { localOnly("content://media/$it") }
        val device = FakeDevice(asksConsent = true, failFromRequest = 2)
        val useCase = DeletePhotoUseCase(syncRepo, cloudRepo, device)

        val first = useCase(userId, items, freeUpSpace = true, deleteFromCloud = false)
            as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        val result = useCase.completeAfterPermissionGranted(userId, first)

        assertEquals(DeletePhotoUseCase.Result.Success(keptOnDevice = items.drop(1_000)), result)
        coVerify(exactly = 1_000) { syncRepo.updateStatusAndDeleteLocal(any(), any()) }
    }

    // ── The chunk plan itself ────────────────────────────────────────────────────────

    @Test
    fun `chunks hold at most the limit of device files, the rest riding with the first`() {
        val onDevice = (1..5).map { localOnly("content://media/$it") }
        val cloudOnly = GalleryItem.CloudOnly(cloud("link-c"))
        val chunks = DeletePhotoUseCase.requestChunks(listOf(cloudOnly) + onDevice, freeUpSpace = true, maxUris = 2)

        assertEquals(listOf(3, 2, 1), chunks.map { it.size })
        assertEquals(cloudOnly, chunks.first().first())
        assertEquals(onDevice, chunks.flatten().drop(1))
    }

    @Test
    fun `a delete that keeps the device files is a single chunk`() {
        val items = (1..5).map { synced("content://media/$it", "link-$it") }
        assertEquals(listOf(items), DeletePhotoUseCase.requestChunks(items, freeUpSpace = false, maxUris = 2))
    }

    // ── The selection as it stands when the delete runs ──────────────────────────────

    private fun row(uri: String, linkId: String?, basis: Int = LinkBasis.PROVEN, status: SyncStatus = SyncStatus.SYNCED) = SyncState(
        localUri = uri, cloudFileId = linkId, localHash = "", cloudHash = null, status = status,
        lastSyncAttemptMs = 0L, lastSyncSuccessMs = null, backedUpAtMs = 1L, sizeBytes = 10L, linkBasis = basis,
    )

    @Test
    fun `a device photo whose upload finished after it was picked loses its Drive copy too`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, listOf("content://media/1")) } returns listOf(row("content://media/1", "link-1"))
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
        }
        coEvery { cloudRepo.observePhotosByLinkIds(listOf("link-1")) } returns flowOf(listOf(cloud("link-1")))

        val result = DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = true,
        )

        coVerify(exactly = 1) { cloudRepo.deleteFiles(userId, listOf("link-1")) }
        coVerify(exactly = 1) { repo.updateStatusAndDeleteLocal("content://media/1", SyncStatus.CLOUD_ONLY) }
        assertEquals(
            "the undo is built from the photo as it was deleted",
            listOf(synced("content://media/1", "link-1")),
            (result as DeletePhotoUseCase.Result.Success).landed(listOf(localOnly("content://media/1"))),
        )
    }

    @Test
    fun `a device photo linked by name alone keeps the Drive photo of that name`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, listOf("content://media/1")) } returns
                listOf(row("content://media/1", "link-1", basis = LinkBasis.NAME))
        }

        DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = true,
        )

        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }
    }

    @Test
    fun `a Drive photo saved to the device after it was picked loses its device copy too`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
            coEvery { getByUri("content://media/1") } returns row("content://media/1", "link-1")
        }
        val localRepo = mockk<LocalMediaRepository>(relaxed = true) {
            coEvery { queryByUri("content://media/1") } returns local("content://media/1")
        }
        val device = FakeDevice(asksConsent = true)

        val result = DeletePhotoUseCase(repo, cloudRepo, device, localRepo)(
            userId, listOf(GalleryItem.CloudOnly(cloud("link-1"))), freeUpSpace = true, deleteFromCloud = true,
        ) as DeletePhotoUseCase.Result.NeedsMediaWritePermission

        assertEquals(listOf(listOf(Uri.parse("content://media/1"))), device.requests)
        assertEquals(listOf("link-1"), result.cloudLinkIds)
        assertEquals(listOf(synced("content://media/1", "link-1")), result.itemsBeingDeleted)
    }

    @Test
    fun `a Drive photo whose saved file is gone from the device stays a Drive delete`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
            coEvery { getByUri("content://media/1") } returns row("content://media/1", "link-1")
        }
        val localRepo = mockk<LocalMediaRepository>(relaxed = true) {
            coEvery { queryByUri("content://media/1") } returns null
        }
        val device = FakeDevice(asksConsent = true)

        val result = DeletePhotoUseCase(repo, cloudRepo, device, localRepo)(
            userId, listOf(GalleryItem.CloudOnly(cloud("link-1"))), freeUpSpace = true, deleteFromCloud = true,
        )

        assertTrue(device.requests.isEmpty())
        assertEquals(DeletePhotoUseCase.Result.Success(), result)
        coVerify(exactly = 1) { cloudRepo.deleteFiles(userId, listOf("link-1")) }
    }

    @Test
    fun `a counterpart the selection already holds is not named twice`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, listOf("content://media/1")) } returns listOf(row("content://media/1", "link-1"))
            coEvery { syncedUrisByCloudId(userId, any()) } returns mapOf("link-1" to listOf("content://media/1"))
            coEvery { getByUri("content://media/1") } returns row("content://media/1", "link-1")
        }
        val picked = listOf(localOnly("content://media/1"), GalleryItem.CloudOnly(cloud("link-1")))
        val useCase = DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))

        assertEquals(picked, useCase.withCurrentPairs(userId, picked))
    }

    @Test
    fun `a device photo whose Drive photo another device file holds keeps that Drive photo`() = runTest {
        // Two device files paired to one Drive photo: the gallery pairs it with one and shows the other as
        // a device photo. Deleting that second file must not take the Drive copy the first one is paired to.
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, listOf("content://media/2")) } returns listOf(row("content://media/2", "link-1"))
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns
                mapOf("link-1" to listOf("content://media/1", "content://media/2"))
        }
        coEvery { cloudRepo.observePhotosByLinkIds(listOf("link-1")) } returns flowOf(listOf(cloud("link-1")))

        DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/2")), freeUpSpace = true, deleteFromCloud = true,
        )

        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }
    }

    @Test
    fun `a Drive photo two device files hold is deleted on Drive alone`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns
                mapOf("link-1" to listOf("content://media/1", "content://media/2"))
        }
        val useCase = DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false), mockk(relaxed = true))
        val picked = listOf(GalleryItem.CloudOnly(cloud("link-1")))

        assertEquals(picked, useCase.withCurrentPairs(userId, picked))
    }

    // The delete sheet offers a device-only selection one row, "move to trash" (free up space, keep Drive
    // copies), and a Drive-only selection one row too (trash on Drive). Such a photo goes as a whole.

    @Test
    fun `a device photo trashed from a device-only selection loses the Drive copy its upload made since`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, listOf("content://media/1")) } returns listOf(row("content://media/1", "link-1"))
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
        }
        coEvery { cloudRepo.observePhotosByLinkIds(listOf("link-1")) } returns flowOf(listOf(cloud("link-1")))

        val result = DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = false,
        ) as DeletePhotoUseCase.Result.Success

        coVerify(exactly = 1) { cloudRepo.deleteFiles(userId, listOf("link-1")) }
        assertEquals("the undo restores the Drive copy too", true, result.ranDeleteFromCloud)
        assertEquals(null, result.ranFreeUpSpace)
    }

    @Test
    fun `a Drive photo trashed from a Drive-only selection loses the device copy saved since`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
            coEvery { getByUri("content://media/1") } returns row("content://media/1", "link-1")
        }
        val localRepo = mockk<LocalMediaRepository>(relaxed = true) {
            coEvery { queryByUri("content://media/1") } returns local("content://media/1")
        }
        val device = FakeDevice(asksConsent = true)

        val result = DeletePhotoUseCase(repo, cloudRepo, device, localRepo)(
            userId, listOf(GalleryItem.CloudOnly(cloud("link-1"))), freeUpSpace = false, deleteFromCloud = true,
        ) as DeletePhotoUseCase.Result.NeedsMediaWritePermission

        assertEquals(listOf(listOf(Uri.parse("content://media/1"))), device.requests)
        assertEquals(listOf("link-1"), result.cloudLinkIds)
        assertTrue("the completion clears the device row", result.freeUpSpace)
    }

    @Test
    fun `removing device copies from a mixed selection keeps the Drive copy a device photo gained`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, listOf("content://media/1")) } returns listOf(row("content://media/1", "link-1"))
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
        }
        coEvery { cloudRepo.observePhotosByLinkIds(listOf("link-1")) } returns flowOf(listOf(cloud("link-1")))

        val result = DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/1"), synced("content://media/2", "link-2")),
            freeUpSpace = true, deleteFromCloud = false,
        ) as DeletePhotoUseCase.Result.Success

        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }
        assertEquals(null, result.ranDeleteFromCloud)
    }

    @Test
    fun `a device photo still uploading when it is trashed has its upload stopped`() = runTest {
        val cancelled = mutableListOf<String>()
        val uploading = row("content://media/1", null, status = SyncStatus.UPLOADING)
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, any()) } returns listOf(uploading)
            coEvery { getByUri("content://media/1") } returns uploading
        }

        DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false), cancelUpload = { cancelled += it })(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = false,
        )

        assertEquals(listOf("content://media/1"), cancelled)
        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }
        coVerify { repo.updateStatusAndDeleteLocal("content://media/1", SyncStatus.LOCAL_ONLY) }
    }

    @Test
    fun `an upload that finished while the trash dialog was open loses its Drive copy on confirm`() = runTest {
        val cancelled = mutableListOf<String>()
        val uploading = row("content://media/1", null, status = SyncStatus.UPLOADING)
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, any()) } returns listOf(uploading)
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns mapOf("link-1" to listOf("content://media/1"))
        }
        val useCase = DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = true), cancelUpload = { cancelled += it })
        val pending = useCase(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = false,
        ) as DeletePhotoUseCase.Result.NeedsMediaWritePermission
        assertTrue(pending.cloudLinkIds.isEmpty())
        // The upload commits before the user confirms.
        coEvery { repo.getByUri("content://media/1") } returns row("content://media/1", "link-1")

        useCase.completeAfterPermissionGranted(userId, pending)

        coVerify(exactly = 1) { cloudRepo.deleteFiles(userId, listOf("link-1")) }
        coVerify { repo.updateStatusAndDeleteLocal("content://media/1", SyncStatus.CLOUD_ONLY) }
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `an upload that finished meanwhile keeps its Drive copy when a second device file holds it`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, any()) } returns emptyList()
            coEvery { getByUri("content://media/1") } returns row("content://media/1", "link-1")
            coEvery { syncedUrisByCloudId(userId, listOf("link-1")) } returns
                mapOf("link-1" to listOf("content://media/1", "content://media/2"))
        }

        DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = false,
        )

        coVerify(exactly = 0) { cloudRepo.deleteFiles(any(), any()) }
    }

    @Test
    fun `a hide keeps the selection as it was picked`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true) {
            coEvery { getOwnByUris(userId, any()) } returns listOf(row("content://media/1", "link-1"))
        }
        DeletePhotoUseCase(repo, cloudRepo, FakeDevice(asksConsent = false))(
            userId, listOf(localOnly("content://media/1")), freeUpSpace = true, deleteFromCloud = false, hide = true,
        )
        coVerify(exactly = 0) { repo.getOwnByUris(any(), any()) }
    }
}
