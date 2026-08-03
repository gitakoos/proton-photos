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

package eu.akoos.photos.presentation.metadata

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which stored fix a landed place write invalidates, per gallery subtype. The rule is the whole
 * point of the function: `photo_location` keys a device photo by its content URI and a cloud photo by
 * its linkId, so an id picked off the wrong side of a synced pair deletes nothing and leaves the map on
 * the old coordinates, silently. Pure, so the matrix is checked without a ViewModel or a database.
 */
class StaleLocationIdsTest {

    private fun local(uri: String) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_000L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1L,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_024L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun localOnly(uri: String) = GalleryItem.LocalOnly(local(uri))

    private fun synced(uri: String, linkId: String) = GalleryItem.Synced(cloud(linkId), local(uri))

    private fun cloudOnly(linkId: String) = GalleryItem.CloudOnly(cloud(linkId))

    @Test
    fun `a device photo is invalidated by its content URI`() {
        val items = listOf(localOnly(URI_A))

        assertEquals(listOf(URI_A), staleLocationIds(items, setOf(URI_A)))
    }

    @Test
    fun `a synced photo is invalidated by its device URI, never its linkId`() {
        // Its device file is what the write changed; the linkId row holds the server-derived fix.
        val items = listOf(synced(URI_A, LINK_A))

        assertEquals(listOf(URI_A), staleLocationIds(items, setOf(URI_A)))
    }

    @Test
    fun `a cloud only photo contributes nothing`() {
        // No device file to write, and its fix came from the server, so there is nothing to re-derive.
        val items = listOf(cloudOnly(LINK_A))

        assertEquals(emptyList<String>(), staleLocationIds(items, setOf(LINK_A)))
    }

    @Test
    fun `only the items the write landed on are invalidated`() {
        // The place field is locked for videos, so a mixed selection saves a subset of what is bound.
        val items = listOf(localOnly(URI_A), localOnly(URI_B), localOnly(URI_C))

        assertEquals(listOf(URI_A, URI_C), staleLocationIds(items, setOf(URI_A, URI_C)))
    }

    @Test
    fun `a mixed selection yields the device URIs alone`() {
        val items = listOf(localOnly(URI_A), synced(URI_B, LINK_A), cloudOnly(LINK_B))

        val ids = staleLocationIds(items, setOf(URI_A, URI_B, LINK_A, LINK_B))

        assertEquals(listOf(URI_A, URI_B), ids)
    }

    @Test
    fun `a uri no bound item claims is not invalidated`() {
        // The saved set is matched against the bound items, so an id belonging to nothing on screen
        // can never reach the delete.
        val items = listOf(localOnly(URI_A))

        assertEquals(emptyList<String>(), staleLocationIds(items, setOf(URI_B)))
    }

    @Test
    fun `nothing saved invalidates nothing`() {
        val items = listOf(localOnly(URI_A), synced(URI_B, LINK_A))

        assertEquals(emptyList<String>(), staleLocationIds(items, emptySet()))
    }

    @Test
    fun `one file bound twice yields its id once`() {
        // A photo can be bound both as itself and as its pair's device side; the delete takes one id.
        val items = listOf(localOnly(URI_A), synced(URI_A, LINK_A))

        assertEquals(listOf(URI_A), staleLocationIds(items, setOf(URI_A)))
    }

    private companion object {
        const val URI_A = "content://media/external/images/media/1000012591"
        const val URI_B = "content://media/external/images/media/1000012592"
        const val URI_C = "content://media/external/images/media/1000012593"
        const val LINK_A = "link-a"
        const val LINK_B = "link-b"
    }
}
