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
 * Strips EXIF from a HEIF still (HEIC, AVIF) in place, for a file the EXIF writer cannot rewrite. The
 * Exif item is found through `meta`/`iinf`/`iloc`. Inside it the GPS IFD is emptied (its entry count and
 * next-IFD link set to zero, its values zeroed), and the stripped tags are dropped from IFD0 and the Exif
 * sub-IFD by compacting each IFD within its own bytes and zeroing what it frees and the values they
 * pointed at. No length changes, so the image items, an appended motion clip and every offset that
 * points at them stay exactly as they were. Every check runs before a byte is written, and any layout
 * this does not fully understand is refused rather than guessed at.
 */
object HeifExifStrip {

    sealed interface Result {
        /** Only bytes in [exifStart, exifEnd) changed. */
        data class Stripped(val exifStart: Long, val exifEnd: Long) : Result

        /** Nothing to strip; nothing was written. */
        data object Unchanged : Result

        /** A layout this cannot patch safely; nothing was written. */
        data class Unsupported(val reason: String) : Result
    }

    // The tags the JPEG strip (ExifHelper.stripToTempFile) removes for each setting, by TIFF tag id.
    private val CAMERA_TAGS = setOf(
        0x010F, // Make
        0x0110, // Model
        0xA433, // LensMake
        0xA434, // LensModel
        0x920A, // FocalLength
        0x829D, // FNumber
        0x9202, // ApertureValue
        0x829A, // ExposureTime
        0x8827, // PhotographicSensitivity / ISOSpeedRatings
        0x9209, // Flash
        0xA403, // WhiteBalance
        0xA402, // ExposureMode
    )
    private val TIMESTAMP_TAGS = setOf(0x0132, 0x9003, 0x9004) // DateTime, DateTimeOriginal, DateTimeDigitized
    private val SOFTWARE_TAGS = setOf(0x0131, 0x9286) // Software, UserComment
    private val AUTHORSHIP_TAGS = setOf(0x013B, 0x8298) // Artist, Copyright

    // XMP properties holding a copy of each group, which this does not rewrite.
    private val GPS_XMP = listOf("GPSLatitude", "GPSLongitude")
    private val CAMERA_XMP = listOf("tiff:Make", "tiff:Model", "LensModel", "LensMake")
    private val TIMESTAMP_XMP = listOf("DateTimeOriginal", "DateTimeDigitized", "xmp:CreateDate", "photoshop:DateCreated", "tiff:DateTime")
    private val SOFTWARE_XMP = listOf("xmp:CreatorTool", "tiff:Software")
    private val AUTHORSHIP_XMP = listOf("dc:creator", "dc:rights", "tiff:Artist", "tiff:Copyright")

    /** The non-GPS tag ids [config] strips, the same groups the JPEG strip removes. */
    fun tagsFor(config: MetadataStripConfig): Set<Int> = buildSet {
        if (config.stripCameraInfo) addAll(CAMERA_TAGS)
        if (config.stripTimestamp) addAll(TIMESTAMP_TAGS)
        if (config.stripSoftwareInfo) addAll(SOFTWARE_TAGS)
        if (config.stripAuthorship) addAll(AUTHORSHIP_TAGS)
    }

    private fun xmpKeysFor(config: MetadataStripConfig): List<String> = buildList {
        if (config.stripGps) addAll(GPS_XMP)
        if (config.stripCameraInfo) addAll(CAMERA_XMP)
        if (config.stripTimestamp) addAll(TIMESTAMP_XMP)
        if (config.stripSoftwareInfo) addAll(SOFTWARE_XMP)
        if (config.stripAuthorship) addAll(AUTHORSHIP_XMP)
    }

    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    private const val GPS_IFD_TAG = 0x8825
    private const val EXIF_IFD_TAG = 0x8769
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

