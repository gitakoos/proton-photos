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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which thumbnails a grid asks for: the cloud cells in view with no decrypted thumbnail yet, plus a short lead
 * past the last visible cell, the same on a person's page, a place, a day, search results and the timeline.
 */
class VisibleThumbnailRequestsTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String, thumbnailUrl: String? = null) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        thumbnailUrl = thumbnailUrl,
        revisionId = "rev1",
    )

    private fun cloudOnly(linkId: String) = GalleryItem.CloudOnly(cloud(linkId))

    private val noUrls = emptyMap<String, String>()

    @Test
    fun `a cloud-only photo with no thumbnail yet is asked for`() {
        assertEquals("pc-upload", pendingCloudThumbnailLinkId(cloudOnly("pc-upload"), noUrls))
    }

    @Test
    fun `a cloud-only photo already decrypted is not asked for again`() {
        val urls = mapOf("pc-upload" to "file:///cache/thumb_pc-upload.jpg")

        assertNull(pendingCloudThumbnailLinkId(cloudOnly("pc-upload"), urls))
    }

    @Test
    fun `a cloud row that carries its own thumbnail is not asked for`() {
        val item = GalleryItem.CloudOnly(cloud("album-row", thumbnailUrl = "file:///cache/thumb_album-row.jpg"))

        assertNull(pendingCloudThumbnailLinkId(item, noUrls))
    }

    @Test
    fun `a device-only photo never asks the cloud`() {
        assertNull(pendingCloudThumbnailLinkId(GalleryItem.LocalOnly(local("content://media/7")), noUrls))
    }

    @Test
    fun `a backed-up photo asks by its linkId, as the timeline does`() {
        val synced = GalleryItem.Synced(cloud("link-1"), local("content://media/1"))

        assertEquals("link-1", pendingCloudThumbnailLinkId(synced, noUrls))
    }

    @Test
    fun `the window covers the visible cells and twelve more`() {
        val items = (0 until 30).map { cloudOnly("c$it") }

        val wanted = thumbnailRequestWindow(items, firstIdx = 3, lastIdx = 5) { pendingCloudThumbnailLinkId(it, noUrls) }

        assertEquals(
            listOf("c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10", "c11", "c12", "c13", "c14", "c15", "c16", "c17"),
            wanted.toList(),
        )
    }

    @Test
    fun `the window stops at the end of the list`() {
        val items = listOf(cloudOnly("a"), cloudOnly("b"), cloudOnly("c"))

        val wanted = thumbnailRequestWindow(items, firstIdx = 1, lastIdx = 2) { pendingCloudThumbnailLinkId(it, noUrls) }

        assertEquals(listOf("b", "c"), wanted.toList())
    }

    @Test
    fun `no visible photo cell asks for nothing`() {
        val items = listOf(cloudOnly("a"))

        val wanted = thumbnailRequestWindow(items, firstIdx = null, lastIdx = null) { pendingCloudThumbnailLinkId(it, noUrls) }

        assertEquals(emptyList<String>(), wanted.toList())
    }

    @Test
    fun `device photos and decrypted cells in view are passed over`() {
        val items = listOf(
            GalleryItem.LocalOnly(local("content://media/1")),
            cloudOnly("cold"),
            cloudOnly("warm"),
        )
        val urls = mapOf("warm" to "file:///cache/thumb_warm.jpg")

        val wanted = thumbnailRequestWindow(items, firstIdx = 0, lastIdx = 2) { pendingCloudThumbnailLinkId(it, urls) }

        assertEquals(listOf("cold"), wanted.toList())
    }

    @Test
    fun `a photo shown twice, as one face each, is asked for once`() {
        val photo = cloudOnly("group-shot")
        val items = listOf(photo, photo)

        val wanted = thumbnailRequestWindow(items, firstIdx = 0, lastIdx = 1) { pendingCloudThumbnailLinkId(it, noUrls) }

        assertEquals(listOf("group-shot"), wanted.toList())
    }
}
