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
 * Which embeddings the semantic walk moves when device photos are backed up, and what the walk then
 * treats as already embedded. Plain gallery types in, no database.
 */
class SemanticRekeyPairsTest {

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

    private fun synced(uri: String, linkId: String) = GalleryItem.Synced(cloud(linkId), local(uri))

    @Test
    fun `a backed-up photo embedded under its device key pairs with its cloud key`() {
        val items = listOf(synced(DEV_A, LINK_A))

        assertEquals(listOf(DEV_A to LINK_A), semanticRekeyPairs(items, embeddedKeys = setOf(DEV_A)))
    }

    @Test
    fun `a backed-up photo never embedded on the device is left to the walk`() {
        // No row to move: the walk embeds the Synced photo under its linkId as usual.
        assertEquals(emptyList<Pair<String, String>>(), semanticRekeyPairs(listOf(synced(DEV_A, LINK_A)), emptySet()))
    }

    @Test
    fun `a cloud key already embedded still pairs, so the device row is dropped`() {
        // The face walk skips this case; here the DAO keeps the linkId row and drops the URI one.
        val items = listOf(synced(DEV_A, LINK_A))

        assertEquals(listOf(DEV_A to LINK_A), semanticRekeyPairs(items, embeddedKeys = setOf(DEV_A, LINK_A)))
    }

    @Test
    fun `device-only and cloud-only photos never pair`() {
        // A guest's library is device photos only, so a guest never re-keys anything.
        val items = listOf(GalleryItem.LocalOnly(local(DEV_B)), GalleryItem.CloudOnly(cloud(LINK_C)))

        assertEquals(emptyList<Pair<String, String>>(), semanticRekeyPairs(items, embeddedKeys = setOf(DEV_B, LINK_C)))
    }

    @Test
    fun `a self pair is skipped`() {
        val items = listOf(synced(LINK_A, LINK_A))

        assertEquals(emptyList<Pair<String, String>>(), semanticRekeyPairs(items, embeddedKeys = setOf(LINK_A)))
    }

    @Test
    fun `a mixed library pairs only the movable photos, in listing order`() {
        val items = listOf(
            synced(DEV_A, LINK_A),
            GalleryItem.LocalOnly(local(DEV_B)),
            synced(DEV_C, LINK_C),
            synced(DEV_D, LINK_D),
        )

        // DEV_C was never embedded on the device, so only A and D move.
        assertEquals(
            listOf(DEV_A to LINK_A, DEV_D to LINK_D),
            semanticRekeyPairs(items, embeddedKeys = setOf(DEV_A, DEV_B, DEV_D)),
        )
    }

    @Test
    fun `after the re-key the backed-up photo is not queued for a second embedding`() {
        val items = listOf(synced(DEV_A, LINK_A), GalleryItem.LocalOnly(local(DEV_B)))
        val embedded = hashSetOf(DEV_A, DEV_B)

        embedded.applyRekeyPairs(semanticRekeyPairs(items, embedded))

        assertEquals(setOf(LINK_A, DEV_B), embedded)
        // The walk's own pending filter over the same library.
        assertEquals(emptyList<GalleryItem>(), items.filter { it.stableId !in embedded })
    }

    private companion object {
        const val DEV_A = "content://media/external/images/media/1"
        const val DEV_B = "content://media/external/images/media/2"
        const val DEV_C = "content://media/external/images/media/3"
        const val DEV_D = "content://media/external/images/media/4"
        const val LINK_A = "linkA"
        const val LINK_C = "linkC"
        const val LINK_D = "linkD"
    }
}