    /** Strips from [file] in place what [config] asks for. */
    fun strip(file: File, config: MetadataStripConfig): Result = runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            when (val found = locate(raf, xmpKeysFor(config))) {
                is Located.Refused -> Result.Unsupported(found.reason)
                Located.NoExif -> Result.Unchanged
                is Located.Exif -> {
                    val bytes = found.bytes
                    val changed = stripTiff(bytes, config.stripGps, tagsFor(config))
                        ?: return@use Result.Unsupported("exif tiff")
                    if (!changed) return@use Result.Unchanged
                    raf.seek(found.offset)
                    raf.write(bytes)
                    Result.Stripped(found.offset, found.offset + bytes.size)
                }
            }
        }
    }.getOrElse { Result.Unsupported(it.javaClass.simpleName) }

    /** The number of GPS entries [file] carries: 0 when it has none, null when its layout is unreadable. */
    fun gpsEntryCount(file: File): Int? = readTiff(file) { bytes, tiff ->
        val gps = subIfd(bytes, tiff, GPS_IFD_TAG) ?: return@readTiff null
        if (gps < 0) 0 else ifdCount(bytes, gps, tiff.big)
    }

    /** Which of [tags] IFD0 and the Exif sub-IFD of [file] still hold; null when its layout is unreadable. */
    fun presentTags(file: File, tags: Set<Int>): Set<Int>? = readTiff(file) { bytes, tiff ->
        val ifds = tagIfds(bytes, tiff) ?: return@readTiff null
        buildSet {
            for (ifd in ifds) {
                val count = ifdCount(bytes, ifd, tiff.big) ?: return@readTiff null
                for (i in 0 until count) u16(bytes, ifd + 2 + i * 12, tiff.big).takeIf { it in tags }?.let(::add)
            }
        }
    }

    private fun <T> readTiff(file: File, read: (ByteArray, Tiff) -> T?): T? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            when (val found = locate(raf, emptyList())) {
                is Located.Refused -> null
                Located.NoExif -> read(EMPTY_EXIF, Tiff(0, true, -1))
                is Located.Exif -> tiffOf(found.bytes)?.let { read(found.bytes, it) }
            }
        }
    }.getOrNull()

    // ── The TIFF structure inside the Exif item ──────────────────────────────────────────────────────

    private val EMPTY_EXIF = ByteArray(0)

    /** The TIFF header's position and byte order, and IFD0's position (-1 for a file without Exif). */
    private class Tiff(val start: Int, val big: Boolean, val ifd0: Int)

    private fun tiffOf(bytes: ByteArray): Tiff? {
        if (bytes.size < 12) return null
        // A 4-byte offset to the TIFF header (past "Exif\0\0"), then the header itself.
        val start = 4 + u32(bytes, 0, true)
        if (start < 4 || start + 8 > bytes.size) return null
        val big = when {
            bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'M'.code.toByte() -> true
            bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte() -> false
            else -> return null
        }
        if (u16(bytes, start + 2, big) != 42) return null
        val ifd0 = start + u32(bytes, start + 4, big)
        if (ifdCount(bytes, ifd0, big) == null) return null
        return Tiff(start, big, ifd0)
    }

    /** The position of the sub-IFD IFD0 points at with [tag]: -1 when absent, null when it does not fit. */
    private fun subIfd(bytes: ByteArray, tiff: Tiff, tag: Int): Int? {
        if (tiff.ifd0 < 0) return -1
        val count = ifdCount(bytes, tiff.ifd0, tiff.big) ?: return null
        for (i in 0 until count) {
            val entry = tiff.ifd0 + 2 + i * 12
            if (u16(bytes, entry, tiff.big) == tag) {
                val at = tiff.start + u32(bytes, entry + 8, tiff.big)
                return at.takeIf { ifdCount(bytes, it, tiff.big) != null }
            }
        }
        return -1
    }

    /** IFD0 and the Exif sub-IFD, where the stripped tags live; null when the sub-IFD does not fit. */
    private fun tagIfds(bytes: ByteArray, tiff: Tiff): List<Int>? {
        if (tiff.ifd0 < 0) return emptyList()
        val exif = subIfd(bytes, tiff, EXIF_IFD_TAG) ?: return null
        return if (exif < 0) listOf(tiff.ifd0) else listOf(tiff.ifd0, exif)
    }

    /** The out-of-line value range of the entry at [entry], null when it holds its value inline. */
    private fun valueRange(bytes: ByteArray, tiff: Tiff, entry: Int): IntRange? {
        val unit = TYPE_SIZES[u16(bytes, entry + 2, tiff.big)] ?: throw BadTiff()
        val total = u32(bytes, entry + 4, tiff.big).toLong() * unit
        if (total < 0) throw BadTiff()
        if (total <= 4) return null
        val from = tiff.start + u32(bytes, entry + 8, tiff.big)
        if (from < tiff.start || from + total > bytes.size) throw BadTiff()
        return from until (from + total).toInt()
    }

    private class BadTiff : Exception()

    /**
     * Strips [bytes] (an Exif item) in memory: the GPS IFD when [gps], and [tags] from IFD0 and the Exif
     * sub-IFD. True when anything changed, false when nothing matched, null on a structure it cannot
     * read, in which case [bytes] is left as it was.
     */
    private fun stripTiff(bytes: ByteArray, gps: Boolean, tags: Set<Int>): Boolean? {
        val tiff = tiffOf(bytes) ?: return null
        val work = bytes.copyOf()
        val changed = try {
            var changed = false
            if (gps) {
                val at = subIfd(work, tiff, GPS_IFD_TAG) ?: return null
                val count = if (at >= 0) ifdCount(work, at, tiff.big) ?: return null else 0
                if (count > 0) {
                    val ranges = (0 until count).mapNotNull { valueRange(work, tiff, at + 2 + it * 12) }
                    ranges.forEach { work.fill(0, it.first, it.last + 1) }
                    work.fill(0, at, at + 2 + count * 12 + 4)
                    changed = true
                }
            }
            if (tags.isNotEmpty()) {
                val ifds = tagIfds(work, tiff) ?: return null
                for (ifd in ifds) if (removeEntries(work, tiff, ifd, tags)) changed = true
            }
            changed
        } catch (_: BadTiff) {
            return null
        }
        work.copyInto(bytes)
        return changed
    }

    /**
     * Drops the entries tagged in [tags] from the IFD at [ifd]: the kept entries move up in order, the
     * count and the next-IFD link follow them, and the freed tail and the dropped values are zeroed. The
     * IFD keeps its start, so nothing that points at it moves.
     */
    private fun removeEntries(bytes: ByteArray, tiff: Tiff, ifd: Int, tags: Set<Int>): Boolean {
        val count = ifdCount(bytes, ifd, tiff.big) ?: throw BadTiff()
        val entries = (0 until count).map { ifd + 2 + it * 12 }
        val (dropped, kept) = entries.partition { u16(bytes, it, tiff.big) in tags }
        if (dropped.isEmpty()) return false
        val droppedValues = dropped.mapNotNull { valueRange(bytes, tiff, it) }
        val keptBytes = kept.map { bytes.copyOfRange(it, it + 12) }
        val linkAt = ifd + 2 + count * 12
        val link = bytes.copyOfRange(linkAt, linkAt + 4)
        droppedValues.forEach { bytes.fill(0, it.first, it.last + 1) }
        val end = linkAt + 4
        bytes.fill(0, ifd, end)
        val countBytes = if (tiff.big) byteArrayOf((kept.size ushr 8).toByte(), kept.size.toByte())
        else byteArrayOf(kept.size.toByte(), (kept.size ushr 8).toByte())
        countBytes.copyInto(bytes, ifd)
        keptBytes.forEachIndexed { i, e -> e.copyInto(bytes, ifd + 2 + i * 12) }
        link.copyInto(bytes, ifd + 2 + kept.size * 12)
        return true
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

    // ── The HEIF boxes that lead to the Exif item ────────────────────────────────────────────────────

    private sealed interface Located {
        data class Refused(val reason: String) : Located
        data object NoExif : Located
        class Exif(val offset: Long, val bytes: ByteArray) : Located
    }

    private class Box(val start: Long, val payload: Long, val end: Long, val type: String)

    private class Extent(val offset: Long, val length: Long)

    /** The Exif item's bytes; refused when an XMP item holds any of [xmpKeys], which this does not rewrite. */
    private fun locate(raf: RandomAccessFile, xmpKeys: List<String>): Located {
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

        if (xmpKeys.isNotEmpty()) {
            for (id in xmpIds) {
                val extent = extents[id] ?: return Located.Refused("xmp location")
                if (extent.length > MAX_XMP_BYTES || extent.offset + extent.length > length) return Located.Refused("xmp size")
                raf.seek(extent.offset)
                val xmp = String(ByteArray(extent.length.toInt()).also { raf.readFully(it) }, Charsets.ISO_8859_1)
                if (xmpKeys.any { xmp.contains(it) }) return Located.Refused("xmp copy")
            }
        }

        if (exifIds.isEmpty()) return Located.NoExif
        if (exifIds.size > 1) return Located.Refused("several exif items")
        val extent = extents[exifIds[0]] ?: return Located.Refused("exif location")
        if (extent.length < 12 || extent.length > MAX_EXIF_BYTES || extent.offset + extent.length > length) {
            return Located.Refused("exif size")
        }
        raf.seek(extent.offset)
        return Located.Exif(extent.offset, ByteArray(extent.length.toInt()).also { raf.readFully(it) })
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

    /** Each item's single file extent; null on a layout this does not know. Items in `idat` or split are left out. */
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
