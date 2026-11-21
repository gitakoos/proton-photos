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

import java.io.ByteArrayOutputStream

/**
 * Minimal HEIF motion photos built byte by byte: `ftyp`, a `meta` with `hdlr`/`pitm`/`iinf`/`iloc`,
 * an `mdat` holding the image item, the Exif item and an optional XMP item, then an appended MP4 clip.
 */
internal object HeifTestFiles {

    /** A built file and where its parts sit. */
    class Built(val bytes: ByteArray, val imageStart: Int, val imageEnd: Int, val exifStart: Int, val exifEnd: Int, val clipStart: Int)

    /** Where the GPS IFD and its values sit inside the TIFF structure [exif] writes (from the TIFF header). */
    const val GPS_IFD_AT = 44
    const val GPS_VALUES_END = 146

    /** Offset of the TIFF header inside the Exif item: a 4-byte offset field, then "Exif\0\0". */
    const val TIFF_IN_ITEM = 10

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { acc, b -> acc + b }
        return u32(8 + payload.size, true) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    fun u16(v: Int, big: Boolean): ByteArray =
        if (big) byteArrayOf((v ushr 8).toByte(), v.toByte()) else byteArrayOf(v.toByte(), (v ushr 8).toByte())

    fun u32(v: Int, big: Boolean): ByteArray =
        if (big) byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        else byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun entry(tag: Int, type: Int, count: Int, value: ByteArray, big: Boolean) =
        u16(tag, big) + u16(type, big) + u32(count, big) + value

    private fun rationals(big: Boolean, vararg pairs: Pair<Int, Int>): ByteArray =
        pairs.fold(ByteArray(0)) { acc, (n, d) -> acc + u32(n, big) + u32(d, big) }

    /** An Exif item: Make "Apple", plus a GPS IFD at 47.49 N / 19.04 E when [withGps]. */
    fun exif(big: Boolean = true, withGps: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(if (big) "MM".toByteArray() else "II".toByteArray())
        out.write(u16(42, big))
        out.write(u32(8, big))
        if (withGps) {
            out.write(u16(2, big))
            out.write(entry(0x010F, 2, 6, u32(38, big), big))
            out.write(entry(0x8825, 4, 1, u32(GPS_IFD_AT, big), big))
            out.write(u32(0, big))
            out.write("Apple\u0000".toByteArray()) // 38..44
            out.write(u16(4, big)) // GPS IFD at 44
            out.write(entry(1, 2, 2, "N\u0000\u0000\u0000".toByteArray(), big))
            out.write(entry(2, 5, 3, u32(98, big), big))
            out.write(entry(3, 2, 2, "E\u0000\u0000\u0000".toByteArray(), big))
            out.write(entry(4, 5, 3, u32(122, big), big))
            out.write(u32(0, big))
            out.write(rationals(big, 47 to 1, 29 to 1, 5244 to 100)) // 98..122
            out.write(rationals(big, 19 to 1, 2 to 1, 2472 to 100)) // 122..146
        } else {
            out.write(u16(1, big))
            out.write(entry(0x010F, 2, 6, u32(26, big), big))
            out.write(u32(0, big))
            out.write("Apple\u0000".toByteArray())
        }
        return u32(6, true) + "Exif\u0000\u0000".toByteArray() + out.toByteArray()
    }

