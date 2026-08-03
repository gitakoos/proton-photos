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

import eu.akoos.photos.data.hidden.VaultMove
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins what the pager shows after the vault moved a photo out from under it.
 *
 * The list handed to the viewer is a snapshot, and a rename or a date edit moves a vault file, so
 * without this the page names a file that is gone and every action taken from it goes to that path.
 */
class ApplyVaultMovesTest {

    private val vaultUri = "file:///data/user/0/eu.akoos.photos/files/hidden/3f1a__1783507135000.jpg"
    private val movedUri = "file:///data/user/0/eu.akoos.photos/files/hidden/Beach trip__1783507135000.jpg"
    private val deviceUri = "content://media/external/images/media/42"

    private fun local(uri: String, name: String = "IMG_0001.jpg") = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    @Test
    fun `a renamed photo keeps its page under the new path and the new name`() {
        val items = listOf(GalleryItem.LocalOnly(local(vaultUri)))
        val moves = mapOf(vaultUri to VaultMove(movedUri, "Beach trip.jpg"))

        val after = applyVaultMoves(items, moves)

        val item = after.single() as GalleryItem.LocalOnly
        assertEquals(movedUri, item.local.uri)
        assertEquals("Beach trip.jpg", item.local.displayName)
    }

    @Test
    fun `a date edit moves the path and leaves the name as it was`() {
        val items = listOf(GalleryItem.LocalOnly(local(vaultUri, "Beach trip.jpg")))
        val moves = mapOf(vaultUri to VaultMove(movedUri))

        val item = applyVaultMoves(items, moves).single() as GalleryItem.LocalOnly

        assertEquals(movedUri, item.local.uri)
        assertEquals("Beach trip.jpg", item.local.displayName)
    }

    @Test
    fun `the list keeps its order and its other photos`() {
        val items = listOf(
            GalleryItem.LocalOnly(local(deviceUri)),
            GalleryItem.LocalOnly(local(vaultUri)),
        )

        val after = applyVaultMoves(items, mapOf(vaultUri to VaultMove(movedUri, "Beach trip.jpg")))

        assertEquals(2, after.size)
        assertEquals(deviceUri, (after[0] as GalleryItem.LocalOnly).local.uri)
        assertEquals(movedUri, (after[1] as GalleryItem.LocalOnly).local.uri)
    }

    @Test
    fun `nothing on the page having moved hands back the very same list`() {
        val items = listOf(GalleryItem.LocalOnly(local(deviceUri)))

        assertSame(items, applyVaultMoves(items, mapOf(vaultUri to VaultMove(movedUri))))
        assertSame(items, applyVaultMoves(items, emptyMap()))
    }
}
