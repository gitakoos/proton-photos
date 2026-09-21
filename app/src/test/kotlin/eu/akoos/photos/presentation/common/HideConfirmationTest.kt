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

import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a hide confirmation is allowed to say about a selection.
 *
 * Three facts drive the sheet and each is claimed only when the selection actually holds it: how
 * many photos leave the phone's gallery for the vault, how many of those keep a Proton Drive copy,
 * and how many have nothing on the phone to move at all. So a selection of device files never reads
 * as if something stayed behind, a cloud-only one never threatens a vault it will not touch, and the
 * Drive-copy promise is never made for photos that have no Drive copy. No Android, no resources:
 * plain JVM assertions over hand-built selections.
 */
class HideConfirmationTest {

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

    private fun wordingOf(items: List<GalleryItem>) =
        hideConfirmWording(HiddenFolderRecords.hideSplit(items))

    @Test
    fun `an all-device selection names its vault count and nothing else`() {
        val wording = wordingOf(listOf(localOnly("uri://a"), localOnly("uri://b"), localOnly("uri://c")))
        assertEquals(3, wording.vaultCount)
        assertEquals("no Drive copy here, so that promise must not be made", 0, wording.cloudCopyCount)
        assertEquals("nothing here is cloud-only, so that clause must not appear", 0, wording.cloudOnlyCount)
        assertTrue(wording.hasClause)
    }

    @Test
    fun `a backed-up photo is counted into the vault like any other device file`() {
        // Its device file moves and its original goes, which is the first clause; that its Drive copy
        // survives the move is the second, and both are true of it at once.
        val wording = wordingOf(listOf(synced("link-1", "uri://1"), synced("link-2", "uri://2")))
        assertEquals(2, wording.vaultCount)
        assertEquals(2, wording.cloudCopyCount)
        assertEquals(0, wording.cloudOnlyCount)
        assertTrue(wording.hasClause)
    }

    @Test
    fun `an all-cloud selection says only that those photos have nothing to move`() {
        val wording = wordingOf(listOf(cloudOnly("link-1"), cloudOnly("link-2")))
        assertEquals(0, wording.vaultCount)
        assertEquals("a cloud-only photo is never vaulted, so it keeps no vault pairing", 0, wording.cloudCopyCount)
        assertEquals(2, wording.cloudOnlyCount)
        assertTrue(wording.hasClause)
    }

    @Test
    fun `a mixed selection says each clause about its own photos`() {
        val wording = wordingOf(
            listOf(localOnly("uri://a"), synced("link-1", "uri://1"), cloudOnly("link-2")),
        )
        assertEquals(2, wording.vaultCount)
        assertEquals("only the synced one of the two vaulted photos has a Drive copy", 1, wording.cloudCopyCount)
        assertEquals(1, wording.cloudOnlyCount)
        assertTrue(wording.hasClause)
    }

    @Test
    fun `an empty selection has nothing to say, which is also nothing to confirm`() {
        val wording = wordingOf(emptyList())
        assertEquals(0, wording.vaultCount)
        assertEquals(0, wording.cloudCopyCount)
        assertEquals(0, wording.cloudOnlyCount)
        assertFalse(wording.hasClause)
        assertEquals(wording, hideConfirmWording(HiddenFolderRecords.HideSplit.EMPTY))
    }

    @Test
    fun `a backed-up photo the caller only knows as a device row keeps its Drive copy clause`() {
        // A device row carries no cloud identity, so its sync pairing is the only thing that names
        // the Drive copy. The file moves either way; what the pairing decides is whether the photo is
        // promised its Drive copy back, and that promise has to follow the pairing rather than the
        // shape of the item the surface happened to hold.
        val wording = hideConfirmWording(
            HiddenFolderRecords.hideSplit(
                items = listOf(localOnly("uri://paired"), localOnly("uri://loose")),
                cloudLinkIdByUri = mapOf("uri://paired" to "link-paired"),
            ),
        )
        assertEquals(2, wording.vaultCount)
        assertEquals(1, wording.cloudCopyCount)
        assertEquals(0, wording.cloudOnlyCount)
    }
}
