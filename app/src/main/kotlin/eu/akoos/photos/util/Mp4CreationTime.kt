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

import android.system.Os
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Rewrites the creation + modification timestamps in an MP4's mvhd/tkhd/mdhd boxes so the file
 * carries a chosen capture date instead of the transcode/mux time. Only fixed-width timestamp
 * fields are overwritten (never box sizes), so the container structure stays intact; a malformed
 * box just stops the walk. Used after a compress transcode or an edit mux so MediaStore, which
 * derives DATE_TAKEN from the mvhd on scan, and Drive both keep the original capture date.
 *
 * The same box walk also reads the mvhd back ([read]), which is what recovers the capture date of a
 * video whose stored date is missing or wrong: the file itself is the only place that answer lives.
 */
object Mp4CreationTime {
    /** Seconds between the MP4 epoch (1904-01-01) and the Unix epoch (1970-01-01); MP4 container
     *  creation/modification times are counted from 1904. */
    private const val MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800L

    /**
     * The same stamp, applied only where it would actually change [file]. Returns true when the file
     * carries [captureEpochMs] afterwards, whether or not a byte was written.
     *
     * For a caller whose job is to hand back the file it was given: a video moved into the vault is a
     * raw byte copy, and rewriting its timestamps on the way out would return a DIFFERENT file from the
     * one that went in, for no gain at all. The stamp itself still matters — it is what gives a restored
     * video its capture time on a scanner that reads the container rather than the row — so it runs for
     * every file that does not already carry that time.
     *
     * The mvhd decides, because it is the box a scan derives the date from and the one [read] answers
     * with: a file already recording that instant there needs nothing, and touching it would only
     * change the bytes.
     */
    fun stampIfChanged(file: File, captureEpochMs: Long): Boolean {
        val recorded = read(file)
        if (recorded != null && !stampWouldChange(recorded, captureEpochMs)) return true
        return stamp(file, captureEpochMs)
    }

    /** Whether stamping [captureEpochMs] onto a file already recording [recordedMs] would change it.
     *  Compared at the whole second the field stores, so a sub-second difference is no difference: the
     *  stamp could not record it and re-writing would land the very value already there. */
    internal fun stampWouldChange(recordedMs: Long, captureEpochMs: Long): Boolean =
        toMp4Time(recordedMs) != toMp4Time(captureEpochMs)

