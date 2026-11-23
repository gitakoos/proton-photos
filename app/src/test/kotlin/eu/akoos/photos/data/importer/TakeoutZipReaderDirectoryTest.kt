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
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The reader on a zip it can read at any position: it goes through the central directory, so every entry is
 * found by its own offset, and an entry whose own bytes are unusable is skipped alone while the entries after
 * it still arrive.
 */
class TakeoutZipReaderDirectoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun noise(seed: Long) = ByteArray(20_000).also { Random(seed).nextBytes(it) }

    private val one = noise(1)
    private val two = noise(2)
    private val three = noise(3)

    private fun randomAccessReader(bytes: ByteArray) =
        TakeoutZipReader(openStream = { ByteArrayInputStream(bytes) }, openRandomAccess = { ByteArrayZipSource(bytes) })

    private fun streamOnlyReader(bytes: ByteArray) = TakeoutZipReader { ByteArrayInputStream(bytes) }

    private fun delivered(reader: TakeoutZipReader): Pair<Map<String, ByteArray>, WalkResult> {
        val seen = LinkedHashMap<String, ByteArray>()
        val walk = reader.forEachMedia { name, input -> seen[name] = input.readBytes() }
        return seen to walk
    }

    @Test
    fun `a stored entry with a data descriptor reads with the entries around it`() {
        val bytes = HandBuiltZip()
            .add("Kamera-Uploads/a.jpg", one)
            .add("Kamera-Uploads/b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("Kamera-Uploads/c.jpg", three)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("Kamera-Uploads/a.jpg", "Kamera-Uploads/b.jpg", "Kamera-Uploads/c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["Kamera-Uploads/b.jpg"])
        assertEquals(0, walk.damagedEntries)
        assertFalse(walk.endedEarly)
        assertFalse(walk.endedShort(bytes.size.toLong()))
    }

    @Test
    fun `the same zip read front to back reads every entry`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(streamOnlyReader(bytes))

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
        assertFalse(walk.endedEarly)
        assertFalse(walk.endedShort(bytes.size.toLong()))
    }

    @Test
    fun `a small entry with a zip64 data descriptor reads in full`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, descriptor = Descriptor.SIZES_64)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a name that is not UTF-8 is read as code page 437`() {
        val latinName = byteArrayOf(0x4B, 0x2F, 0x9A.toByte(), 0x62, 0x2E, 0x6A, 0x70, 0x67)
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("ignored", two, utf8Flag = false, rawName = latinName)
            .add("c.jpg", three)
            .build()

        val (seen, _) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "K/Üb.jpg", "c.jpg"), seen.keys.toList())
    }

    @Test
    fun `an entry in a compression method the reader lacks is skipped and counted`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.DEFLATE64)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
        assertFalse(walk.endedEarly)
    }

    @Test
    fun `an entry whose compressed bytes are broken is skipped and the next still arrives`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, corruptPayload = true)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(three, seen["c.jpg"])
        assertEquals(1, walk.damagedEntries)
    }

    @Test
    fun `an entry that fails its checksum is skipped and the next still arrives`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, recordedCrc = 0x12345678L)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(1, walk.damagedEntries)
    }

    @Test
    fun `a broken file that is not a photo is never read and never counted`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("notes.txt", two, method = HandBuiltZip.DEFLATE64)
            .add("c.jpg", three)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a photo the first pass only counts is not decoded there`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.DEFLATE64)
            .build()

        val scan = randomAccessReader(bytes).scan()

        assertEquals(2, scan.mediaCount)
        assertEquals(0, scan.walk.damagedEntries)
    }

    @Test
    fun `a damaged directory record hands the read to the front to back walk`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("b.jpg", two).add("c.jpg", three).build()
        val secondRecord = bytes.size - 22 - (46 + 5) * 2
        bytes[secondRecord] = 0

        val (seen, _) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), seen.keys.toList())
    }

    @Test
    fun `a zip64 end record leads to the directory`() {
        val bytes = HandBuiltZip(zip64End = true)
            .add("a.jpg", one)
            .add("b.mp4", two)
            .build()

        val (seen, walk) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "b.mp4"), seen.keys.toList())
        assertEquals(2, walk.entries)
    }

    @Test
    fun `bytes in front of the zip do not move the entries`() {
        val zip = HandBuiltZip().add("a.jpg", one).add("c.jpg", three).build()
        val bytes = ByteArray(1_000) { 0x11 } + zip

        val (seen, _) = delivered(randomAccessReader(bytes))

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
        assertArrayEquals(three, seen["c.jpg"])
    }

    @Test
    fun `the first pass counts every readable media entry and indexes the sidecars`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("a.jpg.json", """{"title":"a.jpg","photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray())
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .add("c.jpg", three)
            .build()

        val scan = randomAccessReader(bytes).scan()

        assertEquals(3, scan.mediaCount)
        assertEquals(1, scan.sidecars.size)
    }

    @Test
    fun `the entry time comes from the extended timestamp when there is one`() {
        val bytes = HandBuiltZip().add("a.jpg", one, mtimeSeconds = 1_502_800_000L).build()
        val times = ArrayList<Long?>()

        randomAccessReader(bytes).forEachMediaEntry { entry, _ -> times += entry.modifiedMs }

        assertEquals(listOf<Long?>(1_502_800_000_000L), times)
    }

    @Test
    fun `progress reaches the whole file`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("c.jpg", three).build()
        val seen = ArrayList<Long>()

        val walk = randomAccessReader(bytes).forEachMedia(onBytes = { seen += it }) { _, input -> input.readBytes() }

        assertEquals(2, seen.size)
        assertEquals(bytes.size.toLong(), walk.bytesRead)
    }

    @Test
    fun `a file cut short before its directory is read front to back`() {
        val whole = HandBuiltZip().add("a.jpg", one).add("b.jpg", two).add("c.jpg", three).build()
        val cut = whole.copyOf(45_000)

        val (seen, _) = delivered(randomAccessReader(cut))

        assertEquals(listOf("a.jpg", "b.jpg"), seen.keys.toList())
    }

    @Test
    fun `a source with no random access is read front to back`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("c.jpg", three).build()
        val reader = TakeoutZipReader(
            openStream = { ByteArrayInputStream(bytes) },
            openRandomAccess = { throw IOException("Illegal seek") },
        )

        val (seen, _) = delivered(reader)

        assertEquals(listOf("a.jpg", "c.jpg"), seen.keys.toList())
    }

    @Test
    fun `a source that stops answering mid entry is not swallowed`() {
        val bytes = HandBuiltZip().add("a.jpg", one).add("c.jpg", three).build()
        val failing = object : ZipRandomSource {
            override val size: Long = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position in 100L until 30_000L) throw IOException("connection reset")
                return ByteArrayZipSource(bytes).readAt(position, buffer, offset, length)
            }
            override fun close() = Unit
        }
        val reader = TakeoutZipReader(openStream = { ByteArrayInputStream(bytes) }, openRandomAccess = { failing })

        assertThrows(SourceReadException::class.java) { reader.forEachMedia { _, input -> input.readBytes() } }
    }

    @Test
    fun `a zip on disk reads through a file channel`() {
        val bytes = HandBuiltZip()
            .add("a.jpg", one)
            .add("b.jpg", two, method = HandBuiltZip.STORED, descriptor = Descriptor.SIZES_32)
            .build()
        val file = tmp.newFile("export.zip").apply { writeBytes(bytes) }
        val reader = TakeoutZipReader(
            openStream = { file.inputStream() },
            openRandomAccess = { RandomAccessFile(file, "r").let { ChannelZipSource(it.channel, it) } },
        )

        val (seen, _) = delivered(reader)

        assertEquals(listOf("a.jpg", "b.jpg"), seen.keys.toList())
        assertArrayEquals(two, seen["b.jpg"])
    }
}
