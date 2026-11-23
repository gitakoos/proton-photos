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

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes zips byte by byte, for the layouts `ZipOutputStream` never produces: a stored entry with a data
 * descriptor, a zip64 descriptor on a small entry, a name that is not UTF-8, a compression method the reader
 * does not support, a zip64 end record. Each entry is one call to [add]; [build] writes the central directory.
 */
class HandBuiltZip(private val zip64End: Boolean = false) {

    enum class Descriptor { NONE, SIZES_32, SIZES_64 }

    private class Written(
        val name: ByteArray,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressed: Long,
        val size: Long,
        val offset: Long,
        val mtimeSeconds: Long?,
    )

    private val out = ByteArrayOutputStream()
    private val written = ArrayList<Written>()

    fun add(
        name: String,
        data: ByteArray,
        method: Int = DEFLATED,
        descriptor: Descriptor = Descriptor.NONE,
        utf8Flag: Boolean = true,
        rawName: ByteArray = name.toByteArray(Charsets.UTF_8),
        recordedCrc: Long? = null,
        corruptPayload: Boolean = false,
        mtimeSeconds: Long? = null,
    ): HandBuiltZip {
        val payload = (if (method == STORED) data.copyOf() else deflate(data)).also {
            if (corruptPayload) for (i in 2 until minOf(it.size, 40)) it[i] = 0xFF.toByte()
        }
        val crc = recordedCrc ?: CRC32().apply { update(data) }.value
        val flags = (if (descriptor != Descriptor.NONE) 0x8 else 0) or (if (utf8Flag) 0x800 else 0)
        val offset = out.size().toLong()
        val extra = mtimeSeconds?.let { timestampExtra(it) } ?: ByteArray(0)
        u32(0x04034b50); u16(if (descriptor == Descriptor.SIZES_64) 45 else 20); u16(flags); u16(method)
        u16(DOS_TIME); u16(DOS_DATE)
        if (descriptor == Descriptor.NONE) {
            u32(crc); u32(payload.size.toLong()); u32(data.size.toLong())
        } else {
            u32(0); u32(0); u32(0)
        }
        u16(rawName.size); u16(extra.size); out.write(rawName); out.write(extra)
        out.write(payload)
        if (descriptor != Descriptor.NONE) {
            u32(0x08074b50); u32(crc)
            if (descriptor == Descriptor.SIZES_64) {
                u64(payload.size.toLong()); u64(data.size.toLong())
            } else {
                u32(payload.size.toLong()); u32(data.size.toLong())
            }
        }
        written += Written(rawName, flags, method, crc, payload.size.toLong(), data.size.toLong(), offset, mtimeSeconds)
        return this
    }

    fun build(): ByteArray {
        val directoryStart = out.size().toLong()
        for (w in written) {
            val extra = w.mtimeSeconds?.let { timestampExtra(it) } ?: ByteArray(0)
            u32(0x02014b50); u16(20); u16(20); u16(w.flags); u16(w.method); u16(DOS_TIME); u16(DOS_DATE)
            u32(w.crc); u32(w.compressed); u32(w.size)
            u16(w.name.size); u16(extra.size); u16(0); u16(0); u16(0); u32(0); u32(w.offset)
            out.write(w.name); out.write(extra)
        }
        val directorySize = out.size() - directoryStart
        if (zip64End) {
            val recordAt = out.size().toLong()
            u32(0x06064b50); u64(44); u16(45); u16(45); u32(0); u32(0)
            u64(written.size.toLong()); u64(written.size.toLong()); u64(directorySize); u64(directoryStart)
            u32(0x07064b50); u32(0); u64(recordAt); u32(1)
        }
        val count = if (zip64End) 0xFFFF else written.size
        u32(0x06054b50); u16(0); u16(0); u16(count); u16(count)
        u32(if (zip64End) 0xFFFFFFFFL else directorySize); u32(if (zip64End) 0xFFFFFFFFL else directoryStart); u16(0)
        return out.toByteArray()
    }

    private fun timestampExtra(seconds: Long): ByteArray = byteArrayOf(
        0x55, 0x54, 5, 0, 1,
        seconds.toByte(), (seconds shr 8).toByte(), (seconds shr 16).toByte(), (seconds shr 24).toByte(),
    )

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(data.size + 1024)
        val n = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(n)
    }

    private fun u16(v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    private fun u32(v: Long) {
        for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun u64(v: Long) {
        for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        const val DEFLATE64 = 9

        /** 15 August 2017, 12:30:00 local time. */
        private const val DOS_DATE = ((2017 - 1980) shl 9) or (8 shl 5) or 15
        private const val DOS_TIME = (12 shl 11) or (30 shl 5)
    }
}

/** A zip held in memory, readable at any position. */
class ByteArrayZipSource(private val bytes: ByteArray) : ZipRandomSource {
    override val size: Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() = Unit
}
