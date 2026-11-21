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
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import eu.akoos.photos.util.PlaceFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The place copies an upload strip re-attaches after the primary: a motion photo's clip and an appended picture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadAppendedPlaceTest {

    private lateinit var context: Context
    private lateinit var stripper: UploadStructuralStripper
    private lateinit var fixtures: PlaceFixtures

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stripper = UploadStructuralStripper(context)
        fixtures = PlaceFixtures(context)
    }

    private fun write(bytes: ByteArray): File =
        File.createTempFile("appended_place_", ".jpg", context.cacheDir).apply { writeBytes(bytes) }

    private fun stripMotion(movie: ByteArray, config: MetadataStripConfig): File {
        val source = write(fixtures.motionPhoto(movie))
        return stripper.stripImagePreservingMotion(Uri.fromFile(source).toString(), config)!!
    }

    private fun text(file: File) = String(file.readBytes(), Charsets.ISO_8859_1)

    @Test
    fun `a motion photo upload loses the place in its clip and keeps the clip in place`() {
        val movie = fixtures.movieWithPlace()

        val stripped = stripMotion(movie, MetadataStripConfig(stripGps = true))

        val bytes = stripped.readBytes()
        assertFalse(text(stripped).contains("+38.9067"))
        assertEquals(bytes.size - movie.size, MotionPhotoUtil.detect(stripped)!!.videoOffset.toInt())
        assertArrayEquals(movie.copyOfRange(movie.size - 72, movie.size), bytes.copyOfRange(bytes.size - 72, bytes.size))
        assertFalse(stripper.placeLeftInUpload(stripped))
    }

    @Test
    fun `a motion photo upload loses a loci place in its clip`() {
        val movie = fixtures.movieWithLoci()

        val stripped = stripMotion(movie, MetadataStripConfig(stripGps = true))

        assertFalse(text(stripped).contains("loci"))
        assertEquals(stripped.length() - movie.size, MotionPhotoUtil.detect(stripped)!!.videoOffset)
        assertFalse(stripper.placeLeftInUpload(stripped))
    }

    @Test
    fun `a motion photo strip that keeps the location leaves the clip as it was`() {
        val movie = fixtures.movieWithPlace()

        val stripped = stripMotion(movie, MetadataStripConfig(stripCameraInfo = true))

        val bytes = stripped.readBytes()
        assertArrayEquals(movie, bytes.copyOfRange(bytes.size - movie.size, bytes.size))
    }

    @Test
    fun `a clip whose boxes cannot be read still counts as holding its place`() {
        val movie = fixtures.movieWithPlace()
        val moov = String(movie, Charsets.ISO_8859_1).indexOf("moov") - 4
        movie[moov] = 0x7F

        val stripped = stripMotion(movie, MetadataStripConfig(stripGps = true))

        assertTrue(stripper.placeLeftInUpload(stripped))
    }

    @Test
    fun `a clip that cannot be read and holds only a QuickTime location key counts as holding its place`() {
        val movie = fixtures.movieWithPlace()
        val text = String(movie, Charsets.ISO_8859_1)
        "free".toByteArray().copyInto(movie, text.indexOf("©xyz"))
        movie[text.indexOf("moov") - 4] = 0x7F

        assertTrue(stripper.placeLeftInUpload(write(fixtures.motionPhoto(movie))))
    }

    @Test
    fun `a clip after a picture that is not a JPEG loses its place in place`() {
        val picture = ByteArray(32) { 0x11 }
        val movie = fixtures.movieWithPlace()
        val file = write(picture + movie)

        stripper.scrubAppendedPlace(file, movieStart = picture.size.toLong(), MetadataStripConfig(stripGps = true))

        assertEquals((picture.size + movie.size).toLong(), file.length())
        assertFalse(text(file).contains("+38.9067"))
        assertFalse(text(file).contains("©xyz"))
    }

    @Test
    fun `a stripped PNG whose compressed XMP still names a place is found before the upload`() {
        val xmp = fixtures.xmp("""exif:GPSLongitude="1,25.236E">""")
        val file = write(fixtures.pngWithXmp(xmp, compressed = true))

        assertTrue(stripper.placeLeftInUpload(file))
    }

    @Test
    fun `a motion photo whose clip still holds a place is found before the upload`() {
        val file = write(fixtures.motionPhoto())

        assertTrue(stripper.placeLeftInUpload(file))
    }

    @Test
    fun `an appended gain map loses its GPS in place and its index still lands on it`() {
        val original = fixtures.ultraHdr(mainPlace = false, gainMapPlace = true)
        val file = write(original)
        assertTrue(stripper.placeLeftInUpload(file))

        stripper.scrubAppendedPlace(file, movieStart = null, MetadataStripConfig(stripGps = true))

        val bytes = file.readBytes()
        assertEquals(original.size, bytes.size)
        val second = fixtures.mpfSecondPicture(bytes)
        assertEquals(fixtures.mpfSecondPicture(original), second)
        assertEquals(0xFF.toByte(), bytes[second])
        assertEquals(0xD8.toByte(), bytes[second + 1])
        assertNull(fixtures.exifPlaceAt(bytes, second))
        assertFalse(stripper.placeLeftInUpload(file))
    }

    @Test
    fun `an appended gain map keeps its GPS when the strip keeps the location`() {
        val original = fixtures.ultraHdr(mainPlace = false, gainMapPlace = true)
        val file = write(original)

        stripper.scrubAppendedPlace(file, movieStart = null, MetadataStripConfig(stripCameraInfo = true))

        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `a file that cannot be read counts as holding a place`() {
        assertTrue(stripper.placeLeftInUpload(File(context.cacheDir, "missing_upload.jpg")))
    }
}
