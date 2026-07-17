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

package eu.akoos.photos.data.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On-device coverage that asserts on the real bytes an upload ships. It runs the actual JPEG codec
 * and the real EXIF writer, which a JVM test cannot, so it pins the metadata-strip privacy contract
 * down to the tags present in the output file. The JVM tests only check the decisions.
 */
@RunWith(AndroidJUnit4::class)
class UploadStripBytesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A high-entropy (per-pixel noise) JPEG so the source is photo-sized and downscaling to a lower
     *  resolution reliably shrinks it, rather than a flat/gridded fill that JPEG crushes to nothing. */
    private fun makeSourceJpeg(width: Int, height: Int, quality: Int = 92): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var seed = width * 31 + height + 1
        for (i in pixels.indices) {
            seed = seed * 1103515245 + 12345
            pixels[i] = (0xFF shl 24) or ((seed ushr 8) and 0xFFFFFF)
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File.createTempFile("src_", ".jpg", context.cacheDir)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bmp.recycle()
        return file
    }

    /** A noise JPEG stamped with real EXIF: GPS, camera make/model, capture date and software, one tag
     *  from each strip group so a test can prove which groups survive. Orientation is left NORMAL so the
     *  no-downscale transcode test can assert the pixel dimensions unchanged. */
    private fun makeSourceJpegWithExif(width: Int, height: Int): File {
        val file = makeSourceJpeg(width, height)
        ExifInterface(file.absolutePath).apply {
            setLatLong(47.4979, 19.0402)
            setAttribute(ExifInterface.TAG_MAKE, "TestMake")
            setAttribute(ExifInterface.TAG_MODEL, "TestModel")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:04:23 11:56:39")
            setAttribute(ExifInterface.TAG_SOFTWARE, "TestSoftware")
            saveAttributes()
        }
        return file
    }

    /** The GPS coordinates embedded in [file], or null when it carries none. */
    private fun gpsOf(file: File): DoubleArray? = ExifInterface(file.absolutePath).latLong

    /** The decoded pixel dimensions of the JPEG at [file]. */
    private fun dimensionsOf(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    @Test
    fun compressToTemp_stripping_gps_removes_location_but_keeps_camera() {
        val src = makeSourceJpegWithExif(4000, 3000)
        assertNotNull("fixture must carry GPS so the strip assertion is not vacuous", gpsOf(src))
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.SPACE_SAVER,
                MetadataStripConfig(stripGps = true),
            )
            assertNotNull("a large photo must produce a compressed file", out)
            out!!
            try {
                val exif = ExifInterface(out.absolutePath)
                assertNull("GPS must be gone from the stripped output", exif.latLong)
                assertEquals("camera make must survive a GPS-only strip", "TestMake", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertEquals("camera model must survive a GPS-only strip", "TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun compressToTemp_default_config_keeps_gps() {
        val src = makeSourceJpegWithExif(4000, 3000)
        assertNotNull("fixture must carry GPS so the keep assertion is not vacuous", gpsOf(src))
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.SPACE_SAVER,
                MetadataStripConfig(),
            )
            assertNotNull("a large photo must produce a compressed file", out)
            out!!
            try {
                val latLong = ExifInterface(out.absolutePath).latLong
                assertNotNull("GPS must survive when nothing is stripped", latLong)
                assertEquals(47.4979, latLong!![0], 0.001)
                assertEquals(19.0402, latLong[1], 0.001)
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun compressToTemp_stripping_everything_removes_all_four_groups() {
        val src = makeSourceJpegWithExif(4000, 3000)
        assertNotNull("fixture must carry GPS so the strip assertion is not vacuous", gpsOf(src))
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.SPACE_SAVER,
                MetadataStripConfig(
                    stripGps = true,
                    stripCameraInfo = true,
                    stripTimestamp = true,
                    stripSoftwareInfo = true,
                ),
            )
            assertNotNull("a large photo must produce a compressed file", out)
            out!!
            try {
                val exif = ExifInterface(out.absolutePath)
                assertNull("GPS must be gone", exif.latLong)
                assertNull("make must be gone", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNull("model must be gone", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertNull("capture date must be gone", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertNull("software must be gone", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun transcodeStrippedJpeg_stripping_gps_removes_location_keeps_dimensions_and_make() {
        val src = makeSourceJpegWithExif(1600, 1200)
        assertNotNull("fixture must carry GPS so the strip assertion is not vacuous", gpsOf(src))
        try {
            val out = UploadImageCompressor.transcodeStrippedJpeg(
                context, "file://${src.absolutePath}", MetadataStripConfig(stripGps = true),
            )
            assertNotNull("the strip transcode must produce a file", out)
            out!!
            try {
                val decoded = BitmapFactory.decodeFile(out.absolutePath)
                assertNotNull("the transcode output must decode as a valid JPEG", decoded)
                decoded!!.recycle()

                val (w, h) = dimensionsOf(out)
                assertEquals("width must be preserved, the transcode must not downscale", 1600, w)
                assertEquals("height must be preserved, the transcode must not downscale", 1200, h)

                val exif = ExifInterface(out.absolutePath)
                assertNull("GPS must be gone from the transcoded output", exif.latLong)
                assertEquals("a non-stripped tag must survive the transcode", "TestMake", exif.getAttribute(ExifInterface.TAG_MAKE))
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun transcodeStrippedJpeg_noop_config_keeps_gps() {
        val src = makeSourceJpegWithExif(1600, 1200)
        assertNotNull("fixture must carry GPS so the keep assertion is not vacuous", gpsOf(src))
        try {
            val out = UploadImageCompressor.transcodeStrippedJpeg(
                context, "file://${src.absolutePath}", MetadataStripConfig(),
            )
            assertNotNull("the transcode must produce a file", out)
            out!!
            try {
                val decoded = BitmapFactory.decodeFile(out.absolutePath)
                assertNotNull("the transcode output must decode as a valid JPEG", decoded)
                decoded!!.recycle()

                val latLong = ExifInterface(out.absolutePath).latLong
                assertNotNull("the re-encode alone must not drop GPS, only the gate does", latLong)
                assertEquals(47.4979, latLong!![0], 0.001)
                assertEquals(19.0402, latLong[1], 0.001)
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun writeGpsLocation_adds_coordinates_to_a_jpeg_with_none() {
        val file = makeSourceJpeg(640, 480)
        assertNull("the plain fixture must start with no GPS so the write is what adds it", gpsOf(file))
        try {
            ExifHelper.writeGpsLocation(file, 47.4979, 19.0402)
            val latLong = ExifInterface(file.absolutePath).latLong
            assertNotNull("writeGpsLocation must embed the coordinates", latLong)
            assertEquals(47.4979, latLong!![0], 0.001)
            assertEquals(19.0402, latLong[1], 0.001)
        } finally {
            file.delete()
        }
    }
}
