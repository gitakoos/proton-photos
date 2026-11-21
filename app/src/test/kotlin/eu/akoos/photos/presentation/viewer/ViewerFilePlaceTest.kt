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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.util.PhotoMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The viewer's details show a cloud file's place only where the map would plot it. */
class ViewerFilePlaceTest {

    private val withPlace = PhotoMetadata(model = "Cam", gpsLatitude = 38.9067, gpsLongitude = 1.4206, gpsAltitude = 12.0)

    @Test
    fun `a place removed on purpose is left out of the details`() = runTest {
        val shown = placeShownFromFile(withPlace) { false }

        assertNull(shown.gpsLatitude)
        assertNull(shown.gpsLongitude)
        assertNull(shown.gpsAltitude)
        assertEquals("Cam", shown.model)
    }

    @Test
    fun `a place the rule allows is shown`() = runTest {
        val shown = placeShownFromFile(withPlace) { true }

        assertEquals(withPlace, shown)
    }

    @Test
    fun `a file with no place never asks the rule`() = runTest {
        var asked = false

        placeShownFromFile(PhotoMetadata(model = "Cam")) { asked = true; false }

        assertFalse(asked)
    }
}
