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
import eu.akoos.photos.presentation.common.DeleteRowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a folder delete acts on and what its confirmation may say.
 *
 * Three rules carry it. Every choice takes the device copies, since that is what deleting a folder
 * means. The Drive copies are offered only with an account and a backed-up photo to act on. And no row
 * promises a trash the device does not have: Android 10 and older delete for good, so there the rows
 * leave the trash wording of Android 11 and later out. Plain JVM assertions over hand-built folders.
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
    private val backedUpFolder = listOf(localOnly("uri://a"), synced("uri://b", "link-b"))

    @Test
    fun `a folder with nothing backed up is one red row that only takes the device copies`() {
        val choice = DeviceFolderDeletion.choices(deviceOnlyFolder, isSignedIn = true, sdkInt = android11).single()
        assertEquals(DeleteRowKind.TrashLocal, choice.row.kind)
        assertTrue(choice.row.destructive)
        assertTrue(choice.row.freeUpSpace)
        assertFalse(choice.row.deleteFromCloud)
    }

    @Test
    fun `a backed-up photo adds the Drive copies as a second, red choice`() {
        val choices = DeviceFolderDeletion.choices(backedUpFolder, isSignedIn = true, sdkInt = android11)
        assertEquals(
            listOf(DeleteRowKind.RemoveDevice, DeleteRowKind.RemoveEverywhere),
            choices.map { it.row.kind },
        )
        val keepDrive = choices.first()
        val everywhere = choices.last()
        assertFalse(keepDrive.row.deleteFromCloud)
        assertFalse("keeping the Drive copy leaves a copy, so it is not the red row", keepDrive.row.destructive)
        assertTrue(everywhere.row.deleteFromCloud)
        assertTrue(everywhere.row.destructive)
    }

    @Test
    fun `without an account the Drive copies are never offered`() {
        listOf(android10, android11).forEach { sdkInt ->
            val choice = DeviceFolderDeletion.choices(backedUpFolder, isSignedIn = false, sdkInt = sdkInt).single()
            assertEquals(DeleteRowKind.TrashLocal, choice.row.kind)
            assertFalse(choice.row.deleteFromCloud)
        }
    }

    @Test
    fun `every choice takes the device copies and none keeps them`() {
        for (items in listOf(deviceOnlyFolder, backedUpFolder)) {
            for (signedIn in listOf(true, false)) {
                for (sdkInt in listOf(android10, android11)) {
                    val choices = DeviceFolderDeletion.choices(items, signedIn, sdkInt)
                    assertTrue(choices.isNotEmpty())
                    choices.forEach { choice ->
                        assertTrue(choice.row.freeUpSpace)
                        assertTrue(
                            "a folder delete never offers the cloud-only removal",
                            choice.row.kind != DeleteRowKind.RemoveCloud,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `Android 11 and later word the rows as every other delete sheet does`() {
        val single = DeviceFolderDeletion.choices(deviceOnlyFolder, isSignedIn = true, sdkInt = android11).single()
        assertEquals(R.string.delete_multi_move_trash, single.titleRes)
        assertEquals(R.string.delete_multi_move_trash_desc, single.descRes)

        val (keepDrive, everywhere) = DeviceFolderDeletion.choices(backedUpFolder, isSignedIn = true, sdkInt = android11)
        assertEquals(R.string.delete_multi_remove_device, keepDrive.titleRes)
        assertEquals(R.string.delete_multi_remove_device_desc, keepDrive.descRes)
        assertEquals(R.string.delete_multi_move_trash_everywhere, everywhere.titleRes)
        assertEquals(R.string.delete_multi_move_trash_everywhere_desc, everywhere.descRes)
    }

    @Test
    fun `Android 10 and older never promise a trash`() {
        val single = DeviceFolderDeletion.choices(deviceOnlyFolder, isSignedIn = true, sdkInt = android10).single()
        assertEquals(R.string.delete_button_permanently, single.titleRes)
        assertNull(single.descRes)

        val (keepDrive, everywhere) = DeviceFolderDeletion.choices(backedUpFolder, isSignedIn = true, sdkInt = android10)
        // The Drive backup is kept on every version, so that row reads the same.
        assertEquals(R.string.delete_multi_remove_device, keepDrive.titleRes)
        assertEquals(R.string.delete_multi_remove_device_desc, keepDrive.descRes)
        assertEquals(R.string.device_folder_delete_everywhere, everywhere.titleRes)
        assertEquals(R.string.device_folder_delete_everywhere_desc, everywhere.descRes)

        val trashWording = setOf(
            R.string.delete_multi_move_trash,
            R.string.delete_multi_move_trash_desc,
            R.string.delete_multi_move_trash_everywhere,
            R.string.delete_multi_move_trash_everywhere_desc,
        )
        listOf(single, keepDrive, everywhere)
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
    fun `every choice frees the whole folder's device bytes`() {
        val folder = listOf(localOnly("uri://a", size = 1_000L), synced("uri://b", "link-b", size = 2_500L))
        DeviceFolderDeletion.choices(folder, isSignedIn = true, sdkInt = android11).forEach {
            assertEquals(3_500L, it.row.freedBytes)
        }
    }

    @Test
    fun `an empty folder has nothing to confirm`() {
        assertTrue(DeviceFolderDeletion.choices(emptyList(), isSignedIn = true, sdkInt = android11).isEmpty())
    }

    @Test
    fun `a row with a known Drive copy is typed as backed up and every other as device-only`() {
        val rows = listOf(local("uri://a"), local("uri://b"), local("uri://c"))
        val copy = cloud("link-b")
        val items = DeviceFolderDeletion.items(rows, mapOf("uri://b" to copy))
        assertEquals(
            listOf(GalleryItem.LocalOnly(rows[0]), GalleryItem.Synced(copy, rows[1]), GalleryItem.LocalOnly(rows[2])),
            items,
        )
    }

    @Test
    fun `every device row is kept, so nothing is left behind under the card`() {
        val rows = listOf(local("uri://a"), local("uri://b"))
        val items = DeviceFolderDeletion.items(rows, mapOf("uri://elsewhere" to cloud("link-x")))
        assertEquals(rows.size, items.size)
        assertTrue(items.all { it is GalleryItem.LocalOnly })
    }
}
