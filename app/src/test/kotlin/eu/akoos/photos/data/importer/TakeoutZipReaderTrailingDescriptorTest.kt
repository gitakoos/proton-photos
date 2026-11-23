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
 * The front-to-back walk over a stored entry whose sizes and CRC follow its bytes in a data descriptor, as
 * some zip writers produce and as a download cut short leaves with no central directory to read instead.
 */
class TakeoutZipReaderTrailingDescriptorTest {

    private fun noise(seed: Long, size: Int = 20_000) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private val one = noise(1)
    private val two = noise(2)
    private val three = noise(3)

    private fun reader(bytes: ByteArray) = TakeoutZipReader { ByteArrayInputStream(bytes) }

    private fun delivered(bytes: ByteArray): Pair<Map<String, ByteArray>, WalkResult> {
        val seen = LinkedHashMap<String, ByteArray>()
        val walk = reader(bytes).forEachMedia { name, input -> seen[name] = input.readBytes() }
        return seen to walk
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun `a stored entry with an unsigned data descriptor reads with the entries around it`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32, descriptorSignature = false)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertArrayEquals(three, seen["c.jpg"])
        assertEquals(0, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `a stored entry with a 64-bit data descriptor reads in full`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_64)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a stored entry with an unsigned 64-bit data descriptor reads in full`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_64, descriptorSignature = false)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `header signatures inside a stored entry's bytes do not end it`() {
        val tricky = noise(4).also {
            byteArrayOf(0x50, 0x4B, 0x03, 0x04).copyInto(it, 5_000)
            byteArrayOf(0x50, 0x4B, 0x01, 0x02).copyInto(it, 9_000)
            byteArrayOf(0x50, 0x4B, 0x07, 0x08).copyInto(it, 12_000)
            byteArrayOf(0x50, 0x4B, 0x05, 0x06).copyInto(it, 19_990)
        }
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", tricky, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(tricky, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a stored entry many read chunks long arrives whole`() {
        val big = noise(5, 300_000)
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.mp4", big, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.mp4", "c.jpg"), seen.keys.toList())
        assertArrayEquals(big, seen["b.mp4"])
        assertArrayEquals(three, seen["c.jpg"])
        assertFalse(walk.endedShort(bytes.size.toLong()))
    }

    @Test
    fun `empty and tiny stored entries between deflated entries with descriptors read in full`() {
        val tiny = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val bytes = HandBuiltZip()
            .add("a.jpg", one, descriptor = Descriptor.SIZES_32)
            .add("b.jpg", ByteArray(0), method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", tiny, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("d.jpg", two, descriptor = Descriptor.SIZES_32)
            .add("e.jpg", three, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"), seen.keys.toList())
        assertArrayEquals(one, seen["a.jpg"])
        assertArrayEquals(ByteArray(0), seen["b.jpg"])
        assertArrayEquals(tiny, seen["c.jpg"])
        assertArrayEquals(two, seen["d.jpg"])
        assertArrayEquals(three, seen["e.jpg"])
        assertEquals(0, walk.damagedEntries)
        assertFalse(walk.endedEarly)
        assertFalse(walk.endedShort(bytes.size.toLong()))
    }

    @Test
    fun `a stored entry with a descriptor that ends the file reads in full`() {
        val whole = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .build()
        val entriesOnly = whole.copyOf(indexOf(whole, two.copyOf(32)) + two.size + 16)

        val (seen, walk) = delivered(entriesOnly)

        assertEquals(listOf("a.jpg", "b.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a stored entry cut off before its descriptor is counted and the entries before it are kept`() {
        val whole = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()
        val cut = whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000)

        val (seen, walk) = delivered(cut)

        assertEquals(listOf("a.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
        assertTrue(walk.endedEarly)
    }

    @Test
    fun `a deflated entry cut off the same way counts the same`() {
        val whole = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()
        // The noise does not compress, so the deflated stream carries it verbatim.
        val cut = whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000)

        val (seen, walk) = delivered(cut)

        assertEquals(listOf("a.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
        assertTrue(walk.endedEarly)
    }

    @Test
    fun `a stored entry with a descriptor that fails its checksum is skipped and the next still arrives`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32, recordedCrc = 0x12345678L)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(three, seen["c.jpg"])
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `the first pass steps past stored entries with a descriptor it does not read`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32, recordedCrc = 0x12345678L)
            .add("c.jpg", three, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg.json", """{"title":"c.jpg","photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray())
            .build()

        val scan = reader(bytes).scan()

        assertEquals(3, scan.mediaCount)
        assertEquals(1, scan.sidecars.size)
        assertEquals(0, scan.walk.damagedEntries)
        assertFalse(scan.walk.endedEarly)
        assertFalse(scan.walk.endedShort(bytes.size.toLong()))
    }

    @Test
    fun `folder and dot-dot entries with a descriptor are stepped over`() {
        val bytes = HandBuiltZip()
            .add("Photos/", ByteArray(0), method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("../b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("Photos/c.jpg", three)
            .build()

        val (seen, walk) = delivered(bytes)

        assertEquals(listOf("Photos/c.jpg"), seen.keys.toList())
        assertEquals(3, walk.entries)
    }

    @Test
    fun `the entry time comes from the header of a stored entry with a descriptor`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32, mtimeSeconds = 1_502_800_000L)
            .build()
        val times = ArrayList<Long?>()

        reader(bytes).forEachMediaEntry { entry, _ -> times += entry.modifiedMs }

        assertEquals(listOf<Long?>(1_502_800_000_000L), times)
    }

    @Test
    fun `a source that hands over a few bytes at a time reads every layout in full`() {
        val tiny = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val bytes = HandBuiltZip()
            .add("a.jpg", one, descriptor = Descriptor.SIZES_32)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", tiny, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32, descriptorSignature = false)
            .add("d.jpg", ByteArray(0), method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_64)
            .add("e.jpg", three, descriptor = Descriptor.SIZES_32)
            .add("f.jpg", one, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_64, descriptorSignature = false)
            .build()
        val trickling = TakeoutZipReader {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
                override fun available(): Int = 0
            } as InputStream
        }
        val seen = LinkedHashMap<String, ByteArray>()

        val walk = trickling.forEachMedia { name, input -> seen[name] = input.readBytes() }

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "f.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertArrayEquals(tiny, seen["c.jpg"])
        assertArrayEquals(ByteArray(0), seen["d.jpg"])
        assertArrayEquals(three, seen["e.jpg"])
        assertArrayEquals(one, seen["f.jpg"])
        assertEquals(0, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `a source that stops answering inside a stored entry with a descriptor is not swallowed`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()
        val failAt = indexOf(bytes, two.copyOf(32)) + 5_000
        val failing = TakeoutZipReader {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                private var served = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= failAt) throw IOException("connection reset")
                    return super.read(b, off, minOf(len, failAt - served)).also { if (it > 0) served += it }
                }
            } as InputStream
        }

        assertThrows(IOException::class.java) { failing.forEachMedia { _, input -> input.readBytes() } }
        assertThrows(IOException::class.java) { failing.scan() }
    }

    @Test
    fun `progress past a stored entry with a descriptor never goes backwards or beyond the file`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one, descriptor = Descriptor.SIZES_32)
            .add("b.mp4", noise(6, 150_000), method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()
        val seen = ArrayList<Long>()

        reader(bytes).forEachMedia(onBytes = { seen += it }) { _, input -> input.readBytes() }

        assertEquals(3, seen.size)
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.last() <= bytes.size)
    }
}
