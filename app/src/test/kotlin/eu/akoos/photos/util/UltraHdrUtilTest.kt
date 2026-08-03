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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pins [UltraHdrUtil.hasGainMap] against synthetic JPEG headers built segment by segment, so every
 * marker shape the walk has to survive is exercised without shipping binary fixtures.
 *
 * The case that matters most is the motion photo guard: a Motion Photo also appends a second image
 * and so also carries a two-image MPF directory, and mistaking that for a gain map would light up
 * an HDR affordance on every burst of Samsung and Pixel stills. The pair of files that are BOTH a
 * motion photo and Ultra HDR must still be recognised.
 */
class UltraHdrUtilTest {

    // ---- segment builders -------------------------------------------------

    /** `FF <marker> <2-byte length incl. itself> <payload>`. */
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

    /** APP1 EXIF: the "Exif\0\0" identifier plus an empty big-endian TIFF directory. */
    private fun exifSegment(): ByteArray = segment(
        0xE1,
        ascii("Exif\u0000\u0000") +
            byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
    )

    /** APP1 XMP: the Adobe packet identifier plus the given RDF body. */
    private fun xmpSegment(body: String): ByteArray = segment(
        0xE1,
        ascii("http://ns.adobe.com/xap/1.0/\u0000") + ascii(body),
    )

    /** APP2 ICC profile stub, present purely to push the XMP further down the chain. */
    private fun iccSegment(): ByteArray = segment(
        0xE2,
        ascii("ICC_PROFILE\u0000") + byteArrayOf(0x01, 0x01) + ByteArray(64),
    )

