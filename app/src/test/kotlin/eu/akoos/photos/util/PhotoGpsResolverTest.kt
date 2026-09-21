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
import org.junit.Test

/**
 * Pure coverage for the ISO 6709 parse a device video's location resolves through: the first two
 * signed numbers are latitude and longitude, a trailing altitude is dropped, and a string without a
 * coordinate pair yields null. No Android is instantiated.
 */
class PhotoGpsResolverTest {

    @Test
    fun `a lat lng pair parses to its two signed numbers`() {
        assertEquals(37.7749 to -122.4194, PhotoGpsResolver.parseIso6709("+37.7749-122.4194/"))
    }

    @Test
    fun `a trailing altitude is ignored`() {
        assertEquals(37.7749 to -122.4194, PhotoGpsResolver.parseIso6709("+37.7749-122.4194+010.5/"))
    }

    @Test
    fun `a southern hemisphere pair keeps its signs`() {
        assertEquals(-33.8568 to 151.2153, PhotoGpsResolver.parseIso6709("-33.8568+151.2153/"))
    }

    @Test
    fun `a string with no coordinate pair is null`() {
        assertNull(PhotoGpsResolver.parseIso6709("not a location"))
    }
}