    /** Best-effort: stamp [file]'s mvhd/tkhd/mdhd creation + modification times with [captureEpochMs].
     *  Never throws; returns true when the box walk completed without error. */
    fun stamp(file: File, captureEpochMs: Long): Boolean =
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                patchTimestampBoxes(FileAccess(raf), 0L, raf.length(), toMp4Time(captureEpochMs))
            }
        }.isSuccess

    /** The same stamp against an already-open read-write [fd], which is how a MediaStore item is
     *  reached: under scoped storage its path is not writable, only the descriptor the resolver hands
     *  out. The descriptor stays the caller's to close. Never throws. */
    fun stamp(fd: FileDescriptor, captureEpochMs: Long): Boolean =
        runCatching {
            val access = DescriptorAccess(fd)
            patchTimestampBoxes(access, 0L, access.length(), toMp4Time(captureEpochMs))
        }.isSuccess

    /** The capture instant [file] records in its mvhd, in epoch millis, or null when it records
     *  none. Never throws. */
    fun read(file: File): Long? =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                movieCreationTime(FileAccess(raf), 0L, raf.length())
            }
        }.getOrNull()?.let { fromMp4Time(it) }

    /** The same read against an already-open read [fd], which is how a MediaStore item is reached:
     *  under scoped storage its bytes sit behind the descriptor the resolver hands out rather than
     *  behind a path. The descriptor stays the caller's to close. Never throws. */
    fun read(fd: FileDescriptor): Long? =
        runCatching {
            val access = DescriptorAccess(fd)
            movieCreationTime(access, 0L, access.length())
        }.getOrNull()?.let { fromMp4Time(it) }

    internal fun toMp4Time(captureEpochMs: Long): Long =
        captureEpochMs / 1000L + MP4_EPOCH_OFFSET_SECONDS

    /** [mp4Seconds] back as epoch millis, or null when the field carries no usable instant. Many
     *  muxers leave the creation time 0, and any value landing at or before the Unix epoch is that
     *  same nothing rather than a capture from 1904, so both read as absent instead of as a date a
     *  century out that something downstream would try to correct towards. */
    internal fun fromMp4Time(mp4Seconds: Long): Long? {
        if (mp4Seconds <= 0L) return null
        val epochSeconds = mp4Seconds - MP4_EPOCH_OFFSET_SECONDS
        return if (epochSeconds <= 0L) null else epochSeconds * 1000L
    }

    /** Walks the MP4 box tree in [start, end) for the FIRST mvhd and answers its creation time in MP4
     *  seconds, recursing only into moov, the one container that holds one. Stops at the first
     *  malformed box rather than guessing at offsets, the same as the write walk. */
    private fun movieCreationTime(access: BoxAccess, start: Long, end: Long): Long? {
        var pos = start
        while (pos + 8 <= end) {
            access.seek(pos)
            val size32 = access.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            access.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var boxSize = size32
            var headerSize = 8L
            when {
                size32 == 1L -> {
                    boxSize = access.readLong()
                    headerSize = 16L
                }
                size32 == 0L -> boxSize = end - pos
            }
            if (boxSize < headerSize || pos + boxSize > end) return null
            val contentStart = pos + headerSize
            when (type) {
                "moov" -> movieCreationTime(access, contentStart, pos + boxSize)?.let { return it }
                "mvhd" -> return readTimestampField(access, contentStart)
            }
            pos += boxSize
        }
        return null
    }

    /** The creation_time right after the version/flags word of a full box, in MP4 seconds. Version 1
     *  stores a 64-bit time and version 0 a 32-bit one, the same pair the write side handles; the
     *  32-bit form is read unsigned, because that is how it was written and the 1904 epoch pushes
     *  every date past 2004 beyond the signed range. */
    private fun readTimestampField(access: BoxAccess, contentStart: Long): Long {
        access.seek(contentStart)
        val version = access.readByte().toInt() and 0xFF
        access.seek(contentStart + 4)
        return if (version == 1) access.readLong() else access.readInt().toLong() and 0xFFFFFFFFL
    }

    /** Walks the MP4 box tree in [start, end) and overwrites the timestamp fields of every
     *  mvhd/tkhd/mdhd with [mp4Time], recursing only into the container boxes that hold them. Stops at
     *  the first malformed box rather than guessing at offsets. */
    private fun patchTimestampBoxes(access: BoxAccess, start: Long, end: Long, mp4Time: Long) {
        var pos = start
        while (pos + 8 <= end) {
            access.seek(pos)
            val size32 = access.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            access.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var boxSize = size32
            var headerSize = 8L
            when {
                size32 == 1L -> {
                    boxSize = access.readLong()
                    headerSize = 16L
                }
                size32 == 0L -> boxSize = end - pos
            }
            if (boxSize < headerSize || pos + boxSize > end) return
            val contentStart = pos + headerSize
            when (type) {
                "moov", "trak", "mdia" -> patchTimestampBoxes(access, contentStart, pos + boxSize, mp4Time)
                "mvhd", "tkhd", "mdhd" -> writeTimestampFields(access, contentStart, mp4Time)
            }
            pos += boxSize
        }
    }

    /** Overwrites creation_time + modification_time right after the version/flags word of a full box.
     *  Version 1 stores 64-bit times, version 0 stores 32-bit. */
    private fun writeTimestampFields(access: BoxAccess, contentStart: Long, mp4Time: Long) {
        access.seek(contentStart)
        val version = access.readByte().toInt() and 0xFF
        if (version == 1) {
            access.seek(contentStart + 4)
            access.writeLong(mp4Time)
            access.seek(contentStart + 12)
            access.writeLong(mp4Time)
        } else {
            val seconds = (mp4Time and 0xFFFFFFFFL).toInt()
            access.seek(contentStart + 4)
            access.writeInt(seconds)
            access.seek(contentStart + 8)
            access.writeInt(seconds)
        }
    }

    /** The random-access primitives the box walk needs, so one walk serves both a plain file and a
     *  descriptor. Every value is big-endian, the order MP4 stores its fields in. */
    private interface BoxAccess {
        fun length(): Long
        fun seek(pos: Long)
        fun readByte(): Byte
        fun readInt(): Int
        fun readLong(): Long
        fun readFully(target: ByteArray)
        fun writeInt(value: Int)
        fun writeLong(value: Long)
    }

    private class FileAccess(private val raf: RandomAccessFile) : BoxAccess {
        override fun length(): Long = raf.length()
        override fun seek(pos: Long) = raf.seek(pos)
        override fun readByte(): Byte = raf.readByte()
        override fun readInt(): Int = raf.readInt()
        override fun readLong(): Long = raf.readLong()
        override fun readFully(target: ByteArray) = raf.readFully(target)
        override fun writeInt(value: Int) = raf.writeInt(value)
        override fun writeLong(value: Long) = raf.writeLong(value)
    }

    /** Positional I/O over a descriptor the caller owns and still has to use after the walk. Every
     *  read and write carries the offset it acts at, so nothing is wrapped around the descriptor: no
     *  stream and no channel, which is what keeps the promise that the caller's descriptor survives.
     *  Closing such a wrapper would close the descriptor underneath it, and holding one open would
     *  leak it, while a syscall taking its own offset leaves nothing to close and leaves the
     *  descriptor's own file position where the caller left it. A read-only descriptor stays
     *  read-only, because only the write walk ever reaches a write. */
    private class DescriptorAccess(private val fd: FileDescriptor) : BoxAccess {
        private var pos = 0L

        override fun length(): Long = Os.fstat(fd).st_size

        override fun seek(pos: Long) {
            this.pos = pos
        }

        override fun readByte(): Byte = read(1)[0]

        override fun readInt(): Int = ByteBuffer.wrap(read(4)).int

        override fun readLong(): Long = ByteBuffer.wrap(read(8)).long

        override fun readFully(target: ByteArray) = fill(target)

        override fun writeInt(value: Int) = write(ByteBuffer.allocate(4).putInt(value).array())

        override fun writeLong(value: Long) = write(ByteBuffer.allocate(8).putLong(value).array())

        private fun read(count: Int): ByteArray = ByteArray(count).also { fill(it) }

        /** [target] filled from the current offset. A positional read may answer fewer bytes than it
         *  was asked for, so it is repeated until the request is covered rather than trusted once; a
         *  zero-byte answer is the end of the file. */
        private fun fill(target: ByteArray) {
            var filled = 0
            while (filled < target.size) {
                val count = Os.pread(fd, target, filled, target.size - filled, pos + filled)
                if (count <= 0) throw EOFException("end of file at ${pos + filled}")
                filled += count
            }
            pos += target.size
        }

        /** [bytes] written at the current offset, repeated for the same reason a read is. */
        private fun write(bytes: ByteArray) {
            var written = 0
            while (written < bytes.size) {
                val count = Os.pwrite(fd, bytes, written, bytes.size - written, pos + written)
                if (count <= 0) throw IOException("short write at ${pos + written}")
                written += count
            }
            pos += bytes.size
        }
    }
}
