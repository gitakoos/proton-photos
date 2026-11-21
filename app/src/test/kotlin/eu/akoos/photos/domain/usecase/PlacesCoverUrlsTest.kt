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
import org.junit.Assert.assertSame
import org.junit.Test

class PlacesCoverUrlsTest {

    private fun cloud(id: String, timeSec: Long, url: String? = null) = GalleryItem.CloudOnly(
        CloudPhoto(id, "share1", "vol1", timeSec, "$id.jpg", "image/jpeg", 1_000L, url, "rev-$id"),
    )

    private fun local(id: String, timeMs: Long) = GalleryItem.LocalOnly(
        LocalMediaItem("content://m/$id", timeMs, "$id.jpg", "image/jpeg", 1_000L, "Camera"),
    )

    private fun parts(city: String, cc: String, country: String) = PlaceLabelParts(city, cc, country)

    private val located = listOf(
        LocatedItem("a", 47.5, 19.0, cloud("a", 300)),
        LocatedItem("b", 47.6, 19.1, cloud("b", 100, url = "file:///old/b.jpg")),
        LocatedItem("c", 46.2, 20.1, local("c", 200_000)),
        LocatedItem("d", 48.8, 2.3, cloud("d", 50)),
    )
    private val labels = mapOf(
        "a" to parts("Budapest", "HU", "Hungary"),
        "b" to parts("Budapest", "HU", "Hungary"),
        "c" to parts("Szeged", "HU", "Hungary"),
        "d" to parts("Paris", "FR", "France"),
    )

    /** What the places stream built when every item carried its store URL before the grouping. */
    private fun groupedWithUrlsFirst(urls: Map<String, String>): PlacesData {
        val resolved = located.map { loc ->
            val item = loc.item
            if (item !is GalleryItem.CloudOnly) loc
            else {
                val url = urls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
                if (url == item.cloud.thumbnailUrl) loc
                else loc.copy(item = GalleryItem.CloudOnly(item.cloud.copy(thumbnailUrl = url)))
            }
        }
        return groupPlaces(resolved) { labels[it.id] }
    }

    @Test
    fun `covers carry the same URLs as resolving every item before grouping`() {
        val grouped = groupPlaces(located) { labels[it.id] }
        for (urls in listOf(
            emptyMap(),
            mapOf("a" to "file:///t/a.jpg"),
            mapOf("a" to "file:///t/a.jpg", "b" to "file:///t/b.jpg", "d" to "file:///t/d.jpg"),
            mapOf("zzz" to "file:///t/zzz.jpg"),
        )) {
            assertEquals(urls.toString(), groupedWithUrlsFirst(urls), grouped.withCoverUrls(urls))
        }
    }

    @Test
    fun `a cover with no new URL is handed back as it is`() {
        val grouped = groupPlaces(located) { labels[it.id] }
        val withUrls = grouped.withCoverUrls(mapOf("zzz" to "file:///t/zzz.jpg"))

        for ((before, after) in grouped.cities.zip(withUrls.cities)) assertSame(before.cover, after.cover)
        for ((before, after) in grouped.countries.zip(withUrls.countries)) assertSame(before.cover, after.cover)
    }
}
