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
import java.io.RandomAccessFile

/**
 * Removes the GPS tags from a HEIF still (HEIC, AVIF) in place, for a file the EXIF writer cannot
 * rewrite. The Exif item is found through `meta`/`iinf`/`iloc`; inside it the GPS IFD is emptied (its
 * entry count and next-IFD link set to zero) and every GPS value is zeroed. No length changes, so the
 * image items, an appended motion clip and every offset that points at them stay exactly as they were.
 * Any layout this does not fully understand is refused rather than guessed at.
 */
object HeifExifGps {

    sealed interface Result {
        /** The GPS IFD was emptied; only bytes in [exifStart, exifEnd) changed. */
        data class Erased(val exifStart: Long, val exifEnd: Long) : Result

        /** The file carries no GPS IFD; nothing was written. */
        data object NoGps : Result

        /** A layout this cannot patch safely; nothing was written. */
        data class Unsupported(val reason: String) : Result
    }

    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    private const val GPS_IFD_TAG = 0x8825
    private const val MAX_EXIF_BYTES = 1 shl 20
    private const val MAX_XMP_BYTES = 4 shl 20

    /** True when [file] starts with an `ftyp` box naming a HEIF brand. */
    fun isHeif(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 16) return@use false
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            if (type(raf) != "ftyp" || size < 16 || size > minOf(raf.length(), 4096L)) return@use false
            val brands = ByteArray((size - 8).toInt()).also { raf.readFully(it) }
            // The major brand, then the compatible brands after the 4-byte minor version.
            (listOf(0) + (8 until brands.size - 3 step 4)).any { String(brands, it, 4, Charsets.ISO_8859_1) in HEIF_BRANDS }
        }
    }.getOrDefault(false)

    /** Empties the GPS IFD of [file] in place. */
    fun erase(file: File): Result = runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            when (val found = locate(raf)) {
                is Located.Refused -> Result.Unsupported(found.reason)
                Located.NoExif -> Result.NoGps
                is Located.Exif -> {
                    val gps = found.gps ?: return@use Result.NoGps
                    if (gps.entryCount == 0) return@use Result.NoGps
                    val bytes = found.bytes
                    gps.valueRanges.forEach { (from, to) -> bytes.fill(0, from, to) }
                    bytes.fill(0, gps.ifdStart, gps.ifdEnd)
                    raf.seek(found.offset)
                    raf.write(bytes)
                    Result.Erased(found.offset, found.offset + bytes.size)
                }
            }
        }
    }.getOrElse { Result.Unsupported(it.javaClass.simpleName) }

    /** The number of GPS entries [file] carries: 0 when it has none, null when its layout is unreadable. */
    fun gpsEntryCount(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            when (val found = locate(raf)) {
                is Located.Refused -> null
                Located.NoExif -> 0
                is Located.Exif -> found.gps?.entryCount ?: 0
            }
        }
    }.getOrNull()

    private sealed interface Located {
        data class Refused(val reason: String) : Located
        data object NoExif : Located
        class Exif(val offset: Long, val bytes: ByteArray, val gps: GpsIfd?) : Located
    }

    /** The GPS IFD inside the Exif item's bytes: [ifdStart, ifdEnd) spans its count, entries and link. */
    private class GpsIfd(val entryCount: Int, val ifdStart: Int, val ifdEnd: Int, val valueRanges: List<Pair<Int, Int>>)

    private class Box(val start: Long, val payload: Long, val end: Long, val type: String)

    private class Extent(val offset: Long, val length: Long)

    private fun locate(raf: RandomAccessFile): Located {
        val length = raf.length()
        val top = boxes(raf, 0L, length, stopAt = "meta") ?: return Located.Refused("top-level box")
        if (top.firstOrNull()?.type != "ftyp") return Located.Refused("no ftyp")
        val meta = top.firstOrNull { it.type == "meta" } ?: return Located.Refused("no meta")
        val metaChildren = boxes(raf, meta.payload + 4, meta.end) ?: return Located.Refused("meta box")
        val iinf = metaChildren.singleOrNull { it.type == "iinf" } ?: return Located.Refused("iinf")
        val iloc = metaChildren.singleOrNull { it.type == "iloc" } ?: return Located.Refused("iloc")

        val exifIds = ArrayList<Long>()
        val xmpIds = ArrayList<Long>()
        if (!readItemInfos(raf, iinf, exifIds, xmpIds)) return Located.Refused("item info")
        val extents = readLocations(raf, iloc) ?: return Located.Refused("item locations")

        // XMP can carry its own copy of the coordinates, which this does not rewrite.
        for (id in xmpIds) {
            val extent = extents[id] ?: return Located.Refused("xmp location")
            if (extent.length > MAX_XMP_BYTES || extent.offset + extent.length > length) return Located.Refused("xmp size")
            raf.seek(extent.offset)
            val xmp = String(ByteArray(extent.length.toInt()).also { raf.readFully(it) }, Charsets.ISO_8859_1)
            if (xmp.contains("GPSLatitude") || xmp.contains("GPSLongitude")) return Located.Refused("xmp gps")
        }

        if (exifIds.isEmpty()) return Located.NoExif
        if (exifIds.size > 1) return Located.Refused("several exif items")
        val extent = extents[exifIds[0]] ?: return Located.Refused("exif location")
        if (extent.length < 8 || extent.length > MAX_EXIF_BYTES || extent.offset + extent.length > length) {
            return Located.Refused("exif size")
        }
        raf.seek(extent.offset)
        val bytes = ByteArray(extent.length.toInt()).also { raf.readFully(it) }
        val gps = when (val parsed = parseGpsIfd(bytes)) {
            is GpsParse.Found -> parsed.ifd
            GpsParse.None -> null
            GpsParse.Bad -> return Located.Refused("exif tiff")
        }
        return Located.Exif(extent.offset, bytes, gps)
    }

    /** Collects the ids of the Exif and XMP items; false on an item info layout this does not know. */
    private fun readItemInfos(raf: RandomAccessFile, iinf: Box, exifIds: MutableList<Long>, xmpIds: MutableList<Long>): Boolean {
        raf.seek(iinf.payload)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3)
        val count = if (version == 0) raf.readUnsignedShort().toLong() else raf.readInt().toLong() and 0xFFFFFFFFL
        val entries = boxes(raf, raf.filePointer, iinf.end) ?: return false
        if (entries.size.toLong() != count || entries.any { it.type != "infe" }) return false
        for (infe in entries) {
            raf.seek(infe.payload)
            val infeVersion = raf.readUnsignedByte()
            raf.skipBytes(3)
            if (infeVersion < 2) return false
            val id = if (infeVersion == 2) raf.readUnsignedShort().toLong() else raf.readInt().toLong() and 0xFFFFFFFFL
            raf.skipBytes(2)
            when (type(raf)) {
                "Exif" -> exifIds += id
                "mime" -> {
                    val rest = ByteArray((infe.end - raf.filePointer).toInt().coerceAtLeast(0)).also { raf.readFully(it) }
                    if (String(rest, Charsets.ISO_8859_1).contains("rdf+xml")) xmpIds += id
                }
            }
        }
        return true
    }

    /** Each item's single file extent; null on a layout this does not know (idat, several extents). */
    private fun readLocations(raf: RandomAccessFile, iloc: Box): Map<Long, Extent>? {
        raf.seek(iloc.payload)
        val version = raf.readUnsignedByte()
        if (version > 2) return null
        raf.skipBytes(3)
        val sizes1 = raf.readUnsignedByte()
        val sizes2 = raf.readUnsignedByte()
        val offsetSize = sizes1 ushr 4
        val lengthSize = sizes1 and 0xF
        val baseOffsetSize = sizes2 ushr 4
        val indexSize = if (version >= 1) sizes2 and 0xF else 0
        if (listOf(offsetSize, lengthSize, baseOffsetSize, indexSize).any { it !in setOf(0, 4, 8) }) return null
        val count = if (version < 2) raf.readUnsignedShort().toLong() else raf.readInt().toLong() and 0xFFFFFFFFL
        val out = HashMap<Long, Extent>()
        for (i in 0 until count) {
            if (raf.filePointer >= iloc.end) return null
            val id = if (version < 2) raf.readUnsignedShort().toLong() else raf.readInt().toLong() and 0xFFFFFFFFL
            val method = if (version >= 1) raf.readUnsignedShort() and 0xF else 0
            val dataRef = raf.readUnsignedShort()
            val base = readUint(raf, baseOffsetSize)
            val extentCount = raf.readUnsignedShort()
            var extent: Extent? = null
            repeat(extentCount) {
                readUint(raf, indexSize)
                val offset = readUint(raf, offsetSize)
                val len = readUint(raf, lengthSize)
                extent = Extent(base + offset, len)
            }
            if (method == 0 && dataRef == 0 && extentCount == 1) out[id] = extent!!
        }
        if (raf.filePointer > iloc.end) return null
        return out
    }

    private sealed interface GpsParse {
        class Found(val ifd: GpsIfd) : GpsParse
        data object None : GpsParse
        data object Bad : GpsParse
    }

    /** Finds the GPS IFD in an Exif item: a 4-byte offset to the TIFF header, then the TIFF structure. */
    private fun parseGpsIfd(bytes: ByteArray): GpsParse {
        val tiff = 4 + u32(bytes, 0, big = true)
        if (tiff < 4 || tiff + 8 > bytes.size) return GpsParse.Bad
        val big = when {
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> true
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> false
            else -> return GpsParse.Bad
        }
        if (u16(bytes, tiff + 2, big) != 42) return GpsParse.Bad
        val ifd0 = tiff + u32(bytes, tiff + 4, big)
        val ifd0Count = ifdCount(bytes, ifd0, big) ?: return GpsParse.Bad
        var gpsOffset = -1
        for (i in 0 until ifd0Count) {
            val entry = ifd0 + 2 + i * 12
            if (u16(bytes, entry, big) == GPS_IFD_TAG) gpsOffset = u32(bytes, entry + 8, big)
        }
        if (gpsOffset < 0) return GpsParse.None
        val gps = tiff + gpsOffset
        val count = ifdCount(bytes, gps, big) ?: return GpsParse.Bad
        val ranges = ArrayList<Pair<Int, Int>>()
        for (i in 0 until count) {
            val entry = gps + 2 + i * 12
            val unit = TYPE_SIZES[u16(bytes, entry + 2, big)] ?: return GpsParse.Bad
            val total = u32(bytes, entry + 4, big).toLong() * unit
            if (total > 4) {
                val from = tiff + u32(bytes, entry + 8, big)
                if (from < tiff || from + total > bytes.size) return GpsParse.Bad
                ranges += from to (from + total).toInt()
            }
        }
        return GpsParse.Found(GpsIfd(count, gps, gps + 2 + count * 12 + 4, ranges))
    }

    /** The entry count of the IFD at [at] when it and its next-IFD link fit in [bytes], else null. */
    private fun ifdCount(bytes: ByteArray, at: Int, big: Boolean): Int? {
        if (at < 0 || at + 2 > bytes.size) return null
        val count = u16(bytes, at, big)
        return count.takeIf { at + 2 + it * 12 + 4 <= bytes.size }
    }

    // TIFF field type -> bytes per value.
    private val TYPE_SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 13 to 4)

    private fun u16(b: ByteArray, at: Int, big: Boolean): Int {
        val x = b[at].toInt() and 0xFF
        val y = b[at + 1].toInt() and 0xFF
        return if (big) (x shl 8) or y else (y shl 8) or x
    }

    /** A 32-bit field as an Int; a value past Int range comes back negative and fails every bounds check. */
    private fun u32(b: ByteArray, at: Int, big: Boolean): Int {
        val v = (0 until 4).map { b[at + it].toInt() and 0xFF }
        return if (big) (v[0] shl 24) or (v[1] shl 16) or (v[2] shl 8) or v[3]
        else (v[3] shl 24) or (v[2] shl 16) or (v[1] shl 8) or v[0]
    }

    private fun readUint(raf: RandomAccessFile, size: Int): Long = when (size) {
        0 -> 0L
        4 -> raf.readInt().toLong() and 0xFFFFFFFFL
        else -> raf.readLong()
    }

    private fun type(raf: RandomAccessFile): String =
        String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)

    /** The boxes in [start, end), up to and including the first [stopAt]; null when one does not fit. */
    private fun boxes(raf: RandomAccessFile, start: Long, end: Long, stopAt: String? = null): List<Box>? {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = type(raf)
            var header = 8L
            val size = when (size32) {
                0L -> end - pos
                1L -> raf.readLong().also { header += 8 }
                else -> size32
            }
            if (size < header || pos + size > end) return null
            out += Box(pos, pos + header, pos + size, type)
            if (type == stopAt) break
            pos += size
        }
        return out
    }
}
