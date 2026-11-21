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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesOnePerPhotoTest {

    private fun cloudPhoto(id: String) = CloudPhoto(id, "share1", "vol1", 100L, "$id.jpg", "image/jpeg", 1_000L, null, "rev-$id")

    private fun localMedia(id: String) = LocalMediaItem("content://m/$id", 100_000L, "$id.jpg", "image/jpeg", 1_000L, "Camera")

    private val synced = GalleryItem.Synced(cloudPhoto("link1"), localMedia("1"))

    @Test
    fun `a synced photo located under both keys counts once`() {
        val rows = listOf(
            LocatedItem("link1", 47.53, 21.63, synced),
            LocatedItem("content://m/1", 47.53, 21.63, synced),
        )

        val kept = onePerPhoto(rows)

        assertEquals(listOf("content://m/1"), kept.map { it.id })
    }

    @Test
    fun `a synced photo keeps its device row whichever comes first`() {
        val rows = listOf(
            LocatedItem("content://m/1", 46.25, 20.15, synced),
            LocatedItem("link1", 47.53, 21.63, synced),
        )

        val kept = onePerPhoto(rows)

        assertEquals(listOf(46.25), kept.map { it.latitude })
    }

    @Test
    fun `a synced photo with only its cloud row is kept`() {
        val rows = listOf(LocatedItem("link1", 47.53, 21.63, synced))

        val kept = onePerPhoto(rows)

        assertEquals(listOf("link1"), kept.map { it.id })
    }

    @Test
    fun `different photos all stay`() {
        val cloudOnly = GalleryItem.CloudOnly(cloudPhoto("link2"))
        val localOnly = GalleryItem.LocalOnly(localMedia("3"))
        val rows = listOf(
            LocatedItem("link2", 41.9, 12.5, cloudOnly),
            LocatedItem("content://m/3", 48.8, 2.3, localOnly),
            LocatedItem("link1", 47.53, 21.63, synced),
        )

        val kept = onePerPhoto(rows)

        assertEquals(listOf("link2", "content://m/3", "link1"), kept.map { it.id })
    }

    @Test
    fun `no rows give no places`() {
        assertEquals(emptyList<LocatedItem>(), onePerPhoto(emptyList()))
    }
}
