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

package eu.akoos.photos.data.repository.drive

import androidx.exifinterface.media.ExifInterface
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
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset

/**
 * Coverage for [LargeLibraryDeviceSim], the pure half of the debug device-library generator. The EXIF
 * block is read back through the androidx `ExifInterface`, the same parser family MediaStore's scanner
 * uses, so a file's DATE_TAKEN and GPS fix are known to survive; the rest pins the ownership guard every
 * query and delete is built on, and that the generated content really differs file to file and spreads
 * over the years.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargeLibraryDeviceSimTest {

    // SOI, a JFIF APP0, a 16 x 16 baseline SOF0 and EOI: the marker structure the EXIF parser walks. It
    // never decodes image data, so no scan data is needed.
    private val jpegShell: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private val captureMs: Long = Instant.parse("2019-03-14T15:09:26Z").toEpochMilli()

    private fun parse(bytes: ByteArray) = ExifInterface(ByteArrayInputStream(bytes))

    @Test
    fun `the capture date reads back as the exact instant`() {
        val bytes = LargeLibraryDeviceSim.withExif(jpegShell, LargeLibraryDeviceSim.exifSegment(captureMs, null))
        val exif = parse(bytes)

        assertEquals("2019:03:14 15:09:26", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+00:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        val instant: Long? = exif.dateTimeOriginal
        assertEquals(captureMs, instant)
        assertNull(exif.latLong)
        assertEquals(16, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
    }

    @Test
    fun `a northern eastern fix reads back`() {
        val gps = doubleArrayOf(47.4979, 19.0402)
        val bytes = LargeLibraryDeviceSim.withExif(jpegShell, LargeLibraryDeviceSim.exifSegment(captureMs, gps))
        val exif = parse(bytes)

        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(47.4979, latLong!![0], 1e-5)
        assertEquals(19.0402, latLong[1], 1e-5)
        val instant: Long? = exif.dateTimeOriginal
        assertEquals(captureMs, instant)
    }

    @Test
    fun `a southern western fix keeps its sign`() {
        val gps = doubleArrayOf(-34.6037, -58.3816)
        val bytes = LargeLibraryDeviceSim.withExif(jpegShell, LargeLibraryDeviceSim.exifSegment(captureMs, gps))
        val exif = parse(bytes)

        assertEquals("S", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
        assertEquals("W", exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF))
        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(-34.6037, latLong!![0], 1e-5)
        assertEquals(-58.3816, latLong[1], 1e-5)
    }

    @Test
    fun `the segment sits right after SOI and the rest of the JPEG is untouched`() {
        val segment = LargeLibraryDeviceSim.exifSegment(captureMs, doubleArrayOf(1.0, 2.0))
        val bytes = LargeLibraryDeviceSim.withExif(jpegShell, segment)

        assertEquals(jpegShell.size + segment.size, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xE1.toByte(), bytes[3])
        val declaredLength = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        assertEquals(segment.size - 2, declaredLength)
        assertArrayEquals(
            jpegShell.copyOfRange(2, jpegShell.size),
            bytes.copyOfRange(2 + segment.size, bytes.size),
        )
    }

    @Test
    fun `the ownership guard binds the exact folder and the name prefix`() {
        assertEquals("relative_path = ? AND _display_name LIKE ?", LargeLibraryDeviceSim.ownSelection())
        assertEquals(
            "relative_path = ? AND _display_name LIKE ? AND (is_pending = 1)",
            LargeLibraryDeviceSim.ownSelection("is_pending = 1"),
        )
        assertArrayEquals(arrayOf("Pictures/PFP-Sim/", "pfpsim-%"), LargeLibraryDeviceSim.ownSelectionArgs())
        assertArrayEquals(
            arrayOf("Pictures/PFP-Sim/", "pfpsim-%", "5", "9"),
            LargeLibraryDeviceSim.ownSelectionArgs(arrayOf("5", "9")),
        )
        // An underscore would be a LIKE wildcard and widen the guard beyond the simulator's own names.
        assertFalse(LargeLibraryDeviceSim.NAME_PREFIX.contains('_'))
        assertTrue(LargeLibraryDeviceSim.RELATIVE_PATH.endsWith("/"))
    }

    @Test
    fun `file names carry the prefix, the run and the index`() {
        val name = LargeLibraryDeviceSim.displayName("s3y0j4", 42)

        assertEquals("pfpsim-s3y0j4-000042.jpg", name)
        assertTrue(name.startsWith(LargeLibraryDeviceSim.NAME_PREFIX))
    }

    @Test
    fun `every index of a run draws its own mosaic`() {
        val edge = LargeLibraryDeviceSim.EDGE
        val pixels = IntArray(edge * edge)
        val runSalt = LargeLibraryDeviceSim.mix32(1_700_000_000)
        val seen = HashSet<List<Int>>()
        for (index in 0 until 20_000) {
            LargeLibraryDeviceSim.fillPixels(LargeLibraryDeviceSim.itemSeed(runSalt, index), pixels)
            // Sixteen flat 4 x 4 cells: one pixel per cell identifies the whole image.
            val cells = (0 until 16).map { cell -> pixels[(cell / 4) * 4 * edge + (cell % 4) * 4] }
            assertTrue("index $index repeats an earlier mosaic", seen.add(cells))
        }
    }

    @Test
    fun `each cell is one flat opaque colour`() {
        val edge = LargeLibraryDeviceSim.EDGE
        val pixels = IntArray(edge * edge)
        LargeLibraryDeviceSim.fillPixels(0x5EED, pixels)

        for (y in 0 until edge) {
            for (x in 0 until edge) {
                assertEquals(pixels[(y / 4) * 4 * edge + (x / 4) * 4], pixels[y * edge + x])
            }
        }
        assertTrue(pixels.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `every tint and brightness pair is its own colour`() {
        val colours = (0 until 8).flatMap { tint -> (0 until 4).map { level -> LargeLibraryDeviceSim.cellColor(tint, level) } }

        assertEquals(32, colours.toSet().size)
    }

    @Test
    fun `capture times are whole seconds inside the span and cover every year`() {
        val start = LargeLibraryDeviceSim.SPAN_START_MS
        val end = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli()
        val years = HashSet<Int>()
        for (index in 0 until 10_000) {
            val t = LargeLibraryDeviceSim.captureTimeMs(LargeLibraryDeviceSim.itemSeed(7, index), start, end)
            assertTrue(t >= start && t < end)
            assertEquals(0L, t % 1000L)
            years += Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).year
        }

        assertEquals((2005..2024).toSet(), years)
    }

    @Test
    fun `about three files in four carry a valid GPS fix`() {
        val fixes = (0 until 4_000).mapNotNull { LargeLibraryDeviceSim.gpsFor(LargeLibraryDeviceSim.itemSeed(3, it)) }

        assertTrue("got ${fixes.size} fixes", fixes.size in 2_700..3_300)
        assertTrue(fixes.all { it[0] in -90.0..90.0 && it[1] in -180.0..180.0 })
        assertTrue(fixes.any { it[0] < 0 } && fixes.any { it[1] < 0 })
    }
}
