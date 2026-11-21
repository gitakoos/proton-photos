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
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/** Blanking a place where the EXIF GPS strip does not reach, without moving a byte. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceScrubberTest {

    private val fixtures = PlaceFixtures(ApplicationProvider.getApplicationContext())

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `the place in an Ultra HDR gain map is blanked and the gain map stays where the index says`() {
        val bytes = fixtures.ultraHdr()
        val size = bytes.size

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        val gainMap = fixtures.mpfSecondPicture(bytes)
        assertEquals(size, bytes.size)
        assertTrue(ImagePlaceParts.isJpeg(bytes, gainMap))
        assertNull(fixtures.exifPlaceAt(bytes, gainMap))
        assertTrue(text(bytes).contains("hdrgm:Version=\"1.0\""))
        assertFalse(PlaceLeftovers.holdsPlace(bytes))
    }

    @Test
    fun `a place just written into the main picture is kept while the gain map's is blanked`() {
        val bytes = fixtures.ultraHdr()

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = true)

        assertNotNull(fixtures.exifPlaceAt(bytes, 0))
        assertNull(fixtures.exifPlaceAt(bytes, fixtures.mpfSecondPicture(bytes)))
    }

    @Test
    fun `an MPF index left stale by a rewritten main picture is pointed back at the gain map`() {
        val original = fixtures.ultraHdr(mainPlace = false, gainMapPlace = false)
        val grown = fixtures.withSegmentAt(original, PlaceFixtures.MPF_SEGMENT_END, 0xE3, ByteArray(40))

        PlaceScrubber.scrub(grown, removePlace = false, keepMainExifPlace = false)

        assertTrue(ImagePlaceParts.isJpeg(grown, fixtures.mpfSecondPicture(grown)))
    }

    @Test
    fun `an MPF index that still lands on its pictures is left byte for byte`() {
        val original = fixtures.ultraHdr(mainPlace = false, gainMapPlace = false)
        val bytes = original.copyOf()

        PlaceScrubber.scrub(bytes, removePlace = false, keepMainExifPlace = false)

        assertArrayEquals(original, bytes)
    }

    @Test
    fun `place properties in the XMP are blanked in any namespace and the rest is kept`() {
        val xmp = fixtures.xmp(
            """xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="38,54.402N" drone-dji:GpsLongitude="1.4206" xmp:Rating="5">""" +
                """<exif:GPSLongitude>1,25.236E</exif:GPSLongitude>""" +
                """<Iptc4xmpExt:LocationCreated><rdf:Bag><rdf:li rdf:parseType="Resource">""" +
                """<Iptc4xmpExt:City>Ibiza</Iptc4xmpExt:City></rdf:li></rdf:Bag></Iptc4xmpExt:LocationCreated>""" +
                """<GCamera:MotionPhoto>1</GCamera:MotionPhoto>""",
        )
        val bytes = fixtures.withXmp(fixtures.jpeg(), xmp)
        val size = bytes.size

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertEquals(size, bytes.size)
        assertFalse(text(bytes).contains("38,54"))
        assertFalse(text(bytes).contains("1.4206"))
        assertFalse(text(bytes).contains("25.236"))
        assertFalse(text(bytes).contains("Ibiza"))
        assertTrue(text(bytes).contains("xmp:Rating=\"5\""))
        assertTrue(text(bytes).contains("<GCamera:MotionPhoto>1</GCamera:MotionPhoto>"))
        assertFalse(PlaceLeftovers.holdsPlace(bytes))
    }

    @Test
    fun `an IPTC city is blanked`() {
        val bytes = fixtures.withSegment(fixtures.jpeg(), 0xED, fixtures.iimCity())

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertFalse(text(bytes).contains("Paris"))
        assertFalse(PlaceLeftovers.holdsPlace(bytes))
    }

    @Test
    fun `a motion photo's movie loses its location atoms and keeps its size`() {
        val bytes = fixtures.motionPhoto()
        val size = bytes.size
        assertTrue(PlaceLeftovers.holdsPlace(bytes))

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertEquals(size, bytes.size)
        assertFalse(text(bytes).contains("+38.9067"))
        assertTrue(text(bytes).contains("com.apple.quicktime.location.ISO6709"))
        assertTrue(text(bytes).contains("GCamera:MicroVideoOffset"))
        assertFalse(PlaceLeftovers.holdsPlace(bytes))
    }

    @Test
    fun `a motion photo's loci place is flagged and then blanked`() {
        val bytes = fixtures.motionPhoto(fixtures.movieWithLoci())
        assertTrue(PlaceLeftovers.holdsPlace(bytes))

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertFalse(text(bytes).contains("loci"))
        assertFalse(PlaceLeftovers.holdsPlace(bytes))
    }

    @Test
    fun `the XMP place in a PNG is blanked and its chunk checksum still holds`() {
        val bytes = fixtures.pngWithXmp(fixtures.xmp("""exif:GPSLatitude="38,54.402N">"""), compressed = false)

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertFalse(text(bytes).contains("GPSLatitude"))
        assertTrue(fixtures.pngChunkCrcHolds(bytes, 33))
    }

    @Test
    fun `a PNG's compressed XMP place is left for the check to refuse`() {
        val bytes = fixtures.pngWithXmp(fixtures.xmp("""exif:GPSLatitude="38,54.402N">"""), compressed = true)

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertTrue(PlaceLeftovers.holdsPlace(bytes))
    }

    @Test
    fun `the XMP place in a WebP is blanked`() {
        val bytes = fixtures.webpWithXmp(fixtures.xmp("""exif:GPSLatitude="38,54.402N">"""))

        PlaceScrubber.scrub(bytes, removePlace = true, keepMainExifPlace = false)

        assertFalse(text(bytes).contains("GPSLatitude"))
    }

    @Test
    fun `a file reached only through a descriptor is scrubbed in place`() {
        val file = File.createTempFile("scrub_fd_", ".jpg", ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir)
        file.writeBytes(fixtures.ultraHdr())

        RandomAccessFile(file, "rw").use { PlaceScrubber.scrubDescriptor(it.fd, removePlace = true, keepMainExifPlace = false) }

        val bytes = file.readBytes()
        assertNull(fixtures.exifPlaceAt(bytes, fixtures.mpfSecondPicture(bytes)))
    }

    @Test
    fun `a file opened as a parcel descriptor is scrubbed in place`() {
        val file = File.createTempFile("scrub_pfd_", ".jpg", ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir)
        file.writeBytes(fixtures.ultraHdr())

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
            PlaceScrubber.scrubDescriptor(it.fileDescriptor, removePlace = true, keepMainExifPlace = true)
        }

        val bytes = file.readBytes()
        assertNotNull(fixtures.exifPlaceAt(bytes, 0))
        assertNull(fixtures.exifPlaceAt(bytes, fixtures.mpfSecondPicture(bytes)))
    }
}
