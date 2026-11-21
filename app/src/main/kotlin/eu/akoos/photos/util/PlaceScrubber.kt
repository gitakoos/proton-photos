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

import eu.akoos.photos.util.ImagePlaceParts.EXIF_ID
import eu.akoos.photos.util.ImagePlaceParts.startsWith
import eu.akoos.photos.util.ImagePlaceParts.u16
import eu.akoos.photos.util.ImagePlaceParts.u32
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Takes the copies of a photo's place that its EXIF GPS tags do not cover out of a JPEG, PNG or WebP
 * without moving a byte: the GPS block of each picture appended after the main one (MPF, an Ultra HDR
 * gain map), place properties in any XMP namespace, IPTC place records and a motion photo's movie
 * location atoms are blanked where they are. Every scrub also brings a JPEG's MPF index back in line
 * with where its pictures sit, since a rewrite of the main picture's EXIF changes its size.
 * What cannot be blanked this way (compressed XMP, an unreadable movie) is left for [PlaceLeftovers].
 */
object PlaceScrubber {

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val MPF_ID = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)
    private const val GPS_IFD_TAG = 0x8825
    private const val MP_ENTRY_TAG = 0xB002
    private const val MP_ENTRY_SIZE = 16

    /** Largest file read whole into memory for a scrub; a bigger one is left to [PlaceLeftovers]. */
    private const val MAX_SCRUB_BYTES = 128L shl 20

    /**
     * Scrubs [bytes] in place and reports whether anything changed. With [removePlace] false only the MPF
     * index is repaired; [keepMainExifPlace] leaves the main picture's own EXIF GPS block, which holds a
     * place just written.
     */
    fun scrub(bytes: ByteArray, removePlace: Boolean, keepMainExifPlace: Boolean): Boolean = when {
        ImagePlaceParts.isJpeg(bytes) -> scrubJpeg(bytes, removePlace, keepMainExifPlace)
        !removePlace -> false
        startsWith(bytes, 0, PNG_SIGNATURE) -> scrubPng(bytes)
        startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP) -> scrubWebp(bytes)
        else -> false
    }

    /** Whether [bytes] are a picture format [scrub] reaches: JPEG, PNG or WebP. */
    fun covers(bytes: ByteArray): Boolean = ImagePlaceParts.isJpeg(bytes) || startsWith(bytes, 0, PNG_SIGNATURE) ||
        (startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP))

    /** [scrub] on [file], written back over it only when something changed; the size never changes. */
    fun scrubFile(file: File, removePlace: Boolean, keepMainExifPlace: Boolean) {
        if (file.length() > MAX_SCRUB_BYTES) return
        val bytes = try {
            file.readBytes()
        } catch (_: OutOfMemoryError) {
            return
        }
        if (scrub(bytes, removePlace, keepMainExifPlace)) file.writeBytes(bytes)
    }

    /** [scrub] through an open read-write [fd], for a file only a descriptor reaches; the caller closes it. */
    fun scrubDescriptor(fd: FileDescriptor, removePlace: Boolean, keepMainExifPlace: Boolean) {
        // Neither stream is closed here: closing one would close the caller's descriptor.
        val input = FileInputStream(fd).channel
        val size = input.size()
        if (size > MAX_SCRUB_BYTES) return
        val buffer = try {
            ByteBuffer.allocate(size.toInt())
        } catch (_: OutOfMemoryError) {
            return
        }
        while (buffer.hasRemaining()) {
            if (input.read(buffer, buffer.position().toLong()) < 0) return
        }
        val bytes = buffer.array()
        if (!scrub(bytes, removePlace, keepMainExifPlace)) return
        val output = FileOutputStream(fd).channel
        val out = ByteBuffer.wrap(bytes)
        while (out.hasRemaining()) output.write(out, out.position().toLong())
    }

    private fun scrubJpeg(b: ByteArray, removePlace: Boolean, keepMainExifPlace: Boolean): Boolean {
        val layout = ImagePlaceParts.layout(b) ?: return false
        var changed = false
        if (removePlace) {
            val pictures = listOf(0 until layout.mainEnd) + layout.secondaries
            pictures.forEachIndexed { index, picture ->
                val segments = ImagePlaceParts.segments(b, picture.first) ?: return@forEachIndexed
                for (s in segments) {
                    if (s.marker == 0xE1 && startsWith(b, s.payload, EXIF_ID) && (index > 0 || !keepMainExifPlace)) {
                        changed = blankGpsBlock(b, s.payload + EXIF_ID.size, s.end) || changed
                    }
                    // XMP sits in its own segment, in an extension segment, or inside the EXIF as a TIFF packet.
                    if (s.marker == 0xE1) changed = blankXmp(b, s.payload, s.end) || changed
                    if (s.marker == 0xED) changed = blankIim(b, s.payload, s.end) || changed
                }
            }
            layout.movieStart?.let { if (MovieLocation.process(b, it, b.size, erase = true) > 0) changed = true }
        }
        return repairMpfIndex(b, layout) || changed
    }

    /** Empties the GPS block of the TIFF structure at [tiff]: its entries, their values and its link. */
    private fun blankGpsBlock(b: ByteArray, tiff: Int, end: Int): Boolean {
        val le = endianness(b, tiff) ?: return false
        val ifd0 = tiff + u32(b, tiff + 4, le)
        if (ifd0 + 2 > end) return false
        val entries = u16(b, ifd0.toInt(), le)
        var gps = -1L
        for (k in 0 until entries) {
            val entry = ifd0.toInt() + 2 + 12 * k
            if (entry + 12 > end) return false
            if (u16(b, entry, le) == GPS_IFD_TAG) gps = tiff + u32(b, entry + 8, le)
        }
        if (gps < 0 || gps + 2 > end) return false
        val count = u16(b, gps.toInt(), le)
        val blockEnd = gps + 2 + 12L * count + 4
        if (count == 0 || blockEnd > end) return false
        for (k in 0 until count) {
            val entry = gps.toInt() + 2 + 12 * k
            val size = typeSize(u16(b, entry + 2, le)) * u32(b, entry + 4, le)
            if (size <= 4) continue
            val value = tiff + u32(b, entry + 8, le)
            if (value >= tiff && value + size <= end) b.fill(0, value.toInt(), (value + size).toInt())
        }
        b.fill(0, gps.toInt(), blockEnd.toInt())
        return true
    }

    private fun typeSize(type: Int): Long = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 0
    }

    private fun endianness(b: ByteArray, tiff: Int): Boolean? = when {
        startsWith(b, tiff, byteArrayOf(0x49, 0x49, 0x2A, 0x00)) -> true
        startsWith(b, tiff, byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)) -> false
        else -> null
    }

    /** Overwrites each XMP place property in [from, to) with spaces, which XML reads as plain whitespace. */
    private fun blankXmp(b: ByteArray, from: Int, to: Int): Boolean {
        val spans = ImagePlaceParts.xmpPlaceSpans(b, from, to)
        spans.forEach { b.fill(' '.code.toByte(), it.first, it.last + 1) }
        return spans.isNotEmpty()
    }

    private fun blankIim(b: ByteArray, from: Int, to: Int): Boolean {
        val spans = ImagePlaceParts.iimPlaceValues(b, from, to).filter { span -> span.any { b[it] != ' '.code.toByte() } }
        spans.forEach { b.fill(' '.code.toByte(), it.first, it.last + 1) }
        return spans.isNotEmpty()
    }

    /**
     * Points each MPF index entry at the picture it names, in file order: the first at the main picture
     * with its current size, the rest at the appended ones, as offsets from the index's own header.
     * Left alone when the index does not list exactly the pictures found.
     */
    private fun repairMpfIndex(b: ByteArray, layout: JpegLayout): Boolean {
        val segment = ImagePlaceParts.segments(b, 0)
            ?.firstOrNull { it.marker == 0xE2 && startsWith(b, it.payload, MPF_ID) } ?: return false
        val tiff = segment.payload + MPF_ID.size
        val le = endianness(b, tiff) ?: return false
        val ifd = tiff + u32(b, tiff + 4, le)
        if (ifd + 2 > segment.end) return false
        var table = -1L
        var tableBytes = 0L
        for (k in 0 until u16(b, ifd.toInt(), le)) {
            val entry = ifd.toInt() + 2 + 12 * k
            if (entry + 12 > segment.end) return false
            if (u16(b, entry, le) == MP_ENTRY_TAG) {
                tableBytes = u32(b, entry + 4, le)
                table = tiff + u32(b, entry + 8, le)
            }
        }
        val pictures = tableBytes / MP_ENTRY_SIZE
        if (table < 0 || table + tableBytes > segment.end || pictures != layout.secondaries.size + 1L) return false
        val starts = layout.secondaries.map { it.first.toLong() - tiff }.toSet()
        // An index whose entries all still land on a picture keeps its own order; only the main size is refreshed.
        val stillLands = (1 until pictures.toInt()).all { u32(b, table.toInt() + MP_ENTRY_SIZE * it + 8, le) in starts }
        var changed = false
        for (k in 0 until if (stillLands) 1 else pictures.toInt()) {
            val entry = table.toInt() + MP_ENTRY_SIZE * k
            val (size, offset) = if (k == 0) {
                layout.mainEnd.toLong() to 0L
            } else {
                val picture = layout.secondaries[k - 1]
                (picture.last + 1L - picture.first) to (picture.first.toLong() - tiff)
            }
            if (u32(b, entry + 4, le) != size || u32(b, entry + 8, le) != offset) {
                ImagePlaceParts.putU32(b, entry + 4, size, le)
                ImagePlaceParts.putU32(b, entry + 8, offset, le)
                changed = true
            }
        }
        return changed
    }

    /** Blanks an uncompressed XMP text chunk and refreshes its checksum; compressed ones stay as they are. */
    private fun scrubPng(b: ByteArray): Boolean {
        var changed = false
        var at = 8
        while (at + 12 <= b.size) {
            val length = u32(b, at, false)
            if (length > b.size - at - 12) break
            val type = String(b, at + 4, 4, Charsets.US_ASCII)
            val data = at + 8
            val end = data + length.toInt()
            if (type == "iTXt") {
                val text = uncompressedXmpText(b, data, end)
                if (text >= 0 && blankXmp(b, text, end)) {
                    val crc = CRC32().apply { update(b, at + 4, 4 + length.toInt()) }.value
                    ImagePlaceParts.putU32(b, end, crc, false)
                    changed = true
                }
            }
            if (type == "IEND") break
            at = end + 4
        }
        return changed
    }

    private fun uncompressedXmpText(b: ByteArray, data: Int, end: Int): Int {
        val zero = byteArrayOf(0)
        val keywordEnd = ImagePlaceParts.indexOf(b, zero, data, end)
        if (keywordEnd < 0 || String(b, data, keywordEnd - data, Charsets.ISO_8859_1) != "XML:com.adobe.xmp") return -1
        if (keywordEnd + 2 >= end || b[keywordEnd + 1] != 0.toByte()) return -1
        val language = ImagePlaceParts.indexOf(b, zero, keywordEnd + 3, end)
        val translated = if (language < 0) -1 else ImagePlaceParts.indexOf(b, zero, language + 1, end)
        return if (translated < 0) -1 else translated + 1
    }

    private fun scrubWebp(b: ByteArray): Boolean {
        var changed = false
        var at = 12
        while (at + 8 <= b.size) {
            val size = u32(b, at + 4, true)
            if (size > b.size - at - 8) break
            val data = at + 8
            if (String(b, at, 4, Charsets.US_ASCII) == "XMP ") changed = blankXmp(b, data, data + size.toInt()) || changed
            at = data + size.toInt() + (size % 2).toInt()
        }
        return changed
    }
}
