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

/** A JPEG header segment: its marker, where it starts, where its payload starts and where it ends. */
internal class JpegSegment(val marker: Int, val start: Int, val payload: Int, val end: Int)

/** Where the main picture ends, the pictures appended after it (MPF) and a motion photo's movie start. */
internal class JpegLayout(val mainEnd: Int, val secondaries: List<IntRange>, val movieStart: Int?)

/** Reads the parts of an image file that can carry a copy of its place, without decoding any pixels. */
internal object ImagePlaceParts {

    private const val MAX_APPENDED_PICTURES = 16
    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
    val EXIF_ID = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val PHOTOSHOP_ID = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1)
    private val RESOURCE_ID = "8BIM".toByteArray(Charsets.ISO_8859_1)

    /** IPTC IIM application-record datasets naming a place: city, sublocation, state, country code and name. */
    private val IIM_PLACE_DATASETS = setOf(90, 92, 95, 100, 101)

    private val XMP_PLACE_NAMES = setOf(
        "location", "locationcreated", "locationshown", "locationname", "locationid", "sublocation",
        "city", "state", "provincestate", "country", "countryname", "countrycode", "worldregion",
    )

    fun isJpeg(b: ByteArray, at: Int = 0) = startsWith(b, at, SOI)

    /** The header segments of the JPEG at [start], up to its first scan; null when it is malformed. */
    fun segments(b: ByteArray, start: Int): List<JpegSegment>? {
        if (!isJpeg(b, start)) return null
        val out = ArrayList<JpegSegment>()
        var at = start + 2
        while (at + 4 <= b.size) {
            if (b[at] != 0xFF.toByte()) return null
            val marker = b[at + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> at++
                marker == 0xDA || marker == 0xD9 -> return out
                marker in 0xD0..0xD7 || marker == 0x01 -> at += 2
                else -> {
                    val length = u16(b, at + 2, false)
                    if (length < 2 || at + 2 + length > b.size) return null
                    out += JpegSegment(marker, at, at + 4, at + 2 + length)
                    at += 2 + length
                }
            }
        }
        return null
    }

    /** Just past the end-of-image marker of the JPEG at [start]; null when it is malformed. */
    fun jpegEnd(b: ByteArray, start: Int): Int? {
        if (!isJpeg(b, start)) return null
        var at = start + 2
        while (at + 2 <= b.size) {
            if (b[at] != 0xFF.toByte()) return null
            val marker = b[at + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> at++
                marker == 0xD9 -> return at + 2
                marker in 0xD0..0xD7 || marker == 0x01 -> at += 2
                else -> {
                    if (at + 4 > b.size) return null
                    val length = u16(b, at + 2, false)
                    if (length < 2 || at + 2 + length > b.size) return null
                    at += 2 + length
                    if (marker == 0xDA) at = afterEntropyData(b, at) ?: return null
                }
            }
        }
        return null
    }

    private fun afterEntropyData(b: ByteArray, from: Int): Int? {
        var i = from
        while (i + 1 < b.size) {
            if (b[i] == 0xFF.toByte()) {
                val next = b[i + 1].toInt() and 0xFF
                if (next == 0xFF) {
                    i++
                    continue
                }
                if (next == 0x00 || next in 0xD0..0xD7) {
                    i += 2
                    continue
                }
                return i
            }
            i++
        }
        return null
    }

    /** The pictures and the movie appended after a JPEG's main picture; null when the main one is malformed. */
    fun layout(b: ByteArray): JpegLayout? {
        val mainEnd = jpegEnd(b, 0) ?: return null
        val movieStart = movieStart(b, mainEnd)
        val limit = movieStart ?: b.size
        val secondaries = ArrayList<IntRange>()
        var at = mainEnd
        while (secondaries.size < MAX_APPENDED_PICTURES) {
            val soi = indexOf(b, SOI, at, limit)
            if (soi < 0) break
            val end = jpegEnd(b, soi)
            if (end == null || end > limit) {
                at = soi + SOI.size
                continue
            }
            secondaries += soi until end
            at = end
        }
        return JpegLayout(mainEnd, secondaries, movieStart)
    }

    /** The start of the first ISO media box ("ftyp") after [from], where a motion photo keeps its movie. */
    private fun movieStart(b: ByteArray, from: Int): Int? {
        var search = from + 4
        while (true) {
            val hit = indexOf(b, FTYP, search, b.size)
            if (hit < 0) return null
            val start = hit - 4
            val size = u32(b, start, false)
            if (size >= 8 && size <= b.size - start) return start
            search = hit + 1
        }
    }

    /** Spans of the place values in the IPTC records of a Photoshop (APP13) payload in [from, to). */
    fun iimPlaceValues(b: ByteArray, from: Int, to: Int): List<IntRange> {
        if (!startsWith(b, from, PHOTOSHOP_ID)) return emptyList()
        val out = ArrayList<IntRange>()
        var at = from + PHOTOSHOP_ID.size
        while (at + 12 <= to && startsWith(b, at, RESOURCE_ID)) {
            val id = u16(b, at + 4, false)
            val nameLength = b[at + 6].toInt() and 0xFF
            var data = at + 6 + nameLength + 1
            if ((nameLength + 1) % 2 != 0) data++
            if (data + 4 > to) break
            val size = u32(b, data, false)
            data += 4
            if (size > to - data) break
            val end = data + size.toInt()
            if (id == 0x0404) out += iimRecords(b, data, end)
            at = end + (end - data) % 2
        }
        return out
    }

    private fun iimRecords(b: ByteArray, from: Int, to: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var at = from
        while (at + 5 <= to && b[at] == 0x1C.toByte()) {
            val record = b[at + 1].toInt() and 0xFF
            val dataset = b[at + 2].toInt() and 0xFF
            val length = u16(b, at + 3, false)
            if (length and 0x8000 != 0 || at + 5 + length > to) break
            if (record == 2 && dataset in IIM_PLACE_DATASETS && length > 0) out += (at + 5) until (at + 5 + length)
            at += 5 + length
        }
        return out
    }

    /** Spans of the XMP properties in [from, to) that hold a place, in attribute or element form. */
    fun xmpPlaceSpans(b: ByteArray, from: Int, to: Int): List<IntRange> {
        val text = String(b, from, to - from, Charsets.ISO_8859_1)
        val out = ArrayList<IntRange>()
        ATTRIBUTE.findAll(text).forEach { m ->
            if (isPlaceName(m.groupValues[2])) out += (from + m.range.first)..(from + m.range.last)
        }
        ELEMENT_START.findAll(text).forEach { m ->
            if (!isPlaceName(m.groupValues[2])) return@forEach
            val tagEnd = text.indexOf('>', m.range.last)
            if (tagEnd < 0) return@forEach
            val end = if (text[tagEnd - 1] == '/') tagEnd else closingTagEnd(text, m.groupValues[0].substring(1), tagEnd) ?: return@forEach
            out += (from + m.range.first)..(from + end)
        }
        return out
    }

    private fun isPlaceName(local: String): Boolean {
        val name = local.lowercase()
        return name.startsWith("gps") || name in XMP_PLACE_NAMES
    }

    /** The '>' of the tag closing [qualifiedName] after [from], counting nested tags of the same name. */
    private fun closingTagEnd(text: String, qualifiedName: String, from: Int): Int? {
        val open = Regex("<" + Regex.escape(qualifiedName) + "(?=[\\s/>])|</" + Regex.escape(qualifiedName) + "\\s*>")
        var depth = 1
        var at = from
        while (true) {
            val m = open.find(text, at) ?: return null
            if (m.value.startsWith("</")) {
                depth--
                if (depth == 0) return m.range.last
            } else {
                val tagEnd = text.indexOf('>', m.range.last)
                if (tagEnd < 0) return null
                if (text[tagEnd - 1] != '/') depth++
            }
            at = m.range.last + 1
        }
    }

    private val ATTRIBUTE = Regex("""(?<=\s)([A-Za-z_][\w.-]*):([A-Za-z_][\w.-]*)\s*=\s*("[^"]*"|'[^']*')""")
    private val ELEMENT_START = Regex("""<([A-Za-z_][\w.-]*):([A-Za-z_][\w.-]*)(?=[\s/>])""")

    fun startsWith(b: ByteArray, at: Int, prefix: ByteArray): Boolean =
        at >= 0 && at + prefix.size <= b.size && prefix.indices.all { b[at + it] == prefix[it] }

    fun indexOf(b: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        var i = maxOf(from, 0)
        val last = minOf(to, b.size) - needle.size
        while (i <= last) {
            if (b[i] == needle[0] && needle.indices.all { b[i + it] == needle[it] }) return i
            i++
        }
        return -1
    }

    fun u16(b: ByteArray, at: Int, littleEndian: Boolean): Int {
        if (at < 0 || at + 2 > b.size) return 0
        val hi = b[if (littleEndian) at + 1 else at].toInt() and 0xFF
        val lo = b[if (littleEndian) at else at + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    fun u32(b: ByteArray, at: Int, littleEndian: Boolean): Long {
        if (at < 0 || at + 4 > b.size) return 0
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (b[if (littleEndian) at + 3 - k else at + k].toLong() and 0xFF)
        return v
    }

    fun putU32(b: ByteArray, at: Int, value: Long, littleEndian: Boolean) {
        for (k in 0 until 4) b[if (littleEndian) at + k else at + 3 - k] = (value ushr (8 * k)).toByte()
    }
}

/**
 * Finds, and optionally erases, the location atoms of the ISO media boxes in a byte range: `©xyz` and
 * `loci` become `free` boxes of the same size with a zeroed payload, and the values of the QuickTime
 * `com.apple.quicktime.location` keys are blanked the same way, so no offset in the movie moves.
 */
internal object MovieLocation {

    private val CONTAINERS = setOf("moov", "trak", "udta")
    private val LOCATION_TYPES = setOf("©xyz", "loci")
    private const val QUICKTIME_LOCATION_KEY = "com.apple.quicktime.location"
    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)

    /** How many location values the boxes in [start, end) hold (erased first when [erase]); -1 when unreadable. */
    fun process(b: ByteArray, start: Int, end: Int, erase: Boolean): Int = walk(b, start, end, 0, erase)

    /** Whether the movie in [start, end) still holds a location; one whose boxes cannot be walked is
     *  searched for the location atom and key names instead. */
    fun holdsPlace(b: ByteArray, start: Int, end: Int): Boolean = when (process(b, start, end, erase = false)) {
        0 -> false
        -1 -> (LOCATION_TYPES + QUICKTIME_LOCATION_KEY).any {
            ImagePlaceParts.indexOf(b, it.toByteArray(Charsets.ISO_8859_1), start, end) >= 0
        }
        else -> true
    }

    private fun walk(b: ByteArray, start: Int, end: Int, depth: Int, erase: Boolean): Int {
        var pos = start
        var hits = 0
        var sawMoov = depth > 0
        while (pos + 8 <= end) {
            val box = box(b, pos, end)
            if (box == null) {
                // Data after the movie (a vendor trailer) is not a box; anything else unreadable is fatal.
                if (depth == 0 && sawMoov) break
                return -1
            }
            when {
                depth == 0 && box.type == "moov" -> sawMoov = true
                depth > 0 && box.type in LOCATION_TYPES -> {
                    hits++
                    if (erase) blank(b, box)
                }
                depth > 0 && box.type == "meta" -> hits += metaKeys(b, box.payload, box.end, erase).takeIf { it >= 0 } ?: return -1
            }
            if (box.type in CONTAINERS && (depth > 0 || box.type == "moov")) {
                hits += walk(b, box.payload, box.end, depth + 1, erase).takeIf { it >= 0 } ?: return -1
            }
            pos = box.end
        }
        return if (sawMoov) hits else -1
    }

    private fun metaKeys(b: ByteArray, start: Int, end: Int, erase: Boolean): Int {
        if (end - start < 4) return 0
        val childrenStart = if (ImagePlaceParts.u32(b, start, false) == 0L) start + 4 else start
        val children = boxes(b, childrenStart, end) ?: return -1
        val locationIndices = HashSet<Long>()
        children.firstOrNull { it.type == "keys" }?.let { keys ->
            var pos = keys.payload + 4
            if (pos + 4 > keys.end) return -1
            val count = ImagePlaceParts.u32(b, pos, false)
            pos += 4
            var index = 1L
            while (index <= count) {
                if (pos + 8 > keys.end) return -1
                val size = ImagePlaceParts.u32(b, pos, false)
                if (size < 8 || size > keys.end - pos) return -1
                val name = String(b, pos + 8, (size - 8).toInt(), Charsets.UTF_8)
                if (name.startsWith(QUICKTIME_LOCATION_KEY)) locationIndices += index
                pos += size.toInt()
                index++
            }
        }
        val ilst = children.firstOrNull { it.type == "ilst" } ?: return 0
        val items = boxes(b, ilst.payload, ilst.end) ?: return -1
        var hits = 0
        for (item in items) {
            if (item.index !in locationIndices && item.type !in LOCATION_TYPES) continue
            val values = boxes(b, item.payload, item.end) ?: return -1
            values.filter { it.type != "free" }.forEach {
                hits++
                if (erase) blank(b, it)
            }
        }
        return hits
    }

    private class Box(val start: Int, val payload: Int, val end: Int, val type: String, val index: Long)

    private fun box(b: ByteArray, pos: Int, end: Int): Box? {
        if (pos + 8 > end) return null
        val size32 = ImagePlaceParts.u32(b, pos, false)
        var header = 8
        val size = when (size32) {
            0L -> (end - pos).toLong()
            1L -> {
                if (pos + 16 > end) return null
                header = 16
                (ImagePlaceParts.u32(b, pos + 8, false) shl 32) or ImagePlaceParts.u32(b, pos + 12, false)
            }
            else -> size32
        }
        if (size < header || size > end - pos) return null
        return Box(pos, pos + header, pos + size.toInt(), String(b, pos + 4, 4, Charsets.ISO_8859_1), ImagePlaceParts.u32(b, pos + 4, false))
    }

    private fun boxes(b: ByteArray, start: Int, end: Int): List<Box>? {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val box = box(b, pos, end) ?: return null
            out += box
            pos = box.end
        }
        return out
    }

    private fun blank(b: ByteArray, box: Box) {
        FREE.copyInto(b, box.start + 4)
        b.fill(0, box.payload, box.end)
    }
}
