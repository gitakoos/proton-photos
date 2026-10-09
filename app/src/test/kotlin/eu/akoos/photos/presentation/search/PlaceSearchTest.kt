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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** Typed city and country names finding photos by the place their stored location geocodes to. */
class PlaceSearchTest {

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

    private fun search(
        items: List<GalleryItem>,
        q: String,
        places: Map<String, String>,
        labels: Map<String, List<String>> = emptyMap(),
    ): List<String> =
        SearchFilter.apply(
            items = items, q = q, filter = ContentFilter(), category = GalleryFilter.All,
            foldedMonths = months, foldedCategoryNames = emptyMap(),
            foldedLabels = SearchFilter.foldLabels(labels), foldedPlaces = places,
        ).map { SearchFilter.displayNameOf(it) }

    @Test
    fun `a city or country name finds the photos taken there`() {
        val budapest = local("a.jpg")
        val elsewhere = local("b.jpg")
        val places = mapOf(
            budapest.stableId to "budapest hungary magyarorszag",
            elsewhere.stableId to "vienna austria osterreich",
        )
        assertEquals(listOf("a.jpg"), search(listOf(budapest, elsewhere), "budapest", places))
        assertEquals(listOf("a.jpg"), search(listOf(budapest, elsewhere), "HUNGARY", places))
        assertEquals(listOf("b.jpg"), search(listOf(budapest, elsewhere), "osterreich", places))
    }

    @Test
    fun `a photo without a stored location is never found by a place`() {
        val located = local("a.jpg")
        val unlocated = local("b.jpg")
        val places = mapOf(located.stableId to "paris france")
        assertEquals(listOf("a.jpg"), search(listOf(located, unlocated), "paris", places))
    }

    @Test
    fun `a backed-up photo is found under its linkId and under its device uri`() {
        val a = synced("a.jpg")
        val b = synced("b.jpg")
        val places = mapOf(
            (a.cloud.linkId) to "rome italy",
            (b.local.uri) to "rome italy",
        )
        assertEquals(listOf("a.jpg", "b.jpg"), search(listOf(a, b), "rome", places))
    }

    @Test
    fun `a cloud-only photo is found under its linkId`() {
        val a = cloud("a.jpg")
        assertEquals(listOf("a.jpg"), search(listOf(a), "oslo", mapOf(a.cloud.linkId to "oslo norway")))
    }

    @Test
    fun `a place and a month together narrow a multi-word query`() {
        val a = local("a.jpg")
        val places = mapOf(a.stableId to "lisbon portugal")
        assertEquals(listOf("a.jpg"), search(listOf(a), "lisbon june", places))
        assertTrue(search(listOf(a), "lisbon december", places).isEmpty())
    }

    @Test
    fun `photos named or labelled for the query lead the ones only their place reaches`() {
        val byPlace = local("a.jpg")
        val byLabel = local("b.jpg")
        val byName = local("paris-trip.jpg")
        val places = mapOf(byPlace.stableId to "paris france")
        val labels = mapOf(byLabel.stableId to listOf("Paris"))
        // The place counts with the labels, so a labelled photo and a located one keep the library's order.
        assertEquals(
            listOf("paris-trip.jpg", "a.jpg", "b.jpg"),
            search(listOf(byPlace, byLabel, byName), "paris", places, labels),
        )
    }

    @Test
    fun `an empty place map changes nothing`() {
        val a = local("sunset.jpg")
        assertEquals(listOf("sunset.jpg"), search(listOf(a), "sunset", emptyMap()))
        assertNull(SearchFilter.placesOf(a, emptyMap()))
    }

    @Test
    fun `the place text names the city and the country in both languages, folded and without repeats`() {
        val text = PhotoPlaceIndex.placeText("Budapest", "HU", Locale.forLanguageTag("hu"))
        assertTrue(text, "budapest" in text.split(' '))
        assertTrue(text, "magyarorszag" in text.split(' '))
        assertTrue(text, "hungary" in text.split(' '))
        val english = PhotoPlaceIndex.placeText("Vienna", "AT", Locale.ENGLISH)
        assertEquals(1, english.split(' ').count { it == "austria" })
    }

    @Test
    fun `spots within a few hundred metres share a cell and distant ones do not`() {
        val a = PhotoPlaceIndex.cell(47.49801, 19.04024)
        val near = PhotoPlaceIndex.cell(47.49802, 19.04026)
        val far = PhotoPlaceIndex.cell(48.20849, 16.37208)
        assertEquals(a, near)
        assertNotEquals(a, far)
    }

    @Test
    fun `cells keep both hemispheres and the date line apart`() {
        val cells = setOf(
            PhotoPlaceIndex.cell(-33.86, 151.21),
            PhotoPlaceIndex.cell(33.86, 151.21),
            PhotoPlaceIndex.cell(-33.86, -151.21),
            PhotoPlaceIndex.cell(33.86, -151.21),
        )
        assertEquals(4, cells.size)
    }
}
