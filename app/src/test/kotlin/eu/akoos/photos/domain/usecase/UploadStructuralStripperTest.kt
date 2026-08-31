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
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Coverage for the [UploadStructuralStripper] public API on real on-disk fixtures built in-test (no
 * checked-in binaries). The androidx `ExifInterface` and the marker walks are self-contained Java, so
 * they run under Robolectric on a `file://` URI the ContentResolver opens for real.
 *
 * The high-value property is trailer survival: a Motion Photo split at the wrong byte would corrupt
 * the still or drop the appended MP4 clip, and an Ultra HDR split at the wrong byte would lose the
 * gain map. Each fixture asserts the appended bytes come through byte-for-byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadStructuralStripperTest {

    private lateinit var context: Context
    private lateinit var stripper: UploadStructuralStripper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stripper = UploadStructuralStripper(context)
    }

    // ---- segment builders (mirrors UltraHdrUtilTest) ----------------------
    // Each segment identifier is built as ascii(name) + byteArrayOf(0x00) so the NUL terminator is an
    // explicit byte and the source stays plain ASCII.

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        ) + payload
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun app0(): ByteArray = segment(
        0xE0,
        ascii("JFIF") + byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00),
    )

    /** APP1 EXIF: the Exif identifier plus an empty big-endian TIFF directory. */
    private fun exifSegment(): ByteArray = segment(
        0xE1,
        ascii("Exif") + byteArrayOf(0x00, 0x00) +
            byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
    )

    /** APP1 XMP: the Adobe packet identifier plus the given RDF body. */
    private fun xmpSegment(body: String): ByteArray = segment(
        0xE1,
        ascii("http://ns.adobe.com/xap/1.0/") + byteArrayOf(0x00) + ascii(body),
    )

    /** APP2 MPF directory declaring [imageCount] images (corroboration-only signal). */
    private fun mpfSegment(imageCount: Int): ByteArray = segment(
        0xE2,
        ascii("MPF") + byteArrayOf(0x00) +
            byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08) +
            byteArrayOf(0x00, 0x02) +
            byteArrayOf(0xB0.toByte(), 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x04, 0x30, 0x31, 0x30, 0x30) +
            byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01) +
            byteArrayOf(0x00, 0x00, 0x00, imageCount.toByte()) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
    )

    /** SOI, the given segments, then SOS with a token of entropy data and EOI. */
    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
        out.write(byteArrayOf(0x00, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private val gainMapNamespaceXmp =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description """ +
            """xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" """ +
            """hdrgm:GainMapMax="2.5"/></rdf:RDF></x:xmpmeta>"""

    /** Motion Photo XMP whose MotionPhoto item declares [videoLen] trailer bytes. */
    private fun motionPhotoXmp(videoLen: Int): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description Camera:MotionPhoto="1" """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/"><Container:Directory>""" +
            """<rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>""" +
            """<rdf:li><Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" """ +
            """Item:Length="$videoLen"/></rdf:li></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    /** A standalone XMP APP1 segment (identifier + body), the shape a real primary carries it in. */
    private fun xmpApp1(body: String): ByteArray = segment(
        0xE1,
        ascii("http://ns.adobe.com/xap/1.0/") + byteArrayOf(0x00) + ascii(body),
    )

    /**
     * A minimal but well-formed MP4 trailer: an `ftyp` box then an `mdat` box. `MotionPhotoUtil`
     * pins the split on the `ftyp` box start, and the whole thing is the appended trailer the strip
     * must preserve byte-for-byte.
     */
    private val fakeMp4: ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x18) + ascii("ftyp") + ascii("mp42") +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) + ascii("mp42") + ascii("isom") +
            byteArrayOf(0x00, 0x00, 0x00, 0x10) + ascii("mdat") +
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x02, 0x03, 0x04)

    /**
     * A 1x1 baseline JPEG that androidx `ExifInterface` can write attributes onto (same carrier the
     * ExifHelper coverage uses). A GPS tag is added so the primary strip has real location to remove.
     */
    private val baselineJpeg: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43,
        0x00,
        0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14,
        0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A,
        0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C,
        0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
        0x07, 0x08, 0x09, 0x0A, 0x0B,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02,
        0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11,
        0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81.toByte(),
        0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52,
        0xD1.toByte(), 0xF0.toByte(), 0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A,
        0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A,
        0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A,
        0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(),
        0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(),
        0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(),
        0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(),
        0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(),
        0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(),
        0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(),
        0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(),
        0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE1.toByte(),
        0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(),
        0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(),
        0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(),
        0xFA.toByte(),
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0xBF.toByte(),
        0x80.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte(),
    )

    private fun uriOf(file: File): String = Uri.fromFile(file).toString()

    /**
     * Builds a Motion Photo on disk: a baseline JPEG carrying real GPS, a spliced-in Motion Photo XMP
     * APP1, and the [fakeMp4] appended after the primary EOI. The XMP's declared trailer length
     * matches the appended bytes exactly, so the ftyp scan pins the split on the primary/MP4 boundary.
     */
    private fun buildMotionSource(): File {
        val staging = File.createTempFile("motion_build_", ".jpg", context.cacheDir)
        staging.writeBytes(baselineJpeg)
        ExifInterface(staging.absolutePath).apply {
            setLatLong(47.4979, 19.0402) // Budapest
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "100/1")
            saveAttributes()
        }
        val primaryGps = staging.readBytes()
        staging.delete()

        // Keep the EXIF as the first APP1 (so GPS stays readable) and add the XMP APP1 right after it.
        val xmp = xmpApp1(motionPhotoXmp(fakeMp4.size))
        val firstIsApp1 =
            (primaryGps[2].toInt() and 0xFF) == 0xFF && (primaryGps[3].toInt() and 0xFF) == 0xE1
        val insertAt = if (firstIsApp1) {
            4 + (((primaryGps[4].toInt() and 0xFF) shl 8) or (primaryGps[5].toInt() and 0xFF))
        } else {
            2
        }
        val primary = primaryGps.copyOfRange(0, insertAt) + xmp + primaryGps.copyOfRange(insertAt, primaryGps.size)

        val source = File.createTempFile("motion_src_", ".jpg", context.cacheDir)
        source.writeBytes(primary + fakeMp4)
        return source
    }

    // ---- motion photo -----------------------------------------------------

    @Test
    fun a_motion_photo_is_detected_and_its_mp4_trailer_survives_the_strip() {
        val source = buildMotionSource()
        val uri = uriOf(source)

        assertTrue("the appended MP4 marks this as a motion photo upload", stripper.isMotionPhotoUpload(uri))

        // Fixture sanity: the split lands exactly on the primary/MP4 boundary.
        val info = MotionPhotoUtil.detect(source)
        assertNotNull(info)
        assertEquals(source.length() - fakeMp4.size, info!!.videoOffset)

        val out = stripper.stripImagePreservingMotion(uri, MetadataStripConfig(stripGps = true))
        assertNotNull("a motion photo must yield a rebuilt upload file, never null", out)

        val outBytes = out!!.readBytes()
        // The whole appended MP4 must come through byte-for-byte at the tail of the rebuilt file.
        val tail = outBytes.copyOfRange(outBytes.size - fakeMp4.size, outBytes.size)
        assertArrayEquals("the MP4 trailer must survive byte-for-byte", fakeMp4, tail)

        // And the primary's location must be gone.
        assertNull("GPS must be stripped from the primary", ExifInterface(out.absolutePath).latLong)

        out.delete()
        source.delete()
    }

    // ---- ultra hdr gain map -----------------------------------------------

    @Test
    fun a_gain_map_upload_is_detected_and_the_split_preserves_the_appended_image() {
        val primary = jpeg(exifSegment(), xmpSegment(gainMapNamespaceXmp), mpfSegment(2))
        // A distinct second JPEG standing in for the appended gain map image.
        val gainMapImage = jpeg(app0())
        val source = File.createTempFile("gainmap_src_", ".jpg", context.cacheDir)
        source.writeBytes(primary + gainMapImage)

        assertTrue("the header advertises a gain map", stripper.hasGainMapUpload(uriOf(source)))

        // The split lands one byte past the primary's EOI, exactly the boundary of the appended image.
        val splitAt = stripper.primaryImageEndOffset(source)
        assertEquals(primary.size.toLong(), splitAt)

        // Everything from the split onward is the appended gain map image, byte-for-byte.
        val tail = source.readBytes().copyOfRange(splitAt.toInt(), source.length().toInt())
        assertArrayEquals("the appended gain map image must be preserved byte-for-byte", gainMapImage, tail)

        // NOTE: the end-to-end stripImagePreservingGainMap return is not asserted here because its
        // final gate, verifyGainMapPreserved, calls ImageDecoder.decodeBitmap()/hasGainmap(), which
        // has no reliable Robolectric shadow. The split + trailer bytes above are the load-bearing
        // logic; the no-appended-image fallback of that method is pinned by the plain-JPEG test.
        source.delete()
    }

    // ---- plain still: both paths fall back --------------------------------

    @Test
    fun a_plain_jpeg_has_no_trailer_or_gain_map_and_both_strip_paths_fall_back() {
        val source = File.createTempFile("plain_src_", ".jpg", context.cacheDir)
        source.writeBytes(jpeg(exifSegment()))
        val uri = uriOf(source)

        assertFalse(stripper.isMotionPhotoUpload(uri))
        assertFalse(stripper.hasGainMapUpload(uri))
        // Both return null so the caller runs the ordinary EXIF strip instead.
        assertNull(stripper.stripImagePreservingMotion(uri, MetadataStripConfig(stripGps = true)))
        assertNull(stripper.stripImagePreservingGainMap(uri, MetadataStripConfig(stripGps = true)))

        source.delete()
    }
}
