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

import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two decisions a selection's favourite button makes: which way a press goes, and what the
 * user is told afterwards.
 *
 * The direction is the whole of the mixed-selection rule, and it reads the same two stores the single
 * photo heart does, so a device-only photo answers from the device-side set and a backed-up one from
 * Drive PhotoTag 0. Plain JVM assertions over hand-built items, no Android and no Room.
 */
class FavoriteSelectionTest {

    private val uri = "content://media/external/images/media/1"
    private val otherUri = "content://media/external/images/media/2"

    private fun local(uri: String = this.uri) = LocalMediaItem(
        uri = uri,
        dateTaken = 1_700_000_000_000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String = "link1", tags: Set<Int> = emptySet()) = CloudPhoto(
        linkId = linkId,
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

    // region which way a press goes

    @Test
    fun `a selection with nothing favourited turns them all on`() {
        val items = listOf(
            GalleryItem.LocalOnly(local()),
            GalleryItem.CloudOnly(cloud()),
        )

        assertTrue(favoriteTurnsOn(items, favoriteIds = emptySet()))
    }

    @Test
    fun `a selection where every photo is already a favourite turns them all off`() {
        val items = listOf(
            GalleryItem.LocalOnly(local()),
            GalleryItem.CloudOnly(cloud(tags = setOf(0))),
        )

        assertFalse(favoriteTurnsOn(items, favoriteIds = setOf(uri)))
    }

    @Test
    fun `one photo that is not a favourite makes the whole press an add`() {
        // The ordinary case: a fresh selection that happens to hold one already-favourite photo. The
        // press has to finish the job rather than un-favourite that single photo and leave the rest.
        val items = listOf(
            GalleryItem.LocalOnly(local()),
            GalleryItem.LocalOnly(local(otherUri)),
            GalleryItem.CloudOnly(cloud(tags = setOf(0))),
        )

        assertTrue(favoriteTurnsOn(items, favoriteIds = setOf(uri)))
    }

    @Test
    fun `pressing again with the same selection reverses the first press`() {
        // What the kept selection buys: the add settles every photo, so the very next read answers
        // remove and the second press takes them all back off.
        val items = listOf(
            GalleryItem.LocalOnly(local()),
            GalleryItem.CloudOnly(cloud(tags = setOf(0))),
        )

        assertTrue(favoriteTurnsOn(items, favoriteIds = emptySet()))
        assertFalse(favoriteTurnsOn(items, favoriteIds = setOf(uri)))
    }

    @Test
    fun `an empty selection has nothing to turn on`() {
        assertFalse(favoriteTurnsOn(emptyList(), favoriteIds = emptySet()))
        assertFalse(favoriteTurnsOn(emptyList(), favoriteIds = setOf(uri)))
    }

    // endregion
    // region the direction reads each photo's own store

    @Test
    fun `a backed-up photo follows its tag, not the device-side set`() {
        // Both keys a stale device-side entry could carry for it. Neither speaks for a backed-up
        // photo, so a selection of one already tagged still answers remove.
        val synced = listOf(GalleryItem.Synced(cloud(tags = setOf(0)), local()))
        val cloudOnly = listOf(GalleryItem.CloudOnly(cloud(tags = setOf(0))))

        assertFalse(favoriteTurnsOn(synced, favoriteIds = emptySet()))
        assertFalse(favoriteTurnsOn(cloudOnly, favoriteIds = setOf(uri, "link1")))
    }

    @Test
    fun `a device-only photo follows the set keyed by its own uri`() {
        val items = listOf(GalleryItem.LocalOnly(local()))

        assertFalse(favoriteTurnsOn(items, favoriteIds = setOf(uri)))
        assertTrue(favoriteTurnsOn(items, favoriteIds = setOf(otherUri)))
    }

    @Test
    fun `a selection of bare cloud photos decides the same way`() {
        // The album surface's rows. Every one is backed up, so the answer comes from the tags alone.
        assertTrue(favoriteTurnsOnForCloudPhotos(listOf(cloud(), cloud("link2", tags = setOf(0)))))
        assertFalse(favoriteTurnsOnForCloudPhotos(listOf(cloud(tags = setOf(0, 2)), cloud("link2", tags = setOf(0)))))
        assertFalse(favoriteTurnsOnForCloudPhotos(emptyList()))
    }

    // endregion
    // region the selection follows the write

    @Test
    fun `a settled add carries the tag onto the copy the selection holds`() {
        // A backed-up photo's heart is a tag on the item, and the selection holds the copy taken when
        // the photo was picked. Without this the very next read would still answer add.
        val picked = GalleryItem.CloudOnly(cloud())
        assertTrue(favoriteTurnsOn(listOf(picked), favoriteIds = emptySet()))

        val settled = withFavoriteSettled(picked, setOf("link1"), favorite = true)

        assertFalse(favoriteTurnsOn(listOf(settled), favoriteIds = emptySet()))
    }

    @Test
    fun `a settled remove takes the tag back off`() {
        val picked = GalleryItem.Synced(cloud(tags = setOf(0)), local())
        assertFalse(favoriteTurnsOn(listOf(picked), favoriteIds = emptySet()))

        val settled = withFavoriteSettled(picked, setOf("link1"), favorite = false)

        assertTrue(favoriteTurnsOn(listOf(settled), favoriteIds = emptySet()))
    }

    @Test
    fun `a photo the write did not settle keeps the state it is still in`() {
        // A write Drive refused leaves the photo un-favourited, so the next press has to go on
        // offering the add for it rather than reading as though it had landed.
        val refused = GalleryItem.CloudOnly(cloud("link2"))

        val untouched = withFavoriteSettled(refused, setOf("link1"), favorite = true)

        assertEquals(refused, untouched)
        assertTrue(favoriteTurnsOn(listOf(untouched), favoriteIds = emptySet()))
    }

    @Test
    fun `a device-only photo passes through as it came`() {
        // Its heart is the device-side set, which every surface reads as a live flow, so there is no
        // tag on the item to re-state.
        val picked = GalleryItem.LocalOnly(local())

        assertEquals(picked, withFavoriteSettled(picked, setOf(uri), favorite = true))
    }

    @Test
    fun `the photo's other tags survive the rewrite`() {
        // Tag 0 is the favourite, the rest are categories the same field carries.
        val picked = GalleryItem.CloudOnly(cloud(tags = setOf(2, 8)))

        val added = withFavoriteSettled(picked, setOf("link1"), favorite = true)
        val removed = withFavoriteSettled(added, setOf("link1"), favorite = false)

        assertEquals(setOf(0, 2, 8), (added as GalleryItem.CloudOnly).cloud.tags)
        assertEquals(setOf(2, 8), (removed as GalleryItem.CloudOnly).cloud.tags)
    }

    // endregion
    // region what the user is told

    @Test
    fun `a clean batch says nothing`() {
        // Cheap, visible on every tile it touched and undone by pressing again: a snackbar per press
        // would be noise.
        assertEquals(FavoriteOutcome.AllChanged, favoriteOutcome(changed = 12, failed = 0))
        assertNull(favoriteOutcome(changed = 12, failed = 0).message())
    }

    @Test
    fun `a batch with nothing left to change also says nothing`() {
        assertEquals(FavoriteOutcome.AllChanged, favoriteOutcome(changed = 0, failed = 0))
        assertNull(favoriteOutcome(changed = 0, failed = 0).message())
    }

    @Test
    fun `a batch that landed nowhere says so`() {
        assertEquals(FavoriteOutcome.NoneChanged, favoriteOutcome(changed = 0, failed = 5))
        assertEquals(
            UiMessage(R.string.favorite_selection_failed),
            favoriteOutcome(changed = 0, failed = 5).message(),
        )
    }

    @Test
    fun `a partly refused batch names both halves`() {
        assertEquals(FavoriteOutcome.SomeChanged(7, 3), favoriteOutcome(changed = 7, failed = 3))
        assertEquals(
            UiMessage(R.string.favorite_selection_partial, listOf(7, 3)),
            favoriteOutcome(changed = 7, failed = 3).message(),
        )
    }

    // endregion
}
