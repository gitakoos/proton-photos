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

import androidx.exifinterface.media.ExifInterface
import eu.akoos.photos.data.db.entity.isUsableGpsFix
import eu.akoos.photos.util.ImagePlaceParts.indexOf
import eu.akoos.photos.util.ImagePlaceParts.startsWith
import eu.akoos.photos.util.ImagePlaceParts.u32
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Copies of a photo's place that taking the EXIF GPS tags out leaves behind: XMP location properties in
 * any namespace (a PNG's compressed XMP included), the IPTC location records of a JPEG's Photoshop block,
 * the EXIF place of a picture appended after the main JPEG (MPF/MPO) and the location atoms of a motion
 * photo's movie. [PlaceScrubber] blanks what it can; whatever this still finds afterwards stays in the file.
 */
object PlaceLeftovers {

    private val XMP_PLACE_KEYS = listOf(
        "gpslatitude", "gpslongitude", "locationcreated", "locationshown",
        "iptc4xmpcore:location", "photoshop:city", "photoshop:state", "photoshop:country",
    ).map { it.toByteArray(Charsets.US_ASCII) }

    private const val MAX_JPEG_STARTS = 64
    private const val MAX_INFLATED_XMP = 4 shl 20
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    fun holdsPlace(bytes: ByteArray): Boolean =
        containsXmpPlace(bytes, 0, bytes.size) ||
            (ImagePlaceParts.isJpeg(bytes) && jpegPartsHoldPlace(bytes)) ||
            (startsWith(bytes, 0, PNG_SIGNATURE) && compressedPngXmpHoldsPlace(bytes))

    private fun containsXmpPlace(b: ByteArray, from: Int, to: Int): Boolean =
        XMP_PLACE_KEYS.any { indexOfIgnoreCase(b, it, from, to) >= 0 }

    /** Every JPEG start after the main picture is read, not only the pictures the layout could walk, so
     *  one cut short or past the walk's limit still counts. */
    private fun jpegPartsHoldPlace(b: ByteArray): Boolean {
        val layout = ImagePlaceParts.layout(b) ?: return anyJpegStartHoldsPlace(b, 2, b.size)
        val iim = ImagePlaceParts.segments(b, 0).orEmpty().filter { it.marker == 0xED }
            .flatMap { ImagePlaceParts.iimPlaceValues(b, it.payload, it.end) }
        return iim.any { span -> span.any { b[it] != ' '.code.toByte() } } ||
            anyJpegStartHoldsPlace(b, layout.mainEnd, layout.movieStart ?: b.size) ||
            (layout.movieStart != null && MovieLocation.holdsPlace(b, layout.movieStart, b.size))
    }

    private fun exifPlaceAt(b: ByteArray, from: Int, to: Int): Boolean {
        val fix = runCatching { ExifInterface(ByteArrayInputStream(b, from, to - from)).latLong }.getOrNull()
        return fix != null && isUsableGpsFix(fix[0], fix[1])
    }

    /** Every JPEG start in [from, to) read for its own EXIF place; more starts than [MAX_JPEG_STARTS]
     *  count as holding one. */
    private fun anyJpegStartHoldsPlace(b: ByteArray, from: Int, to: Int): Boolean {
        var checked = 0
        var at = from
        while (checked < MAX_JPEG_STARTS) {
            at = indexOf(b, SOI, at, to)
            if (at < 0) return false
            checked++
            if (exifPlaceAt(b, at, to)) return true
            at += SOI.size
        }
        return true
    }

    /** XMP a PNG keeps deflated in an iTXt or zTXt chunk, which a plain byte search cannot see. */
    private fun compressedPngXmpHoldsPlace(b: ByteArray): Boolean {
        var at = 8
        while (at + 12 <= b.size) {
            val length = u32(b, at, false)
            val type = String(b, at + 4, 4, Charsets.US_ASCII)
            val data = at + 8
            if (length > b.size - data) return false
            val end = data + length.toInt()
            val keywordEnd = indexOf(b, byteArrayOf(0), data, end)
            val keyword = if (keywordEnd > data) String(b, data, keywordEnd - data, Charsets.ISO_8859_1) else ""
            if (keyword.contains("xmp", ignoreCase = true)) {
                val deflated = when {
                    type == "iTXt" && keywordEnd + 1 < end && b[keywordEnd + 1] == 1.toByte() -> {
                        val language = indexOf(b, byteArrayOf(0), keywordEnd + 3, end)
                        val translated = if (language < 0) -1 else indexOf(b, byteArrayOf(0), language + 1, end)
                        if (translated < 0) return true
                        translated + 1
                    }
                    type == "zTXt" -> keywordEnd + 2
                    else -> -1
                }
                if (deflated in data until end) {
                    val xmp = inflate(b, deflated, end - deflated) ?: return true
                    if (containsXmpPlace(xmp, 0, xmp.size)) return true
                }
            }
            if (type == "IEND") return false
            at = end + 4
        }
        return false
    }

    private fun inflate(b: ByteArray, offset: Int, length: Int): ByteArray? = runCatching {
        val inflater = Inflater()
        try {
            inflater.setInput(b, offset, length)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED_XMP) return null
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }.getOrNull()

    private fun indexOfIgnoreCase(b: ByteArray, lowerNeedle: ByteArray, from: Int, to: Int): Int {
        val first = lowerNeedle[0]
        var i = from
        while (i <= to - lowerNeedle.size) {
            if (lower(b[i]) == first && lowerNeedle.indices.all { lower(b[i + it]) == lowerNeedle[it] }) return i
            i++
        }
        return -1
    }

    private fun lower(c: Byte): Byte = if (c in 'A'.code.toByte()..'Z'.code.toByte()) (c + 32).toByte() else c
}
