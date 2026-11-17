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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared [buildDeleteUndoAction], the single builder every delete surface (the timeline,
 * search, album detail, device folders) uses so they all offer the identical Undo. The invariant held
 * here is that Undo is offered only for copies that can actually come back: a cloud trash is always
 * reversible, a device copy only when it went to the recoverable Android 11+ system trash
 * ([localRecoverable]). A permanent removal (a pre-Android-11 delete or a hide) offers no device Undo,
 * a cloud-only item never contributes a device URI, and a delete with nothing restorable yields null.
 * No Android, no Context, no Robolectric: plain JVM assertions over hand-built GalleryItem selections.
 */
class DeleteUndoActionsTest {

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

    private val mixed = listOf(
        localOnly("uri://local-only"),
        synced("link-synced", "uri://synced"),
        cloudOnly("link-cloud-only"),
    )

    @Test
    fun `a cloud-only delete carries the trashed cloud linkIds and no device uris`() {
        // "Remove the cloud copies, keep local files": freeUpSpace = false.
        val undo = buildDeleteUndoAction(
            mixed, freeUpSpace = false, deleteFromCloud = true, hide = false, localRecoverable = false,
        )!!
        assertEquals(listOf("link-synced", "link-cloud-only"), undo.cloudLinkIds)
        assertTrue(undo.localTrashedUris.isEmpty())
        assertTrue(undo.syncedRelinks.isEmpty())
    }

    @Test
    fun `a free-up-space delete on the system trash carries the device uris`() {
        val undo = buildDeleteUndoAction(
            mixed, freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = true,
        )!!
        assertEquals(listOf("uri://local-only", "uri://synced"), undo.localTrashedUris)
        assertEquals(listOf("link-synced", "link-cloud-only"), undo.cloudLinkIds)
    }

    @Test
    fun `a synced delete carries one relink pair per synced photo`() {
        val items = listOf(synced("link-s", "uri://s"), synced("link-t", "uri://t"))
        val undo = buildDeleteUndoAction(
            items, freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = true,
        )!!
        assertEquals(
            listOf(
                UndoAction.Delete.Relink("uri://s", "link-s", 1024L),
                UndoAction.Delete.Relink("uri://t", "link-t", 1024L),
            ),
            undo.syncedRelinks,
        )
    }

