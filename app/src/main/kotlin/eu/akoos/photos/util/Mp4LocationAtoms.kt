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
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "Mp4LocationAtoms"

/**
 * Removes the location atoms (`©xyz`, `loci`) from an MP4 in place, for when the strip remux fails.
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
}
