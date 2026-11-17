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

package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.util.HeifExifGps
import eu.akoos.photos.util.HeifTestFiles
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The verified GPS patch the motion strip applies to a HEIF motion photo it cannot rewrite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeifMotionGpsPatchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stripper = UploadStructuralStripper(context)
    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun file(bytes: ByteArray): File =
        File.createTempFile("motion_", ".heic", context.cacheDir).also { it.writeBytes(bytes); files += it }

    @Test
    fun `the joined copy loses its GPS and keeps the clip and every other byte`() {
        val built = HeifTestFiles.heic()
        val source = file(built.bytes)
        val joined = file(built.bytes)

        assertTrue(stripper.eraseHeifMotionGps(source, joined, built.clipStart.toLong()))

        val after = joined.readBytes()
        assertEquals(0, HeifExifGps.gpsEntryCount(joined))
        assertArrayEquals(built.bytes.copyOfRange(0, built.exifStart), after.copyOfRange(0, built.exifStart))
        assertArrayEquals(built.bytes.copyOfRange(built.exifEnd, built.bytes.size), after.copyOfRange(built.exifEnd, after.size))
        assertArrayEquals("the source is never written", built.bytes, source.readBytes())
    }

    @Test
    fun `a layout the patch refuses leaves the copy byte-exact`() {
        val built = HeifTestFiles.heic(xmp = "<rdf:Description exif:GPSLongitude=\"19,2.47E\"/>")
        val source = file(built.bytes)
        val joined = file(built.bytes)

        assertFalse(stripper.eraseHeifMotionGps(source, joined, built.clipStart.toLong()))

        assertArrayEquals(built.bytes, joined.readBytes())
    }

    @Test
    fun `a patch that fails its checks is undone`() {
        val built = HeifTestFiles.heic()
        val source = file(built.bytes)
        val joined = file(built.bytes)

        // A clip said to start inside the Exif item fails the check that the patch stays before it.
        assertFalse(stripper.eraseHeifMotionGps(source, joined, built.exifStart.toLong()))

        assertArrayEquals(built.bytes, joined.readBytes())
        assertEquals(4, HeifExifGps.gpsEntryCount(joined))
    }

    @Test
    fun `a file without GPS passes untouched`() {
        val built = HeifTestFiles.heic(exif = HeifTestFiles.exif(withGps = false))
        val source = file(built.bytes)
        val joined = file(built.bytes)

        assertTrue(stripper.eraseHeifMotionGps(source, joined, built.clipStart.toLong()))

        assertArrayEquals(built.bytes, joined.readBytes())
    }
}
