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

package eu.akoos.photos.data.importer

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/** A file that can be read at any position, so a zip can be read through its central directory. */
interface ZipRandomSource : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [position]; -1 at the end of the file. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** A [ZipRandomSource] over a [FileChannel]; [owner] is closed with it. */
class ChannelZipSource(private val channel: FileChannel, private val owner: Closeable = channel) : ZipRandomSource {
    override val size: Long = channel.size()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), position)

    override fun close() = owner.close()
}

/** One entry as the central directory records it. [dataStart] is resolved from the local header on open. */
class ZipDirectoryEntry internal constructor(
    val name: String,
    val modifiedMs: Long?,
    internal val flags: Int,
    internal val method: Int,
    internal val crc: Long,
    internal val compressedSize: Long,
    internal val size: Long,
    internal val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")

    /** About where this entry's bytes end in the file (short by its header), for progress. */
    val endOffset: Long get() = localHeaderOffset + compressedSize
}

/**
 * Reads a zip through its central directory, the way an unpacker on a computer does, instead of walking the
 * local headers front to back. Sizes come from the directory, so an entry written with a data descriptor
 * (stored or deflated, 32 or 64-bit) or with zip64 fields reads like any other, and an entry whose own bytes
 * are unusable fails alone: every other entry is still found by its offset. Names that are not valid UTF-8
 * are read as code page 437, as the zip format defines, so an odd name never stops the read.
 */
