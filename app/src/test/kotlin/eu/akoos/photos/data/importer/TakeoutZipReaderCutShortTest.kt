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
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a walk that stopped early stopped because the file ends (a download cut short: the entries run into
 * the end of the file and no directory follows) or because of damage further in, and that a whole zip is
 * neither.
 */
class TakeoutZipReaderCutShortTest {

    private fun noise(seed: Long, size: Int = 20_000) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private val one = noise(1)
    private val two = noise(2)
    private val three = noise(3)

    private fun walk(bytes: ByteArray): WalkResult =
        TakeoutZipReader { ByteArrayInputStream(bytes) }.forEachMedia { _, input -> input.readBytes() }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun threePhotos(descriptor: Descriptor = Descriptor.NONE, method: Int = HandBuiltZip.DEFLATED) = HandBuiltZip()
        .add("a.jpg", one, method = method, descriptor = descriptor)
        .add("b.jpg", two, method = method, descriptor = descriptor)
        .add("c.jpg", three, method = method, descriptor = descriptor)
        .build()

    private fun directoryStart(bytes: ByteArray) = indexOf(bytes, byteArrayOf(0x50, 0x4B, 0x01, 0x02))

    private fun headerOf(bytes: ByteArray, payload: ByteArray) =
        indexOf(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04), from = indexOf(bytes, payload.copyOf(32)) - 64)

    @Test
    fun `a whole zip read front to back is neither ended early nor cut short`() {
        val result = walk(threePhotos())

        assertFalse(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `a whole zip read through its directory is not cut short`() {
        val bytes = threePhotos()

        val result = TakeoutZipReader({ ByteArrayZipSource(bytes) }) { ByteArrayInputStream(bytes) }
            .forEachMedia { _, input -> input.readBytes() }

        assertFalse(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `a zip that ends where the next entry would start is cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(headerOf(whole, three)))

        assertTrue(result.endedEarly)
        assertTrue(result.cutShort)
        assertEquals(0, result.damagedEntries)
    }

    @Test
    fun `a zip that ends where its directory would start is cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(directoryStart(whole)))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside an entry header is cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(headerOf(whole, three) + 12))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends after the first two bytes of a header is cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(headerOf(whole, three) + 2))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside an entry name is cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(headerOf(whole, three) + 32))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside a deflated entry with known sizes is cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000))

        assertTrue(result.endedEarly)
        assertTrue(result.cutShort)
        assertEquals(1, result.damagedEntries)
    }

    @Test
    fun `a zip that ends inside a stored entry with known sizes is cut short`() {
        val whole = threePhotos(method = HandBuiltZip.STORED)

        val result = walk(whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside a deflated entry with a trailing descriptor is cut short`() {
        val whole = threePhotos(descriptor = Descriptor.SIZES_32)

        val result = walk(whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside the descriptor of a deflated entry is cut short`() {
        val whole = threePhotos(descriptor = Descriptor.SIZES_32)

        val result = walk(whole.copyOf(headerOf(whole, three) - 6))

        assertTrue(result.endedEarly)
        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside a stored entry with a trailing descriptor is cut short`() {
        val whole = threePhotos(descriptor = Descriptor.SIZES_32, method = HandBuiltZip.STORED)

        val result = walk(whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000))

        assertTrue(result.cutShort)
    }

    @Test
    fun `a cut zip is cut short through the reader that tries the directory first`() {
        val whole = threePhotos()
        val cut = whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000)

        val result = TakeoutZipReader({ ByteArrayZipSource(cut) }) { ByteArrayInputStream(cut) }
            .forEachMedia { _, input -> input.readBytes() }

        assertTrue(result.cutShort)
    }

    @Test
    fun `a zip that ends inside its directory has every entry and is not cut short`() {
        val whole = threePhotos()

        val result = walk(whole.copyOf(directoryStart(whole) + 10))

        assertFalse(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `a broken header in the middle ends the walk as damage, not as cut short`() {
        val bytes = threePhotos()
        bytes[headerOf(bytes, three)] = 0

        val result = walk(bytes)

        assertTrue(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `a broken header with the file ending soon after is still damage`() {
        val whole = threePhotos()
        val third = headerOf(whole, three)
        val bytes = whole.copyOf(third + 40)
        bytes[third] = 0

        val result = walk(bytes)

        assertTrue(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `deflated data that does not inflate ends the walk as damage`() {
        val bytes = threePhotos(descriptor = Descriptor.SIZES_32)
        // The noise does not compress, so its first stored block header sits five bytes before it; type 3 is invalid.
        bytes[indexOf(bytes, two.copyOf(32)) - 5] = 0x07

        val result = walk(bytes)

        assertTrue(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `a descriptor that matches nothing in a whole zip is damage, as its end record shows`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()
        val descriptor = indexOf(bytes, two.copyOf(32)) + two.size
        bytes[descriptor + 8] = (bytes[descriptor + 8].toInt() xor 0x01).toByte()

        val result = walk(bytes)

        assertTrue(result.endedEarly)
        assertFalse(result.cutShort)
    }

    @Test
    fun `the first pass reports a cut zip as cut short too`() {
        val whole = threePhotos()
        val cut = whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000)

        val scan = TakeoutZipReader { ByteArrayInputStream(cut) }.scan()

        assertTrue(scan.walk.cutShort)
    }

    @Test
    fun `the photos before the cut still arrive whole`() {
        val whole = threePhotos()
        val cut = whole.copyOf(indexOf(whole, two.copyOf(32)) + 10_000)
        val seen = LinkedHashMap<String, ByteArray>()

        val result = TakeoutZipReader { ByteArrayInputStream(cut) }.forEachMedia { name, input -> seen[name] = input.readBytes() }

        assertEquals(listOf("a.jpg"), seen.keys.toList())
        assertArrayEquals(one, seen["a.jpg"])
        assertTrue(result.cutShort)
    }
}
