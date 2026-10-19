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

package eu.akoos.photos.presentation.albums

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumContentsRowsTest {

    private fun cloud(id: String, name: String, size: Long) = CloudPhoto(
        linkId = id,
        shareId = "share",
        volumeId = "volume",
        captureTime = 1_700_000_000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        thumbnailUrl = null,
        revisionId = "rev-$id",
    )

    private fun local(uri: String, name: String, size: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        bucketName = "Camera",
    )

    @Test
    fun `cloud rows keep the album order and Drive's sizes`() {
        val rows = albumContentsRowsOfCloud(
            listOf(cloud("b", "second.jpg", 200), cloud("a", "first.jpg", 0), cloud("c", "third.mp4", 3_000)),
        )
        assertEquals(
            listOf(
                AlbumContentsRow("second.jpg", 200),
                AlbumContentsRow("first.jpg", 0),
                AlbumContentsRow("third.mp4", 3_000),
            ),
            rows,
        )
    }

    @Test
    fun `device rows are sized by the device file, also for a backed-up photo`() {
        val rows = albumContentsRowsOfDevice(
            listOf(
                GalleryItem.LocalOnly(local("content://1", "a.jpg", 111)),
                GalleryItem.Synced(cloud("x", "a-on-drive.jpg", 999), local("content://2", "b.jpg", 222)),
            ),
        )
        assertEquals(listOf(AlbumContentsRow("a.jpg", 111), AlbumContentsRow("b.jpg", 222)), rows)
    }

    @Test
    fun `a cloud-only item has no device file and gives no device row`() {
        val rows = albumContentsRowsOfDevice(
            listOf(
                GalleryItem.CloudOnly(cloud("x", "remote.jpg", 999)),
                GalleryItem.LocalOnly(local("content://1", "here.jpg", 5)),
            ),
        )
        assertEquals(listOf(AlbumContentsRow("here.jpg", 5)), rows)
    }

    @Test
    fun `empty inputs give no rows`() {
        assertTrue(albumContentsRowsOfCloud(emptyList()).isEmpty())
        assertTrue(albumContentsRowsOfDevice(emptyList()).isEmpty())
    }
}
