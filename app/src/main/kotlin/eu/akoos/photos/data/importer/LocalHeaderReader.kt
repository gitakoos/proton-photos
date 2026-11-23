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

import eu.akoos.photos.data.importer.ZipCentralDirectory.Companion.u16
import eu.akoos.photos.data.importer.ZipCentralDirectory.Companion.u32
import eu.akoos.photos.data.importer.ZipCentralDirectory.Companion.u64
import java.io.Closeable
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipException

/** One entry met walking the local headers: its bytes, read once, and the way past it. */
internal abstract class LocalEntry(val name: String, val modifiedMs: Long?) : InputStream() {

    /** Moves the source past this entry without checking what the caller left unread; an entry whose end cannot be found raises. */
    abstract fun skipRest()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }
}

/**
 * Reads a zip's entries front to back from their local headers, for a file whose central directory cannot be
 * read. An entry with known sizes ends where its compressed size says. An entry whose sizes follow it in a
 * data descriptor ends where its deflated data does (the descriptor whose sizes match the inflated data, in
 * any of its four layouts), or, when it is stored, encrypted or in an unknown method, at the next header
 * signature whose preceding descriptor records exactly the distance back to the data start. An encrypted
 * entry or one in an unknown method is stepped past without being read. The entries end at the central
 * directory; anything else where a header should be is a fault. Bytes read past an entry go back into
 * [source] (which needs [SOURCE_PUSHBACK] of room), so at most one read chunk is held at a time.
 */
internal class LocalHeaderReader(private val source: PushbackInputStream) : Closeable {

    private val inflater = Inflater(true)
    private val scratch = ByteArray(INPUT)

    /** The next entry, or null at the central directory. */
    fun next(): LocalEntry? {
        val fixed = ByteArray(LOCAL_HEADER_SIZE)
        val got = readUpTo(fixed, fixed.size)
        if (got < fixed.size || u32(fixed, 0) != LOCAL_SIGNATURE) {
            if (got > 0) source.unread(fixed, 0, got)
            if (got >= 4 && u32(fixed, 0) in DIRECTORY_SIGNATURES) return null
            throw ZipException(if (got == 0) "entries end without the central directory" else "no local header where an entry starts")
        }
        val flags = u16(fixed, 6)
        val method = u16(fixed, 8)
        val nameLength = u16(fixed, 26)
        val extraLength = u16(fixed, 28)
        val rest = ByteArray(nameLength + extraLength)
        if (readUpTo(rest, rest.size) < rest.size) throw ZipException("unexpected end of the file")
        var compressed = u32(fixed, 18)
        var size = u32(fixed, 22)
        var modifiedMs: Long? = null
        forEachExtra(rest, nameLength, extraLength) { id, at, length ->
            if (id == EXTRA_ZIP64) {
                var p = at
                if (size == 0xFFFFFFFFL && p + 8 <= at + length) size = u64(rest, p).also { p += 8 }
                if (compressed == 0xFFFFFFFFL && p + 8 <= at + length) compressed = u64(rest, p)
            } else if (id == EXTRA_TIMESTAMP && length >= 5 && rest[at].toInt() and 1 != 0) {
                modifiedMs = u32(rest, at + 1) * 1000
            }
        }
        val name = ZipCentralDirectory.decodeName(rest, 0, nameLength, flags and FLAG_UTF8 != 0)
        val time = modifiedMs ?: ZipCentralDirectory.dosToMillis(u16(fixed, 12), u16(fixed, 10))
        val readable = flags and FLAG_ENCRYPTED == 0 && (method == METHOD_STORED || method == METHOD_DEFLATED)
        val deflated = method == METHOD_DEFLATED
        return when {
            flags and FLAG_DESCRIPTOR == 0 -> RangeEntry(name, time, compressed, u32(fixed, 14), readable, deflated)
            readable && deflated -> InflatedTrailingEntry(name, time)
            else -> ScannedEntry(name, time, readable)
        }
    }

    override fun close() = inflater.end()

    /** An entry whose compressed size is in its header. */
    private inner class RangeEntry(
        name: String,
        modifiedMs: Long?,
        private var remaining: Long,
        private val recordedCrc: Long,
        private val readable: Boolean,
        private val deflated: Boolean,
    ) : LocalEntry(name, modifiedMs) {
        private val crc = CRC32()
        private var checked = false
        private var padded = false

        init {
            if (deflated) inflater.reset()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!readable) throw ZipException("unsupported zip entry")
            val n = if (deflated) inflate(b, off, len, ::feed) else readStored(b, off, len)
            if (n < 0) {
                verify(crc, recordedCrc, checked)
                checked = true
                return -1
            }
            crc.update(b, off, n)
            return n
        }

