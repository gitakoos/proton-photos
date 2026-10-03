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

package eu.akoos.photos.presentation.folders

import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.presentation.common.DeleteRowKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a folder delete acts on and what its confirmation may say.
 *
 * Four rules carry it. The delete takes the device copies and never a Drive copy. Which photo is
 * backed up follows its sync record's pairing, never a match by name. The one row is neutral only
 * when every photo keeps a Drive copy. And no row promises a trash the device does not have: Android
 * 10 and older delete for good. Plain JVM assertions over hand-built folders.
 */
class DeviceFolderDeletionTest {

    private val android11 = 30
    private val android10 = 29

    private fun local(uri: String, size: Long = 1_000L) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = size,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_000L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun localOnly(uri: String, size: Long = 1_000L) = GalleryItem.LocalOnly(local(uri, size))

    private fun synced(uri: String, linkId: String, size: Long = 1_000L) =
        GalleryItem.Synced(cloud(linkId), local(uri, size))

    private val deviceOnlyFolder = listOf(localOnly("uri://a"), localOnly("uri://b"))
    private val mixedFolder = listOf(localOnly("uri://a"), synced("uri://b", "link-b"))
    private val backedUpFolder = listOf(synced("uri://a", "link-a"), synced("uri://b", "link-b"))

    @Test
    fun `a folder with nothing backed up is one red row that only takes the device copies`() {
        val choice = DeviceFolderDeletion.choice(deviceOnlyFolder, isSignedIn = true, sdkInt = android11)!!
        assertEquals(DeleteRowKind.TrashLocal, choice.row.kind)
        assertTrue(choice.row.destructive)
        assertTrue(choice.row.freeUpSpace)
        assertFalse(choice.row.deleteFromCloud)
    }

    @Test
    fun `a fully backed-up folder is the neutral row that keeps the Drive backup`() {
        val choice = DeviceFolderDeletion.choice(backedUpFolder, isSignedIn = true, sdkInt = android11)!!
        assertEquals(DeleteRowKind.RemoveDevice, choice.row.kind)
        assertFalse("every photo keeps a Drive copy, so it is not the red row", choice.row.destructive)
        assertFalse(choice.row.deleteFromCloud)
    }

    @Test
    fun `a device-only photo among backed-up ones keeps the row red`() {
        val choice = DeviceFolderDeletion.choice(mixedFolder, isSignedIn = true, sdkInt = android11)!!
        assertEquals(DeleteRowKind.TrashLocal, choice.row.kind)
        assertTrue(choice.row.destructive)
        assertFalse(choice.row.deleteFromCloud)
    }

    @Test
    fun `without an account the row is the plain device delete`() {
        listOf(android10, android11).forEach { sdkInt ->
            val choice = DeviceFolderDeletion.choice(backedUpFolder, isSignedIn = false, sdkInt = sdkInt)!!
            assertEquals(DeleteRowKind.TrashLocal, choice.row.kind)
            assertFalse(choice.row.deleteFromCloud)
        }
    }

    @Test
    fun `a folder delete never takes a Drive copy`() {
        for (items in listOf(deviceOnlyFolder, mixedFolder, backedUpFolder)) {
            for (signedIn in listOf(true, false)) {
                for (sdkInt in listOf(android10, android11)) {
                    val choice = DeviceFolderDeletion.choice(items, signedIn, sdkInt)!!
                    assertTrue(choice.row.freeUpSpace)
                    assertFalse(choice.row.deleteFromCloud)
                    assertTrue(
                        "a folder delete offers no row that touches Drive",
                        choice.row.kind == DeleteRowKind.TrashLocal || choice.row.kind == DeleteRowKind.RemoveDevice,
                    )
                    // What the delete then acts on: device uris only, no Drive link.
                    val targets = DeletePhotoUseCase.computeDeleteTargets(items, freeUpSpace = true, deleteFromCloud = false)
                    assertTrue(targets.cloudLinkIds.isEmpty())
                    assertEquals(items.size, targets.localUriStrings.size)
                }
            }
        }
    }

    @Test
    fun `Android 11 and later word the row as every other delete sheet does`() {
        val deviceOnly = DeviceFolderDeletion.choice(deviceOnlyFolder, isSignedIn = true, sdkInt = android11)!!
        assertEquals(R.string.delete_multi_move_trash, deviceOnly.titleRes)
        assertEquals(R.string.delete_multi_move_trash_desc, deviceOnly.descRes)

        val backedUp = DeviceFolderDeletion.choice(backedUpFolder, isSignedIn = true, sdkInt = android11)!!
        assertEquals(R.string.delete_multi_remove_device, backedUp.titleRes)
        assertEquals(R.string.delete_multi_remove_device_desc, backedUp.descRes)
    }

    @Test
    fun `Android 10 and older never promise a trash`() {
        val deviceOnly = DeviceFolderDeletion.choice(mixedFolder, isSignedIn = true, sdkInt = android10)!!
        assertEquals(R.string.delete_button_permanently, deviceOnly.titleRes)
        assertNull(deviceOnly.descRes)

        // The Drive backup is kept on every version, so that row reads the same.
        val backedUp = DeviceFolderDeletion.choice(backedUpFolder, isSignedIn = true, sdkInt = android10)!!
        assertEquals(R.string.delete_multi_remove_device, backedUp.titleRes)
        assertEquals(R.string.delete_multi_remove_device_desc, backedUp.descRes)

        val trashWording = setOf(R.string.delete_multi_move_trash, R.string.delete_multi_move_trash_desc)
        listOf(deviceOnly, backedUp)
            .flatMap { listOfNotNull(it.titleRes, it.descRes) }
            .forEach { assertFalse(it in trashWording) }
    }

