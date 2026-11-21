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

package eu.akoos.photos.presentation.memories

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.usecase.ThemeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the Collection turns the classified keys into theme cards over the live library. */
class ThemeBucketsTest {

    private fun item(uri: String, t: Long) = GalleryItem.LocalOnly(
        LocalMediaItem(uri = uri, dateTaken = t, displayName = "$uri.jpg", mimeType = "image/jpeg", sizeBytes = 1L, bucketName = "Camera"),
    )

    private val titles: (Int) -> String = { "t$it" }

    // Category ids used below: 0 is Food, 3 is Beach.
    @Test
    fun `items follow library order and the cover is the best scoring photo`() {
        val library = listOf(item("c", 300), item("b", 200), item("a", 100))
        val snapshot = ThemeSnapshot(mapOf(0 to listOf("a", "c")))
        val buckets = buildThemeBuckets(snapshot, library, titles)
        assertEquals(1, buckets.size)
        assertEquals(listOf("c", "a"), buckets[0].items.map { it.stableId })
        assertEquals("a", buckets[0].cover.stableId)
    }

    @Test
    fun `a key missing from the library, such as a vaulted photo, drops out`() {
        val library = listOf(item("a", 100))
        val snapshot = ThemeSnapshot(mapOf(0 to listOf("gone", "a"), 3 to listOf("gone")))
        val buckets = buildThemeBuckets(snapshot, library, titles)
        assertEquals(listOf(0), buckets.map { it.id })
        assertEquals("a", buckets[0].cover.stableId)
    }

    @Test
    fun `larger themes come first and an empty snapshot gives nothing`() {
        val library = listOf(item("a", 3), item("b", 2), item("c", 1))
        val snapshot = ThemeSnapshot(mapOf(0 to listOf("a"), 3 to listOf("a", "b", "c")))
        assertEquals(listOf(3, 0), buildThemeBuckets(snapshot, library, titles).map { it.id })
        assertTrue(buildThemeBuckets(ThemeSnapshot.EMPTY, library, titles).isEmpty())
    }
}