        private fun readStored(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw ZipException("zip entry cut off")
            remaining -= n
            return n
        }

        // A raw deflate stream may need one byte past its end before the inflater reports it finished.
        private fun feed() {
            if (remaining == 0L) {
                if (padded) throw ZipException("zip entry cut off")
                padded = true
                scratch[0] = 0
                inflater.setInput(scratch, 0, 1)
                return
            }
            val n = source.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) throw ZipException("zip entry cut off")
            remaining -= n
            inflater.setInput(scratch, 0, n)
        }

        override fun skipRest() {
            checked = true
            skipFully(remaining)
            remaining = 0
        }
    }

    /** A deflated entry whose sizes and CRC follow its data: it ends where the inflater does. */
    private inner class InflatedTrailingEntry(name: String, modifiedMs: Long?) : LocalEntry(name, modifiedMs) {
        private val crc = CRC32()
        private var checked = false
        private var fed = 0
        private var recordedCrc: Long? = null
        private var lost: String? = null

        init {
            inflater.reset()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = inflateOrLose(b, off, len)
            if (n < 0) {
                verify(crc, findEnd(), checked)
                checked = true
                return -1
            }
            crc.update(b, off, n)
            return n
        }

        override fun skipRest() {
            checked = true
            val sink = ByteArray(INPUT)
            while (inflateOrLose(sink, 0, sink.size) >= 0) Unit
            findEnd()
        }

        private fun inflateOrLose(b: ByteArray, off: Int, len: Int): Int {
            lost?.let { throw ZipException(it) }
            if (recordedCrc != null) return -1
            return try {
                inflate(b, off, len, ::feed)
            } catch (e: ZipException) {
                lost = e.message
                throw e
            }
        }

        private fun feed() {
            fed = source.read(scratch, 0, scratch.size)
            if (fed < 0) throw ZipException("zip entry cut off")
            inflater.setInput(scratch, 0, fed)
        }

        /** The descriptor's CRC, once the bytes the inflater did not use are back in the source. */
        private fun findEnd(): Long {
            recordedCrc?.let { return it }
            lost?.let { throw ZipException(it) }
            val left = inflater.remaining
            if (left > 0) source.unread(scratch, fed - left, left)
            val found = descriptorAt(inflater.bytesRead, inflater.bytesWritten)
            if (found == null) {
                lost = "no data descriptor matches the entry"
                throw ZipException(lost)
            }
            recordedCrc = found
            return found
        }
    }

    /**
     * An entry found to end by a forward scan: a stored one with a data descriptor ([readable]), or one that
     * is encrypted or in an unknown method. At most one read chunk plus a descriptor's length is held.
     */
    private inner class ScannedEntry(name: String, modifiedMs: Long?, private val readable: Boolean) : LocalEntry(name, modifiedMs) {
        private val window = ByteArray(CHUNK + CARRY)
        private var base = 0L
        private var filled = 0
        private var scanned = 0
        private var delivered = 0
        private var end = -1
        private var recordedCrc = 0L
        private var sourceEnded = false
        private val crc = CRC32()
        private var checked = false

        /** Window bytes known to be the entry's own: a descriptor found later can only start past them. */
        private val ready: Int get() = if (end >= 0) end else maxOf(0, scanned - LONGEST_DESCRIPTOR)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!readable) throw ZipException("unsupported zip entry")
            while (delivered == ready) {
                if (end >= 0) {
                    verify(crc, recordedCrc, checked)
                    checked = true
                    return -1
                }
                fill()
            }
            val n = minOf(len, ready - delivered)
            System.arraycopy(window, delivered, b, off, n)
            crc.update(window, delivered, n)
            delivered += n
            return n
        }

        override fun skipRest() {
            checked = true
            while (end < 0) {
                delivered = ready
                fill()
            }
            delivered = end
        }

        private fun fill() {
            if (sourceEnded) {
                if (!descriptorEndsAt(filled)) throw ZipException("zip entry cut off")
                return
            }
            compact()
            val n = source.read(window, filled, window.size - filled)
            if (n < 0) {
                sourceEnded = true
                return
            }
            filled += n
            while (scanned + 4 <= filled) {
                if (window[scanned] == 'P'.code.toByte() && isNextHeader(window, scanned, filled) && descriptorEndsAt(scanned)) {
                    source.unread(window, scanned, filled - scanned)
                    filled = scanned
                    return
                }
                scanned++
            }
        }

        private fun compact() {
            if (delivered == 0) return
            System.arraycopy(window, delivered, window, 0, filled - delivered)
            base += delivered
            filled -= delivered
            scanned -= delivered
            delivered = 0
        }

        /** True, with [end] and [recordedCrc] set, when a descriptor ending at [ends] records its own distance from the data start. */
        private fun descriptorEndsAt(ends: Int): Boolean {
            for ((signed, wide) in SCAN_LAYOUTS) {
                val at = ends - (if (wide) 20 else 12)
                val start = at - (if (signed) 4 else 0)
                if (start < 0) continue
                if (signed && u32(window, start) != DESCRIPTOR_SIGNATURE) continue
                val distance = base + start
                val recorded = if (wide) u64(window, at + 4) else u32(window, at + 4)
                if (recorded != (if (wide) distance else distance and 0xFFFFFFFFL)) continue
                end = start
                recordedCrc = u32(window, at)
                return true
            }
            return false
        }
    }

    private fun inflate(b: ByteArray, off: Int, len: Int, feed: () -> Unit): Int {
        while (true) {
            val n = try {
                inflater.inflate(b, off, len)
            } catch (e: DataFormatException) {
                throw ZipException("invalid deflate data").also { it.initCause(e) }
            }
            if (n > 0) return n
            if (inflater.finished()) return -1
            if (inflater.needsDictionary()) throw ZipException("unsupported deflate data")
            if (inflater.needsInput()) feed()
        }
    }

    /**
     * The CRC of the descriptor that follows a deflated entry, whose sizes match the data's, in any of its four
     * layouts; one followed by a header wins a tie. What follows the descriptor goes back into the source.
     */
    private fun descriptorAt(compressed: Long, uncompressed: Long): Long? {
        val bytes = ByteArray(LONGEST_DESCRIPTOR + 4)
        val got = readUpTo(bytes, bytes.size)
        val at = if (got >= 4 && u32(bytes, 0) == DESCRIPTOR_SIGNATURE) 4 else 0
        var match: Pair<Long, Int>? = null
        for (wide in listOf(false, true)) {
            val length = at + (if (wide) 20 else 12)
            if (got < length) continue
            val c = if (wide) u64(bytes, at + 4) else u32(bytes, at + 4)
            val u = if (wide) u64(bytes, at + 12) else u32(bytes, at + 8)
            val mask = if (wide) -1L else 0xFFFFFFFFL
            if (c != compressed and mask || u != uncompressed and mask) continue
            val found = u32(bytes, at) to length
            if (got == length || isNextHeader(bytes, length, got)) {
                match = found
                break
            }
            if (match == null) match = found
        }
        val length = match?.second ?: 0
        if (got > length) source.unread(bytes, length, got - length)
        return match?.first
    }

    private fun verify(crc: CRC32, recordedCrc: Long, checked: Boolean) {
        if (!checked && crc.value != recordedCrc) throw ZipException("invalid entry CRC")
    }

    private fun skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val n = source.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw ZipException("zip entry cut off")
            left -= n
        }
    }

    private fun readUpTo(into: ByteArray, length: Int): Int {
        var done = 0
        while (done < length) {
            val n = source.read(into, done, length - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    companion object {
        private const val INPUT = 8 * 1024
        private const val CHUNK = 64 * 1024
        private const val CARRY = 32
        private const val LONGEST_DESCRIPTOR = 24
        private const val LOCAL_HEADER_SIZE = 30
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val DESCRIPTOR_SIGNATURE = 0x08074b50L
        private const val FLAG_ENCRYPTED = 0x1
        private const val FLAG_DESCRIPTOR = 0x8
        private const val FLAG_UTF8 = 0x800
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val EXTRA_ZIP64 = 0x0001
        private const val EXTRA_TIMESTAMP = 0x5455

        /** The pushback room the source needs for the bytes this reader puts back. */
        const val SOURCE_PUSHBACK = CHUNK + CARRY + INPUT

        /** What follows the last entry of a whole zip: its central directory, or one of its end records. */
        private val DIRECTORY_SIGNATURES = setOf(0x02014b50L, 0x06064b50L, 0x07064b50L, 0x06054b50L)

        /** (signed, 64-bit) in the order a tie is settled. */
        private val SCAN_LAYOUTS = listOf(true to false, false to false, true to true, false to true)

        private fun isNextHeader(bytes: ByteArray, at: Int, limit: Int): Boolean {
            if (at + 4 > limit) return false
            val signature = u32(bytes, at)
            return signature == LOCAL_SIGNATURE || signature in DIRECTORY_SIGNATURES
        }

        private fun forEachExtra(bytes: ByteArray, from: Int, length: Int, onField: (id: Int, at: Int, length: Int) -> Unit) {
            var field = from
            while (field + 4 <= from + length) {
                val id = u16(bytes, field)
                val size = u16(bytes, field + 2)
                val data = field + 4
                if (data + size > from + length) return
                onField(id, data, size)
                field = data + size
            }
        }
    }
}
