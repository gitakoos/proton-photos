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

import android.util.Log
import eu.akoos.photos.data.db.entity.isUsableGpsFix
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

private const val TAG = "Mp4LocationAtoms"

/**
 * Reads and writes the location atoms of an MP4. [readIso6709] and [writeIso6709] carry a source's
 * ISO 6709 string (`+47.4979+019.0402+120.000/`, altitude and zero padding included) into an output
 * as it was, which neither muxer can do: both write only a latitude and longitude in their own format.
 *
 * [erase] removes the location atoms (`©xyz`, `loci`) from an MP4 in place, for when the strip remux fails.
 * Each one becomes a `free` box of the same size with its payload zeroed, so no offset in the file
 * moves and the coordinates are gone from the bytes too. Covers the user-data boxes of the movie and
 * of each track, where Android muxers and cameras put the location, and the QuickTime `meta` keys
 * (`com.apple.quicktime.location.*`), whose values are blanked the same way.
 */
object Mp4LocationAtoms {

    private const val HEADER = 8
    private val CONTAINERS = setOf("moov", "trak", "udta")
    private val LOCATION_TYPES = setOf("©xyz", "loci")
    private const val QUICKTIME_LOCATION_KEY = "com.apple.quicktime.location"
    private val TOP_LEVEL_TYPES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")
    private val FREE = byteArrayOf('f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte())

    /**
     * Erases every location atom of [file]. True when the whole box tree was walked, so the file now
     * holds none (whether or not it had one); false when the file is not a readable MP4, after which the
     * caller must treat the location as still present.
     */
    fun erase(file: File): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { raf -> walk(raf, 0L, raf.length(), depth = 0) }
    }.onFailure { Log.w(TAG, "location erase failed: ${it.message}") }.getOrDefault(false)