    @Test
    fun `the device trash arrives with Android 11`() {
        assertTrue(DeviceFolderDeletion.hasDeviceTrash(30))
        assertTrue(DeviceFolderDeletion.hasDeviceTrash(35))
        assertFalse(DeviceFolderDeletion.hasDeviceTrash(29))
        assertFalse(DeviceFolderDeletion.hasDeviceTrash(26))
    }

    @Test
    fun `the row frees the whole folder's device bytes`() {
        val folder = listOf(localOnly("uri://a", size = 1_000L), synced("uri://b", "link-b", size = 2_500L))
        assertEquals(3_500L, DeviceFolderDeletion.choice(folder, isSignedIn = true, sdkInt = android11)!!.row.freedBytes)
    }

    @Test
    fun `an empty folder has nothing to confirm`() {
        assertNull(DeviceFolderDeletion.choice(emptyList(), isSignedIn = true, sdkInt = android11))
    }

    @Test
    fun `a row with a recorded pairing is typed as backed up and every other as device-only`() {
        val rows = listOf(local("uri://a"), local("uri://b"), local("uri://c"))
        val copy = cloud("link-b")
        val items = DeviceFolderDeletion.items(rows, mapOf("uri://b" to "link-b"), mapOf("link-b" to copy))
        assertEquals(
            listOf(GalleryItem.LocalOnly(rows[0]), GalleryItem.Synced(copy, rows[1]), GalleryItem.LocalOnly(rows[2])),
            items,
        )
    }

    @Test
    fun `a pairing the cloud listing has not caught up with still types the row as backed up`() {
        val row = local("uri://a")
        val item = DeviceFolderDeletion.items(listOf(row), mapOf("uri://a" to "link-new"), emptyMap()).single()
        assertTrue(item is GalleryItem.Synced)
        assertEquals("link-new", (item as GalleryItem.Synced).cloud.linkId)
        assertEquals(row, item.local)
        // So its row becomes cloud-only, never a record of a photo that was never backed up.
        assertEquals(
            eu.akoos.photos.domain.entity.SyncStatus.CLOUD_ONLY,
            DeletePhotoUseCase.postDeleteSyncStatus(item, freeUpSpace = true, hide = false),
        )
    }

    @Test
    fun `every device row is kept, so nothing is left behind under the card`() {
        val rows = listOf(local("uri://a"), local("uri://b"))
        val items = DeviceFolderDeletion.items(rows, mapOf("uri://elsewhere" to "link-x"), mapOf("link-x" to cloud("link-x")))
        assertEquals(rows.size, items.size)
        assertTrue(items.all { it is GalleryItem.LocalOnly })
    }

    @Test
    fun `the sync record's pairing decides, never a same-named Drive photo`() = runTest {
        val userId = UserId("user1")
        val rows = listOf(local("uri://a"), local("uri://b"))
        val accountManager = mockk<AccountManager> { every { getPrimaryUserId() } returns flowOf(userId) }
        val localRepo = mockk<LocalMediaRepository> { coEvery { queryByBucket("Camera") } returns rows }
        val syncRepo = mockk<SyncStateRepository> {
            coEvery { cloudPairedLinkIds(userId, any()) } returns mapOf("uri://b" to "link-b")
        }
        // The listing holds a photo named exactly like uri://a's, which a name match would pair it to.
        val driveRepo = mockk<DrivePhotoRepository> {
            every { observePhotosByLinkIds(any()) } returns flowOf(listOf(cloud("link-b"), cloud("link-namesake")))
        }
        val controller = DeviceFolderDeleteController(accountManager, localRepo, syncRepo, driveRepo)

        controller.ask(this, "Camera")
        advanceUntilIdle()

        val items = controller.request.value!!.items!!
        assertEquals(GalleryItem.LocalOnly(rows[0]), items[0])
        assertEquals(GalleryItem.Synced(cloud("link-b"), rows[1]), items[1])
        coVerify { syncRepo.cloudPairedLinkIds(userId, listOf("uri://a", "uri://b")) }
        coVerify { driveRepo.observePhotosByLinkIds(listOf("link-b")) }
    }

    @Test
    fun `without an account every row is device-only and no pairing is read`() = runTest {
        val rows = listOf(local("uri://a"))
        val accountManager = mockk<AccountManager> { every { getPrimaryUserId() } returns flowOf(null) }
        val localRepo = mockk<LocalMediaRepository> { coEvery { queryByBucket("Camera") } returns rows }
        val syncRepo = mockk<SyncStateRepository>()
        val controller = DeviceFolderDeleteController(accountManager, localRepo, syncRepo, mockk())

        controller.ask(this, "Camera")
        advanceUntilIdle()

        assertEquals(listOf(GalleryItem.LocalOnly(rows[0])), controller.request.value!!.items)
        coVerify(exactly = 0) { syncRepo.cloudPairedLinkIds(any(), any()) }
    }
}
