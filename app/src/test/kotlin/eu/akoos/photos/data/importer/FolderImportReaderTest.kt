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

import eu.akoos.photos.domain.importer.ImportFailure
import eu.akoos.photos.domain.importer.ImportFailureKind
import eu.akoos.photos.domain.importer.TakeoutSidecar
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A picked folder read as an export. The central promise is parity: a folder and a zip of that folder give
 * the same entries, sidecars and albums, so every step after the reader treats them alike.
 */
class FolderImportReaderTest {

    private lateinit var tmp: File
    private lateinit var root: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("folder-import").toFile()
        root = File(tmp, "Export").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun sidecar(seconds: Long, description: String? = null): ByteArray =
        ("""{"photoTakenTime":{"timestamp":"$seconds"}""" +
            (description?.let { ""","description":"$it"""" } ?: "") + "}").toByteArray()

    private fun exportTree() = FolderTestTrees.write(
        root,
        mapOf(
            "Takeout/Google Photos/Trip/IMG_1.jpg" to "one".toByteArray(),
            "Takeout/Google Photos/Trip/IMG_1.jpg.json" to sidecar(1_609_459_200, "Beach"),
            "Takeout/Google Photos/Photos from 2020/IMG_2.jpg" to "two".toByteArray(),
            "Takeout/Google Photos/Photos from 2020/IMG_2.jpg.supplemental-metadata.json" to sidecar(1_577_836_800),
            "Takeout/Google Photos/Trip/metadata.json" to """{"title":"Trip"}""".toByteArray(),
            "Ente/Family/a.heic" to "three".toByteArray(),
            "Ente/Family/a.mov" to "four".toByteArray(),
            "Ente/Family/metadata/a.heic.json" to sidecar(1_600_000_000),
            "loose.png" to "five".toByteArray(),
            "notes.txt" to "not media".toByteArray(),
        ),
    )

    private fun names(reader: ImportSourceReader): List<String> {
        val out = mutableListOf<String>()
        reader.forEachMediaEntry { entry, _ -> out += entry.name }
        return out
    }

    @Test
    fun `a folder and a zip of it give the same sidecars, media, entry names and albums`() {
        exportTree()
        val folder = FolderTestTrees.reader(root)
        val zip = TakeoutZipReader { ByteArrayInputStream(FolderTestTrees.zipOf(root)) }

        val folderScan = folder.scan()
        val zipScan = zip.scan()
        assertEquals(zipScan.sidecars, folderScan.sidecars)
        assertEquals(3, folderScan.sidecars.size)
        assertEquals(zipScan.mediaCount, folderScan.mediaCount)
        assertEquals(5, folderScan.mediaCount)

        val folderNames = names(folder)
        val zipNames = names(zip)
        assertEquals(zipNames.toSet(), folderNames.toSet())
        assertTrue(folderNames.all { it.startsWith("Export/") })
        assertEquals(
            zipNames.map(TakeoutSidecar::albumFolderOf).toSet(),
            folderNames.map(TakeoutSidecar::albumFolderOf).toSet(),
        )
        assertEquals(setOf("Trip", "Family", "Export", null), folderNames.map(TakeoutSidecar::albumFolderOf).toSet())
        assertEquals(
            "Beach",
            TakeoutSidecar.lookup(folderScan.sidecars, "Export/Takeout/Google Photos/Trip/IMG_1.jpg")?.description,
        )
        val folderPairs = LivePhotoPairing.stillByVideo(folderNames.map { LivePhotoPairing.Entry(it, null) })
        assertEquals(LivePhotoPairing.stillByVideo(zipNames.map { LivePhotoPairing.Entry(it, null) }), folderPairs)
        assertEquals(mapOf("Export/Ente/Family/a.mov" to "Export/Ente/Family/a.heic"), folderPairs)
    }

    @Test
    fun `each media entry carries its bytes and the file's modified time`() {
        val modified = 1_623_751_200_000L
        FolderTestTrees.write(root, mapOf("Trip/IMG_1.jpg" to "pixels".toByteArray()), modifiedMs = modified)

        val seen = mutableListOf<Pair<MediaEntry, ByteArray>>()
        FolderTestTrees.reader(root).forEachMediaEntry { entry, input -> seen += entry to input.readBytes() }

        assertEquals(1, seen.size)
        assertEquals(MediaEntry("Export/Trip/IMG_1.jpg", modified), seen[0].first)
        assertArrayEquals("pixels".toByteArray(), seen[0].second)
    }

    @Test
    fun `hidden files and folders are left out`() {
        FolderTestTrees.write(
            root,
            mapOf(
                "a.jpg" to "a".toByteArray(),
                ".hidden.jpg" to "h".toByteArray(),
                ".thumbnails/t.jpg" to "t".toByteArray(),
                "Trip/.trashed-1700000000-b.jpg" to "b".toByteArray(),
            ),
        )

        val reader = FolderTestTrees.reader(root)

        assertEquals(listOf("Export/a.jpg"), names(reader))
        assertEquals(1, reader.scan().mediaCount)
    }

    @Test
    fun `the progress reaches the listed total and the walk is never short`() {
        exportTree()
        val reader = FolderTestTrees.reader(root)
        val total = reader.totalBytes()
        val ticks = mutableListOf<Long>()

        val scan = reader.scan { ticks += it }
        val walk = reader.forEachMediaEntry(onBytes = { ticks += it }) { _, _ -> }

        assertEquals(root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, total)
        assertEquals(total, ticks.last())
        assertEquals(total, walk.bytesRead)
        assertEquals(total, scan.walk.bytesRead)
        assertFalse(walk.endedEarly)
        assertFalse(walk.endedShort(total))
        assertEquals(0, walk.damagedEntries)
    }

    @Test
    fun `a file that vanished after the listing is skipped and counted`() {
        exportTree()
        val reader = FolderTestTrees.reader(root)
        reader.scan()
        assertTrue(File(root, "loose.png").delete())

        val seen = mutableListOf<String>()
        val walk = reader.forEachMediaEntry { entry, _ -> seen += entry.name }

        assertEquals(1, walk.damagedEntries)
        assertEquals(4, seen.size)
        assertFalse(seen.any { it.endsWith("loose.png") })
    }

    @Test
    fun `a file that fails partway through is skipped while the folder still answers`() {
        exportTree()
        val reader = FolderImportReader(
            list = { FolderTree.list(root.name, root.path, FolderTestTrees::children) },
            open = { entry ->
                val file = File(entry.handle).inputStream()
                if (!entry.name.endsWith("a.heic")) {
                    file
                } else {
                    object : FilterInputStream(file) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("I/O error")
                    }
                }
            },
            rootReachable = { true },
        )

        val seen = mutableListOf<String>()
        val walk = reader.forEachMediaEntry { entry, input -> input.readBytes(); seen += entry.name }

        assertEquals(1, walk.damagedEntries)
        assertEquals(4, seen.size)
    }

    @Test
    fun `a folder that is gone ends the walk as a source failure`() {
        exportTree()
        val reader = FolderTestTrees.reader(root)
        reader.scan()
        assertTrue(root.deleteRecursively())

        val failure = runCatching { reader.forEachMediaEntry { _, _ -> } }.exceptionOrNull()

        assertTrue(failure is SourceReadException)
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(failure!!))
    }