    /** True when [file] opens with an ISO base media box (MP4, MOV, 3GP), the containers [erase] can read. */
    fun isIsoMedia(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER) return@use false
            raf.seek(4)
            val type = ByteArray(4).also { raf.readFully(it) }
            String(type, Charsets.ISO_8859_1) in TOP_LEVEL_TYPES
        }
    }.getOrDefault(false)

    /** Walks the boxes in [start, end); false on a malformed header. */
    private fun walk(raf: RandomAccessFile, start: Long, end: Long, depth: Int): Boolean {
        var pos = start
        var sawMoov = depth > 0
        while (pos + HEADER <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { raf.readFully(it) }
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var headerSize = HEADER.toLong()
            val boxSize = when (size32) {
                0L -> end - pos
                1L -> raf.readLong().also { headerSize += 8 }
                else -> size32
            }
            if (boxSize < headerSize) return false
            if (pos + boxSize > end) {
                // A top-level box cut short after the movie box (a truncated mdat) leaves nothing more
                // to erase; anywhere else the tree cannot be trusted.
                if (depth == 0 && sawMoov && type != "moov") break
                return false
            }
            when {
                depth == 0 && type == "moov" -> sawMoov = true
                depth > 0 && type in LOCATION_TYPES -> blank(raf, pos, headerSize, boxSize)
                depth > 0 && type == "meta" -> if (!eraseMetaKeys(raf, pos + headerSize, pos + boxSize)) return false
            }
            if (type in CONTAINERS && (depth > 0 || type == "moov")) {
                if (!walk(raf, pos + headerSize, pos + boxSize, depth + 1)) return false
            }
            pos += boxSize
        }
        return sawMoov
    }

    /** Turns the box at [pos] into a `free` box of the same size and zeroes its payload. */
    private fun blank(raf: RandomAccessFile, pos: Long, headerSize: Long, boxSize: Long) {
        raf.seek(pos + 4)
        raf.write(FREE)
        raf.seek(pos + headerSize)
        zero(raf, boxSize - headerSize)
    }

    /**
     * Blanks the values of the QuickTime location keys inside a `meta` box spanning [start, end): the
     * `keys` box names them by index and the `ilst` box holds one item per index, whose value boxes
     * become zeroed `free` boxes. An `ilst` item typed `©xyz` is blanked as well. False on a malformed box.
     */
    private fun eraseMetaKeys(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        if (end - start < 4) return true
        // An MP4 `meta` is a full box (version and flags, all zero in practice) before its children;
        // a QuickTime one starts with its first child, whose size is never zero.
        raf.seek(start)
        val childrenStart = if (raf.readInt() == 0) start + 4 else start
        val children = boxes(raf, childrenStart, end) ?: return false
        val locationIndices = HashSet<Long>()
        children.firstOrNull { it.type == "keys" }?.let { keys ->
            var pos = keys.payload + 4
            if (pos + 4 > keys.end) return false
            raf.seek(pos)
            val count = raf.readInt().toLong() and 0xFFFFFFFFL
            pos += 4
            for (index in 1..count) {
                if (pos + 8 > keys.end) return false
                raf.seek(pos)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                if (size < 8 || pos + size > keys.end) return false
                raf.skipBytes(4)
                val name = ByteArray((size - 8).toInt()).also { raf.readFully(it) }
                if (String(name, Charsets.UTF_8).startsWith(QUICKTIME_LOCATION_KEY)) locationIndices += index
                pos += size
            }
        }
        val ilst = children.firstOrNull { it.type == "ilst" } ?: return true
        val items = boxes(raf, ilst.payload, ilst.end) ?: return false
        for (item in items) {
            if (item.index !in locationIndices && item.type !in LOCATION_TYPES) continue
            val values = boxes(raf, item.payload, item.end) ?: return false
            values.forEach { blank(raf, it.start, it.payload - it.start, it.end - it.start) }
        }
        return true
    }

    private class Box(val start: Long, val payload: Long, val end: Long, val type: String, val index: Long)

    /** The child boxes in [start, end), or null when one of them does not fit. */
    private fun boxes(raf: RandomAccessFile, start: Long, end: Long): List<Box>? {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + HEADER <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { raf.readFully(it) }
            var headerSize = HEADER.toLong()
            val size = when (size32) {
                0L -> end - pos
                1L -> raf.readLong().also { headerSize += 8 }
                else -> size32
            }
            if (size < headerSize || pos + size > end) return null
            val index = ((typeBytes[0].toLong() and 0xFF) shl 24) or ((typeBytes[1].toLong() and 0xFF) shl 16) or
                ((typeBytes[2].toLong() and 0xFF) shl 8) or (typeBytes[3].toLong() and 0xFF)
            out += Box(pos, pos + headerSize, pos + size, String(typeBytes, Charsets.ISO_8859_1), index)
            pos += size
        }
        return out
    }

    private fun zero(raf: RandomAccessFile, count: Long) {
        val chunk = ByteArray(minOf(count, 8192L).toInt())
        var left = count
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            raf.write(chunk, 0, n)
            left -= n
        }
    }

    /** Longest location string carried over; an ISO 6709 point with altitude and a CRS fits well within it. */
    private const val MAX_ISO6709_LENGTH = 128

    /** Largest movie box read into memory; the sample tables of hours of video stay far below it. */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    private const val XYZ = "©xyz"
    private const val LOCI = "loci"
    private const val QUICKTIME_ISO6709_KEY = "com.apple.quicktime.location.ISO6709"

    /** The packed ISO-639-2/T code for "eng", the language both muxers give their `©xyz`. */
    private const val DEFAULT_XYZ_LANGUAGE = 0x15C7

    /**
     * The ISO 6709 location string [file] carries: its movie `©xyz` atom, else its QuickTime
     * `com.apple.quicktime.location.ISO6709` key. Null when it has none, when the value is not printable
     * ASCII (a redacted read hands back zeroes) or when the file cannot be read. Never throws.
     */
    fun readIso6709(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { readIso6709(it.channel) }
    }.getOrNull()

    /** [readIso6709] through an open, seekable [channel], which stays open. Never throws. */
    fun readIso6709(channel: FileChannel): String? = runCatching {
        val moovSpan = topLevelBoxes(channel, strict = false)?.firstOrNull { it.type == "moov" }
        moovSpan?.let { span -> readMoov(channel, span)?.let { movieIso6709(it, headerSize(span)) } }
    }.onFailure { Log.w(TAG, "location read failed: ${it.message}") }.getOrNull()

    /**
     * Writes [iso6709] as [file]'s movie `©xyz` atom, in place of the one there (a muxer's two-number
     * version) or as a new one. The file is rebuilt beside itself and swapped in only once it reads back
     * with that value and with every chunk offset inside its media data, so a failure at any step leaves
     * [file] as it was. When the movie box sits ahead of the media data, a `free` box right after it takes
     * up the size change, or else every chunk offset moves with it. True when [file] carries the value
     * afterwards. Never throws.
     */
    fun writeIso6709(file: File, iso6709: String): Boolean {
        val value = iso6709.toByteArray(Charsets.US_ASCII)
        if (printable(value, 0, value.size) != iso6709) return false
        var temp: File? = null
        return runCatching {
            when (val plan = RandomAccessFile(file, "r").use { planRewrite(it.channel, value) }) {
                Plan.Unchanged -> true
                Plan.Unsupported -> false
                is Plan.Splice -> {
                    val target = File.createTempFile("loc_", ".tmp", file.absoluteFile.parentFile)
                    temp = target
                    splice(file, target, plan)
                    if (verifyRewrite(target, iso6709, plan.newLength)) {
                        replace(target, file)
                        true
                    } else {
                        Log.w(TAG, "rewritten location does not read back; output left as it was")
                        false
                    }
                }
            }
        }.onFailure { Log.w(TAG, "location write failed: ${it.message}") }
            .getOrDefault(false)
            .also { temp?.takeIf { it.exists() }?.delete() }
    }

    /** A top-level box of a file. */
    private class Span(val start: Long, val payload: Long, val end: Long, val type: String)

    /** A box inside an in-memory movie box. */
    private class Node(val start: Int, val payload: Int, val end: Int, val type: String)

    private sealed interface Plan {
        object Unchanged : Plan
        object Unsupported : Plan

        /** The new file: the old bytes before [keepBefore], then [replacement], then the old bytes from [resumeAt]. */
        class Splice(val keepBefore: Long, val replacement: ByteArray, val resumeAt: Long, val newLength: Long) : Plan
    }

    private fun headerSize(span: Span): Int = (span.payload - span.start).toInt()

    private fun planRewrite(channel: FileChannel, value: ByteArray): Plan {
        val top = topLevelBoxes(channel, strict = true) ?: return Plan.Unsupported
        // Fragmented files address their samples relative to boxes this rewrite would move.
        if (top.any { it.type == "moof" || it.type == "mfra" || it.type == "sidx" }) return Plan.Unsupported
        val moovSpan = top.singleOrNull { it.type == "moov" } ?: return Plan.Unsupported
        val moov = readMoov(channel, moovSpan) ?: return Plan.Unsupported
        val moovStart = headerSize(moovSpan)
        val moovKids = children(moov, moovStart, moov.size) ?: return Plan.Unsupported
        if (moovKids.any { it.type == "mvex" }) return Plan.Unsupported

        val udta = moovKids.firstOrNull { it.type == "udta" }
        val udtaKids = if (udta == null) emptyList() else children(moov, udta.payload, udta.end) ?: return Plan.Unsupported
        val oldXyz = udtaKids.filter { it.type == XYZ }
        val language = oldXyz.firstOrNull()?.takeIf { it.end - it.payload >= 4 }?.let { u16(moov, it.payload + 2) }
            ?: DEFAULT_XYZ_LANGUAGE
        val newXyz = box(XYZ, short(value.size) + short(language) + value)
        if (oldXyz.size == 1 && moov.copyOfRange(oldXyz[0].start, oldXyz[0].end).contentEquals(newXyz)) return Plan.Unchanged

        // The user-data box with its first `©xyz` replaced (or the new one added) and any others dropped.
        val udtaPayload = ByteArrayOutputStream()
        var placed = false
        for (kid in udtaKids) {
            if (kid.type != XYZ) {
                udtaPayload.write(moov, kid.start, kid.end - kid.start)
            } else if (!placed) {
                udtaPayload.write(newXyz)
                placed = true
            }
        }
        if (!placed) udtaPayload.write(newXyz)
        if (udta != null) {
            val tail = udtaKids.lastOrNull()?.end ?: udta.payload
            udtaPayload.write(moov, tail, udta.end - tail)
        }
        val newUdta = box("udta", udtaPayload.toByteArray())

        val moovPayload = ByteArrayOutputStream()
        for (kid in moovKids) {
            if (kid === udta) moovPayload.write(newUdta) else moovPayload.write(moov, kid.start, kid.end - kid.start)
        }
        if (udta == null) moovPayload.write(newUdta)
        val moovTail = moovKids.lastOrNull()?.end ?: moovStart
        moovPayload.write(moov, moovTail, moov.size - moovTail)
        val newMoov = box("moov", moovPayload.toByteArray())
        val delta = newMoov.size.toLong() - moov.size

        val length = channel.size()
        val next = top.getOrNull(top.indexOf(moovSpan) + 1)
        if (delta != 0L && next != null && (next.type == "free" || next.type == "skip") && headerSize(next) == HEADER) {
            val freeSize = next.end - next.start - delta
            if (freeSize in HEADER.toLong()..MAX_MOOV_BYTES) {
                // The padding a streamable muxer leaves after the movie box takes up the change: nothing moves.
                return Plan.Splice(moovSpan.start, newMoov + box("free", ByteArray((freeSize - HEADER).toInt())), next.end, length)
            }
        }
        if (!shiftChunkOffsets(newMoov, threshold = moovSpan.end, delta = delta)) return Plan.Unsupported
        return Plan.Splice(moovSpan.start, newMoov, moovSpan.end, length + delta)
    }

    /** Writes [plan] applied to [source] into [target]. */
    private fun splice(source: File, target: File, plan: Plan.Splice) {
        RandomAccessFile(source, "r").use { src ->
            RandomAccessFile(target, "rw").use { dst ->
                val input = src.channel
                val output = dst.channel
                output.truncate(0)
                copyRange(input, 0L, plan.keepBefore, output)
                val buffer = ByteBuffer.wrap(plan.replacement)
                while (buffer.hasRemaining()) output.write(buffer)
                copyRange(input, plan.resumeAt, input.size() - plan.resumeAt, output)
                output.force(true)
            }
        }
    }

    private fun copyRange(input: FileChannel, from: Long, count: Long, output: FileChannel) {
        var done = 0L
        while (done < count) {
            val n = input.transferTo(from + done, count - done, output)
            if (n <= 0L) throw IOException("copy stalled at ${from + done}")
            done += n
        }
    }

    /** Whether [file] is [expectedLength] long, tiles into boxes, carries [value] and has every chunk inside its media data. */
    private fun verifyRewrite(file: File, value: String, expectedLength: Long): Boolean =
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            if (channel.size() != expectedLength) return@use false
            val top = topLevelBoxes(channel, strict = true) ?: return@use false
            val moovSpan = top.singleOrNull { it.type == "moov" } ?: return@use false
            val moov = readMoov(channel, moovSpan) ?: return@use false
            if (movieIso6709(moov, headerSize(moovSpan)) != value) return@use false
            val media = top.filter { it.type == "mdat" }
            forEachChunkOffset(moov, headerSize(moovSpan)) { at, wide ->
                val offset = if (wide) u64(moov, at) else u32(moov, at)
                media.any { offset >= it.payload && offset < it.end }
            }
        }

    /** Moves [temp] over [file], atomically where the file system can. */
    private fun replace(temp: File, file: File) {
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Log.d(TAG, "no atomic move here: ${e.message}")
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Adds [delta] to every chunk offset of [moov] at or past [threshold]; false on a malformed table or an overflow. */
    private fun shiftChunkOffsets(moov: ByteArray, threshold: Long, delta: Long): Boolean {
        if (delta == 0L) return true
        return forEachChunkOffset(moov, HEADER) { at, wide ->
            val offset = if (wide) u64(moov, at) else u32(moov, at)
            if (offset < threshold) return@forEachChunkOffset true
            val moved = offset + delta
            when {
                moved < 0L -> false
                wide -> { putLong(moov, at, moved); true }
                moved > 0xFFFFFFFFL -> false
                else -> { putInt(moov, at, moved); true }
            }
        }
    }

    /**
     * Calls [visit] with the position of every `stco` (32-bit) and `co64` (64-bit, [visit]'s second
     * argument) entry in the movie box [moov], whose children start at [start]. False when a table does
     * not fit or [visit] answers false.
     */
    private fun forEachChunkOffset(moov: ByteArray, start: Int, visit: (Int, Boolean) -> Boolean): Boolean {
        val kids = children(moov, start, moov.size) ?: return false
        for (trak in kids.filter { it.type == "trak" }) {
            var node: Node? = trak
            for (type in listOf("mdia", "minf", "stbl")) {
                val parent = node ?: break
                node = (children(moov, parent.payload, parent.end) ?: return false).firstOrNull { it.type == type }
            }
            val stbl = node ?: continue
            for (table in children(moov, stbl.payload, stbl.end) ?: return false) {
                val width = when (table.type) {
                    "stco" -> 4
                    "co64" -> 8
                    else -> continue
                }
                if (table.end - table.payload < 8) return false
                val count = u32(moov, table.payload + 4)
                if (table.payload + 8 + count * width > table.end) return false
                for (i in 0 until count.toInt()) {
                    if (!visit(table.payload + 8 + i * width, width == 8)) return false
                }
            }
        }
        return true
    }

    /** The location string of the movie box whose children span [start, end) of [moov]: a user-data `©xyz`
     *  first, else the QuickTime ISO 6709 key, else a user-data 3GPP `loci` written out in ISO 6709. */
    internal fun movieIso6709(moov: ByteArray, start: Int, end: Int = moov.size): String? {
        val kids = children(moov, start, end) ?: return null
        for (udta in kids.filter { it.type == "udta" }) {
            val xyz = children(moov, udta.payload, udta.end)?.firstOrNull { it.type == XYZ } ?: continue
            if (xyz.end - xyz.payload < 4) continue
            val length = minOf(u16(moov, xyz.payload), xyz.end - xyz.payload - 4)
            printable(moov, xyz.payload + 4, length)?.let { return it }
        }
        for (meta in kids.filter { it.type == "meta" }) quickTimeIso6709(moov, meta)?.let { return it }
        for (udta in kids.filter { it.type == "udta" }) {
            val loci = children(moov, udta.payload, udta.end)?.firstOrNull { it.type == LOCI } ?: continue
            lociIso6709(moov, loci.payload, loci.end)?.let { return it }
        }
        return null
    }

    /**
     * The place of a 3GPP `loci` payload in [start, end) as an ISO 6709 string: after the full box header,
     * the packed language and the NUL-terminated name (UTF-8, or UTF-16 after a byte order mark) and the
     * role byte come longitude, latitude and altitude, each signed 16.16 fixed point. Null when the box is
     * cut short or the position is out of range or 0, 0.
     */
    internal fun lociIso6709(buf: ByteArray, start: Int, end: Int): String? {
        val name = start + 4 + 2
        val role = afterLociName(buf, name, end) ?: return null
        val coordinates = role + 1
        if (coordinates + 12 > end) return null
        val longitude = fixed16(buf, coordinates)
        val latitude = fixed16(buf, coordinates + 4)
        val altitude = fixed16(buf, coordinates + 8)
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !isUsableGpsFix(latitude, longitude)) return null
        return String.format(Locale.ROOT, "%+08.4f%+09.4f%+.3f/", latitude, longitude, altitude)
    }

    private fun afterLociName(buf: ByteArray, from: Int, end: Int): Int? {
        if (from + 1 < end && buf[from] == 0xFE.toByte() && buf[from + 1] == 0xFF.toByte()) {
            var at = from + 2
            while (at + 1 < end) {
                if (buf[at] == 0.toByte() && buf[at + 1] == 0.toByte()) return at + 2
                at += 2
            }
            return null
        }
        for (at in from until end) if (buf[at] == 0.toByte()) return at + 1
        return null
    }

    private fun fixed16(b: ByteArray, at: Int): Double = u32(b, at).toInt() / 65536.0

    /** The UTF-8 value of the `com.apple.quicktime.location.ISO6709` key of a QuickTime `meta` box. */
    private fun quickTimeIso6709(buf: ByteArray, meta: Node): String? {
        var start = meta.payload
        if (meta.end - start >= 4 && u32(buf, start) == 0L) start += 4
        val kids = children(buf, start, meta.end) ?: return null
        val keys = kids.firstOrNull { it.type == "keys" } ?: return null
        val ilst = kids.firstOrNull { it.type == "ilst" } ?: return null
        var pos = keys.payload + 4
        if (pos + 4 > keys.end) return null
        val count = u32(buf, pos)
        pos += 4
        var wanted = -1L
        var index = 1L
        while (index <= count) {
            if (pos + 8 > keys.end) return null
            val size = u32(buf, pos)
            if (size < 8 || pos + size > keys.end) return null
            if (String(buf, pos + 8, (size - 8).toInt(), Charsets.UTF_8) == QUICKTIME_ISO6709_KEY) {
                wanted = index
                break
            }
            pos += size.toInt()
            index++
        }
        if (wanted < 0) return null
        val item = children(buf, ilst.payload, ilst.end)?.firstOrNull { u32(buf, it.start + 4) == wanted } ?: return null
        val data = children(buf, item.payload, item.end)?.firstOrNull { it.type == "data" } ?: return null
        // A `data` value opens with its type (1 = UTF-8) and locale.
        if (data.end - data.payload < 8 || u32(buf, data.payload) != 1L) return null
        return printable(buf, data.payload + 8, data.end - data.payload - 8)
    }

    /** The [length] bytes of [buf] at [from] as text when they are printable ASCII (a trailing NUL dropped), else null. */
    private fun printable(buf: ByteArray, from: Int, length: Int): String? {
        var n = length
        while (n > 0 && buf[from + n - 1] == 0.toByte()) n--
        if (n == 0 || n > MAX_ISO6709_LENGTH) return null
        for (i in from until from + n) if (buf[i] < 0x20 || buf[i] > 0x7E) return null
        return String(buf, from, n, Charsets.US_ASCII)
    }

    /** The top-level boxes of [channel]. With [strict], null unless they tile the file exactly; otherwise the ones before the first that does not fit. */
    private fun topLevelBoxes(channel: FileChannel, strict: Boolean): List<Span>? {
        val length = channel.size()
        val out = ArrayList<Span>()
        val header = ByteArray(16)
        var pos = 0L
        while (pos + HEADER <= length) {
            readAt(channel, pos, header, 0, HEADER)
            val size32 = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = HEADER.toLong()
            val size = when (size32) {
                0L -> length - pos
                1L -> {
                    if (pos + 16 > length) return if (strict) null else out
                    readAt(channel, pos + HEADER, header, HEADER, 8)
                    headerSize = 16L
                    u64(header, HEADER)
                }
                else -> size32
            }
            if (size < headerSize || pos + size > length) return if (strict) null else out
            out += Span(pos, pos + headerSize, pos + size, type)
            pos += size
        }
        return if (strict && pos != length) null else out
    }

    /** The whole movie box [span] of [channel], or null when it is too big to hold. */
    private fun readMoov(channel: FileChannel, span: Span): ByteArray? {
        val size = span.end - span.start
        if (size > MAX_MOOV_BYTES) return null
        return ByteArray(size.toInt()).also { readAt(channel, span.start, it, 0, it.size) }
    }

    private fun readAt(channel: FileChannel, position: Long, into: ByteArray, offset: Int, count: Int) {
        val buffer = ByteBuffer.wrap(into, offset, count)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position() - offset) < 0) throw EOFException("box past the end")
        }
    }

    /** The child boxes of [buf] in [start, end), or null when one of them does not fit. */
    private fun children(buf: ByteArray, start: Int, end: Int): List<Node>? {
        val out = ArrayList<Node>()
        var pos = start
        while (pos + HEADER <= end) {
            val size32 = u32(buf, pos)
            var headerSize = HEADER
            val size = when (size32) {
                0L -> (end - pos).toLong()
                1L -> {
                    if (pos + 16 > end) return null
                    headerSize = 16
                    u64(buf, pos + HEADER)
                }
                else -> size32
            }
            if (size < headerSize || pos + size > end) return null
            out += Node(pos, pos + headerSize, (pos + size).toInt(), String(buf, pos + 4, 4, Charsets.ISO_8859_1))
            pos += size.toInt()
        }
        return out
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        putInt(out, 0, out.size.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, HEADER)
        return out
    }

    private fun short(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun putInt(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }
}
