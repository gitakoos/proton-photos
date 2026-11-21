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

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Copies of a place an EXIF GPS strip leaves in XMP, IPTC, appended pictures and a motion photo's movie. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceLeftoversTest {

    private val fixtures = PlaceFixtures(ApplicationProvider.getApplicationContext())

    private fun withXmpProperty(property: String) = fixtures.withXmp(fixtures.jpeg(), fixtures.xmp("$property>"))

    @Test
    fun `a JPEG with no place anywhere is clean`() {
        assertFalse(PlaceLeftovers.holdsPlace(fixtures.jpeg()))
    }

    @Test
    fun `an EXIF namespace GPS property in the XMP is found`() {
        assertTrue(PlaceLeftovers.holdsPlace(withXmpProperty("""exif:GPSLatitude="38,54.402N"""")))
    }

    @Test
    fun `a drone maker's GPS property in the XMP is found`() {
        assertTrue(PlaceLeftovers.holdsPlace(withXmpProperty("""drone-dji:GpsLatitude="38.9067"""")))
    }

    @Test
    fun `an IPTC location in the XMP is found`() {
        assertTrue(PlaceLeftovers.holdsPlace(withXmpProperty("""Iptc4xmpExt:LocationCreated="Ibiza"""")))
    }

    @Test
    fun `XMP with no place in it is clean`() {
        assertFalse(PlaceLeftovers.holdsPlace(withXmpProperty("""xmp:Rating="5"""")))
    }

    @Test
    fun `an IPTC city in the Photoshop block is found`() {
        assertTrue(PlaceLeftovers.holdsPlace(fixtures.withSegment(fixtures.jpeg(), 0xED, fixtures.iimCity())))
    }

    @Test
    fun `a picture appended after the main one with its own place is found`() {
        assertTrue(PlaceLeftovers.holdsPlace(fixtures.jpeg() + fixtures.jpeg(withPlace = true)))
    }

    @Test
    fun `a picture appended after the main one and cut short with its own place is found`() {
        val appended = fixtures.jpeg(withPlace = true)

        assertTrue(PlaceLeftovers.holdsPlace(fixtures.jpeg() + appended.copyOf(appended.size - 2)))
    }

    @Test
    fun `a thumbnail kept inside a segment of the main picture is clean`() {
        assertFalse(PlaceLeftovers.holdsPlace(fixtures.withSegment(fixtures.jpeg(), 0xE4, fixtures.jpeg())))
    }

    @Test
    fun `a picture appended after the main one with no place is clean`() {
        assertFalse(PlaceLeftovers.holdsPlace(fixtures.jpeg() + fixtures.jpeg()))
    }

    @Test
    fun `a motion photo's movie with a location is found`() {
        assertTrue(PlaceLeftovers.holdsPlace(fixtures.motionPhoto()))
    }

    @Test
    fun `a place in a PNG's compressed XMP is found`() {
        val xmp = fixtures.xmp("""exif:GPSLongitude="1,25.236E">""")

        assertTrue(PlaceLeftovers.holdsPlace(fixtures.pngWithXmp(xmp, compressed = true)))
    }

    @Test
    fun `a PNG's compressed XMP with no place is clean`() {
        val xmp = fixtures.xmp("""xmp:Rating="5">""")

        assertFalse(PlaceLeftovers.holdsPlace(fixtures.pngWithXmp(xmp, compressed = true)))
    }
}
