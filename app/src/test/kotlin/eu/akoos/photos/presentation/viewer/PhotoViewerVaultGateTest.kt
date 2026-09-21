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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the viewer offers a photo the vault holds. Four of the five outward-bound actions upload
 * the photo before they can do anything with it, so offering any of them would undo the hide the user
 * asked for — and the whole point of the gate is that it withholds them without touching what an
 * ordinary photo gets. Both halves are asserted here, on plain values: no Android, no Compose.
 */
class PhotoViewerVaultGateTest {

    private val vaultRoot = "file:///data/user/0/eu.akoos.photos/files/hidden/"

    /** Stands in for HiddenStorageManager.isHiddenUri: a vault uri is a file under the vault dir. */
    private val isVaultUri: (String) -> Boolean = { it.startsWith(vaultRoot) }

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

    private fun vaulted() = GalleryItem.LocalOnly(local("${vaultRoot}9a3f-4d21.jpg"))
    private fun deviceOnly() = GalleryItem.LocalOnly(local("content://media/external/images/media/42"))
    private fun synced() = GalleryItem.Synced(cloud("link1"), local("content://media/external/images/media/43"))
    private fun cloudOnly() = GalleryItem.CloudOnly(cloud("link2"))

    // ── which item is vaulted ────────────────────────────────────────────────────────────────────

    @Test
    fun `a device-only photo in the vault names its vault file`() {
        assertEquals(
            "${vaultRoot}9a3f-4d21.jpg",
            PhotoViewerVaultGate.vaultUriOf(vaulted(), isVaultUri),
        )
    }

    @Test
    fun `an ordinary device photo names no vault file`() {
        assertNull(PhotoViewerVaultGate.vaultUriOf(deviceOnly(), isVaultUri))
        assertNull(PhotoViewerVaultGate.vaultUriOf(synced(), isVaultUri))
    }

    @Test
    fun `a cloud photo can never name one, and neither can no photo at all`() {
        // A cloud photo has no device file, and its own hide is a filter that leaves it in place.
        assertNull(PhotoViewerVaultGate.vaultUriOf(cloudOnly(), isVaultUri))
        assertNull(PhotoViewerVaultGate.vaultUriOf(null, isVaultUri))
    }

    @Test
    fun `the question is asked of the local uri, not of the item kind`() {
        // Nothing today puts a backed-up photo's device copy in the vault, but the gate answers on
        // the file rather than on that assumption, so it stays right if one ever does.
        val syncedInVault = GalleryItem.Synced(cloud("link3"), local("${vaultRoot}c1d2.jpg"))
        assertEquals("${vaultRoot}c1d2.jpg", PhotoViewerVaultGate.vaultUriOf(syncedInVault, isVaultUri))
    }

    // ── what a vaulted photo may still do ────────────────────────────────────────────────────────

    @Test
    fun `a vaulted photo is offered nothing that would upload it`() {
        val actions = PhotoViewerVaultGate.outboundActions(vaulted(), vaulted = true)

        assertFalse(actions.backUpToDrive)
        assertFalse(actions.addToAlbum)
        assertFalse(actions.shareWithPeople)
        assertFalse(actions.publicLink)
    }

    @Test
    fun `a vaulted photo may still be sent to another app`() {
        // The one route that moves the bytes the user is already looking at without putting the
        // photo back on Drive, so the share drawer still has a row and Share stays worth opening.
        val actions = PhotoViewerVaultGate.outboundActions(vaulted(), vaulted = true)

        assertTrue(actions.sendToApp)
        assertTrue(actions.anyShareRoute)
    }

    @Test
    fun `a device-only photo outside the vault keeps every action`() {
        val actions = PhotoViewerVaultGate.outboundActions(deviceOnly(), vaulted = false)

        assertEquals(
            ViewerOutboundActions(
                backUpToDrive = true,
                addToAlbum = true,
                sendToApp = true,
                shareWithPeople = true,
                publicLink = true,
            ),
            actions,
        )
    }

    @Test
    fun `a backed-up photo keeps everything but the back-up it does not need`() {
        for (item in listOf(synced(), cloudOnly())) {
            val actions = PhotoViewerVaultGate.outboundActions(item, vaulted = false)
            assertFalse(actions.backUpToDrive)
            assertTrue(actions.addToAlbum)
            assertTrue(actions.sendToApp)
            assertTrue(actions.shareWithPeople)
            assertTrue(actions.publicLink)
        }
    }

    @Test
    fun `no photo is offered nothing`() {
        val actions = PhotoViewerVaultGate.outboundActions(null, vaulted = false)

        assertFalse(actions.anyShareRoute)
        assertFalse(actions.backUpToDrive)
        assertFalse(actions.addToAlbum)
    }
}
