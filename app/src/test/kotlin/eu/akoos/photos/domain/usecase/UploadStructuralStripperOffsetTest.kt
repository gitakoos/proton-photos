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
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Pins the byte-level JPEG marker walk in [UploadStructuralStripper.primaryImageEndOffset] and
 * [UploadStructuralStripper.firstEoiFrom] against synthetic JPEGs built segment by segment, the same
 * construction style [UltraHdrUtilTest] uses. This is the offset that splits an Ultra HDR still from
 * its appended gain map (and, structurally, any trailer): land it one byte wrong and the primary is
 * corrupted or the appended image is dropped, so every shape the walk must survive is exercised here.
 *
 * Pure JVM: the two functions read a [RandomAccessFile] and touch no Android runtime, so a relaxed
 * mock stands in for the [Context] the class carries but never dereferences on this path.
 */
class UploadStructuralStripperOffsetTest {

    private val stripper = UploadStructuralStripper(mockk<Context>(relaxed = true))

    // ---- segment builders (mirrors UltraHdrUtilTest) ----------------------
    // Identifiers are built as ascii(name) + byteArrayOf(0x00) so the NUL terminator is an explicit
    // byte, keeping the source plain ASCII.

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

    /** APP0 JFIF header, the ordinary first segment of a baseline JPEG. */
    private fun app0(): ByteArray = segment(
        0xE0,
        ascii("JFIF") + byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00),
    )

    /** SOI, the given segments, then SOS with a token of entropy data and EOI. The primary EOI sits
     *  at the very end, so one byte past it is exactly the array length. */
    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
        out.write(byteArrayOf(0x00, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    // ---- helpers ----------------------------------------------------------

    private fun offsetOf(bytes: ByteArray): Long {
        val file = File.createTempFile("upload_offset_", ".jpg")
        return try {
            file.writeBytes(bytes)
            stripper.primaryImageEndOffset(file)
        } finally {
            file.delete()
        }
    }

    private inline fun <T> withRaf(bytes: ByteArray, block: (RandomAccessFile, Long) -> T): T {
        val file = File.createTempFile("upload_eoi_", ".bin")
        return try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use { block(it, file.length()) }
        } finally {
            file.delete()
        }
    }

    /** Index one byte past the FIRST `0xFF 0xD9` anywhere in [bytes], the value a naive scan returns. */
    private fun naiveFirstEoiEnd(bytes: ByteArray): Long {
        var i = 0
        while (i < bytes.size - 1) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD9) {
                return (i + 2).toLong()
            }
            i++
        }
        return -1L
    }

    // ---- primaryImageEndOffset --------------------------------------------

    @Test
    fun a_baseline_jpeg_ends_one_byte_past_the_primary_eoi() {
        val bytes = jpeg(app0())
        // The EOI is the last two bytes, so one byte past it is the end of the file.
        assertEquals(bytes.size.toLong(), offsetOf(bytes))
    }

    @Test
    fun an_inner_eoi_inside_an_appn_payload_is_stepped_over() {
        // The APP1 payload carries a fake `0xFF 0xD9` (a thumbnail's own EOI). The walk must follow
        // the marker length chain over the whole segment and land on the REAL primary EOI, not this
        // inner one, which a naive byte scan would stop at first.
        val innerEoiApp1 = segment(
            0xE1,
            ascii("Exif") + byteArrayOf(0x00, 0x00) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte()) +
                byteArrayOf(0x11, 0x22, 0x33),
        )
        val bytes = jpeg(innerEoiApp1)

        val offset = offsetOf(bytes)
        val naive = naiveFirstEoiEnd(bytes)

        // Sanity: the naive scan really is fooled by the inner EOI (it sits before the real one).
        assertTrue("fixture must contain an inner EOI ahead of the real one", naive in 1 until bytes.size.toLong())
        // The real answer is the trailing EOI, strictly past the inner one the naive scan found.
        assertEquals(bytes.size.toLong(), offset)
        assertNotEquals(naive, offset)
        assertTrue(offset > naive)
    }

    @Test
    fun a_stream_with_no_scan_and_no_eoi_returns_the_sentinel() {
        // SOI + a single APP0 and nothing more: the marker walk runs off the end before any SOS.
        val truncated = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0()
        assertEquals(-1L, offsetOf(truncated))
    }

    @Test
    fun a_scan_that_never_reaches_an_eoi_returns_the_sentinel() {
        // SOS is present but the entropy data is never terminated by an EOI.
        val noEoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0() +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00) +
            byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertEquals(-1L, offsetOf(noEoi))
    }

    @Test
    fun bytes_that_are_not_a_jpeg_return_the_sentinel() {
        assertEquals(-1L, offsetOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun a_stream_shorter_than_a_marker_returns_the_sentinel() {
        assertEquals(-1L, offsetOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    // ---- firstEoiFrom -----------------------------------------------------

    @Test
    fun first_eoi_from_returns_one_byte_past_the_eoi_pair() {
        // `00 11 FF D9 22`: the EOI pair sits at index 2, so the answer is index 4.
        withRaf(byteArrayOf(0x00, 0x11, 0xFF.toByte(), 0xD9.toByte(), 0x22)) { raf, len ->
            assertEquals(4L, stripper.firstEoiFrom(raf, 0L, len))
        }
    }

    @Test
    fun first_eoi_from_ignores_an_eoi_before_the_start_offset() {
        // `FF D9 00 FF D9`: starting the scan at offset 2 must skip the leading EOI and find the
        // second pair, proving the offset gate (the scan begins at SOS, past any header EOI).
        withRaf(byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())) { raf, len ->
            assertEquals(5L, stripper.firstEoiFrom(raf, 2L, len))
        }
    }

    @Test
    fun first_eoi_from_returns_the_sentinel_when_no_eoi_follows() {
        withRaf(byteArrayOf(0x00, 0x11, 0x22, 0xFF.toByte(), 0x00)) { raf, len ->
            assertEquals(-1L, stripper.firstEoiFrom(raf, 0L, len))
        }
    }
}
