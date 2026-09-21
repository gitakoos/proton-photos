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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the forward-geocode helpers extracted from [OfflineGeocoder]: TSV parsing, the
 * prefix-over-substring name ranking with a country filter and cap, the distinct + sorted country
 * list, and the country centroid. A synthetic in-memory dataset feeds every case, so no asset is read
 * and no Android / Context is touched. Diacritic folding is pinned by matching "zurich" to "Zürich".
 */
class OfflineGeocoderForwardTest {

    private fun row(name: String, cc: String, lat: Double, lon: Double) =
        OfflineGeocoder.CityRow(name = name, countryCode = cc, latitude = lat, longitude = lon)

    private fun dataset(): List<OfflineGeocoder.CityRow> = listOf(
        row("San Francisco", "US", 37.77, -122.42),
        row("Santiago", "CL", -33.45, -70.66),
        row("Busan", "KR", 35.18, 129.08),
        row("Zürich", "CH", 47.37, 8.54),
        row("Paris", "FR", 48.85, 2.35),
        row("Parma", "IT", 44.80, 10.33),
        row("San Diego", "US", 32.72, -117.16),
    )

    // parseCityRows: valid rows in, malformed rows dropped.

    @Test
    fun `parseCityRows keeps valid rows and skips short or non-numeric ones`() {
        val lines = listOf(
            "San Francisco\t37.77\t-122.42\tUS",
            "Busan\t35.18\t129.08\tKR",
            "Broken\tnotanumber\t1.0\tXX",
            "TooFew\t1.0\t2.0",
            "",
        ).asSequence()

        val rows = OfflineGeocoder.parseCityRows(lines)

        assertEquals(2, rows.size)
        assertEquals("San Francisco", rows[0].name)
        assertEquals("US", rows[0].countryCode)
        assertEquals(37.77, rows[0].latitude, 1e-9)
        assertEquals(-122.42, rows[0].longitude, 1e-9)
        assertEquals("Busan", rows[1].name)
    }

    // searchCities: ranking, filtering, cap, folding.

    @Test
    fun `searchCities ranks prefix matches ahead of substring matches`() {
        val names = OfflineGeocoder.searchCities(dataset(), "san", null, 30).map { it.name }
        // Prefix matches (San Diego, San Francisco, Santiago) sorted by name, then the substring (Busan).
        assertEquals(listOf("San Diego", "San Francisco", "Santiago", "Busan"), names)
    }

    @Test
    fun `searchCities restricts to the requested country`() {
        val hits = OfflineGeocoder.searchCities(dataset(), "san", "US", 30)
        assertEquals(listOf("San Diego", "San Francisco"), hits.map { it.name })
        assertTrue(hits.all { it.countryCode == "US" })
    }

    @Test
    fun `searchCities caps at the limit`() {
        val hits = OfflineGeocoder.searchCities(dataset(), "san", null, 2)
        assertEquals(2, hits.size)
        assertEquals(listOf("San Diego", "San Francisco"), hits.map { it.name })
    }

    @Test
    fun `searchCities matches ignoring diacritics`() {
        val hits = OfflineGeocoder.searchCities(dataset(), "zurich", null, 30)
        assertEquals(1, hits.size)
        assertEquals("Zürich", hits[0].name)
    }

    @Test
    fun `searchCities ranks an exact name first`() {
        val hits = OfflineGeocoder.searchCities(dataset(), "parma", null, 30)
        assertEquals("Parma", hits.first().name)
    }

    @Test
    fun `searchCities returns nothing for a blank query or a non-positive limit`() {
        assertTrue(OfflineGeocoder.searchCities(dataset(), "   ", null, 30).isEmpty())
        assertTrue(OfflineGeocoder.searchCities(dataset(), "san", null, 0).isEmpty())
    }

    // centroidOf: mean of a country's rows.

    @Test
    fun `centroidOf returns the mean coordinate of a country's cities`() {
        val point = OfflineGeocoder.centroidOf(dataset(), "US")
        assertEquals(35.245, point!!.first, 1e-9)
        assertEquals(-119.79, point.second, 1e-9)
    }

    @Test
    fun `centroidOf ignores country-code case`() {
        assertEquals(
            OfflineGeocoder.centroidOf(dataset(), "US"),
            OfflineGeocoder.centroidOf(dataset(), "us"),
        )
    }

    @Test
    fun `centroidOf is null for a country with no cities`() {
        assertNull(OfflineGeocoder.centroidOf(dataset(), "ZZ"))
    }

    // distinctCountries: distinct, mapped, sorted.

    @Test
    fun `distinctCountries returns distinct codes sorted by display name`() {
        val countries = OfflineGeocoder.distinctCountries(dataset()) { it }
        // US appears twice in the dataset but collapses to one entry; identity mapper sorts by code.
        assertEquals(listOf("CH", "CL", "FR", "IT", "KR", "US"), countries.map { it.code })
    }

    @Test
    fun `distinctCountries applies the display-name mapper and sorts by it`() {
        val mapper: (String) -> String = { code -> if (code == "US") "Aaa" else "z-$code" }
        val countries = OfflineGeocoder.distinctCountries(dataset(), mapper)
        // "Aaa" (US) sorts ahead of every "z-…" entry.
        assertEquals("US", countries.first().code)
        assertEquals("Aaa", countries.first().displayName)
    }
}
