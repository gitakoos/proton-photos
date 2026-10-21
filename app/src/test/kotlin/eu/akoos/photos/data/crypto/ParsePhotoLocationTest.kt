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

package eu.akoos.photos.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The location a photo's Drive metadata carries: a 0, 0 fix reads as none, a real position near it is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParsePhotoLocationTest {

    @Test
    fun `a real position parses`() {
        assertEquals(47.4979 to 19.0402, parsePhotoLocation("""{"Location":{"Latitude":47.4979,"Longitude":19.0402}}"""))
    }

    @Test
    fun `a Drive location at 0, 0 is no location`() {
        assertNull(parsePhotoLocation("""{"Location":{"Latitude":0,"Longitude":0}}"""))
        assertNull(parsePhotoLocation("""{"Location":{"Latitude":0.0,"Longitude":-0.0}}"""))
    }

    @Test
    fun `a real position next to 0, 0 is kept`() {
        assertEquals(0.0001 to 0.0, parsePhotoLocation("""{"Location":{"Latitude":0.0001,"Longitude":0}}"""))
    }
}
