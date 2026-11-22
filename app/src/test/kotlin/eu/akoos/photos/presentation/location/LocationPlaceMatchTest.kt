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
 * dataset city, the key the Places list groups a city by, so the page holds the photos its Places card
 * counts and two cities of one name in one country keep apart.
 */
class LocationPlaceMatchTest {

    @Test
    fun `a photo of the same dataset city belongs`() {
        assertTrue(isPhotoInPlace(placeCityId = 4021, photoCityId = 4021))
    }

    @Test
    fun `a city of the same name in the same country does not`() {
        assertFalse(isPhotoInPlace(placeCityId = 4021, photoCityId = 9377))
    }

    @Test
    fun `an unknown city matches nothing`() {
        assertFalse(isPhotoInPlace(placeCityId = -1, photoCityId = -1))
        assertFalse(isPhotoInPlace(placeCityId = -1, photoCityId = 4021))
    }

    @Test
    fun `the first dataset row matches itself`() {
        assertTrue(isPhotoInPlace(placeCityId = 0, photoCityId = 0))
    }
}