    @Test
    fun `a cloud-only item never contributes a device uri`() {
        val items = listOf(cloudOnly("link-cloud-only"))
        for (freeUpSpace in listOf(true, false)) {
            for (deleteFromCloud in listOf(true, false)) {
                for (localRecoverable in listOf(true, false)) {
                    val undo = buildDeleteUndoAction(items, freeUpSpace, deleteFromCloud, hide = false, localRecoverable)
                        ?: continue
                    assertTrue(
                        "cloud-only must never yield a device uri (freeUp=$freeUpSpace, fromCloud=$deleteFromCloud, recoverable=$localRecoverable)",
                        undo.localTrashedUris.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `a delete with nothing restorable yields no undo`() {
        // Keep-cloud, keep-local on device-only photos: no cloud trash, no recoverable device trash.
        assertNull(
            buildDeleteUndoAction(
                listOf(localOnly("uri://a")),
                freeUpSpace = true, deleteFromCloud = false, hide = false, localRecoverable = false,
            ),
        )
        // An empty selection has nothing to restore no matter the flags.
        assertNull(
            buildDeleteUndoAction(
                emptyList(), freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = true,
            ),
        )
    }

    @Test
    fun `a hide never yields a delete undo because its device removal is permanent`() {
        for (freeUpSpace in listOf(true, false)) {
            for (deleteFromCloud in listOf(true, false)) {
                for (localRecoverable in listOf(true, false)) {
                    assertNull(
                        "a hide must never offer a delete undo (freeUp=$freeUpSpace, fromCloud=$deleteFromCloud, recoverable=$localRecoverable)",
                        buildDeleteUndoAction(mixed, freeUpSpace, deleteFromCloud, hide = true, localRecoverable),
                    )
                }
            }
        }
    }

    @Test
    fun `a permanent local delete offers no device undo but still restores the cloud copy`() {
        // freeUpSpace removes the device files, but localRecoverable = false (pre-Android-11): the device
        // copies are gone for good, so no device undo, while the trashed cloud copy stays reversible.
        val items = listOf(localOnly("uri://local-only"), synced("link-synced", "uri://synced"))
        val undo = buildDeleteUndoAction(
            items, freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = false,
        )!!
        assertTrue(undo.localTrashedUris.isEmpty())
        assertTrue(undo.syncedRelinks.isEmpty())
        assertEquals(listOf("link-synced"), undo.cloudLinkIds)
    }

    // The bar's count: each photo once, whichever of its copies went to trash.

    private fun mixOf(paired: Int, deviceOnly: Int, driveOnly: Int): List<GalleryItem> =
        (1..paired).map { synced("link-p$it", "uri://p$it") } +
            (1..deviceOnly).map { localOnly("uri://d$it") } +
            (1..driveOnly).map { cloudOnly("link-c$it") }

    @Test
    fun `a delete everywhere of paired, device-only and Drive-only photos counts every photo once`() {
        // 23 paired + 2 device-only + 2 Drive-only: 25 Drive links and 25 device files, 27 photos.
        val undo = buildDeleteUndoAction(
            mixOf(paired = 23, deviceOnly = 2, driveOnly = 2),
            freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = true,
        )!!
        assertEquals(25, undo.cloudLinkIds.size)
        assertEquals(25, undo.localTrashedUris.size)
        assertEquals(27, undo.count)
    }

    @Test
    fun `a delete everywhere of paired photos only counts each pair once`() {
        val undo = buildDeleteUndoAction(
            mixOf(paired = 27, deviceOnly = 0, driveOnly = 0),
            freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = true,
        )!!
        assertEquals(27, undo.count)
    }

    @Test
    fun `a Drive-only delete counts the photos whose Drive copy went`() {
        // Device files kept: the paired and Drive-only photos lose their Drive copy, the device-only ones
        // are untouched.
        val undo = buildDeleteUndoAction(
            mixOf(paired = 3, deviceOnly = 2, driveOnly = 4),
            freeUpSpace = false, deleteFromCloud = true, hide = false, localRecoverable = true,
        )!!
        assertEquals(7, undo.count)
    }

    @Test
    fun `a delete with no device trash counts the photos whose Drive copy can come back`() {
        val undo = buildDeleteUndoAction(
            mixOf(paired = 3, deviceOnly = 2, driveOnly = 4),
            freeUpSpace = true, deleteFromCloud = true, hide = false, localRecoverable = false,
        )!!
        assertEquals(7, undo.count)
    }

    @Test
    fun `an undo built from its lists alone counts a paired photo once`() {
        val undo = UndoAction.Delete(
            cloudLinkIds = listOf("link-p1", "link-c1"),
            localTrashedUris = listOf("uri://p1", "uri://d1"),
            syncedRelinks = listOf(UndoAction.Delete.Relink("uri://p1", "link-p1", 1024L)),
        )
        assertEquals(3, undo.count)
    }

    // buildHideUndoAction: the shared rule every hide surface (the timeline, search, the viewer, device
    // folders) uses so they all offer the identical restore. A hide vaults only on-device-only photos and
    // moves the bytes into the app-private vault before removing the MediaStore copy, so any vaulted copy
    // is restorable and is offered, while a selection that vaulted nothing raises no bar.

    @Test
    fun `a hide of device-backed items offers to restore exactly the vaulted uris`() {
        val selection = listOf(localOnly("uri://a"), localOnly("uri://b"), cloudOnly("link-c"))
        val vaulted = selection.filterIsInstance<GalleryItem.LocalOnly>().map { "vault://${it.local.uri}" }
        val undo = buildHideUndoAction(vaulted)!!
        assertEquals(listOf("vault://uri://a", "vault://uri://b"), undo.hiddenUris)
        assertEquals(2, undo.count)
    }

    @Test
    fun `a hide that vaulted nothing offers no undo`() {
        assertNull(buildHideUndoAction(emptyList()))
    }

    @Test
    fun `a cloud-only or synced selection has nothing to vault so it offers no hide undo`() {
        // Only an on-device-only photo can be vaulted, so a hide drops cloud-backed items before storing.
        // With no LocalOnly in the selection the vault set is empty and the bar must not appear.
        val selection = listOf(cloudOnly("link-1"), synced("link-2", "uri://2"))
        val vaulted = selection.filterIsInstance<GalleryItem.LocalOnly>().map { "vault://${it.local.uri}" }
        assertTrue(vaulted.isEmpty())
        assertNull(buildHideUndoAction(vaulted))
    }
}
