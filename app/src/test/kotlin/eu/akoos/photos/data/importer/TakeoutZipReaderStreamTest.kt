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

import eu.akoos.photos.data.importer.HandBuiltZip.Descriptor
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The front-to-back walk over the entries it cannot read (encrypted, an unknown method, broken data) and the
 * descriptor layouts beyond the common one: an unreadable entry is counted and stepped past, and a read
 * failure of the source is never taken for damage in the archive.
 */
class TakeoutZipReaderStreamTest {

    private fun noise(seed: Long, size: Int = 20_000) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private val one = noise(1)
    private val two = noise(2)
    private val three = noise(3)

    private fun reader(bytes: ByteArray) = TakeoutZipReader { ByteArrayInputStream(bytes) }

    private fun delivered(reader: TakeoutZipReader): Pair<Map<String, ByteArray>, WalkResult> {
        val seen = LinkedHashMap<String, ByteArray>()
        val walk = reader.forEachMedia { name, input -> seen[name] = input.readBytes() }
        return seen to walk
    }

    private fun delivered(bytes: ByteArray) = delivered(reader(bytes))

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `an encrypted entry with known sizes is skipped, counted, and the next still arrives`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two, encrypted = true).add("c.jpg", three).build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(three, seen["c.jpg"])
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `an encrypted entry with a data descriptor is skipped, counted, and the next still arrives`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, descriptor = Descriptor.SIZES_32, encrypted = true)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `an entry in an unknown method with known sizes is skipped, counted, and the next still arrives`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two, method = HandBuiltZip.DEFLATE64).add("c.jpg", three).build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `an entry in an unknown method with a data descriptor is skipped, counted, and the next still arrives`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.DEFLATE64, descriptor = Descriptor.SIZES_32, descriptorSignature = false)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `an unreadable file that is not a photo is never read and never counted`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("notes.txt", two, method = HandBuiltZip.DEFLATE64).add("c.jpg", three).build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a photo the first pass only counts is not decoded there`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two, encrypted = true).add("c.jpg", three).build()

        val scan = reader(bytes).scan()

        assertEquals(3, scan.mediaCount)
        assertEquals(0, scan.walk.damagedEntries)
        assertFalse(scan.walk.endedEarly)
    }

    @Test
    fun `a deflated entry whose compressed bytes are broken is skipped and the next still arrives`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two, corruptPayload = true).add("c.jpg", three).build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(three, seen["c.jpg"])
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `a deflated entry with a 64-bit data descriptor reads and the next still arrives`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two, descriptor = Descriptor.SIZES_64).add("c.jpg", three).build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertArrayEquals(three, seen["c.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a deflated entry with an unsigned 64-bit data descriptor reads and the next still arrives`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, descriptor = Descriptor.SIZES_64, descriptorSignature = false)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `an empty deflated entry with a 64-bit descriptor keeps the walk in step`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", ByteArray(0), descriptor = Descriptor.SIZES_64)
            .add("b.jpg", two)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `a name that is not UTF-8 is read as code page 437`() {
        val latinName = byteArrayOf(0x4B, 0x2F, 0x9A.toByte(), 0x62, 0x2E, 0x6A, 0x70, 0x67)
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("ignored", two, utf8Flag = false, rawName = latinName)
            .add("c.jpg", three)
            .build()

        val (seen, _) = delivered(bytes)

        assertEquals(listOf("a.jpg", "K/Üb.jpg", "c.jpg"), seen.keys.toList())
    }

    @Test
    fun `a file that stops between entries ends the walk early with every entry kept`() {
        val whole = HandBuiltZip().add("a.jpg", one).add("b.jpg", two).build()
        val entriesOnly = whole.copyOf(indexOf(whole, byteArrayOf(0x50, 0x4B, 0x01, 0x02)))

        val (seen, walk) = delivered(entriesOnly)

        assertEquals(listOf("a.jpg", "b.jpg"), seen.keys.toList())
        assertEquals(0, walk.damagedEntries)
        assertTrue(walk.endedEarly)
    }

    @Test
    fun `a source read failure inside an entry propagates instead of counting the entry as damaged`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two, method = HandBuiltZip.STORED).add("c.jpg", three).build()
        val failAt = indexOf(bytes, two.copyOf(32)) + 5_000
        val failsOnce = TakeoutZipReader {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                private var served = 0
                private var failed = false
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= failAt && !failed) {
                        failed = true
                        throw IOException("connection reset")
                    }
                    val cap = if (failed) len else minOf(len, failAt - served)
                    return super.read(b, off, cap).also { if (it > 0) served += it }
                }
            } as InputStream
        }

        assertThrows(SourceReadException::class.java) { failsOnce.forEachMedia { _, input -> input.readBytes() } }
    }

    @Test
    fun `a source that hands over a few bytes at a time steps past every unreadable entry`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one, descriptor = Descriptor.SIZES_64)
            .add("b.jpg", two, encrypted = true)
            .add("c.jpg", three, descriptor = Descriptor.SIZES_32, encrypted = true)
            .add("d.jpg", one, method = HandBuiltZip.DEFLATE64, descriptor = Descriptor.SIZES_64, descriptorSignature = false)
            .add("e.jpg", two, descriptor = Descriptor.SIZES_64, descriptorSignature = false)
            .build()
        val trickling = TakeoutZipReader {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
                override fun available(): Int = 0
            } as InputStream
        }

        val (seen, walk) = delivered(trickling)

        assertEquals(listOf("a.jpg", "e.jpg"), seen.keys.toList())
        assertArrayEquals(one, seen["a.jpg"])
        assertArrayEquals(two, seen["e.jpg"])
        assertEquals(3, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }
}
