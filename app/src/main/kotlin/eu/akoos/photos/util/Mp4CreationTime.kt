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
 * Rewrites the creation + modification timestamps in an MP4's mvhd/tkhd/mdhd boxes so the file
 * carries a chosen capture date instead of the transcode/mux time. Only fixed-width timestamp
 * fields are overwritten (never box sizes), so the container structure stays intact; a malformed
 * box just stops the walk. Used after a compress transcode or an edit mux so MediaStore, which
 * derives DATE_TAKEN from the mvhd on scan, and Drive both keep the original capture date.
 */
object Mp4CreationTime {
    /** Seconds between the MP4 epoch (1904-01-01) and the Unix epoch (1970-01-01); MP4 container
     *  creation/modification times are counted from 1904. */
    private const val MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800L

    /** Best-effort: stamp [file]'s mvhd/tkhd/mdhd creation + modification times with [captureEpochMs].
     *  Never throws; returns true when the box walk completed without error. */
    fun stamp(file: File, captureEpochMs: Long): Boolean {
        val mp4Time = captureEpochMs / 1000L + MP4_EPOCH_OFFSET_SECONDS
        return runCatching {
            RandomAccessFile(file, "rw").use { raf -> patchTimestampBoxes(raf, 0L, raf.length(), mp4Time) }
        }.isSuccess
    }

    /** Walks the MP4 box tree in [start, end) and overwrites the timestamp fields of every
     *  mvhd/tkhd/mdhd with [mp4Time], recursing only into the container boxes that hold them. Stops at
     *  the first malformed box rather than guessing at offsets. */
    private fun patchTimestampBoxes(raf: RandomAccessFile, start: Long, end: Long, mp4Time: Long) {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var boxSize = size32
            var headerSize = 8L
            when {
                size32 == 1L -> {
                    boxSize = raf.readLong()
                    headerSize = 16L
                }
                size32 == 0L -> boxSize = end - pos
            }
            if (boxSize < headerSize || pos + boxSize > end) return
            val contentStart = pos + headerSize
            when (type) {
                "moov", "trak", "mdia" -> patchTimestampBoxes(raf, contentStart, pos + boxSize, mp4Time)
                "mvhd", "tkhd", "mdhd" -> writeTimestampFields(raf, contentStart, mp4Time)
            }
            pos += boxSize
        }
    }

    /** Overwrites creation_time + modification_time right after the version/flags word of a full box.
     *  Version 1 stores 64-bit times, version 0 stores 32-bit. */
    private fun writeTimestampFields(raf: RandomAccessFile, contentStart: Long, mp4Time: Long) {
        raf.seek(contentStart)
        val version = raf.readByte().toInt() and 0xFF
        if (version == 1) {
            raf.seek(contentStart + 4)
            raf.writeLong(mp4Time)
            raf.seek(contentStart + 12)
            raf.writeLong(mp4Time)
        } else {
            val seconds = (mp4Time and 0xFFFFFFFFL).toInt()
            raf.seek(contentStart + 4)
            raf.writeInt(seconds)
            raf.seek(contentStart + 8)
            raf.writeInt(seconds)
        }
    }
}
