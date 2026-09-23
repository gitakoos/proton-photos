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

package eu.akoos.photos.presentation.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which photos the place page gathers: a photo belongs when its coordinates geocode to the page's
 * city in the page's country. The two parts compare as they are, the way the Places list groups a city,
 * so the page holds the photos its Places card counts.
 */
class LocationPlaceMatchTest {

    @Test
    fun `the same city in the same country belongs`() {
        assertTrue(isPhotoInPlace("Budapest", "HU", photoCity = "Budapest", photoCountryCode = "HU"))
    }

    @Test
    fun `a town of the same name in another country does not`() {
        assertFalse(isPhotoInPlace("Paris", "FR", photoCity = "Paris", photoCountryCode = "US"))
    }

    @Test
    fun `another city in the same country does not`() {
        assertFalse(isPhotoInPlace("Budapest", "HU", photoCity = "Debrecen", photoCountryCode = "HU"))
    }

    @Test
    fun `a blank part is no wildcard`() {
        assertFalse(isPhotoInPlace("Budapest", "HU", photoCity = "", photoCountryCode = "HU"))
        assertFalse(isPhotoInPlace("", "HU", photoCity = "Budapest", photoCountryCode = "HU"))
        assertFalse(isPhotoInPlace("Budapest", "HU", photoCity = "Budapest", photoCountryCode = ""))
        assertFalse(isPhotoInPlace("Budapest", "", photoCity = "Budapest", photoCountryCode = "HU"))
    }

    @Test
    fun `blank parts match the same blank parts`() {
        assertTrue(isPhotoInPlace("", "HU", photoCity = "", photoCountryCode = "HU"))
        assertTrue(isPhotoInPlace("Budapest", "", photoCity = "Budapest", photoCountryCode = ""))
        assertTrue(isPhotoInPlace("", "", photoCity = "", photoCountryCode = ""))
    }

    @Test
    fun `the parts compare exactly`() {
        assertFalse(isPhotoInPlace("Budapest", "HU", photoCity = "budapest", photoCountryCode = "HU"))
        assertFalse(isPhotoInPlace("Budapest", "HU", photoCity = "Budapest", photoCountryCode = "hu"))
    }
}
