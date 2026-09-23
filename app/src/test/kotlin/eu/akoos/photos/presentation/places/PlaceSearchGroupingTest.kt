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

package eu.akoos.photos.presentation.places

import eu.akoos.photos.domain.usecase.PlaceCity
import eu.akoos.photos.util.countryLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

/**
 * Pins the place search's country headings: one per country, named from the ISO code in the app's
 * language with the stored name as the fallback, and the blocks in the alphabetical order of what the
 * headings say.
 */
class PlaceSearchGroupingTest {

    private fun city(name: String, code: String, storedCountry: String) = PlaceCity(
        city = name,
        countryCode = code,
        countryName = storedCountry,
        latitude = 0.0,
        longitude = 0.0,
        count = 1,
        cover = null,
    )

    /** Country names as the platform gives them with the app set to Hungarian. */
    private val hungarian = mapOf("AT" to "Ausztria", "DE" to "Németország", "HU" to "Magyarország")

    private fun group(
        cities: List<PlaceCity>,
        names: Map<String, String> = hungarian,
        order: Comparator<in String> = naturalOrder(),
    ): List<PlaceSearchEntry> = groupPlaceSearch(cities, order) { city ->
        countryLabel(city.countryCode, city.countryName) { code -> names[code].orEmpty() }
    }

    /** The entries in short: "# label" for a heading, the city name for a city. */
    private fun shape(entries: List<PlaceSearchEntry>): List<String> = entries.map { entry ->
        when (entry) {
            is PlaceSearchEntry.Country -> "# " + entry.label
            is PlaceSearchEntry.City -> entry.city.city
        }
    }

    @Test
    fun `each country gets one heading over its cities`() {
        val cities = listOf(
            city("Szeged", "HU", "Hungary"),
            city("Wien", "AT", "Austria"),
            city("Budapest", "HU", "Hungary"),
            city("Graz", "AT", "Austria"),
        )

        assertEquals(
            listOf("# Ausztria", "Graz", "Wien", "# Magyarország", "Budapest", "Szeged"),
            shape(group(cities)),
        )
    }

    @Test
    fun `a heading names the country in the app language, not the stored one`() {
        assertEquals(listOf("# Németország", "Berlin"), shape(group(listOf(city("Berlin", "DE", "Germany")))))
    }

    @Test
    fun `cities stored under different languages still share one heading`() {
        // Names geocoded before a language switch can sit beside newer ones; the code keeps the
        // country in one block.
        val cities = listOf(city("Budapest", "HU", "Hungary"), city("Szeged", "HU", "Magyarország"))

        assertEquals(listOf("# Magyarország", "Budapest", "Szeged"), shape(group(cities)))
    }

    @Test
    fun `the stored name stands in when the code has no name`() {
        // The platform hands an unknown code back as it is, or nothing at all.
        assertEquals("Kosovo", countryLabel("XK", "Kosovo") { it })
        assertEquals("Kosovo", countryLabel("XK", "Kosovo") { "" })
        assertEquals("Somewhere", countryLabel("", "Somewhere") { "Unused" })
        assertEquals("XK", countryLabel("XK", "") { it })
        assertEquals("Magyarország", countryLabel("HU", "Hungary") { "Magyarország" })
    }

    @Test
    fun `accented headings sort where the language puts them`() {
        val french = mapOf("DE" to "Allemagne", "US" to "États-Unis", "ZM" to "Zambie")
        val cities = listOf(
            city("Lusaka", "ZM", "Zambia"),
            city("Boston", "US", "United States"),
            city("Berlin", "DE", "Germany"),
        )

        assertEquals(
            listOf("# Allemagne", "Berlin", "# États-Unis", "Boston", "# Zambie", "Lusaka"),
            shape(group(cities, french, Collator.getInstance(Locale.FRENCH))),
        )
    }

    @Test
    fun `keys stay unique when two countries share a city name`() {
        val english = mapOf("GB" to "United Kingdom", "US" to "United States")
        val cities = listOf(city("Springfield", "US", "United States"), city("Springfield", "GB", "United Kingdom"))
        val entries = group(cities, english)

        assertEquals(
            listOf("# United Kingdom", "Springfield", "# United States", "Springfield"),
            shape(entries),
        )
        val keys = entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `no cities, no headings`() {
        assertEquals(emptyList<PlaceSearchEntry>(), group(emptyList()))
    }

    // ── the search filter ────────────────────────────────────────────────────────────────────────

    private val library = listOf(
        city("Budapest", "HU", "Hungary"),
        city("Debrecen", "HU", "Hungary"),
        city("Wien", "AT", "Austria"),
        city("Berlin", "DE", "Germany"),
    )

    private fun search(query: String, names: Map<String, String> = hungarian): List<String> =
        filterPlaceCities(library, query) { city ->
            countryLabel(city.countryCode, city.countryName) { code -> names[code].orEmpty() }
        }.map { it.city }

    @Test
    fun `an empty query lists every city`() {
        assertEquals(listOf("Budapest", "Debrecen", "Wien", "Berlin"), search("  "))
    }

    @Test
    fun `a city is found by its own name`() {
        assertEquals(listOf("Debrecen"), search("debre"))
    }

    @Test
    fun `a country is found by the name its heading shows and lists all its cities`() {
        assertEquals(listOf("Budapest", "Debrecen"), search("magyar"))
        assertEquals(listOf("Wien"), search("Ausztria"))
    }

    @Test
    fun `a country is found by its stored name too`() {
        assertEquals(listOf("Budapest", "Debrecen"), search("hungary"))
    }

    @Test
    fun `country matching ignores case and accents`() {
        assertEquals(listOf("Berlin"), search("NEMETORSZAG"))
        assertEquals(listOf("Budapest", "Debrecen"), search("magyarorszag"))
    }

    @Test
    fun `a query matching nothing lists nothing`() {
        assertEquals(emptyList<String>(), search("tokyo"))
    }

    @Test
    fun `without a heading lookup the stored country name still matches`() {
        assertEquals(listOf("Berlin"), filterPlaceCities(library, "germ").map { it.city })
    }
}