    /** One TIFF field; [data] already holds the value in the file's byte order. */
    class Field(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun ascii(tag: Int, text: String) = (text + "\u0000").toByteArray().let { Field(tag, 2, it.size, it) }

    /**
     * An Exif item with IFD0 (Make, Orientation, Software, DateTime), an Exif sub-IFD (FNumber,
     * DateTimeOriginal, PixelXDimension) and the GPS IFD [exif] writes, laid out in that order.
     */
    fun exifFull(big: Boolean = true): ByteArray {
        val ifd0 = listOf(
            ascii(0x010F, "Apple"),
            Field(0x0112, 3, 1, u16(1, big) + ByteArray(2)),
            ascii(0x0131, "G998BXXU9FXA1"),
            ascii(0x0132, "2024:05:01 10:00:00"),
        )
        val exifIfd = listOf(
            Field(0x829D, 5, 1, u32(18, big) + u32(10, big)),
            ascii(0x9003, "2024:05:01 09:59:58"),
            Field(0xA002, 4, 1, u32(4000, big)),
        )
        val gps = listOf(
            Field(1, 2, 2, "N\u0000\u0000\u0000".toByteArray()),
            Field(2, 5, 3, rationals(big, 47 to 1, 29 to 1, 5244 to 100)),
            Field(3, 2, 2, "E\u0000\u0000\u0000".toByteArray()),
            Field(4, 5, 3, rationals(big, 19 to 1, 2 to 1, 2472 to 100)),
        )
        fun ifdSize(n: Int) = 2 + n * 12 + 4
        fun outOfLine(fields: List<Field>) = fields.sumOf { if (it.data.size > 4) it.data.size else 0 }
        val ifd0At = 8
        val exifAt = ifd0At + ifdSize(ifd0.size + 2) + outOfLine(ifd0)
        val gpsAt = exifAt + ifdSize(exifIfd.size) + outOfLine(exifIfd)
        val out = ByteArrayOutputStream()
        out.write(if (big) "MM".toByteArray() else "II".toByteArray())
        out.write(u16(42, big))
        out.write(u32(ifd0At, big))
        fun writeIfd(at: Int, fields: List<Field>) {
            var values = at + ifdSize(fields.size)
            val tail = ByteArrayOutputStream()
            out.write(u16(fields.size, big))
            for (f in fields.sortedBy { it.tag }) {
                out.write(u16(f.tag, big) + u16(f.type, big) + u32(f.count, big))
                if (f.data.size > 4) {
                    out.write(u32(values, big))
                    tail.write(f.data)
                    values += f.data.size
                } else {
                    out.write(f.data + ByteArray(4 - f.data.size))
                }
            }
            out.write(u32(0, big))
            out.write(tail.toByteArray())
        }
        writeIfd(ifd0At, ifd0 + Field(0x8769, 4, 1, u32(exifAt, big)) + Field(0x8825, 4, 1, u32(gpsAt, big)))
        writeIfd(exifAt, exifIfd)
        writeIfd(gpsAt, gps)
        return u32(6, true) + "Exif\u0000\u0000".toByteArray() + out.toByteArray()
    }

    fun mp4Clip(): ByteArray = box("ftyp", "mp42".toByteArray(), ByteArray(4), "isommp42".toByteArray()) +
        box("mdat", ByteArray(48) { (it * 7).toByte() })

    /**
     * A HEIC still with [exif] (none when null), an optional XMP item holding [xmp], and [clip] after it.
     * [infeVersion] and [ilocVersion] / [exifMethod] pick the item info and location layouts.
     */
    fun heic(
        exif: ByteArray? = exif(),
        xmp: String? = null,
        clip: ByteArray = mp4Clip(),
        infeVersion: Int = 2,
        ilocVersion: Int = 0,
        exifMethod: Int = 0,
    ): Built {
        val image = ByteArray(64) { (it + 1).toByte() }
        val xmpBytes = xmp?.toByteArray(Charsets.UTF_8)
        val ftyp = box("ftyp", "heic".toByteArray(), ByteArray(4), "mif1heic".toByteArray())

        data class Item(val id: Int, val type: String, val data: ByteArray, val contentType: String? = null)
        val items = buildList {
            add(Item(1, "hvc1", image))
            if (exif != null) add(Item(2, "Exif", exif))
            if (xmpBytes != null) add(Item(3, "mime", xmpBytes, "application/rdf+xml"))
        }

        fun meta(dataStart: Int): ByteArray {
            val infes = items.fold(ByteArray(0)) { acc, it ->
                val idBytes = if (infeVersion >= 3) u32(it.id, true) else u16(it.id, true)
                val tail = "\u0000".toByteArray() + (it.contentType?.let { c -> (c + "\u0000").toByteArray() } ?: ByteArray(0))
                acc + box("infe", byteArrayOf(infeVersion.toByte(), 0, 0, 0), idBytes, u16(0, true), it.type.toByteArray(), tail)
            }
            val iinf = box("iinf", byteArrayOf(0, 0, 0, 0), u16(items.size, true), infes)
            var at = dataStart
            val locs = items.fold(ByteArray(0)) { acc, it ->
                val method = if (ilocVersion >= 1) u16(if (it.type == "Exif") exifMethod else 0, true) else ByteArray(0)
                val rec = u16(it.id, true) + method + u16(0, true) + u16(1, true) + u32(at, true) + u32(it.data.size, true)
                at += it.data.size
                acc + rec
            }
            val iloc = box("iloc", byteArrayOf(ilocVersion.toByte(), 0, 0, 0), byteArrayOf(0x44, 0x00), u16(items.size, true), locs)
            val hdlr = box("hdlr", ByteArray(8), "pict".toByteArray(), ByteArray(13))
            val pitm = box("pitm", ByteArray(4), u16(1, true))
            return box("meta", ByteArray(4), hdlr, pitm, iinf, iloc)
        }

        val metaSize = meta(0).size
        val dataStart = ftyp.size + metaSize + 8
        val mdat = box("mdat", *items.map { it.data }.toTypedArray())
        val bytes = ftyp + meta(dataStart) + mdat + clip
        val exifStart = if (exif != null) dataStart + image.size else -1
        return Built(
            bytes = bytes,
            imageStart = dataStart,
            imageEnd = dataStart + image.size,
            exifStart = exifStart,
            exifEnd = if (exif != null) exifStart + exif.size else -1,
            clipStart = bytes.size - clip.size,
        )
    }
}
