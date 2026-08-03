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

import java.io.File
import java.io.InputStream

/**
 * Detects whether a JPEG carries an Ultra HDR gain map.
 *
 * An Ultra HDR still (what Android 14+ writes, the Google Ultra HDR / ISO 21496-1 pairing) is an
 * ordinary SDR JPEG whose header advertises a second, appended image holding the per-pixel gain
 * map. Two XMP signals mark it and either one alone is conclusive:
 *  - The Adobe HDR gain map namespace `http://ns.adobe.com/hdr-gain-map/1.0/`, which normally
 *    surfaces on the primary's XMP packet as `hdrgm:Version="1.0"`.
 *  - A GContainer directory (`http://ns.google.com/photos/1.0/container/`) listing an item with
 *    `Item:Semantic="GainMap"`.
 *
 * The MPF (APP2) directory is deliberately left unparsed. It only states that the file holds two or
 * more images, which is equally true of a Motion Photo whose appended image is an MP4 clip, so
 * reading it as evidence would flag every motion photo as Ultra HDR. At most it could corroborate a
 * marker already found in XMP, never establish one. The two features also coexist on recent phones,
 * so a `Semantic="MotionPhoto"` item never suppresses a gain map match.
 *
 * The walk is header-only: it follows the APPn marker chain and stops at SOS, so the pixel data and
 * the appended gain map image are never read and no bitmap is ever allocated. Defensive throughout,
 * any malformed, truncated or unreadable input yields false.
 */
object UltraHdrUtil {

    /**
     * True when the JPEG bytes on [input] advertise a gain map. Reads only the marker segments that
     * precede the scan data, so a multi-megabyte file costs a few kilobytes of IO. The caller owns
     * and closes [input].
     */
    fun hasGainMap(input: InputStream): Boolean = try {
        scanHeader(input)
    } catch (e: Exception) {
        false
    }

    /** True when [file] is a JPEG whose header advertises a gain map. Never reads the whole file. */
    fun hasGainMap(file: File): Boolean = try {
        if (!file.isFile || file.length() <= 0L) {
            false
        } else {
            // Buffered because the marker chain is walked a byte at a time between segments.
            file.inputStream().buffered().use { scanHeader(it) }
        }
    } catch (e: Exception) {
        false
    }

    private const val HDR_GAIN_MAP_NS = "http://ns.adobe.com/hdr-gain-map/1.0/"

    /** The attribute form the namespace almost always appears with, kept as a second needle so a
     *  packet that declares the prefix elsewhere is still recognised. */
    private val HDRGM_VERSION_REGEX = Regex("""hdrgm:Version\s*=\s*["']""")

    /** GContainer directory form: an item whose semantic is the gain map image. */
    private val SEMANTIC_GAIN_MAP_REGEX = Regex("""Semantic\s*=\s*["']GainMap["']""")

    // Ceiling on how far the marker walk reads before giving up. Every APPn segment is capped at
    // 64 KB by its 2-byte length field, so this covers a long chain of EXIF, XMP, ICC and MPF
    // segments while keeping a file that is not really a JPEG from being walked end to end.
    private const val MAX_HEADER_SCAN_BYTES = 512 * 1024

    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_PAD = 0x00
    private const val MARKER_TEM = 0x01
    private const val MARKER_RST_FIRST = 0xD0
    private const val MARKER_RST_LAST = 0xD7
    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP_FIRST = 0xE0
    private const val MARKER_APP_LAST = 0xEF

    /**
     * Walks the JPEG marker chain and returns true at the first APPn segment carrying a gain map
     * needle. Any corruption, truncation or non-JPEG prefix ends the walk with false.
     */
    private fun scanHeader(input: InputStream): Boolean {
        val pair = ByteArray(2)
        if (!readFully(input, pair, 2)) return false
        if ((pair[0].toInt() and 0xFF) != MARKER_PREFIX || (pair[1].toInt() and 0xFF) != MARKER_SOI) return false

        var consumed = 2
        while (consumed < MAX_HEADER_SCAN_BYTES) {
            val prefix = input.read()
            consumed++
            // Anything other than 0xFF here means the walk has lost sync with the segment chain.
            if (prefix != MARKER_PREFIX) return false

            // A marker may be padded with any number of extra 0xFF fill bytes.
            var marker = input.read()
            consumed++
            while (marker == MARKER_PREFIX) {
                marker = input.read()
                consumed++
            }
            if (marker < 0) return false

            when {
                // Scan data begins at SOS, so the header ends here. Whatever follows (including the
                // appended gain map image and its own XMP) is not part of the primary's header.
                marker == MARKER_SOS || marker == MARKER_EOI -> return false
                // Standalone markers carry no length field.
                marker == MARKER_SOI || marker == MARKER_TEM -> continue
                marker in MARKER_RST_FIRST..MARKER_RST_LAST -> continue
                // A stuffed 0x00 belongs to entropy-coded data, never to a header marker.
                marker == MARKER_PAD -> return false
            }

            if (!readFully(input, pair, 2)) return false
            consumed += 2
            // The big-endian length counts its own two bytes, so anything below 2 is corrupt.
            val length = ((pair[0].toInt() and 0xFF) shl 8) or (pair[1].toInt() and 0xFF)
            if (length < 2) return false
            val payloadLength = length - 2

            if (marker in MARKER_APP_FIRST..MARKER_APP_LAST) {
                val payload = ByteArray(payloadLength)
                if (!readFully(input, payload, payloadLength)) return false
                consumed += payloadLength
                if (carriesGainMapMarker(payload, payloadLength)) return true
            } else {
                if (!skipFully(input, payloadLength)) return false
                consumed += payloadLength
            }
        }
        return false
    }

    private fun carriesGainMapMarker(payload: ByteArray, length: Int): Boolean {
        // Latin-1 maps every byte to a char, so the ASCII XMP needles are found verbatim inside the
        // otherwise-binary segment without decoding it as a real XMP packet.
        val text = String(payload, 0, length, Charsets.ISO_8859_1)
        return text.contains(HDR_GAIN_MAP_NS) ||
            HDRGM_VERSION_REGEX.containsMatchIn(text) ||
            SEMANTIC_GAIN_MAP_REGEX.containsMatchIn(text)
    }

    /** Fills [count] bytes of [buffer] from [input]. False when the stream ends first. */
    private fun readFully(input: InputStream, buffer: ByteArray, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /** Discards [count] bytes from [input]. False when the stream ends first. */
    private fun skipFully(input: InputStream, count: Int): Boolean {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() is allowed to return 0 well before the end of the stream, so a single read
            // keeps the loop making progress and distinguishes a stall from a real EOF.
            if (input.read() < 0) return false
            remaining--
        }
        return true
    }
}