class ZipCentralDirectory private constructor(
    private val source: ZipRandomSource,
    val entries: List<ZipDirectoryEntry>,
) {

    val fileSize: Long get() = source.size

    /**
     * The entry's uncompressed bytes. A damaged local header, an unsupported compression method, encryption,
     * a stream that does not inflate or does not match its recorded size and CRC raise a [ZipException]; a
     * failure of [source] itself raises a [SourceReadException].
     */
    fun open(entry: ZipDirectoryEntry): InputStream {
        if (entry.flags and FLAG_ENCRYPTED != 0) throw ZipException("encrypted entry")
        if (entry.compressedSize < 0 || entry.size < 0) throw ZipException("invalid entry size")
        if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED) {
            throw ZipException("unsupported compression method")
        }
        val header = ByteArray(LOCAL_HEADER_SIZE)
        readFully(source, entry.localHeaderOffset, header)
        if (u32(header, 0) != LOCAL_SIGNATURE) throw ZipException("invalid local header")
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE + u16(header, 26) + u16(header, 28)
        if (dataStart + entry.compressedSize > source.size) throw ZipException("entry beyond the end of the file")
        val raw = RangeStream(source, dataStart, entry.compressedSize)
        val data = if (entry.method == METHOD_STORED) raw else EntryInflaterStream(raw)
        return CheckedEntryStream(data, entry.size, entry.crc)
    }

    companion object {
        private const val LOCAL_HEADER_SIZE = 30
        private const val FLAG_ENCRYPTED = 0x1
        private const val FLAG_UTF8 = 0x800
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val END_SIGNATURE = 0x06054b50L
        private const val END_SIZE = 22
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_END_SIGNATURE = 0x06064b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val CENTRAL_HEADER_SIZE = 46
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val MAX_COMMENT = 0xFFFF
        private const val MAX_DIRECTORY_BYTES = 256L * 1024 * 1024
        private const val EXTRA_ZIP64 = 0x0001
        private const val EXTRA_TIMESTAMP = 0x5455
        private const val BUFFER = 64 * 1024
        private val CP437: Charset = runCatching { Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)

        /**
         * The directory of the zip in [source], or null when the file has no readable one (cut short, split over
         * several parts, or not a zip): the caller then walks the local headers instead. A failure of [source]
         * itself raises a [SourceReadException].
         */
        fun read(source: ZipRandomSource): ZipCentralDirectory? {
            val size = source.size
            if (size < END_SIZE) return null
            val tailLength = minOf(size, (END_SIZE + MAX_COMMENT).toLong()).toInt()
            val tailStart = size - tailLength
            val tail = ByteArray(tailLength)
            readFully(source, tailStart, tail)
            val end = (tailLength - END_SIZE downTo 0).firstOrNull { u32(tail, it) == END_SIGNATURE } ?: return null
            val endAt = tailStart + end
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0) return null
            var directorySize = u32(tail, end + 12)
            var directoryOffset = u32(tail, end + 16)
            var directoryEnd = endAt
            val zip64 = readZip64End(source, endAt)
            if (zip64 != null) {
                directorySize = zip64.directorySize
                directoryOffset = zip64.directoryOffset
                directoryEnd = zip64.recordAt
            }
            // Bytes put in front of the zip (a self-extractor) shift every recorded offset by the same amount.
            val shift = directoryEnd - (directoryOffset + directorySize)
            if (shift < 0 || directoryOffset < 0 || directorySize < 0 || directorySize > MAX_DIRECTORY_BYTES) return null
            val directory = ByteArray(directorySize.toInt())
            readFully(source, directoryOffset + shift, directory)
            val entries = parseEntries(directory, shift, size) ?: return null
            return ZipCentralDirectory(source, entries.sortedBy { it.localHeaderOffset })
        }

        private class Zip64End(val recordAt: Long, val directorySize: Long, val directoryOffset: Long)

        private fun readZip64End(source: ZipRandomSource, endAt: Long): Zip64End? {
            if (endAt < ZIP64_LOCATOR_SIZE) return null
            val locator = ByteArray(ZIP64_LOCATOR_SIZE)
            readFully(source, endAt - ZIP64_LOCATOR_SIZE, locator)
            if (u32(locator, 0) != ZIP64_LOCATOR_SIGNATURE) return null
            val recorded = u64(locator, 8)
            val record = ByteArray(56)
            // A self-extractor's prefix shifts the recorded position too; the record sits right before the locator.
            val candidates = listOf(recorded, endAt - ZIP64_LOCATOR_SIZE - record.size)
            for (at in candidates.distinct()) {
                if (at < 0 || at + record.size > source.size) continue
                readFully(source, at, record)
                if (u32(record, 0) == ZIP64_END_SIGNATURE) return Zip64End(at, u64(record, 40), u64(record, 48))
            }
            return null
        }

        private fun parseEntries(directory: ByteArray, shift: Long, fileSize: Long): List<ZipDirectoryEntry>? {
            val out = ArrayList<ZipDirectoryEntry>()
            var at = 0
            while (at + CENTRAL_HEADER_SIZE <= directory.size) {
                if (u32(directory, at) != CENTRAL_SIGNATURE) return null
                val flags = u16(directory, at + 8)
                val method = u16(directory, at + 10)
                val dosTime = u16(directory, at + 12)
                val dosDate = u16(directory, at + 14)
                val crc = u32(directory, at + 16)
                var compressed = u32(directory, at + 20)
                var size = u32(directory, at + 24)
                val nameLength = u16(directory, at + 28)
                val extraLength = u16(directory, at + 30)
                val commentLength = u16(directory, at + 32)
                var offset = u32(directory, at + 42)
                val nameAt = at + CENTRAL_HEADER_SIZE
                val extraAt = nameAt + nameLength
                val next = extraAt + extraLength + commentLength
                if (next > directory.size) return null
                var mtime: Long? = null
                var field = extraAt
                while (field + 4 <= extraAt + extraLength) {
                    val id = u16(directory, field)
                    val length = u16(directory, field + 2)
                    val data = field + 4
                    if (data + length > extraAt + extraLength) break
                    if (id == EXTRA_ZIP64) {
                        var p = data
                        if (size == 0xFFFFFFFFL && p + 8 <= data + length) size = u64(directory, p).also { p += 8 }
                        if (compressed == 0xFFFFFFFFL && p + 8 <= data + length) compressed = u64(directory, p).also { p += 8 }
                        if (offset == 0xFFFFFFFFL && p + 8 <= data + length) offset = u64(directory, p)
                    } else if (id == EXTRA_TIMESTAMP && length >= 5 && directory[data].toInt() and 1 != 0) {
                        mtime = u32(directory, data + 1) * 1000
                    }
                    field = data + length
                }
                val localOffset = offset + shift
                if (localOffset in 0 until fileSize) {
                    out += ZipDirectoryEntry(
                        name = decodeName(directory, nameAt, nameLength, flags and FLAG_UTF8 != 0),
                        modifiedMs = mtime ?: dosToMillis(dosDate, dosTime),
                        flags = flags,
                        method = method,
                        crc = crc,
                        compressedSize = compressed,
                        size = size,
                        localHeaderOffset = localOffset,
                    )
                }
                at = next
            }
            return out
        }

        /** UTF-8 when the bytes are valid UTF-8, else code page 437, whatever the entry's UTF-8 flag claims. */
        internal fun decodeName(bytes: ByteArray, offset: Int, length: Int, flaggedUtf8: Boolean): String {
            val strict = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                strict.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
            } catch (e: CharacterCodingException) {
                if (flaggedUtf8) String(bytes, offset, length, Charsets.UTF_8) else String(bytes, offset, length, CP437)
            }
        }

        private fun dosToMillis(date: Int, time: Int): Long? = try {
            LocalDateTime.of(
                ((date shr 9) and 0x7F) + 1980,
                (date shr 5) and 0x0F,
                date and 0x1F,
                (time shr 11) and 0x1F,
                (time shr 5) and 0x3F,
                ((time and 0x1F) * 2).coerceAtMost(59),
            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            null
        }

        private fun readFully(source: ZipRandomSource, position: Long, into: ByteArray) {
            var done = 0
            while (done < into.size) {
                val n = sourceRead(source, position + done, into, done, into.size - done)
                if (n < 0) throw ZipException("unexpected end of the file")
                done += n
            }
        }

        internal fun sourceRead(source: ZipRandomSource, position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            try {
                source.readAt(position, buffer, offset, length)
            } catch (e: SourceReadException) {
                throw e
            } catch (e: IOException) {
                throw SourceReadException(e)
            }

        private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

        private fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)
    }

    /** [length] bytes of [source] from [start]. */
    private class RangeStream(private val source: ZipRandomSource, start: Long, length: Long) : InputStream() {
        private var position = start
        private val end = start + length

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= end) return -1
            val n = sourceRead(source, position, b, off, minOf(len.toLong(), end - position).toInt())
            if (n < 0) throw ZipException("unexpected end of the file")
            position += n
            return n
        }
    }

    /** Inflates a raw deflate stream and frees the native inflater on close. */
    private class EntryInflaterStream(raw: InputStream) : InflaterInputStream(raw, Inflater(true), BUFFER) {
        private var padded = false

        // A raw deflate stream may need one byte past its end before the inflater reports it finished.
        override fun fill() {
            val n = `in`.read(buf, 0, buf.size)
            if (n < 0) {
                if (padded) throw EOFException("Unexpected end of ZLIB input stream")
                padded = true
                buf[0] = 0
                len = 1
            } else {
                len = n
            }
            inf.setInput(buf, 0, len)
        }

        override fun close() {
            try {
                super.close()
            } finally {
                inf.end()
            }
        }
    }

    /** Raises a [ZipException] at the end of the entry when its size or CRC differs from the directory's. */
    private class CheckedEntryStream(
        private val delegate: InputStream,
        private val expectedSize: Long,
        private val expectedCrc: Long,
    ) : InputStream() {
        private val crc = CRC32()
        private var count = 0L
        private var checked = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = delegate.read(b, off, len)
            if (n < 0) {
                verify()
                return -1
            }
            crc.update(b, off, n)
            count += n
            if (count > expectedSize) throw ZipException("invalid entry size")
            return n
        }

        private fun verify() {
            if (checked) return
            checked = true
            if (count != expectedSize) throw ZipException("invalid entry size")
            if (crc.value != expectedCrc) throw ZipException("invalid entry CRC")
        }

        override fun close() = delegate.close()
    }
}
