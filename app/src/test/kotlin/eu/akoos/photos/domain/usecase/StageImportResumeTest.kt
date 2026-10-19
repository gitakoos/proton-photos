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

package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.dao.ImportStagedDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.importer.FolderTestTrees
import eu.akoos.photos.data.importer.ImportDriveMatcher
import eu.akoos.photos.data.importer.ImportSource
import eu.akoos.photos.data.importer.TakeoutZipReader
import eu.akoos.photos.data.importer.ImportScanCache
import eu.akoos.photos.domain.importer.ImportFailure
import eu.akoos.photos.domain.importer.ImportFailureKind
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.ImportEventLog
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The staging pass as a long import meets it: stopped partway and started again, losing its source, and
 * reading an archive that is damaged near the end. The Android shell is real (files, the cache, the log);
 * the database and Drive are stand-ins that remember what was staged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageImportResumeTest {

    private lateinit var context: Context
    private lateinit var useCase: StageImportUseCase
    private val staged = mutableListOf<ImportStagedEntity>()
    private val zipId = "content://takeout/export.zip"
    private val user = UserId("user")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, ImportScanCache.DIR_NAME).deleteRecursively()
        ImportEventLog.reset(context.filesDir)
        staged.clear()

        val dao = mockk<ImportStagedDao>(relaxed = true)
        coEvery { dao.upsert(any<List<ImportStagedEntity>>()) } answers {
            val rows = firstArg<List<ImportStagedEntity>>()
            staged.removeAll { old -> rows.any { it.entryName == old.entryName } }
            staged += rows
        }
        coEvery { dao.entryNames(any()) } answers { staged.map { it.entryName } }
        coEvery { dao.countForZip(any()) } answers { staged.size }
        coEvery { dao.clearForZip(any()) } answers { staged.clear() }
        coEvery { dao.thumbPaths(any()) } returns emptyList()
        val drive = mockk<DrivePhotoRepository>()
        every { drive.cloudContentHash(any()) } returns null
        useCase = StageImportUseCase(
            context,
            dao,
            ImportDriveMatcher(drive, mockk<PhotoListingDao>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
        )
    }

    private fun cache() = ImportScanCache(File(context.cacheDir, ImportScanCache.DIR_NAME))

    private fun zipOf(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (i in 1..count) {
                zip.putNextEntry(ZipEntry("Takeout/p$i.jpg"))
                zip.write(ByteArray(20_000).also { Random(i.toLong()).nextBytes(it) })
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("Takeout/p1.jpg.json"))
            zip.write("""{"photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun stage(
        zip: ByteArray,
        open: () -> InputStream = { ByteArrayInputStream(zip) },
        onProgress: suspend (StageProgress) -> Unit = {},
    ) = runBlocking {
        useCase.stage(user, zipId, ImportSource(TakeoutZipReader(open), "export.zip") { zip.size.toLong() }, onProgress)
    }

    @Test
    fun `an interrupted pass continues from the saved scan and the rows already staged`() {
        val zip = zipOf(6)
        var opens = 0
        val open = { opens++; ByteArrayInputStream(zip) as InputStream }

        val first = runCatching {
            stage(zip, open) { p -> if (p.phase == StagePhase.STAGE && p.done >= 3) throw CancellationException("stop") }
        }
        assertTrue(first.exceptionOrNull() is CancellationException)
        assertEquals("what was staged before the stop is kept", 3, staged.size)
        assertEquals("one read for the scan, one for staging", 2, opens)
        assertTrue("the scan is kept for the next attempt", cache().exists(zipId))

        val seen = mutableListOf<StagePhase>()
        val result = stage(zip, open) { seen += it.phase }

        assertEquals(6, result.staged)
        assertEquals("no photo is staged twice", 6, staged.map { it.entryName }.toSet().size)
        assertEquals("the saved scan replaced the first read of the archive", 3, opens)
        assertTrue("the catch-up over the staged part is reported", StagePhase.CATCH_UP in seen)
        assertFalse(result.endedEarly)
        assertFalse("a finished pass leaves no saved scan behind", cache().exists(zipId))

        val log = ImportEventLog.text(context.filesDir)
        assertTrue(log, log.contains("stage resume"))
        assertTrue(log, log.contains("alreadyStaged=3"))
        assertTrue(log, log.contains("stage done staged=6"))
    }

    @Test
    fun `every staged row gets a date, the sidecar first and the entry time when nothing else has one`() {
        val modified = 1_623_751_200_000L
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("Takeout/p1.jpg", "Takeout/plain.jpg").forEach { name ->
                zip.putNextEntry(ZipEntry(name).apply { time = modified })
                zip.write(ByteArray(2_000).also { Random(7).nextBytes(it) })
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("Takeout/p1.jpg.json"))
            zip.write("""{"photoTakenTime":{"timestamp":"1609459200"}}""".toByteArray())
            zip.closeEntry()
        }

        stage(out.toByteArray())

        val dates = staged.associate { it.entryName to it.dateMs }
        assertEquals(1_609_459_200_000L, dates["Takeout/p1.jpg"])
        assertEquals(modified, dates["Takeout/plain.jpg"])
    }

    @Test
    fun `a folder stages the same rows as a zip of that folder`() {
        val modified = 1_623_751_200_000L
        val tmp = Files.createTempDirectory("stage-folder").toFile()
        try {
            val root = File(tmp, "Export")
            FolderTestTrees.write(
                root,
                mapOf(
                    "Takeout/Google Photos/Trip/p1.jpg" to ByteArray(2_000).also { Random(1).nextBytes(it) },
                    "Takeout/Google Photos/Trip/p1.jpg.json" to
                        """{"photoTakenTime":{"timestamp":"1609459200"},"description":"Beach"}""".toByteArray(),
                    "Family/plain.jpg" to ByteArray(2_000).also { Random(2).nextBytes(it) },
                    "Family/IMG_3.heic" to ByteArray(2_000).also { Random(3).nextBytes(it) },
                    "Family/IMG_3.mov" to ByteArray(2_000).also { Random(4).nextBytes(it) },
                ),
                modifiedMs = modified,
            )
            fun rows() = staged.map { listOf(it.entryName, it.title, it.dateMs, it.description, it.albumName, it.sizeBytes) }
                .sortedBy { it[0].toString() }

            val zip = FolderTestTrees.zipOf(root, modifiedMs = modified)
            stage(zip)
            val fromZip = rows()
            staged.clear()

            val result = runBlocking { useCase.stage(user, zipId, FolderTestTrees.source(root), {}) }

            assertEquals(4, result.staged)
            assertFalse(result.endedEarly)
            assertEquals(fromZip, rows())
            val dates = staged.associate { it.entryName to it.dateMs }
            assertEquals(1_609_459_200_000L, dates["Export/Takeout/Google Photos/Trip/p1.jpg"])
            assertEquals(modified, dates["Export/Family/plain.jpg"])
            assertEquals(setOf("Trip", "Family"), staged.mapNotNull { it.albumName }.toSet())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a pass that was not interrupted starts clean even when the same zip was staged before`() {
        val zip = zipOf(4)
        stage(zip)
        assertEquals(4, staged.size)

        val again = stage(zip)

        assertEquals(4, again.staged)
        val log = ImportEventLog.text(context.filesDir)
        assertFalse("a re-pick of a finished zip is a start, not a resume", log.contains("stage resume"))
    }

    @Test
    fun `a source that stops answering leaves the cause and the phase in the log`() {
        val zip = zipOf(5)
        val failing = {
            object : FilterInputStream(ByteArrayInputStream(zip)) {
                private var served = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served > 4_000) throw IOException("connection reset by peer")
                    return super.read(b, off, minOf(len, 1_500)).also { if (it > 0) served += it }
                }
            } as InputStream
        }

        val failure = runCatching { stage(zip, failing) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(ImportFailureKind.SOURCE_UNREADABLE, ImportFailure.kindOf(failure!!))
        val log = ImportEventLog.text(context.filesDir)
        assertTrue(log, log.contains("stage failed phase=scan SourceReadException"))
        assertFalse("the message never reaches the log", log.contains("connection reset"))
    }

    @Test
    fun `an archive that stops being readable keeps its readable part and says so`() {
        val zip = zipOf(4)
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        var third = -1
        var found = 0
        for (i in 0..zip.size - 4) {
            if (header.indices.all { zip[i + it] == header[it] } && ++found == 3) {
                third = i
                break
            }
        }
        assertTrue(third > 0)
        zip[third] = 0

        val result = stage(zip)

        assertTrue(result.endedEarly)
        assertEquals("the two photos before the damage are staged", 2, staged.size)
        assertTrue(result.readPercent in 0..99)
        assertFalse(cache().exists(zipId))
        assertTrue(ImportEventLog.text(context.filesDir).contains("endedEarly=true"))
    }
}
