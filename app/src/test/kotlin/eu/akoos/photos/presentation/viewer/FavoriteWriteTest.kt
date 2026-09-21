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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what one favourite tap writes and where. The heart is read back from the device-side set for a
 * photo that lives only on the device and from Drive PhotoTag 0 for a backed-up one, so the write has
 * to divide the same way or a tap lands somewhere nothing looks.
 *
 * Covers both pure halves: [favoriteIdsAfterToggle] for the device-side set (including the stale
 * entries it clears as photos are touched) and [favoriteAfterCloudWrite] for where the heart settles
 * once the server has answered. Plain JVM assertions over hand-built items, no Android and no Room.
 */
class FavoriteWriteTest {

    private val uri = "content://media/external/images/media/1"
    private val linkId = "link1"
    private val otherUri = "content://media/external/images/media/2"

    private fun local(uri: String = this.uri) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String = this.linkId) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    // region a device-only photo writes the device-side set

    @Test
    fun `a device-only photo puts its uri into the set and takes it back out`() {
        val item = GalleryItem.LocalOnly(local())

        assertEquals(setOf(uri), favoriteIdsAfterToggle(item, emptySet(), nowFavorite = true))
        assertEquals(emptySet<String>(), favoriteIdsAfterToggle(item, setOf(uri), nowFavorite = false))
    }

    @Test
    fun `a device-only photo leaves every other entry in the set alone`() {
        val item = GalleryItem.LocalOnly(local())

        assertEquals(
            setOf(uri, otherUri),
            favoriteIdsAfterToggle(item, setOf(otherUri), nowFavorite = true),
        )
        assertEquals(
            setOf(otherUri),
            favoriteIdsAfterToggle(item, setOf(uri, otherUri), nowFavorite = false),
        )
    }

    // endregion
    // region a backed-up photo writes no device-side entry

    @Test
    fun `favouriting a synced photo adds nothing to the set`() {
        val item = GalleryItem.Synced(cloud(), local())

        // The heart goes to Drive tag 0. An entry here would be read by nobody.
        assertEquals(emptySet<String>(), favoriteIdsAfterToggle(item, emptySet(), nowFavorite = true))
        assertFalse(uri in favoriteIdsAfterToggle(item, emptySet(), nowFavorite = true))
        assertFalse(linkId in favoriteIdsAfterToggle(item, emptySet(), nowFavorite = true))
    }

    @Test
    fun `favouriting a cloud-only photo adds nothing to the set`() {
        val item = GalleryItem.CloudOnly(cloud())

        assertEquals(emptySet<String>(), favoriteIdsAfterToggle(item, emptySet(), nowFavorite = true))
    }

    // endregion
    // region the set self-heals under both keys as photos are touched

    @Test
    fun `touching a synced photo clears the uri left behind when it was backed up`() {
        val item = GalleryItem.Synced(cloud(), local())

        // The shape a photo favourited on the device and uploaded afterwards leaves behind.
        assertEquals(
            emptySet<String>(),
            favoriteIdsAfterToggle(item, setOf(uri), nowFavorite = true),
        )
        assertEquals(
            emptySet<String>(),
            favoriteIdsAfterToggle(item, setOf(uri), nowFavorite = false),
        )
    }

    @Test
    fun `touching a synced photo clears the linkId written while it was cloud-only`() {
        // A photo favourited in the cloud and then downloaded is keyed on its uri from then on, so
        // no un-favourite can reach the linkId entry. This is the only place that takes it out.
        val item = GalleryItem.Synced(cloud(), local())

        assertEquals(emptySet<String>(), favoriteIdsAfterToggle(item, setOf(linkId), nowFavorite = false))
        assertEquals(emptySet<String>(), favoriteIdsAfterToggle(item, setOf(uri, linkId), nowFavorite = true))
    }

    @Test
    fun `touching a cloud-only photo clears its linkId`() {
        val item = GalleryItem.CloudOnly(cloud())

        assertEquals(emptySet<String>(), favoriteIdsAfterToggle(item, setOf(linkId), nowFavorite = true))
    }

    @Test
    fun `the sweep only ever takes out the touched photo's own keys`() {
        val item = GalleryItem.Synced(cloud(), local())

        assertEquals(
            setOf(otherUri, "link2"),
            favoriteIdsAfterToggle(item, setOf(uri, linkId, otherUri, "link2"), nowFavorite = true),
        )
    }

    // endregion
    // region a rejected cloud write leaves the heart where it was

    @Test
    fun `a rejected cloud write puts the heart back rather than leaving it flipped`() {
        // Offline, signed out, or a server that refused: the tap flipped the heart optimistically and
        // the write never landed, so the state Drive still holds is the one to show.
        assertFalse(favoriteAfterCloudWrite(previous = false, attempted = true, ok = false))
        assertTrue(favoriteAfterCloudWrite(previous = true, attempted = false, ok = false))
    }

    @Test
    fun `a cloud write that lands keeps the flip`() {
        assertTrue(favoriteAfterCloudWrite(previous = false, attempted = true, ok = true))
        assertFalse(favoriteAfterCloudWrite(previous = true, attempted = false, ok = true))
    }

    // endregion
}
