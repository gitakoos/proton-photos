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

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.akoos.photos.domain.entity.UploadCompressionTier
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.MetadataStripConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The strip contract against a REAL HEIC the device camera produced, read through the same MediaStore
 * content Uri the upload reads. A synthesised JPEG cannot cover this: HEIC is precisely the container
 * ExifInterface refuses to rewrite, so the in-place strip silently no-ops on it and, without the
 * transcode, the metadata the user asked to remove would ship anyway.
 *
 * The location tests need a geotagged HEIC, which only exists when the camera had location tagging on,
 * so they look one up separately and skip when the device has none. The rest assert on the camera and
 * timestamp groups, which every HEIC here carries; the gate is per-group and shared, so a group it
 * drops is a group it drops.
 *
 * Every test skips when the device has no HEIC at all.
 */
@RunWith(AndroidJUnit4::class)
class RealHeicStripTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The first HEIC in MediaStore with its declared mime, or null when the device has none. */
    private fun findHeic(): Pair<Uri, String>? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/hei%'",
            null,
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val mime = c.getString(1)
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) to mime
            }
        }
        return null
    }

    /** The first HEIC in MediaStore that actually carries a GPS fix, or null when none does. */
    private fun findHeicWithGps(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/hei%'",
            null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0),
                )
                if (exifOf(uri)?.latLong != null) return uri
            }
        }
        return null
    }

    private fun exifOf(uri: Uri): ExifInterface? =
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }

    private fun makeOf(uri: Uri): String? = exifOf(uri)?.getAttribute(ExifInterface.TAG_MAKE)

    private fun dimensionsOf(uri: Uri): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        return opts.outWidth to opts.outHeight
    }

    private fun dimensionsOf(file: java.io.File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun mimeOf(file: java.io.File): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outMimeType
    }

    /**
     * The premise of the whole transcode path: a HEIC cannot be stripped in place, so the strip step
     * yields nothing and the caller would otherwise upload the untouched original.
     */
    @Test
    fun a_real_heic_cannot_be_stripped_in_place() {
        val found = findHeic()
        assumeTrue("no HEIC on this device", found != null)
        val (uri, mime) = found!!
        assertTrue("MediaStore must report a heic mime, got $mime", mime.startsWith("image/hei"))
        assertNotNull("the fixture must carry camera EXIF so the strip assertions are not vacuous", makeOf(uri))

        val stripped = ExifHelper.stripToTempFile(context, uri.toString(), MetadataStripConfig(stripCameraInfo = true))
        assertNull("an in-place strip must not produce a stripped HEIC copy", stripped)

        assertTrue(
            "the gate must select a strip transcode for a heic when no compression runs",
            UploadImageCompressor.needsStripTranscode(mime, stripOnUpload = true, compressWillRun = false),
        )
    }

    /**
     * The un-gated transcode copies the source EXIF onto the JPEG it emits. That copy is exactly why an
     * unwritable container would ship the metadata the strip is meant to erase if the gate were absent.
     */
    @Test
    fun transcoding_a_real_heic_without_a_strip_keeps_its_camera_exif() {
        val found = findHeic()
        assumeTrue("no HEIC on this device", found != null)
        val (uri, _) = found!!
        val sourceMake = makeOf(uri)
        assertNotNull("the fixture must carry camera EXIF", sourceMake)

        val out = UploadImageCompressor.transcodeStrippedJpeg(context, uri.toString(), MetadataStripConfig())
        assertNotNull("a real heic must transcode", out)
        out!!
        try {
            assertEquals("the transcode must emit a JPEG", "image/jpeg", mimeOf(out))
            assertEquals(
                "camera make must survive when nothing is stripped",
                sourceMake,
                ExifInterface(out.absolutePath).getAttribute(ExifInterface.TAG_MAKE),
            )
        } finally {
            out.delete()
        }
    }

    /**
     * The fix: with the strip config applied, the JPEG produced from a real HEIC carries none of the
     * groups the user chose to remove, at full resolution.
     *
     * BitmapFactory reports the stored pixel dimensions and ignores the orientation tag, while the
     * transcode bakes that rotation into the pixels and declares the result NORMAL. A portrait source
     * therefore comes back with its two dimensions swapped, which is upright, not downscaled. The
     * metadata assertions run first so a dimension mismatch can never mask a privacy failure.
     */
    @Test
    fun transcoding_a_real_heic_with_a_strip_removes_the_requested_groups() {
        val found = findHeic()
        assumeTrue("no HEIC on this device", found != null)
        val (uri, _) = found!!
        assertNotNull("the fixture must carry camera EXIF", makeOf(uri))
        val sourceDimensions = dimensionsOf(uri)
        assertTrue("the fixture must decode", sourceDimensions.first > 0 && sourceDimensions.second > 0)
        val rotation = exifOf(uri)?.rotationDegrees ?: 0

        val out = UploadImageCompressor.transcodeStrippedJpeg(
            context,
            uri.toString(),
            MetadataStripConfig(stripGps = true, stripCameraInfo = true, stripTimestamp = true),
        )
        assertNotNull("a real heic must transcode", out)
        out!!
        try {
            val exif = ExifInterface(out.absolutePath)
            assertNull("camera make must be gone", exif.getAttribute(ExifInterface.TAG_MAKE))
            assertNull("camera model must be gone", exif.getAttribute(ExifInterface.TAG_MODEL))
            assertNull("capture date must be gone", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertNull("location must be gone", exif.latLong)

            assertEquals("the transcode must emit a JPEG", "image/jpeg", mimeOf(out))
            val expectedUpright =
                if (rotation == 90 || rotation == 270) sourceDimensions.second to sourceDimensions.first
                else sourceDimensions
            assertEquals("the transcode must keep the full resolution, upright", expectedUpright, dimensionsOf(out))
            assertEquals(
                "the upright copy must declare a normal orientation",
                ExifInterface.ORIENTATION_NORMAL,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
            )
        } finally {
            out.delete()
        }
    }

    /**
     * The exact reported leak, end to end on a geotagged HEIC: metadata removal plus compression. The
     * in-place strip cannot touch the container, so the compressor receives the untouched original and
     * its EXIF re-injection is the only thing standing between the user and a location on Drive.
     */
    @Test
    fun compressing_a_geotagged_heic_with_a_strip_removes_its_location() {
        val uri = findHeicWithGps()
        assumeTrue("no geotagged HEIC on this device", uri != null)
        uri!!
        assertNotNull("the fixture must carry GPS so the strip assertion is not vacuous", exifOf(uri)?.latLong)

        val out = UploadImageCompressor.compressToTemp(
            context, uri.toString(), UploadCompressionTier.SPACE_SAVER, MetadataStripConfig(stripGps = true),
        )
        assertNotNull("a full size heic must compress", out)
        out!!
        try {
            assertNull("location must be gone from the compressed upload", ExifInterface(out.absolutePath).latLong)
        } finally {
            out.delete()
        }
    }

    /** The counterpart: with nothing stripped the same compression keeps the location, so the gate is
     *  what removes it rather than the re-encode. */
    @Test
    fun compressing_a_geotagged_heic_without_a_strip_keeps_its_location() {
        val uri = findHeicWithGps()
        assumeTrue("no geotagged HEIC on this device", uri != null)
        uri!!
        val sourceGps = exifOf(uri)!!.latLong!!

        val out = UploadImageCompressor.compressToTemp(
            context, uri.toString(), UploadCompressionTier.SPACE_SAVER, MetadataStripConfig(),
        )
        assertNotNull("a full size heic must compress", out)
        out!!
        try {
            val outGps = ExifInterface(out.absolutePath).latLong
            assertNotNull("location must survive when nothing is stripped", outGps)
            assertEquals(sourceGps[0], outGps!![0], 0.001)
            assertEquals(sourceGps[1], outGps[1], 0.001)
        } finally {
            out.delete()
        }
    }

    /** The no-compression half of the same scenario: the strip transcode must drop a real GPS fix. */
    @Test
    fun transcoding_a_geotagged_heic_removes_its_location() {
        val uri = findHeicWithGps()
        assumeTrue("no geotagged HEIC on this device", uri != null)
        uri!!
        assertNotNull("the fixture must carry GPS so the strip assertion is not vacuous", exifOf(uri)?.latLong)

        val out = UploadImageCompressor.transcodeStrippedJpeg(
            context, uri.toString(), MetadataStripConfig(stripGps = true),
        )
        assertNotNull("a geotagged heic must transcode", out)
        out!!
        try {
            assertNull("location must be gone from the transcoded upload", ExifInterface(out.absolutePath).latLong)
        } finally {
            out.delete()
        }
    }
}
