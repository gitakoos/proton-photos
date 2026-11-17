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

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The in-place GPS patch of a HEIF motion photo, on files built byte by byte. */
class HeifExifGpsTest {

    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun file(bytes: ByteArray): File =
        File.createTempFile("heif_", ".heic").also { it.writeBytes(bytes); files += it }

    private fun ByteArray.text(): String = String(this, Charsets.ISO_8859_1)

    private fun assertGpsErased(built: HeifTestFiles.Built) {
        val f = file(built.bytes)
        assertEquals(4, HeifExifGps.gpsEntryCount(f))

        val result = HeifExifGps.erase(f)

        assertEquals(HeifExifGps.Result.Erased(built.exifStart.toLong(), built.exifEnd.toLong()), result)
        val after = f.readBytes()
        assertEquals(built.bytes.size, after.size)
        for (i in after.indices) {
            if (i < built.exifStart || i >= built.exifEnd) assertEquals("byte $i outside the exif", built.bytes[i], after[i])
        }
        val tiff = built.exifStart + HeifTestFiles.TIFF_IN_ITEM
        val gps = after.copyOfRange(tiff + HeifTestFiles.GPS_IFD_AT, tiff + HeifTestFiles.GPS_VALUES_END)
        assertTrue("the GPS IFD and its values are zeroed", gps.all { it == 0.toByte() })
        assertTrue("the other tags stay", after.text().contains("Apple"))
        assertEquals(0, HeifExifGps.gpsEntryCount(f))
        assertArrayEquals(
            "the motion clip is untouched",
            built.bytes.copyOfRange(built.clipStart, built.bytes.size),
            after.copyOfRange(built.clipStart, after.size),
        )
        assertEquals(HeifExifGps.Result.NoGps, HeifExifGps.erase(f))
    }

    @Test
    fun `the GPS IFD of a big-endian Exif item is emptied in place`() {
        assertGpsErased(HeifTestFiles.heic())
    }

    @Test
    fun `the GPS IFD of a little-endian Exif item is emptied in place`() {
        assertGpsErased(HeifTestFiles.heic(exif = HeifTestFiles.exif(big = false)))
    }

    @Test
    fun `version 3 item infos and version 1 locations are read too`() {
        assertGpsErased(HeifTestFiles.heic(infeVersion = 3, ilocVersion = 1))
    }

    @Test
    fun `an XMP item without coordinates does not stop the patch`() {
        assertGpsErased(HeifTestFiles.heic(xmp = "<x:xmpmeta><rdf:RDF GCamera:MotionPhoto=\"1\"/></x:xmpmeta>"))
    }

    @Test
    fun `a file without GPS is left byte for byte as it was`() {
        for (built in listOf(HeifTestFiles.heic(exif = HeifTestFiles.exif(withGps = false)), HeifTestFiles.heic(exif = null))) {
            val f = file(built.bytes)
            assertEquals(HeifExifGps.Result.NoGps, HeifExifGps.erase(f))
            assertArrayEquals(built.bytes, f.readBytes())
            assertEquals(0, HeifExifGps.gpsEntryCount(f))
        }
    }

    @Test
    fun `layouts it cannot patch safely are refused and left untouched`() {
        val refused = listOf(
            HeifTestFiles.heic(xmp = "<rdf:Description exif:GPSLatitude=\"47,29.87N\"/>"),
            HeifTestFiles.heic(ilocVersion = 1, exifMethod = 1),
            HeifTestFiles.heic(infeVersion = 1),
        )
        for (built in refused) {
            val f = file(built.bytes)
            assertTrue(HeifExifGps.erase(f) is HeifExifGps.Result.Unsupported)
            assertArrayEquals(built.bytes, f.readBytes())
        }
        val truncated = HeifTestFiles.heic().bytes.copyOf(100)
        val f = file(truncated)
        assertTrue(HeifExifGps.erase(f) is HeifExifGps.Result.Unsupported)
        assertArrayEquals(truncated, f.readBytes())
    }

    @Test
    fun `a HEIF still is told apart from an mp4 and a jpeg`() {
        assertTrue(HeifExifGps.isHeif(file(HeifTestFiles.heic().bytes)))
        assertFalse(HeifExifGps.isHeif(file(HeifTestFiles.mp4Clip())))
        assertFalse(HeifExifGps.isHeif(file(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + ByteArray(40))))
    }
}
