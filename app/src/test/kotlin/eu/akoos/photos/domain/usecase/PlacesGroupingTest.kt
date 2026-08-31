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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesGroupingTest {

    private fun item(id: String, timeMs: Long) = GalleryItem.LocalOnly(
        LocalMediaItem(
            uri = "content://m/$id",
            dateTaken = timeMs,
            displayName = id,
            mimeType = "image/jpeg",
            sizeBytes = 1_000,
            bucketName = "Camera",
        ),
    )

    private fun located(id: String, lat: Double, lng: Double, timeMs: Long) =
        LocatedItem(id, lat, lng, item(id, timeMs))

    private fun parts(city: String, cc: String, country: String) = PlaceLabelParts(city, cc, country)

    @Test
    fun `two cities in one country fold into one country`() {
        val items = listOf(
            located("a", 47.5, 19.0, 100),
            located("b", 47.6, 19.1, 200), // same city as a
            located("c", 46.2, 20.1, 300), // a second city, same country
        )
        val labels = mapOf(
            "a" to parts("Budapest", "HU", "Hungary"),
            "b" to parts("Budapest", "HU", "Hungary"),
            "c" to parts("Szeged", "HU", "Hungary"),
        )
        val data = groupPlaces(items) { labels[it.id] }

        assertEquals(2, data.cities.size)
        assertEquals(1, data.countries.size)
        val hu = data.countries.first()
        assertEquals("HU", hu.countryCode)
        assertEquals(3, hu.count)
        assertEquals(2, hu.cityCount)
    }

    @Test
    fun `a city cover is its newest photo`() {
        val items = listOf(
            located("old", 47.5, 19.0, 100),
            located("new", 47.5, 19.0, 900),
            located("mid", 47.5, 19.0, 500),
        )
        val labels = items.associate { it.id to parts("Budapest", "HU", "Hungary") }
        val city = groupPlaces(items) { labels[it.id] }.cities.single()
        assertEquals(3, city.count)
        assertEquals(900L, city.cover?.captureTimeMs)
    }

    @Test
    fun `same city name in two countries stays apart`() {
        val items = listOf(located("us", 43.0, -89.4, 100), located("uk", 55.9, -3.2, 100))
        val labels = mapOf(
            "us" to parts("Springfield", "US", "United States"),
            "uk" to parts("Springfield", "GB", "United Kingdom"),
        )
        val data = groupPlaces(items) { labels[it.id] }
        assertEquals(2, data.cities.size)
        assertEquals(2, data.countries.size)
    }

    @Test
    fun `an unplaceable item is dropped`() {
        val items = listOf(located("a", 47.5, 19.0, 100), located("nowhere", 0.0, 0.0, 100))
        val labels = mapOf("a" to parts("Budapest", "HU", "Hungary"))
        val data = groupPlaces(items) { labels[it.id] }
        assertEquals(1, data.cities.size)
        assertEquals(1, data.cities.single().count)
    }

    @Test
    fun `busiest place leads both lists`() {
        val items = buildList {
            repeat(3) { add(located("big$it", 47.5, 19.0, it.toLong())) } // Budapest, HU x3
            add(located("small", 48.2, 16.4, 100)) // Vienna, AT x1
        }
        val labels = HashMap<String, PlaceLabelParts>()
        (0 until 3).forEach { labels["big$it"] = parts("Budapest", "HU", "Hungary") }
        labels["small"] = parts("Vienna", "AT", "Austria")
        val data = groupPlaces(items) { labels[it.id] }
        assertEquals("Budapest", data.cities.first().city)
        assertEquals("HU", data.countries.first().countryCode)
    }

    @Test
    fun `empty input yields empty groups`() {
        val data = groupPlaces(emptyList()) { parts("X", "XX", "X") }
        assertTrue(data.cities.isEmpty())
        assertTrue(data.countries.isEmpty())
    }

    @Test
    fun `a country with no covered city yields a null cover`() {
        // Defensive: if a city ever had a null cover, the country tolerates it.
        val items = listOf(located("a", 47.5, 19.0, 100))
        val data = groupPlaces(items) { parts("Budapest", "HU", "Hungary") }
        // The single city has a cover, so the country does too.
        assertEquals(items.single().item.captureTimeMs, data.countries.single().cover?.captureTimeMs)
        assertNull(groupPlaces(emptyList<LocatedItem>()) { null }.countries.firstOrNull()?.cover)
    }
}
