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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On-device coverage for [UploadImageCompressor]. Runs against the real BitmapFactory / Bitmap so it
 * exercises the exact decode-and-encode path the upload uses. This is the test that catches the
 * bounds-only decode regression (decodeStream returns null in inJustDecodeBounds mode, so a null
 * guard on the decode result made every image bail before the dimensions were read).
 */
@RunWith(AndroidJUnit4::class)
class UploadImageCompressorTest {

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

    private fun longEdgeOf(file: File): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return maxOf(opts.outWidth, opts.outHeight)
    }

    @Test
    fun spaceSaver_produces_a_smaller_downscaled_jpeg() {
        val src = makeSourceJpeg(4000, 3000)
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.SPACE_SAVER,
            )
            assertNotNull("a large photo must produce a compressed file", out)
            out!!
            try {
                assertTrue(
                    "compressed (${out.length()}) must be smaller than source (${src.length()})",
                    out.length() < src.length(),
                )
                assertTrue(
                    "long edge must be capped at 2560, was ${longEdgeOf(out)}",
                    longEdgeOf(out) <= 2560,
                )
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun light_tier_keeps_full_resolution() {
        val src = makeSourceJpeg(1600, 1200)
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.LIGHT,
            )
            // LIGHT has no downscale cap; if it produced a smaller file the dimensions must be intact.
            if (out != null) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(out.absolutePath, opts)
                    assertEquals(1600, opts.outWidth)
                    assertEquals(1200, opts.outHeight)
                } finally {
                    out.delete()
                }
            }
        } finally {
            src.delete()
        }
    }

    /** A source JPEG stamped with GPS, camera, software and a rotated orientation, so a test can
     *  assert the compressor carries that metadata onto the recompressed output. */
    private fun makeSourceJpegWithExif(width: Int, height: Int): File {
        val file = makeSourceJpeg(width, height)
        ExifInterface(file.absolutePath).apply {
            setLatLong(47.4979, 19.0402)
            setAttribute(ExifInterface.TAG_MAKE, "TestMake")
            setAttribute(ExifInterface.TAG_MODEL, "TestModel")
            setAttribute(ExifInterface.TAG_SOFTWARE, "TestSoftware")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:04:23 11:56:39")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file
    }

    @Test
    fun preserves_full_exif_metadata_on_the_compressed_copy() {
        // Guards the regression where a compressed upload dropped every EXIF tag except the date, so
        // GPS, camera and software vanished from the cloud copy.
        val src = makeSourceJpegWithExif(4000, 3000)
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.SPACE_SAVER,
            )
            assertNotNull("a large photo must produce a compressed file", out)
            out!!
            try {
                val exif = ExifInterface(out.absolutePath)
                val latLong = exif.latLong
                assertNotNull("GPS must survive compression", latLong)
                assertEquals(47.4979, latLong!![0], 0.001)
                assertEquals(19.0402, latLong[1], 0.001)
                assertEquals("TestMake", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertEquals("TestModel", exif.getAttribute(ExifInterface.TAG_MODEL))
                assertEquals("TestSoftware", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                assertEquals("2026:04:23 11:56:39", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                // Orientation must be reset to NORMAL: the compressor bakes rotation into the pixels.
                assertEquals(
                    ExifInterface.ORIENTATION_NORMAL,
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
                )
            } finally {
                out.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun never_inflates_a_tiny_solid_image() {
        // A 16x16 solid image is already near-minimal as a JPEG; recompressing cannot beat it, so the
        // compressor must return null and let the caller upload the original untouched.
        val src = makeSourceJpeg(16, 16)
        try {
            val out = UploadImageCompressor.compressToTemp(
                context, "file://${src.absolutePath}", UploadCompressionTier.SPACE_SAVER,
            )
            if (out != null) {
                assertTrue("if a file is returned it must be strictly smaller", out.length() < src.length())
                out.delete()
            }
        } finally {
            src.delete()
        }
    }
}
