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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which store answers "is this photo a favourite", the rule the grid hearts, the Favourites tab
 * and the viewer's heart all read through [isItemFavorite]. Drive PhotoTag 0 is the favourite tag.
 *
 * The two stores are not equal partners: a photo that lives only on the device has nothing but the
 * device-side set, and a backed-up one has a server tag that another client can change and that the
 * heart writes to directly. Plain JVM assertions over hand-built items, no Android and no Room.
 */
class FavoriteSourceTest {

    private val uri = "content://media/external/images/media/1"

    private fun local(uri: String = this.uri) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    private fun cloud(tags: Set<Int> = emptySet()) = CloudPhoto(
        linkId = "link1",
        shareId = "share1",
        volumeId = "vol1",
        captureTime = 1_700_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev1",
        tags = tags,
    )

    // region a device-only photo answers from the device-side set

    @Test
    fun `a device-only photo is favourite when the local set holds its uri`() {
        val item = GalleryItem.LocalOnly(local())

        assertTrue(isItemFavorite(item, favoriteIds = setOf(uri)))
        assertFalse(isItemFavorite(item, favoriteIds = emptySet()))
    }

    @Test
    fun `a device-only photo reads the set keyed by its own uri, not another photo's`() {
        val item = GalleryItem.LocalOnly(local())

        assertFalse(isItemFavorite(item, favoriteIds = setOf("content://media/external/images/media/2")))
    }

    // endregion
    // region a backed-up photo answers from the server tag alone

    @Test
    fun `a synced photo follows the tag and ignores the local set`() {
        val favourite = GalleryItem.Synced(cloud(tags = setOf(0)), local())
        val notFavourite = GalleryItem.Synced(cloud(tags = setOf(5)), local())

        // The tag decides both ways, whatever the device-side set happens to still hold.
        assertTrue(isItemFavorite(favourite, favoriteIds = emptySet()))
        assertFalse(isItemFavorite(notFavourite, favoriteIds = setOf(uri)))
    }

    @Test
    fun `a synced photo whose live tags say not-favourite is not favourite`() {
        // The exact shape of the viewer bug: the item is a snapshot taken when the grid was tapped,
        // so it still carries tag 0 after the un-favourite that already reached Drive. The live tag
        // set is the answer, and the stale one on the item must not put the heart back on.
        val stale = GalleryItem.Synced(cloud(tags = setOf(0)), local())

        assertFalse(isItemFavorite(stale, favoriteIds = setOf(uri), liveCloudTags = emptySet()))
        assertFalse(isItemFavorite(stale, favoriteIds = setOf(uri), liveCloudTags = setOf(5)))
    }

    @Test
    fun `live tags turn the heart on for an item whose snapshot has no tag 0`() {
        val stale = GalleryItem.Synced(cloud(tags = emptySet()), local())

        assertTrue(isItemFavorite(stale, favoriteIds = emptySet(), liveCloudTags = setOf(0)))
    }

    @Test
    fun `no live tags falls back to the set the item carries`() {
        // What a caller reading straight off the library flow passes, and what the viewer falls back
        // to while the library holds no row for the photo yet.
        val favourite = GalleryItem.Synced(cloud(tags = setOf(0)), local())
        val notFavourite = GalleryItem.Synced(cloud(tags = emptySet()), local())

        assertTrue(isItemFavorite(favourite, favoriteIds = emptySet(), liveCloudTags = null))
        assertFalse(isItemFavorite(notFavourite, favoriteIds = setOf(uri), liveCloudTags = null))
    }

    @Test
    fun `a cloud-only photo follows the tag`() {
        val favourite = GalleryItem.CloudOnly(cloud(tags = setOf(0, 2)))
        val notFavourite = GalleryItem.CloudOnly(cloud(tags = setOf(2)))

        assertTrue(isItemFavorite(favourite, favoriteIds = emptySet()))
        assertFalse(isItemFavorite(notFavourite, favoriteIds = setOf("link1")))
        assertFalse(isItemFavorite(favourite, favoriteIds = emptySet(), liveCloudTags = setOf(2)))
    }

    // endregion

    @Test
    fun `the local set never speaks for a backed-up photo under either key`() {
        // Both keys a device-side entry could plausibly carry: the local uri a Synced photo is
        // stored under, and the linkId a cloud-only one is.
        val synced = GalleryItem.Synced(cloud(tags = emptySet()), local())
        val cloudOnly = GalleryItem.CloudOnly(cloud(tags = emptySet()))
        val bothKeys = setOf(uri, "link1")

        assertFalse(isItemFavorite(synced, favoriteIds = bothKeys))
        assertFalse(isItemFavorite(cloudOnly, favoriteIds = bothKeys))
    }
}
