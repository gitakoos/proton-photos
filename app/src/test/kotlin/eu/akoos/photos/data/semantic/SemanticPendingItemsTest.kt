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

package eu.akoos.photos.data.semantic

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which photos the semantic walk still embeds. A photo whose embedding came from another device's shared
 * backup is keyed by its Drive linkId, and a backed-up photo on this phone is keyed by the same linkId, so
 * turning indexing on here embeds only the photos that hold no row yet.
 */
class SemanticPendingItemsTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        sizeBytes = 1_000,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    @Test
    fun `a synced photo with an imported embedding is not queued`() {
        val synced = GalleryItem.Synced(cloud("link-1"), local("content://media/1"))

        assertEquals(emptyList<GalleryItem>(), semanticPendingItems(listOf(synced), setOf("link-1")))
    }

    @Test
    fun `a cloud only photo with an imported embedding is not queued`() {
        assertEquals(emptyList<GalleryItem>(), semanticPendingItems(listOf(GalleryItem.CloudOnly(cloud("link-2"))), setOf("link-2")))
    }

    @Test
    fun `only the photos without a row are queued`() {
        val covered = GalleryItem.Synced(cloud("link-1"), local("content://media/1"))
        val bare = GalleryItem.Synced(cloud("link-3"), local("content://media/3"))
        val deviceOnly = GalleryItem.LocalOnly(local("content://media/4"))

        val pending = semanticPendingItems(listOf(covered, bare, deviceOnly), setOf("link-1"))

        assertEquals(listOf(bare, deviceOnly), pending)
    }
}
