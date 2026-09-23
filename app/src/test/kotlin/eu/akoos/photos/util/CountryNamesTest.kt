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
import org.junit.Test
import java.util.Locale

/**
 * Pins the country naming every surface shares: the ISO code named in the requested language, the
 * stored geocode name when the platform has none, the code as the last resort, and the "City, Country"
 * join.
 */
class CountryNamesTest {

    private val hungarian = Locale.forLanguageTag("hu")

    @Test
    fun `a code is named in the requested language`() {
        assertEquals("Magyarország", countryName("HU", hungarian))
        assertEquals("Németország", countryName("DE", hungarian, storedName = "Germany"))
        assertEquals("Germany", countryName("DE", Locale.ENGLISH))
        assertEquals("Germany", countryName("DE", Locale.ENGLISH, storedName = "Deutschland"))
    }

    @Test
    fun `a blank code falls back to the stored name`() {
        assertEquals("Somewhere", countryName("", Locale.ENGLISH, storedName = "Somewhere"))
        assertEquals("Somewhere", countryName("  ", Locale.ENGLISH, storedName = "Somewhere"))
        assertEquals("", countryName("", Locale.ENGLISH))
    }

    @Test
    fun `an unknown code falls back to the stored name, then to the code`() {
        // The platform hands a code it has no name for back as it is.
        assertEquals("Oceania", countryName("QQ", Locale.ENGLISH, storedName = "Oceania"))
        assertEquals("QQ", countryName("QQ", Locale.ENGLISH))
    }

    @Test
    fun `the platform echoing the code or naming nothing falls back`() {
        assertEquals("Kosovo", countryLabel("XK", "Kosovo") { it })
        assertEquals("Kosovo", countryLabel("XK", "Kosovo") { it.lowercase() })
        assertEquals("Kosovo", countryLabel("XK", "Kosovo") { "  " })
        assertEquals("XK", countryLabel("XK", "") { it })
        assertEquals("Magyarország", countryLabel(" HU ", "Hungary") { if (it == "HU") " Magyarország " else "" })
    }

    @Test
    fun `a blank code never reaches the platform`() {
        assertEquals("Somewhere", countryLabel("", "Somewhere") { error("looked up a blank code") })
    }

    @Test
    fun `city and country join like the geocoder label`() {
        assertEquals("Budapest, Magyarország", cityCountryLabel("Budapest", "HU", "Hungary", hungarian))
        assertEquals("Berlin, Germany", cityCountryLabel("Berlin", "DE", "Deutschland", Locale.ENGLISH))
        assertEquals("Atlantis, Oceania", cityCountryLabel("Atlantis", "QQ", "Oceania", Locale.ENGLISH))
    }

    @Test
    fun `a blank city leaves just the country`() {
        assertEquals("Magyarország", cityCountryLabel("", "HU", "Hungary", hungarian))
        assertEquals("Germany", cityCountryLabel("  ", "DE", "", Locale.ENGLISH))
    }

    @Test
    fun `a country with no name leaves just the city`() {
        assertEquals("Atlantis", cityCountryLabel("Atlantis", "", "", Locale.ENGLISH))
    }
}
