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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.GalleryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** Typed words finding photos by their labels, through the same match the rest of the haystack uses. */
class LabelSearchTest {

    private val day = Calendar.getInstance().apply { clear(); set(2024, 5, 15, 12, 0, 0) }.timeInMillis
    private val months = SearchFilter.foldedMonthNames(Locale.ENGLISH)

    private fun local(name: String) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = "content://media/$name", dateTaken = day, displayName = name, mimeType = "image/jpeg",
            sizeBytes = 1_000L, bucketName = null,
        ),
    )

    private fun cloudPhoto(name: String) = CloudPhoto(
        linkId = "link-$name", shareId = "share", volumeId = "vol", captureTime = day / 1000L,
        displayName = name, mimeType = "image/jpeg", sizeBytes = 1_000L, thumbnailUrl = null,
        revisionId = "rev",
    )

    private fun cloud(name: String) = GalleryItem.CloudOnly(cloudPhoto(name))

    private fun synced(name: String) = GalleryItem.Synced(
        cloud = cloudPhoto(name),
        local = LocalMediaItem(
            uri = "content://media/$name", dateTaken = day, displayName = name,
            mimeType = "image/jpeg", sizeBytes = 1_000L, bucketName = null,
        ),
    )

    private fun search(items: List<GalleryItem>, q: String, labels: Map<String, List<String>>): List<String> =
        SearchFilter.apply(
            items = items, q = q, filter = ContentFilter(), category = GalleryFilter.All,
            foldedMonths = months, foldedCategoryNames = emptyMap(),
            foldedLabels = SearchFilter.foldLabels(labels),
        ).map { SearchFilter.displayNameOf(it) }

    @Test
    fun `a typed word finds a photo by its label, case and accents ignored`() {
        val a = local("a.jpg"); val b = local("b.jpg")
        val labels = mapOf(a.stableId to listOf("Nyaralás"))
        assertEquals(listOf("a.jpg"), search(listOf(a, b), "NYARALAS", labels))
        assertEquals(listOf("a.jpg"), search(listOf(a, b), "nyar", labels))
    }

    @Test
    fun `a label with spaces matches each of its words`() {
        val a = cloud("a.jpg")
        val labels = mapOf(a.stableId to listOf("Lake Balaton"))
        assertEquals(listOf("a.jpg"), search(listOf(a), "balaton lake", labels))
    }

    @Test
    fun `a backed-up photo is found by labels under its linkId and under its device uri`() {
        val s = synced("s.jpg")
        assertEquals(listOf("s.jpg"), search(listOf(s), "beach", mapOf(s.cloud.linkId to listOf("Beach"))))
        assertEquals(listOf("s.jpg"), search(listOf(s), "beach", mapOf(s.local.uri to listOf("Beach"))))
        val both = mapOf(s.cloud.linkId to listOf("Beach"), s.local.uri to listOf("Family"))
        assertEquals(listOf("s.jpg"), search(listOf(s), "beach family", both))
    }

    @Test
    fun `a device photo's uri key does not leak to a cloud photo of the same name`() {
        val c = cloud("x.jpg")
        assertEquals(emptyList<String>(), search(listOf(c), "beach", mapOf("content://media/x.jpg" to listOf("Beach"))))
    }

    @Test
    fun `a photo without labels is unaffected`() {
        val a = local("sunset.jpg"); val b = local("b.jpg")
        val labels = mapOf(b.stableId to listOf("Work"))
        assertEquals(listOf("sunset.jpg"), search(listOf(a, b), "sunset", labels))
        assertEquals(listOf("b.jpg"), search(listOf(a, b), "work", labels))
        assertNull(SearchFilter.labelsOf(a, SearchFilter.foldLabels(labels)))
    }

    @Test
    fun `every word must match, a label word and a filename word together`() {
        val a = local("IMG_100.jpg"); val b = local("IMG_200.jpg"); val c = local("DSC_100.jpg")
        val labels = mapOf(a.stableId to listOf("Wedding"), c.stableId to listOf("Wedding"))
        assertEquals(listOf("IMG_100.jpg"), search(listOf(a, b, c), "wedding img", labels))
        assertEquals(emptyList<String>(), search(listOf(a, b, c), "wedding 200", labels))
    }

    @Test
    fun `matches lead with the photos named for the query, then the labelled, then the rest`() {
        // All three are dated June 2024, so the month alone reaches them; the library lists them in
        // the order the result must undo.
        val dateOnly = local("a.jpg")
        val labelled = local("b.jpg")
        val named = local("june-party.jpg")
        val labels = mapOf(labelled.stableId to listOf("June trip"))
        assertEquals(
            listOf("june-party.jpg", "b.jpg", "a.jpg"),
            search(listOf(dateOnly, labelled, named), "june", labels),
        )
    }

    @Test
    fun `labels separated by commas find the photos that have either`() {
        val a = local("a.jpg")
        val b = local("b.jpg")
        val c = local("c.jpg")
        val labels = mapOf(a.stableId to listOf("Beach"), b.stableId to listOf("Work"))
        assertEquals(listOf("a.jpg", "b.jpg"), search(listOf(a, b, c), "beach, work", labels))
        assertEquals(listOf("a.jpg", "b.jpg"), search(listOf(a, b, c), "work,beach", labels))
        assertEquals(listOf("a.jpg"), search(listOf(a, b, c), "beach", labels))
    }

    @Test
    fun `words inside one comma part must all match, so a part can narrow`() {
        val a = local("a.jpg")
        val b = local("b.jpg")
        val c = local("c.jpg")
        val labels = mapOf(
            a.stableId to listOf("Beach", "Sunset"),
            b.stableId to listOf("Beach"),
            c.stableId to listOf("Work"),
        )
        assertEquals(listOf("a.jpg"), search(listOf(a, b, c), "beach sunset", labels))
        assertEquals(listOf("a.jpg", "c.jpg"), search(listOf(a, b, c), "beach sunset, work", labels))
    }

    @Test
    fun `a trailing or doubled comma does not change the query, and commas alone are no query`() {
        val a = local("a.jpg")
        val b = local("b.jpg")
        val labels = mapOf(a.stableId to listOf("Beach"), b.stableId to listOf("Work"))
        assertEquals(listOf("a.jpg"), search(listOf(a, b), "beach,", labels))
        assertEquals(listOf("a.jpg", "b.jpg"), search(listOf(a, b), "beach,, work", labels))
        assertEquals(emptyList<String>(), search(listOf(a, b), ",", labels))
        assertEquals(emptyList<String>(), search(listOf(a, b), " , ,", labels))
    }

    @Test
    fun `a photo matching two comma parts ranks by the better one and is listed once`() {
        val dateOnly = local("a.jpg")
        val named = local("beach.jpg")
        val labels = mapOf(dateOnly.stableId to listOf("Work"))
        // "beach" names the second photo (name tier); "work" reaches the first by its label.
        assertEquals(
            listOf("beach.jpg", "a.jpg"),
            search(listOf(dateOnly, named), "work, beach", labels),
        )
        assertEquals(
            listOf("beach.jpg"),
            search(listOf(dateOnly, named), "beach, beach", labels),
        )
    }

    @Test
    fun `photos in the same tier keep the order the library gave`() {
        val first = local("june-1.jpg")
        val second = local("june-2.jpg")
        val third = local("june-3.jpg")
        assertEquals(
            listOf("june-1.jpg", "june-2.jpg", "june-3.jpg"),
            search(listOf(first, second, third), "june", emptyMap()),
        )
    }

    @Test
    fun `a name and a label together can satisfy a multi-word query in the labelled tier`() {
        val mixed = local("beach.jpg")
        val named = local("beach-sunset.jpg")
        val labels = mapOf(mixed.stableId to listOf("Sunset"))
        assertEquals(
            listOf("beach-sunset.jpg", "beach.jpg"),
            search(listOf(mixed, named), "beach sunset", labels),
        )
    }
}
