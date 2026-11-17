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

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/**
 * The source's ISO 6709 string carried into a muxed output, on box trees laid out the way the two
 * muxers write them: a two-number `©xyz` in the movie's user data, the movie box ahead of or behind the
 * media data, with or without the padding a streamable muxer leaves.
 */
class Mp4LocationCarryTest {

    private val dir: File = Files.createTempDirectory("loccarry").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val withAltitude = "+47.4979+019.0402+120.000/"
    private val negativeAltitude = "-33.8688+151.2093-012.500/"
    private val noAltitude = "+47.4979+019.0402/"

    /** What Media3's muxer writes for the fix above: no altitude, no zero padding. */
    private val muxerXyz = "+47.4979+19.0402/"

    private val samples = List(3) { i -> ByteArray(40) { (i * 50 + it).toByte() } }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + payload.size)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }
        return out.toByteArray()
    }

    private fun xyz(iso: String): ByteArray {
        val s = iso.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeShort(s.size); writeShort(0x15C7); write(s) }
        return box("©xyz", out.toByteArray())
    }

    private fun int(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun long(v: Long): ByteArray = int((v ushr 32).toInt()) + int(v.toInt())

    private fun chunkTable(offsets: List<Long>, wide: Boolean): ByteArray {
        val entries = offsets.fold(ByteArray(0)) { acc, o -> acc + if (wide) long(o) else int(o.toInt()) }
        return box(if (wide) "co64" else "stco", int(0) + int(offsets.size) + entries)
    }

    private fun trak(offsets: List<Long>, wide: Boolean): ByteArray =
        box("trak", box("tkhd", ByteArray(84)), box("mdia", box("minf", box("stbl", box("stsd", ByteArray(16)), chunkTable(offsets, wide)))))

    /** A QuickTime `meta` with [keys] and one `ilst` item per key holding [values]. */
    private fun quickTimeMeta(keys: List<String>, values: List<String>): ByteArray {
        val keyEntries = keys.fold(ByteArray(0)) { acc, k ->
            val name = k.toByteArray(Charsets.UTF_8)
            acc + int(8 + name.size) + "mdta".toByteArray() + name
        }
        val items = values.foldIndexed(ByteArray(0)) { i, acc, v ->
            acc + box(String(int(i + 1), Charsets.ISO_8859_1), box("data", int(1) + int(0) + v.toByteArray(Charsets.UTF_8)))
        }
        return box("meta", box("hdlr", ByteArray(25)), box("keys", int(0) + int(keys.size) + keyEntries), box("ilst", items))
    }

    private val ftyp = box("ftyp", "isom".toByteArray() + int(0))
    private val fpsMeta = quickTimeMeta(listOf("com.android.capture.fps"), listOf("30.0"))

    private fun moov(offsets: List<Long>, wide: Boolean, udta: ByteArray?): ByteArray =
        box("moov", *listOfNotNull(box("mvhd", ByteArray(100)), trak(offsets, wide), udta, fpsMeta).toTypedArray())

    private fun mdat(): ByteArray = box("mdat", *samples.toTypedArray())

    private fun offsetsFrom(mdatStart: Int): List<Long> = samples.indices.map { (mdatStart + 8 + it * 40).toLong() }

    /** A muxed file with its movie box first, then [paddingBytes] of `free` (none at 0), then the media. */
    private fun moovFirst(udta: ByteArray?, paddingBytes: Int = 0, wide: Boolean = false): ByteArray {
        val free = if (paddingBytes > 0) box("free", ByteArray(paddingBytes - 8)) else ByteArray(0)
        val sized = moov(offsetsFrom(0), wide, udta)
        return ftyp + moov(offsetsFrom(ftyp.size + sized.size + free.size), wide, udta) + free + mdat()
    }

    /** A muxed file with the media first and its movie box last. */
    private fun moovLast(udta: ByteArray?): ByteArray = ftyp + mdat() + moov(offsetsFrom(ftyp.size), false, udta)

    private fun file(bytes: ByteArray): File = File(dir, "out.mp4").also { it.writeBytes(bytes) }

    private fun ByteArray.indexOf(text: String): Int = String(this, Charsets.ISO_8859_1).indexOf(text)

    private fun ByteArray.contains(text: String): Boolean = indexOf(text) >= 0

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** Whether every chunk offset in [bytes] still points at the sample it pointed at before. */
    private fun samplesAtTheirOffsets(bytes: ByteArray): Boolean {
        val stco = bytes.indexOf("stco")
        val co64 = bytes.indexOf("co64")
        val wide = stco < 0
        val table = if (wide) co64 else stco
        val count = u32(bytes, table + 8).toInt()
        return (0 until count).all { i ->
            val offset = if (wide) (u32(bytes, table + 12 + i * 8) shl 32) or u32(bytes, table + 16 + i * 8)
            else u32(bytes, table + 12 + i * 4)
            bytes.copyOfRange(offset.toInt(), offset.toInt() + 40).contentEquals(samples[i])
        }
    }

    private fun noTempLeft() = assertEquals(listOf("out.mp4"), dir.list()!!.toList())

    @Test
    fun `the source string with its altitude replaces the muxer's two numbers`() {
        for (source in listOf(withAltitude, negativeAltitude, noAltitude)) {
            val f = file(moovLast(box("udta", xyz(muxerXyz))))

            assertTrue(Mp4LocationAtoms.writeIso6709(f, source))

            val after = f.readBytes()
            assertEquals(source, Mp4LocationAtoms.readIso6709(f))
            assertTrue(after.contains(source))
            assertFalse(after.contains(muxerXyz))
            assertTrue(samplesAtTheirOffsets(after))
            noTempLeft()
        }
    }

    @Test
    fun `the written atom is the source's bytes in the layout both muxers use`() {
        val f = file(moovLast(box("udta", xyz(muxerXyz))))

        assertTrue(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        val after = f.readBytes()
        val expected = xyz(withAltitude)
        val at = after.indexOf("©xyz") - 4
        assertArrayEquals(expected, after.copyOfRange(at, at + expected.size))
    }

    @Test
    fun `a movie box ahead of the media takes the size change out of the padding after it`() {
        val before = moovFirst(box("udta", xyz(muxerXyz)), paddingBytes = 256)
        val f = file(before)

        assertTrue(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        val after = f.readBytes()
        assertEquals(before.size, after.size)
        assertEquals(withAltitude, Mp4LocationAtoms.readIso6709(f))
        // Nothing past the padding moved, so the media and its offsets are byte for byte as they were.
        val mdat = mdat()
        assertArrayEquals(mdat, after.copyOfRange(after.size - mdat.size, after.size))
        assertTrue(samplesAtTheirOffsets(after))
        noTempLeft()
    }

    @Test
    fun `a movie box ahead of the media without padding moves every chunk offset`() {
        for (wide in listOf(false, true)) {
            val before = moovFirst(box("udta", xyz(muxerXyz)), wide = wide)
            val f = file(before)

            assertTrue(Mp4LocationAtoms.writeIso6709(f, negativeAltitude))

            val after = f.readBytes()
            assertEquals(before.size + negativeAltitude.length - muxerXyz.length, after.size)
            assertEquals(negativeAltitude, Mp4LocationAtoms.readIso6709(f))
            assertTrue(samplesAtTheirOffsets(after))
            noTempLeft()
        }
    }

    @Test
    fun `padding too small for the change falls back to moving the offsets`() {
        val before = moovFirst(box("udta", xyz(muxerXyz)), paddingBytes = 12)
        val f = file(before)

        assertTrue(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        assertEquals(withAltitude, Mp4LocationAtoms.readIso6709(f))
        assertTrue(samplesAtTheirOffsets(f.readBytes()))
    }

    @Test
    fun `an output without a location atom gets one and keeps its other metadata`() {
        val f = file(moovFirst(udta = null))

        assertTrue(Mp4LocationAtoms.writeIso6709(f, noAltitude))

        val after = f.readBytes()
        assertEquals(noAltitude, Mp4LocationAtoms.readIso6709(f))
        assertTrue(after.contains("com.android.capture.fps"))
        assertTrue(after.contains("30.0"))
        assertTrue(samplesAtTheirOffsets(after))
    }

    @Test
    fun `other user data stays and a second location atom goes`() {
        val udta = box("udta", box("name", "clip".toByteArray()), xyz(muxerXyz), xyz("+01.0000+002.0000/"))
        val f = file(moovLast(udta))

        assertTrue(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        val after = f.readBytes()
        assertTrue(after.contains("clip"))
        assertFalse(after.contains("+01.0000"))
        assertEquals(1, String(after, Charsets.ISO_8859_1).split("©xyz").size - 1)
        assertEquals(withAltitude, Mp4LocationAtoms.readIso6709(f))
    }

    @Test
    fun `an output already carrying the string is not touched`() {
        val before = moovFirst(box("udta", xyz(withAltitude)), paddingBytes = 64)
        val f = file(before)
        val modified = f.lastModified()

        assertTrue(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        assertArrayEquals(before, f.readBytes())
        assertEquals(modified, f.lastModified())
        noTempLeft()
    }

    @Test
    fun `the strip still erases a carried string`() {
        val f = file(moovFirst(box("udta", xyz(muxerXyz))))
        assertTrue(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        assertTrue(Mp4LocationAtoms.erase(f))

        val after = f.readBytes()
        assertFalse(after.contains("+47.4979"))
        assertFalse(after.contains("+120.000"))
        assertNull(Mp4LocationAtoms.readIso6709(f))
    }

    @Test
    fun `a broken output is left exactly as it was`() {
        val truncated = moovFirst(box("udta", xyz(muxerXyz))).let { it.copyOf(it.size - 10) }
        val f = file(truncated)

        assertFalse(Mp4LocationAtoms.writeIso6709(f, withAltitude))

        assertArrayEquals(truncated, f.readBytes())
        noTempLeft()
    }

    @Test
    fun `a value that is not a printable location string is refused`() {
        val before = moovLast(box("udta", xyz(muxerXyz)))
        val f = file(before)

        assertFalse(Mp4LocationAtoms.writeIso6709(f, ""))
        assertFalse(Mp4LocationAtoms.writeIso6709(f, "+47.4979\u0000+019.0402/"))

        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun `the source string is read from the movie user data or the QuickTime key`() {
        assertEquals(withAltitude, Mp4LocationAtoms.readIso6709(file(moovLast(box("udta", xyz(withAltitude))))))

        val mov = ftyp + box(
            "moov",
            box("mvhd", ByteArray(100)),
            quickTimeMeta(
                listOf("com.apple.quicktime.make", "com.apple.quicktime.location.ISO6709"),
                listOf("Apple", negativeAltitude),
            ),
        ) + mdat()
        assertEquals(negativeAltitude, Mp4LocationAtoms.readIso6709(file(mov)))
    }

    @Test
    fun `a redacted or missing location reads as none`() {
        val redacted = box("©xyz", int(0x001A15C7) + ByteArray(26))
        assertNull(Mp4LocationAtoms.readIso6709(file(moovLast(box("udta", redacted)))))
        assertNull(Mp4LocationAtoms.readIso6709(file(moovLast(udta = null))))
        assertNull(Mp4LocationAtoms.readIso6709(file("not a video".toByteArray())))
    }
}
