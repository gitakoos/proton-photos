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

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Small image files that carry a place where an EXIF GPS strip does not reach. Needs Robolectric. */
internal class PlaceFixtures(private val context: Context) {

    fun jpeg(withPlace: Boolean = false): ByteArray {
        val file = File.createTempFile("place_fixture_", ".jpg", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (withPlace) ExifInterface(file.absolutePath).apply { setLatLong(38.9067, 1.4206); saveAttributes() }
        return file.readBytes().also { file.delete() }
    }

    fun png(): ByteArray {
        val file = File.createTempFile("place_fixture_", ".png", context.cacheDir)
        file.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.readBytes().also { file.delete() }
    }

    fun withSegment(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray = withSegmentAt(jpeg, 2, marker, payload)

    fun withSegmentAt(bytes: ByteArray, at: Int, marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        val header = byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte())
        return bytes.copyOfRange(0, at) + header + payload + bytes.copyOfRange(at, bytes.size)
    }

    fun xmp(body: String) =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description $body</rdf:Description></rdf:RDF></x:xmpmeta>"""

    fun withXmp(jpeg: ByteArray, xmp: String): ByteArray =
        withSegment(jpeg, 0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray())

    fun iimCity(): ByteArray {
        val city = "Paris".toByteArray()
        val iim = byteArrayOf(0x1C, 0x02, 90, 0x00, city.size.toByte()) + city
        return "Photoshop 3.0\u0000".toByteArray() + "8BIM".toByteArray() + byteArrayOf(0x04, 0x04, 0, 0, 0, 0, 0, iim.size.toByte()) + iim
    }

    /**
     * An Ultra HDR-like file: a main JPEG whose MPF index (inserted first) points at a gain map JPEG
     * appended after it. Both pictures carry a place when asked; the gain map keeps its own XMP.
     */
    fun ultraHdr(mainPlace: Boolean = true, gainMapPlace: Boolean = true): ByteArray {
        val main = jpeg(mainPlace)
        val gainMap = withXmp(jpeg(gainMapPlace), GAIN_MAP_XMP)
        val mainSize = main.size + 4 + MPF_PAYLOAD_SIZE
        val mpHeader = 2 + 4 + 4
        return withSegment(main, 0xE2, mpf(mainSize.toLong(), gainMap.size.toLong(), (mainSize - mpHeader).toLong())) + gainMap
    }

    /** Where the second MPF entry says the gain map starts, as a position in [bytes]. */
    fun mpfSecondPicture(bytes: ByteArray): Int {
        val tiff = String(bytes, Charsets.ISO_8859_1).indexOf("MPF\u0000") + 4
        return tiff + ImagePlaceParts.u32(bytes, tiff + MP_TABLE + 16 + 8, false).toInt()
    }

    fun exifPlaceAt(bytes: ByteArray, from: Int): DoubleArray? =
        ExifInterface(ByteArrayInputStream(bytes, from, bytes.size - from)).latLong

    private fun mpf(mainSize: Long, secondSize: Long, secondOffset: Long): ByteArray {
        val b = ByteArray(MPF_PAYLOAD_SIZE)
        "MPF\u0000MM".toByteArray(Charsets.ISO_8859_1).copyInto(b)
        val t = 4
        b[t + 3] = 0x2A
        ImagePlaceParts.putU32(b, t + 4, 8, false)
        b[t + 9] = 3
        entry(b, t + 10, 0xB000, 7, 4, 0x30313030)
        entry(b, t + 22, 0xB001, 4, 1, 2)
        entry(b, t + 34, 0xB002, 7, 32, MP_TABLE.toLong())
        ImagePlaceParts.putU32(b, t + MP_TABLE, 0x00030000, false)
        ImagePlaceParts.putU32(b, t + MP_TABLE + 4, mainSize, false)
        ImagePlaceParts.putU32(b, t + MP_TABLE + 20, secondSize, false)
        ImagePlaceParts.putU32(b, t + MP_TABLE + 24, secondOffset, false)
        return b
    }

    private fun entry(b: ByteArray, at: Int, tag: Int, type: Int, count: Long, value: Long) {
        b[at] = (tag shr 8).toByte()
        b[at + 1] = tag.toByte()
        b[at + 3] = type.toByte()
        ImagePlaceParts.putU32(b, at + 4, count, false)
        ImagePlaceParts.putU32(b, at + 8, value, false)
    }

    /** A motion photo: a JPEG with the movie appended, whose user data and QuickTime keys hold a place. */
    fun motionPhoto(movie: ByteArray = movieWithPlace()): ByteArray {
        val main = withXmp(jpeg(), xmp("""xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="${movie.size}">"""))
        return main + movie
    }

    fun movieWithPlace(): ByteArray {
        val iso = "+38.9067+001.4206/".toByteArray()
        val xyz = box("©xyz", byteArrayOf(0, iso.size.toByte(), 0x15, 0xC7.toByte()) + iso)
        val keys = box("keys", ByteArray(4) + byteArrayOf(0, 0, 0, 1) + box("mdta", "com.apple.quicktime.location.ISO6709".toByteArray()))
        val item = boxWithType(byteArrayOf(0, 0, 0, 1), box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + iso))
        val meta = box("meta", box("hdlr", ByteArray(24)) + keys + box("ilst", item))
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("udta", xyz) + meta)
        return box("ftyp", "isom".toByteArray() + ByteArray(4) + "isom".toByteArray()) + moov + box("mdat", ByteArray(64) { 7 })
    }

    /** A movie whose only place is a 3GPP `loci` box (47.5, 19.25). */
    fun movieWithLoci(): ByteArray {
        val coordinates = byteArrayOf(0, 0x13, 0x40, 0, 0, 0x2F, 0x80.toByte(), 0, 0, 0, 0, 0)
        val loci = box("loci", byteArrayOf(0, 0, 0, 0, 0x15, 0xC7.toByte()) + "Home\u0000".toByteArray() + byteArrayOf(0) + coordinates)
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("udta", loci))
        return box("ftyp", "isom".toByteArray() + ByteArray(4) + "isom".toByteArray()) + moov + box("mdat", ByteArray(64) { 7 })
    }

    private fun box(type: String, payload: ByteArray) = boxWithType(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun boxWithType(type: ByteArray, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + type + payload
    }

    /** A PNG with its XMP in an iTXt chunk right after the header chunk, deflated when [compressed]. */
    fun pngWithXmp(xmp: String, compressed: Boolean): ByteArray {
        val text = if (compressed) deflate(xmp.toByteArray()) else xmp.toByteArray()
        val data = "XML:com.adobe.xmp".toByteArray() + byteArrayOf(0, if (compressed) 1 else 0, 0, 0, 0) + text
        val png = png()
        val afterHeader = 8 + 25
        return png.copyOfRange(0, afterHeader) + chunk("iTXt", data) + png.copyOfRange(afterHeader, png.size)
    }

    /** True when the chunk at [at] carries the checksum of its type and data. */
    fun pngChunkCrcHolds(bytes: ByteArray, at: Int): Boolean {
        val length = ImagePlaceParts.u32(bytes, at, false).toInt()
        val crc = CRC32().apply { update(bytes, at + 4, 4 + length) }.value
        return ImagePlaceParts.u32(bytes, at + 8 + length, false) == crc
    }

    fun webpWithXmp(xmp: String): ByteArray {
        val data = xmp.toByteArray()
        val chunk = "XMP ".toByteArray() + le32(data.size) + data + if (data.size % 2 == 1) ByteArray(1) else ByteArray(0)
        return "RIFF".toByteArray() + le32(4 + chunk.size) + "WEBP".toByteArray() + chunk
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray() + data
        val crc = CRC32().apply { update(body) }.value
        val n = data.size
        val out = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + body + ByteArray(4)
        ImagePlaceParts.putU32(out, out.size - 4, crc, false)
        return out
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(input); finish() }
        val buf = ByteArray(input.size + 64)
        val n = deflater.deflate(buf)
        deflater.end()
        return buf.copyOf(n)
    }

    companion object {
        const val GAIN_MAP_XMP = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></rdf:RDF></x:xmpmeta>"""
        private const val MP_TABLE = 50
        private const val MPF_PAYLOAD_SIZE = 4 + MP_TABLE + 32

        /** Where the MPF segment [ultraHdr] inserts first ends. */
        const val MPF_SEGMENT_END = 2 + 4 + MPF_PAYLOAD_SIZE
    }
}