    @Test
    fun `a revoked grant while listing is a source failure`() {
        val reader = FolderImportReader(
            list = { throw SecurityException("Permission Denial") },
            open = { throw AssertionError("nothing is opened") },
            rootReachable = { false },
        )

        val failure = runCatching { reader.scan() }.exceptionOrNull()

        assertTrue(failure is SourceReadException)
        assertTrue(failure!!.cause?.cause is SecurityException)
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(failure))
        assertEquals("an unknown size does not throw", -1L, ImportSource(reader, "Export") { reader.totalBytes() }.totalBytes())
    }

    @Test
    fun `a failure of the caller's own work is not taken for a missing file`() {
        exportTree()
        val reader = FolderTestTrees.reader(root)

        val failure = runCatching {
            reader.forEachMediaEntry { _, _ -> throw IOException("write failed: ENOSPC (No space left on device)") }
        }.exceptionOrNull()

        assertTrue(failure is IOException && failure !is SourceReadException)
        assertEquals(ImportFailureKind.STORAGE_FULL, ImportFailure.kindOf(failure!!))
    }

    @Test
    fun `the folder is listed once per reader and each opened file is closed`() {
        exportTree()
        var listings = 0
        val open = mutableListOf<InputStream>()
        val closed = mutableListOf<InputStream>()
        val reader = FolderImportReader(
            list = { listings++; FolderTree.list(root.name, root.path, FolderTestTrees::children) },
            open = { entry ->
                object : FilterInputStream(File(entry.handle).inputStream()) {
                    override fun close() {
                        closed += this
                        super.close()
                    }
                }.also { open += it }
            },
            rootReachable = { true },
        )

        reader.totalBytes()
        reader.scan()
        reader.forEachMediaEntry { _, input -> input.close() }

        assertEquals(1, listings)
        assertEquals("the four sidecars, then the five media", 4 + 5, open.size)
        assertTrue(open.all { it in closed })
    }

    @Test
    fun `a folder whose provider gives no name is named after its document id`() {
        assertEquals("Camera", SafTreeLister.fallbackName("primary:DCIM/Camera"))
        assertEquals("Photos", SafTreeLister.fallbackName("1234-5678:Photos"))
        assertEquals("Import", SafTreeLister.fallbackName("primary:"))
        assertNull(MediaEntry("x.jpg", null).modifiedMs)
    }
}
