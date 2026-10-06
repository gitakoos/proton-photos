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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the reader behaves on the archives a real multi-gigabyte export eventually produces: one with a
 * damaged entry, one the stream cannot step past, and a source that stops answering. A damaged archive
 * must hand over everything readable before the damage instead of throwing it away; a source failure
 * must still surface so the caller can retry it.
 */
class TakeoutZipReaderResilienceTest {

    private fun noise(seed: Long, size: Int) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun reader(bytes: ByteArray) = TakeoutZipReader { ByteArrayInputStream(bytes) }

    private val one = noise(1, 20_000)
    private val two = noise(2, 20_000)
    private val three = noise(3, 20_000)

    private fun threePhotos() = buildZip(listOf("a.jpg" to one, "b.jpg" to two, "c.jpg" to three))

    @Test
    fun `both passes report how far into the file they have read`() {
        val bytes = threePhotos()
        val seen = mutableListOf<Long>()

        val walk = reader(bytes).forEachMedia(onBytes = { seen += it }) { _, input -> input.readBytes() }

        assertEquals(3, seen.size)
        assertEquals("progress never goes backwards", seen.sorted(), seen)
        assertTrue(seen.last() in 1..bytes.size.toLong())
        assertFalse(walk.endedEarly)
        assertFalse(walk.endedShort(bytes.size.toLong()))

        val scanSeen = mutableListOf<Long>()
        reader(bytes).scan { scanSeen += it }
        assertEquals(3, scanSeen.size)
    }

    @Test
    fun `an entry that fails its checksum is skipped and the rest still arrive`() {
        val bytes = threePhotos()
        // The payload is incompressible, so it sits in the zip verbatim and one flipped byte leaves a
        // stream that decodes but fails its CRC.
        val start = indexOf(bytes, two.copyOf(32))
        assertTrue("the payload sits in the zip verbatim", start >= 0)
        val at = start + 100
        bytes[at] = (bytes[at].toInt() xor 0x55).toByte()

        val delivered = mutableListOf<String>()
        val walk = reader(bytes).forEachMedia { name, input ->
            input.readBytes()
            delivered += name
        }

        assertEquals("a.jpg arrived before the damage", "a.jpg", delivered.first())
        assertTrue("the damage is counted, not thrown", walk.damagedEntries + (if (walk.endedEarly) 1 else 0) >= 1)
    }

    @Test
    fun `a header the stream cannot step past ends the walk with everything before it`() {
        val bytes = threePhotos()
        val secondPayload = indexOf(bytes, two.copyOf(32))
        assertTrue("the payload sits in the zip verbatim", secondPayload >= 0)
        val thirdHeader = indexOf(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04), from = secondPayload)
        assertTrue(thirdHeader > 0)
        bytes[thirdHeader] = 0

        val delivered = mutableListOf<String>()
        val walk = reader(bytes).forEachMedia { name, input ->
            input.readBytes()
            delivered += name
        }

        assertEquals(listOf("a.jpg", "b.jpg"), delivered)
        // The stream reads a bad header as "no more entries", so the walk itself ends without a fault; the
        // file going on far past where the entries stopped is what gives the damage away.
        assertTrue(walk.endedShort(bytes.size.toLong()))

        val scan = reader(bytes).scan()
        assertEquals("the first pass counts what was readable", 2, scan.mediaCount)
        assertTrue(scan.walk.endedShort(bytes.size.toLong()))
        assertFalse("an intact archive is not flagged", reader(threePhotos()).scan().walk.endedShort(threePhotos().size.toLong()))
    }

    @Test
    fun `a source that stops answering is not swallowed`() {
        val bytes = threePhotos()
        val failing = TakeoutZipReader {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                private var served = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served > 6_000) throw IOException("connection reset")
                    return super.read(b, off, minOf(len, 2_000)).also { if (it > 0) served += it }
                }
            } as InputStream
        }

        assertThrows(IOException::class.java) { failing.forEachMedia { _, input -> input.readBytes() } }
        assertThrows(IOException::class.java) { failing.scan() }
    }

    @Test
    fun `cancellation from the caller passes straight through`() {
        val reader = reader(threePhotos())
        assertThrows(CancellationException::class.java) {
            reader.forEachMedia { _, _ -> throw CancellationException("stop") }
        }
    }
}
