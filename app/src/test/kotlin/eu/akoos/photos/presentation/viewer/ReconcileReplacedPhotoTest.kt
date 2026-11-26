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
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins what an open viewer shows after a Drive photo was replaced by an edited copy (a date or place
 * change, a compression): the copy, not the trashed original the snapshot still holds.
 */
class ReconcileReplacedPhotoTest {

    private fun cloud(linkId: String, captureTime: Long = 1_700_000_000L) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTime,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    @Test
    fun `a Drive-only photo replaced by a copy shows the copy`() {
        val original = GalleryItem.CloudOnly(cloud("old"))
        val copy = GalleryItem.CloudOnly(cloud("new", captureTime = 1_600_000_000L))
        val neighbour = GalleryItem.CloudOnly(cloud("other"))

        val after = reconcileViewerItems(
            snapshot = listOf(neighbour, original),
            live = listOf(neighbour, copy),
            replaced = mapOf("old" to "new"),
        )

        assertEquals(listOf(neighbour, copy), after)
    }

    @Test
    fun `a copy replaced again is followed to the newest one`() {
        val original = GalleryItem.CloudOnly(cloud("a"))
        val newest = GalleryItem.CloudOnly(cloud("c"))

        val after = reconcileViewerItems(
            snapshot = listOf(original),
            live = listOf(newest),
            replaced = mapOf("a" to "b", "b" to "c"),
        )

        assertEquals(listOf(newest), after)
    }

    @Test
    fun `a copy not in the timeline yet leaves the page as it was`() {
        val original = GalleryItem.CloudOnly(cloud("old"))
        val unrelated = GalleryItem.CloudOnly(cloud("other"))

        val after = reconcileViewerItems(listOf(original), listOf(unrelated), mapOf("old" to "new"))

        assertEquals(listOf(original), after)
    }

    @Test
    fun `a loop in the replacements ends without hanging`() {
        val original = GalleryItem.CloudOnly(cloud("a"))
        val unrelated = GalleryItem.CloudOnly(cloud("z"))

        val after = reconcileViewerItems(listOf(original), listOf(unrelated), mapOf("a" to "b", "b" to "a"))

        assertEquals(listOf(original), after)
    }

    @Test
    fun `a backed-up photo still finds its new pairing by the device file`() {
        val uri = "content://media/external/images/media/7"
        val original = GalleryItem.Synced(cloud("old"), local(uri))
        val repaired = GalleryItem.Synced(cloud("new"), local(uri))

        val after = reconcileViewerItems(listOf(original), listOf(repaired), emptyMap())

        assertEquals(listOf(repaired), after)
    }

    @Test
    fun `a Drive photo saved over its unpaired device twin leaves one page for it`() {
        val uri = "content://media/external/images/media/9"
        val deviceTwin = GalleryItem.LocalOnly(local(uri))
        val driveCopy = GalleryItem.CloudOnly(cloud("x"))
        val neighbour = GalleryItem.CloudOnly(cloud("other"))
        val paired = GalleryItem.Synced(cloud("x"), local(uri))

        val after = reconcileViewerItems(listOf(deviceTwin, driveCopy, neighbour), listOf(paired, neighbour))

        assertEquals(listOf(paired, neighbour), after)
    }

    @Test
    fun `nothing replaced keeps the same list instance`() {
        val item = GalleryItem.CloudOnly(cloud("x"))
        val snapshot = listOf(item)

        assertSame(snapshot, reconcileViewerItems(snapshot, listOf(item), mapOf("gone" to "elsewhere")))
    }
}
