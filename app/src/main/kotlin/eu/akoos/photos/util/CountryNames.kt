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

import java.util.Locale

/**
 * The name a country shows under: [displayNameOf] names the ISO [code], and the [storedName] the geocode
 * carried stands in when there is no code or the platform has no name for it (it then hands the code
 * back, or nothing); the code itself is the last resort. Display only, so keys and stored rows keep the
 * code. Pure.
 */
fun countryLabel(code: String, storedName: String, displayNameOf: (String) -> String): String {
    val iso = code.trim()
    val displayed = if (iso.isEmpty()) "" else displayNameOf(iso).trim()
    return when {
        displayed.isNotEmpty() && !displayed.equals(iso, ignoreCase = true) -> displayed
        storedName.isNotBlank() -> storedName
        else -> iso
    }
}

/** The ISO [code]'s country name in [locale] from the platform, with [countryLabel]'s fallbacks. */
fun countryName(code: String, locale: Locale, storedName: String = ""): String =
    countryLabel(code, storedName) { Locale("", it).getDisplayCountry(locale) }

/**
 * "City, Country" joined like [OfflineGeocoder.GeoLabel.label], the country named in [locale] as
 * [countryName] names it. A blank [city] leaves just the country, a country with no name just the city.
 */
fun cityCountryLabel(city: String, code: String, storedName: String, locale: Locale): String {
    val country = countryName(code, locale, storedName)
    return when {
        city.isBlank() -> country
        country.isBlank() -> city
        else -> "$city, $country"
    }
}
