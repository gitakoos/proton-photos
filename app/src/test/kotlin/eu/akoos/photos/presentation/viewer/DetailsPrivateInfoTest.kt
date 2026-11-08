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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DetailsPrivateInfoTest {

    @Test
    fun `a file without private tags offers nothing to remove`() {
        assertTrue(privateInfoItems(PhotoMetadata(width = 1200, height = 1600, orientation = 1)).isEmpty())
    }

    @Test
    fun `each group the file carries is listed with what it holds`() {
        val items = privateInfoItems(
            PhotoMetadata(
                make = "Samsung",
                model = "SM-S901B",
                lensModel = "23 mm",
                gpsLatitude = 47.49786,
                gpsLongitude = 19.04023,
                software = "One UI camera",
                artist = "Photographer",
                copyright = "2026",
            ),
            Locale.US,
        )
        assertEquals(
            listOf(PrivateInfoKind.LOCATION, PrivateInfoKind.CAMERA, PrivateInfoKind.SOFTWARE, PrivateInfoKind.AUTHOR),
            items.map { it.kind },
        )
        assertEquals("47.4979, 19.0402", items[0].value)
        assertEquals("Samsung SM-S901B, 23 mm", items[1].value)
        assertEquals("Photographer, 2026", items[3].value)
    }

    @Test
    fun `exposure settings alone still count as camera info`() {
        val items = privateInfoItems(PhotoMetadata(aperture = "1.8", exposureTime = "1/120", isoSpeed = "50"), Locale.US)
        assertEquals(listOf(PrivateInfoKind.CAMERA), items.map { it.kind })
        assertEquals("f/1.8 · 1/120 · ISO 50", items[0].value)
    }

    @Test
    fun `the strip removes exactly the groups picked`() {
        val config = stripConfigFor(setOf(PrivateInfoKind.LOCATION, PrivateInfoKind.AUTHOR))
        assertTrue(config.stripGps)
        assertTrue(config.stripAuthorship)
        assertFalse(config.stripCameraInfo)
        assertFalse(config.stripSoftwareInfo)
        assertFalse(config.stripTimestamp)
    }
}
