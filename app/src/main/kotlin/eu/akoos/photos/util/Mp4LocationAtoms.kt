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
 * of each track, which is where every muxer the app writes with puts its location.
 */
object Mp4LocationAtoms {

    private const val HEADER = 8
    private val CONTAINERS = setOf("moov", "trak", "udta")
    private val LOCATION_TYPES = setOf("©xyz", "loci")
    private val FREE = byteArrayOf('f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte())

    /**
     * Erases every location atom of [file]. True when the whole box tree was walked, so the file now
     * holds none (whether or not it had one); false when the file is not a readable MP4, after which the
     * caller must treat the location as still present.
     */
    fun erase(file: File): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { raf -> walk(raf, 0L, raf.length(), depth = 0) }
    }.onFailure { Log.w(TAG, "location erase failed: ${it.message}") }.getOrDefault(false)

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
            if (boxSize < headerSize || pos + boxSize > end) return false
            when {
                depth == 0 && type == "moov" -> sawMoov = true
                depth > 0 && type in LOCATION_TYPES -> {
                    raf.seek(pos + 4)
                    raf.write(FREE)
                    raf.seek(pos + headerSize)
                    zero(raf, boxSize - headerSize)
                }
            }
            if (type in CONTAINERS && (depth > 0 || type == "moov")) {
                if (!walk(raf, pos + headerSize, pos + boxSize, depth + 1)) return false
            }
            pos += boxSize
        }
        return sawMoov
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