    /**
     * APP2 MPF directory declaring [imageCount] images: the "MPF\0" identifier, a big-endian TIFF
     * header, then the MPFVersion and NumberOfImages tags. This is the corroboration-only signal
     * that must never flip the result by itself.
     */
    private fun mpfSegment(imageCount: Int): ByteArray = segment(
        0xE2,
        ascii("MPF\u0000") +
            byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08) +
            byteArrayOf(0x00, 0x02) +
            // MPFVersion (0xB000), UNDEFINED, 4 bytes, "0100"
            byteArrayOf(0xB0.toByte(), 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x04, 0x30, 0x31, 0x30, 0x30) +
            // NumberOfImages (0xB001), LONG, 1 value
            byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01) +
            byteArrayOf(0x00, 0x00, 0x00, imageCount.toByte()) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
    )

    /** Prepends an extra 0xFF fill byte, which encoders are allowed to pad a marker with. */
    private fun padded(segment: ByteArray): ByteArray = byteArrayOf(0xFF.toByte()) + segment

    /** SOI, the given segments, then SOS with a token of scan data and EOI. */
    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
        out.write(byteArrayOf(0x00, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun detect(bytes: ByteArray): Boolean =
        ByteArrayInputStream(bytes).use { UltraHdrUtil.hasGainMap(it) }

    // ---- XMP bodies -------------------------------------------------------

    private val gainMapNamespaceXmp =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description """ +
            """xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" """ +
            """hdrgm:GainMapMax="2.5"/></rdf:RDF></x:xmpmeta>"""

    private val containerGainMapXmp =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/"><Container:Directory>""" +
            """<rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>""" +
            """<rdf:li><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" """ +
            """Item:Length="51200"/></rdf:li></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private val motionPhotoXmp =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description Camera:MotionPhoto="1" """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/"><Container:Directory>""" +
            """<rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>""" +
            """<rdf:li><Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" """ +
            """Item:Length="1048576"/></rdf:li></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private val motionPhotoAndGainMapXmp =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description Camera:MotionPhoto="1" """ +
            """xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/"><Container:Directory>""" +
            """<rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>""" +
            """<rdf:li><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" """ +
            """Item:Length="51200"/></rdf:li><rdf:li><Container:Item Item:Semantic="MotionPhoto" """ +
            """Item:Mime="video/mp4" Item:Length="1048576"/></rdf:li></Container:Directory>""" +
            """</rdf:Description></rdf:RDF></x:xmpmeta>"""

    // ---- detection --------------------------------------------------------

    @Test
    fun a_baseline_jpeg_with_only_exif_has_no_gain_map() {
        assertFalse(detect(jpeg(exifSegment())))
    }

    @Test
    fun the_adobe_hdr_gain_map_namespace_is_detected() {
        assertTrue(detect(jpeg(exifSegment(), xmpSegment(gainMapNamespaceXmp))))
    }

    @Test
    fun an_hdrgm_version_attribute_without_the_namespace_uri_is_detected() {
        assertTrue(detect(jpeg(xmpSegment("""<rdf:Description hdrgm:Version="1.0"/>"""))))
    }

    @Test
    fun a_container_item_with_the_gain_map_semantic_is_detected() {
        assertTrue(detect(jpeg(exifSegment(), xmpSegment(containerGainMapXmp))))
    }

    @Test
    fun a_motion_photo_with_a_two_image_mpf_is_not_ultra_hdr() {
        // The regression guard: two MPF images plus a Container item is exactly a motion photo, and
        // nothing in that shape says gain map.
        assertFalse(detect(jpeg(exifSegment(), xmpSegment(motionPhotoXmp), mpfSegment(2))))
    }

    @Test
    fun a_file_that_is_both_a_motion_photo_and_ultra_hdr_is_detected() {
        assertTrue(detect(jpeg(exifSegment(), xmpSegment(motionPhotoAndGainMapXmp), mpfSegment(3))))
    }

    @Test
    fun gain_map_xmp_behind_several_other_segments_is_still_found() {
        assertTrue(
            detect(
                jpeg(
                    segment(0xE0, ascii("JFIF\u0000") + byteArrayOf(0x01, 0x02, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)),
                    exifSegment(),
                    iccSegment(),
                    segment(0xEE, ascii("Adobe\u0000") + ByteArray(8)),
                    // A quantisation table between the app segments: a non-APPn payload gets skipped,
                    // not scanned, and the walk has to resume cleanly after it.
                    segment(0xDB, byteArrayOf(0x00) + ByteArray(64) { 0x10.toByte() }),
                    xmpSegment(gainMapNamespaceXmp),
                    mpfSegment(2),
                ),
            ),
        )
    }

    @Test
    fun a_marker_padded_with_fill_bytes_is_still_walked() {
        assertTrue(detect(jpeg(padded(exifSegment()), padded(padded(xmpSegment(gainMapNamespaceXmp))))))
    }

    // ---- malformed input --------------------------------------------------

    @Test
    fun a_stream_truncated_inside_the_xmp_segment_is_not_ultra_hdr() {
        val full = jpeg(exifSegment(), xmpSegment(gainMapNamespaceXmp))
        assertFalse(detect(full.copyOf(full.size - 40)))
    }

    @Test
    fun an_empty_stream_is_not_ultra_hdr() {
        assertFalse(detect(ByteArray(0)))
    }

    @Test
    fun bytes_that_are_not_a_jpeg_are_not_ultra_hdr() {
        assertFalse(detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ascii(" not a jpeg at all ") + ascii(HDR_GAIN_MAP_MARKER)))
    }

    @Test
    fun a_segment_length_below_two_ends_the_walk() {
        // A length of 1 cannot even cover its own two bytes, so the chain is corrupt from here on.
        val corrupt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x01) +
            ascii(HDR_GAIN_MAP_MARKER)
        assertFalse(detect(corrupt))
    }

    @Test
    fun a_gain_map_marker_after_the_scan_data_is_not_read() {
        // The appended gain map image carries its own XMP. Only the primary's header counts, so the
        // walk must stop at SOS rather than string-scanning the rest of the file.
        assertFalse(detect(jpeg(exifSegment()) + xmpSegment(gainMapNamespaceXmp)))
    }

    // ---- file overload ----------------------------------------------------

    @Test
    fun the_file_overload_detects_a_gain_map_on_disk() {
        val file = File.createTempFile("ultra_hdr_", ".jpg")
        try {
            file.writeBytes(jpeg(exifSegment(), xmpSegment(gainMapNamespaceXmp)))
            assertTrue(UltraHdrUtil.hasGainMap(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun a_missing_file_is_not_ultra_hdr() {
        val missing = File(System.getProperty("java.io.tmpdir"), "ultra_hdr_absent_${System.nanoTime()}.jpg")
        assertFalse(UltraHdrUtil.hasGainMap(missing))
    }

    private companion object {
        const val HDR_GAIN_MAP_MARKER = """hdrgm:Version="1.0" http://ns.adobe.com/hdr-gain-map/1.0/"""
    }
}
