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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the two safety decisions of the app's most destructive path, extracted from
 * [DeletePhotoUseCase]: [DeletePhotoUseCase.computeDeleteTargets] (which device URIs and which cloud
 * linkIds a delete acts on) and [DeletePhotoUseCase.postDeleteSyncStatus] (the SyncState a row lands in
 * once its device copy is gone). The invariant pinned here is that a copy the user chose to keep is
 * never destroyed: a keep-local delete touches no device file, a free-up-space delete never trashes a
 * cloud copy, a CloudOnly item never contributes a device URI, and a LocalOnly item never contributes a
 * cloud linkId. No Android, no Context, no ContentResolver, no MediaStore, no Robolectric: plain JVM
 * assertions on the inputs.
 */
class DeletePhotoUseCaseTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun localOnly(uri: String) = GalleryItem.LocalOnly(local(uri))
    private fun cloudOnly(linkId: String) = GalleryItem.CloudOnly(cloud(linkId))
    private fun synced(linkId: String, uri: String) = GalleryItem.Synced(cloud(linkId), local(uri))

    // computeDeleteTargets: the cloud/local split that decides what actually gets destroyed.

    @Test
    fun `a cloud-only delete never removes the only device copy`() {
        val items = listOf(
            localOnly("uri://local-only"),
            synced("link-synced", "uri://synced"),
            cloudOnly("link-cloud-only"),
        )

        val targets = DeletePhotoUseCase.computeDeleteTargets(
            items, freeUpSpace = false, deleteFromCloud = true,
        )

        // A keep-local delete touches no device file: the LocalOnly item's sole copy and the Synced
        // item's local twin both survive.
        assertTrue(targets.localUriStrings.isEmpty())
        // The LocalOnly item has no cloud copy, so only the Synced and CloudOnly cloud copies are
        // trashed; the Synced item gives up its cloud id but keeps its device file.
        assertEquals(listOf("link-synced", "link-cloud-only"), targets.cloudLinkIds)
    }

    @Test
    fun `freeing up space removes device copies and never trashes a cloud copy`() {
        val items = listOf(
            localOnly("uri://local-only"),
            synced("link-synced", "uri://synced"),
            cloudOnly("link-cloud-only"),
        )

        val targets = DeletePhotoUseCase.computeDeleteTargets(
            items, freeUpSpace = true, deleteFromCloud = false,
        )

        // Both device-backed items free their local file; the CloudOnly item has no device copy to free.
        assertEquals(listOf("uri://local-only", "uri://synced"), targets.localUriStrings)
        // deleteFromCloud is false, so no cloud copy is ever trashed.
        assertTrue(targets.cloudLinkIds.isEmpty())
    }

    @Test
    fun `deleting everywhere populates both the cloud and device delete sets`() {
        val items = listOf(
            localOnly("uri://local-only"),
            synced("link-synced", "uri://synced"),
            cloudOnly("link-cloud-only"),
        )

        // "Delete everywhere" on a selection that has local files runs with freeUpSpace = hasLocal.
        val targets = DeletePhotoUseCase.computeDeleteTargets(
            items, freeUpSpace = true, deleteFromCloud = true,
        )

        assertEquals(listOf("link-synced", "link-cloud-only"), targets.cloudLinkIds)
        assertEquals(listOf("uri://local-only", "uri://synced"), targets.localUriStrings)
    }

    @Test
    fun `a cloud-only item never contributes a device uri in any flow`() {
        val items = listOf(cloudOnly("link-cloud-only"))
        for (freeUpSpace in listOf(true, false)) {
            for (deleteFromCloud in listOf(true, false)) {
                val targets = DeletePhotoUseCase.computeDeleteTargets(items, freeUpSpace, deleteFromCloud)
                assertTrue(
                    "cloud-only must never yield a device uri (freeUp=$freeUpSpace, fromCloud=$deleteFromCloud)",
                    targets.localUriStrings.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `a local-only item never contributes a cloud id in any flow`() {
        val items = listOf(localOnly("uri://local-only"))
        for (freeUpSpace in listOf(true, false)) {
            for (deleteFromCloud in listOf(true, false)) {
                val targets = DeletePhotoUseCase.computeDeleteTargets(items, freeUpSpace, deleteFromCloud)
                assertTrue(
                    "local-only must never yield a cloud id (freeUp=$freeUpSpace, fromCloud=$deleteFromCloud)",
                    targets.cloudLinkIds.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `an empty input yields empty target sets`() {
        val targets = DeletePhotoUseCase.computeDeleteTargets(
            emptyList(), freeUpSpace = true, deleteFromCloud = true,
        )
        assertTrue(targets.cloudLinkIds.isEmpty())
        assertTrue(targets.localUriStrings.isEmpty())
    }

    // postDeleteSyncStatus: the row's status once its device copy has been removed.

    @Test
    fun `a local-only row becomes LOCAL_ONLY after its device copy is removed`() {
        assertEquals(
            SyncStatus.LOCAL_ONLY,
            DeletePhotoUseCase.postDeleteSyncStatus(localOnly("uri://local-only"), freeUpSpace = true, hide = false),
        )
    }

    @Test
    fun `a local-only row lands on LOCAL_ONLY whichever way it got there`() {
        // Its whole corner of the table, since a photo with no cloud copy has no other state to be in:
        // no pairing to keep, and nothing on Drive a hide could leave behind.
        for (freeUpSpace in listOf(true, false)) {
            for (hide in listOf(true, false)) {
                assertEquals(
                    SyncStatus.LOCAL_ONLY,
                    DeletePhotoUseCase.postDeleteSyncStatus(localOnly("uri://local-only"), freeUpSpace, hide),
                )
            }
        }
    }

    @Test
    fun `a synced row becomes HIDDEN on a hide flow even while freeing space`() {
        assertEquals(
            SyncStatus.HIDDEN,
            DeletePhotoUseCase.postDeleteSyncStatus(synced("link", "uri://synced"), freeUpSpace = true, hide = true),
        )
        assertEquals(
            SyncStatus.HIDDEN,
            DeletePhotoUseCase.postDeleteSyncStatus(synced("link", "uri://synced"), freeUpSpace = false, hide = true),
        )
    }

    @Test
    fun `a vaulted synced row lands on HIDDEN and on nothing else`() {
        // The whole point of the state: a SYNCED row whose local path is absent from the device
        // gallery is destroyed by the reconcile pass, and a CLOUD_ONLY one presents a vaulted photo as
        // if it were only on Drive. HIDDEN is what both passes skip.
        for (freeUpSpace in listOf(true, false)) {
            val status = DeletePhotoUseCase.postDeleteSyncStatus(
                synced("link", "uri://synced"), freeUpSpace, hide = true,
            )
            assertEquals(SyncStatus.HIDDEN, status)
            assertNotEquals(SyncStatus.SYNCED, status)
            assertNotEquals(SyncStatus.CLOUD_ONLY, status)
        }
    }

    @Test
    fun `a synced row becomes CLOUD_ONLY when its device copy is freed`() {
        assertEquals(
            SyncStatus.CLOUD_ONLY,
            DeletePhotoUseCase.postDeleteSyncStatus(synced("link", "uri://synced"), freeUpSpace = true, hide = false),
        )
    }

    @Test
    fun `a synced row that keeps its device copy does not transition`() {
        assertNull(
            DeletePhotoUseCase.postDeleteSyncStatus(synced("link", "uri://synced"), freeUpSpace = false, hide = false),
        )
    }

    @Test
    fun `a cloud-only row never transitions its sync state`() {
        for (freeUpSpace in listOf(true, false)) {
            for (hide in listOf(true, false)) {
                assertNull(DeletePhotoUseCase.postDeleteSyncStatus(cloudOnly("link"), freeUpSpace, hide))
            }
        }
    }

    // ── removesFromGallery: one case per button the delete sheet offers ────────────────────

    @Test
    fun `trashing a device-only photo removes it`() {
        assertTrue(
            DeletePhotoUseCase.removesFromGallery(
                localOnly("uri://local"), freeUpSpace = true, deleteFromCloud = false,
            )
        )
    }

    @Test
    fun `freeing a synced photo's device space keeps it, as a cloud photo`() {
        assertFalse(
            DeletePhotoUseCase.removesFromGallery(
                synced("link", "uri://synced"), freeUpSpace = true, deleteFromCloud = false,
            )
        )
    }

    @Test
    fun `trashing a synced photo's cloud copy keeps it, as a device photo`() {
        assertFalse(
            DeletePhotoUseCase.removesFromGallery(
                synced("link", "uri://synced"), freeUpSpace = false, deleteFromCloud = true,
            )
        )
    }

    @Test
    fun `deleting a synced photo everywhere removes it`() {
        assertTrue(
            DeletePhotoUseCase.removesFromGallery(
                synced("link", "uri://synced"), freeUpSpace = true, deleteFromCloud = true,
            )
        )
    }

    @Test
    fun `trashing a cloud-only photo removes it`() {
        assertTrue(
            DeletePhotoUseCase.removesFromGallery(
                cloudOnly("link"), freeUpSpace = false, deleteFromCloud = true,
            )
        )
    }

    @Test
    fun `a delete that targets neither side removes nothing`() {
        val items = listOf(localOnly("uri://local"), cloudOnly("link"), synced("link2", "uri://synced"))
        for (item in items) {
            assertFalse(
                DeletePhotoUseCase.removesFromGallery(item, freeUpSpace = false, deleteFromCloud = false)
            )
        }
    }

    @Test
    fun `removal agrees with the targets the same delete would act on`() {
        val items = listOf(localOnly("uri://local"), cloudOnly("link"), synced("link2", "uri://synced"))
        for (item in items) {
            for (freeUpSpace in listOf(true, false)) {
                for (deleteFromCloud in listOf(true, false)) {
                    val targets = DeletePhotoUseCase.computeDeleteTargets(
                        listOf(item), freeUpSpace, deleteFromCloud,
                    )
                    // A photo is gone only when every copy it has was targeted, so the count of
                    // targets has to match the number of copies the item holds.
                    val copies = when (item) {
                        is GalleryItem.Synced -> 2
                        else -> 1
                    }
                    val targeted = targets.cloudLinkIds.size + targets.localUriStrings.size
                    assertEquals(
                        "item=$item freeUpSpace=$freeUpSpace deleteFromCloud=$deleteFromCloud",
                        targeted == copies,
                        DeletePhotoUseCase.removesFromGallery(item, freeUpSpace, deleteFromCloud),
                    )
                }
            }
        }
    }
}
