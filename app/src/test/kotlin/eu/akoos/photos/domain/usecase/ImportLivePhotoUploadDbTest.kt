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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.db.AppDatabase
import androidx.datastore.preferences.core.edit
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.importer.FolderTestTrees
import eu.akoos.photos.data.importer.ImportDriveMatcher
import eu.akoos.photos.data.importer.ImportSource
import eu.akoos.photos.data.importer.TakeoutZipReader
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.AlbumListEventBus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Live Photo in an import is a still and a video that Drive keeps as one photo: the still goes up with the
 * Live Photo tag, then the video goes up joined to the still's link. These tests run the real upload pass over
 * a small archive and a real database, with the Drive upload replaced by a recorder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportLivePhotoUploadDbTest {

    private data class Sent(
        val name: String,
        val mainPhotoLinkId: String?,
        val extraTags: Set<Int>,
        val link: String,
        val dateTaken: Long = 0L,
    )

    private lateinit var db: AppDatabase
    private lateinit var useCase: ImportTakeoutUseCase
    private val sent = mutableListOf<Sent>()
    private val userId = UserId("user-1")
    private var failVideoUploads = 0

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { ctx.settingsDataStore.edit { it.clear() } }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = mockk<DrivePhotoRepository>(relaxed = true)
        coEvery { repo.cloudContentHash(any()) } answers { "hash-${firstArg<String>()}" }
        coEvery {
            repo.uploadFileLinked(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val item = secondArg<LocalMediaItem>()
            if (item.displayName.endsWith(".mp4") && failVideoUploads > 0) {
                failVideoUploads--
                throw IOException("upload failed")
            }
            synchronized(sent) {
                val link = "link-${sent.size + 1}"
                sent += Sent(item.displayName, arg(5), arg(6), link, item.dateTaken)
                link
            }
        }
        useCase = ImportTakeoutUseCase(
            context = ctx,
            drivePhotoRepository = repo,
            writeLocalPhotoMetadataUseCase = mockk(relaxed = true),
            importStagedDao = db.importStagedDao(),
            importHistoryDao = db.importHistoryDao(),
            importUploadedDao = db.importUploadedDao(),
            importAlbumMemberDao = db.importAlbumMemberDao(),
            cloudAlbumDao = db.cloudAlbumDao(),
            photoListingDao = db.photoListingDao(),
            albumListEvents = AlbumListEventBus(),
            appDatabase = db,
            compressionStep = UploadCompressionStep(ctx, UploadStructuralStripper(ctx), mockk(relaxed = true)),
            driveMatcher = ImportDriveMatcher(repo, db.photoListingDao(), db.importUploadedDao(), mockk(relaxed = true)),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun zipOf(vararg names: String): ByteArray = zipWithTimes(*names.map { it to null }.toTypedArray())

    private fun zipWithTimes(vararg entries: Pair<String, Long?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, time) ->
                zip.putNextEntry(ZipEntry(name).apply { if (time != null) this.time = time })
                zip.write("bytes of $name".toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun row(
        name: String,
        liveOf: String? = null,
        uploaded: Boolean = false,
        linkId: String? = null,
        dateMs: Long? = 1_700_000_000_000L,
    ) =
        ImportStagedEntity(
            zipId = ZIP, entryName = name, title = name.substringAfterLast('/'), dateMs = dateMs,
            sizeBytes = 20, uploaded = uploaded, stagedAt = 1L, liveOf = liveOf, linkId = linkId,
        )

    private suspend fun upload(zip: ByteArray, onProgress: suspend (ImportProgress) -> Unit = {}) = useCase.uploadStaged(
        userId = userId, zipId = ZIP, runId = "run-1", albumMode = ImportAlbumMode.NONE,
        source = ImportSource(TakeoutZipReader { ByteArrayInputStream(zip) }, "export.zip") { zip.size.toLong() },
        onProgress = onProgress,
    )

    @Test
    fun `the video goes up after its still and joined to the still's link`() = runBlocking {
        val zip = zipOf("Trip/IMG_1.jpg", "Trip/IMG_1.mp4", "Trip/solo.jpg")
        db.importStagedDao().upsert(
            listOf(row("Trip/IMG_1.jpg"), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg"), row("Trip/solo.jpg")),
        )

        val summary = upload(zip)

        val still = sent.single { it.name == "IMG_1.jpg" }
        val video = sent.single { it.name == "IMG_1.mp4" }
        val solo = sent.single { it.name == "solo.jpg" }
        assertNull("the still joins nothing", still.mainPhotoLinkId)
        assertEquals("the still carries the Live Photo tag", setOf(3), still.extraTags)
        assertEquals("the video joins the still", still.link, video.mainPhotoLinkId)
        assertTrue("the video carries no category of its own", video.extraTags.isEmpty())
        assertNull(solo.mainPhotoLinkId)
        assertTrue("a plain photo has no extra tag", solo.extraTags.isEmpty())
        assertTrue("the video is sent after the still", sent.indexOf(video) > sent.indexOf(still))
        assertEquals("a Live Photo counts once", 2, summary.total)
        assertEquals(2, summary.imported)
        assertEquals(0, summary.failed)
        val history = db.importHistoryDao().byRun("run-1")!!
        assertEquals(2, history.total)
        assertEquals(2, history.uploaded)
        assertEquals("the undo ledger keeps the video's own row", 3, db.importUploadedDao().pendingByRun("run-1").size)
        assertTrue(db.importStagedDao().allForZip(ZIP).all { it.uploaded })
        assertEquals(still.link, db.importStagedDao().allForZip(ZIP).single { it.entryName == "Trip/IMG_1.jpg" }.linkId)
    }

    @Test
    fun `a picked folder uploads like a zip of it, the video joined to its still`() = runBlocking {
        val tmp = Files.createTempDirectory("upload-folder").toFile()
        try {
            val root = File(tmp, "Export")
            FolderTestTrees.write(
                root,
                listOf("Trip/IMG_1.jpg", "Trip/IMG_1.mp4", "Trip/solo.jpg")
                    .associateWith { "bytes of Export/$it".toByteArray() },
            )
            db.importStagedDao().upsert(
                listOf(
                    row("Export/Trip/IMG_1.jpg"),
                    row("Export/Trip/IMG_1.mp4", liveOf = "Export/Trip/IMG_1.jpg"),
                    row("Export/Trip/solo.jpg"),
                ),
            )

            val summary = useCase.uploadStaged(
                userId = userId, zipId = ZIP, runId = "run-1", albumMode = ImportAlbumMode.NONE,
                source = FolderTestTrees.source(root), onProgress = {},
            )

            val still = sent.single { it.name == "IMG_1.jpg" }
            val video = sent.single { it.name == "IMG_1.mp4" }
            assertEquals(setOf(3), still.extraTags)
            assertEquals(still.link, video.mainPhotoLinkId)
            assertEquals(2, summary.imported)
            assertEquals(0, summary.skipped)
            assertTrue(db.importStagedDao().allForZip(ZIP).all { it.uploaded })
            assertEquals("Export", db.importHistoryDao().byRun("run-1")?.fileName)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a video whose still was sent before a kill is still joined to it`() = runBlocking {
        val zip = zipOf("Trip/IMG_1.jpg", "Trip/IMG_1.mp4")
        db.importStagedDao().upsert(
            listOf(
                row("Trip/IMG_1.jpg", uploaded = true, linkId = "link-earlier"),
                row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg"),
            ),
        )

        upload(zip)

        assertEquals("only the video is sent", listOf("IMG_1.mp4"), sent.map { it.name })
        assertEquals("link-earlier", sent.single().mainPhotoLinkId)
    }

    @Test
    fun `a video whose still did not go up stays pending`() = runBlocking {
        val zip = zipOf("Trip/IMG_1.mp4")
        db.importStagedDao().upsert(listOf(row("Trip/IMG_1.jpg"), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg")))

        upload(zip)

        assertTrue("nothing is sent for a video with no still link", sent.isEmpty())
        assertTrue(db.importStagedDao().pendingUpload(ZIP).any { it.entryName == "Trip/IMG_1.mp4" })
    }

    @Test
    fun `a video whose still is in Drive without a known link goes up as a video of its own`() = runBlocking {
        val zip = zipOf("Trip/IMG_1.mp4")
        db.importStagedDao().upsert(
            listOf(row("Trip/IMG_1.jpg", uploaded = true), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg")),
        )

        upload(zip)

        assertEquals(listOf("IMG_1.mp4"), sent.map { it.name })
        assertNull(sent.single().mainPhotoLinkId)
        assertTrue(db.importStagedDao().pendingUpload(ZIP).isEmpty())
    }

    @Test
    fun `excluding the still excludes its video, and the review shows one photo`() = runBlocking {
        val dao = db.importStagedDao()
        dao.upsert(listOf(row("Trip/IMG_1.jpg"), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg"), row("Trip/solo.jpg")))

        assertEquals(
            "the review lists the still and the plain photo, not the video",
            listOf("Trip/IMG_1.jpg", "Trip/solo.jpg"),
            dao.observeForZip(ZIP).first().map { it.entryName }.sorted(),
        )
        assertEquals(2, dao.observeIncludedCount(ZIP).first())

        dao.setExcluded(ZIP, listOf("Trip/IMG_1.jpg"), true)

        assertEquals(listOf("Trip/solo.jpg"), dao.pendingUpload(ZIP).map { it.entryName })
        assertEquals(1, dao.observeIncludedCount(ZIP).first())

        dao.setExcluded(ZIP, listOf("Trip/IMG_1.jpg"), false)
        assertEquals(3, dao.pendingUpload(ZIP).size)
    }

    @Test
    fun `a row staged without a date is dated by its zip entry, never the epoch`() = runBlocking {
        val modified = 1_623_751_200_000L
        val zip = zipWithTimes("Trip/meme.jpg" to modified)
        db.importStagedDao().upsert(listOf(row("Trip/meme.jpg", dateMs = null)))

        val summary = upload(zip)

        assertEquals(1, summary.imported)
        assertEquals(modified, sent.single().dateTaken)
        assertEquals(modified, db.importStagedDao().allForZip(ZIP).single().dateMs)
        assertEquals(modified, db.importUploadedDao().pendingByRun("run-1").single().dateMs)
    }

    @Test
    fun `a dateless video takes the date its dateless still was given`() = runBlocking {
        val stillTime = 1_623_751_200_000L
        val zip = zipWithTimes("Trip/IMG_1.jpg" to stillTime, "Trip/IMG_1.mp4" to stillTime + 7_200_000L)
        db.importStagedDao().upsert(
            listOf(row("Trip/IMG_1.jpg", dateMs = null), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg", dateMs = null)),
        )

        upload(zip)

        assertEquals(stillTime, sent.single { it.name == "IMG_1.jpg" }.dateTaken)
        assertEquals(stillTime, sent.single { it.name == "IMG_1.mp4" }.dateTaken)
        assertTrue(db.importStagedDao().allForZip(ZIP).all { it.dateMs != null })
    }

    @Test
    fun `progress counts a Live Photo once`() = runBlocking {
        val zip = zipOf("Trip/IMG_1.jpg", "Trip/IMG_1.mp4", "Trip/solo.jpg")
        db.importStagedDao().upsert(
            listOf(row("Trip/IMG_1.jpg"), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg"), row("Trip/solo.jpg")),
        )
        val progress = mutableListOf<ImportProgress>()

        upload(zip) { synchronized(progress) { progress += it } }

        assertEquals(2, progress.maxOf { it.total })
        assertEquals(2, progress.last().done)
        assertTrue(progress.all { it.done <= it.total })
    }

    @Test
    fun `a failed video keeps its still, and the next run joins it`() = runBlocking {
        val dao = db.importStagedDao()
        val zip = zipOf("Trip/IMG_1.jpg", "Trip/IMG_1.mp4")
        dao.upsert(listOf(row("Trip/IMG_1.jpg"), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg")))
        failVideoUploads = 1

        val first = upload(zip)
        dao.clearResolved(ZIP)

        val still = sent.single()
        assertEquals("IMG_1.jpg", still.name)
        assertEquals(1, first.total)
        assertEquals("an unfinished Live Photo is not an import", 0, first.imported)
        assertEquals(0, first.skipped)
        assertEquals(1, first.failed)
        val kept = dao.allForZip(ZIP).single { it.entryName == "Trip/IMG_1.jpg" }
        assertEquals("the still's row survives with its link", still.link, kept.linkId)
        assertEquals(listOf(ZIP), dao.zipsWithPending())
        assertEquals(1, dao.observeIncludedCount(ZIP).first())
        assertEquals(listOf("Trip/IMG_1.jpg"), dao.observeForZip(ZIP).first().map { it.entryName })

        val second = upload(zip)
        dao.clearResolved(ZIP)

        val video = sent.single { it.name == "IMG_1.mp4" }
        assertEquals("only the video is sent again", 2, sent.size)
        assertEquals(still.link, video.mainPhotoLinkId)
        assertEquals(1, second.total)
        assertEquals(1, second.imported)
        assertTrue(dao.allForZip(ZIP).isEmpty())
    }

    @Test
    fun `an excluded video does not keep its uploaded still`() = runBlocking {
        val dao = db.importStagedDao()
        dao.upsert(
            listOf(
                row("Trip/IMG_1.jpg", uploaded = true, linkId = "link-earlier"),
                row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg").copy(excluded = true),
            ),
        )

        dao.clearResolved(ZIP)

        assertTrue(dao.allForZip(ZIP).isEmpty())
    }

    @Test
    fun `a video whose still row is gone is listed and goes up on its own`() = runBlocking {
        val dao = db.importStagedDao()
        val zip = zipOf("Trip/IMG_1.mp4")
        dao.upsert(listOf(row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg")))
        assertEquals(listOf("Trip/IMG_1.mp4"), dao.observeForZip(ZIP).first().map { it.entryName })
        assertEquals(1, dao.observeIncludedCount(ZIP).first())

        val summary = upload(zip)

        assertEquals(listOf("IMG_1.mp4"), sent.map { it.name })
        assertNull(sent.single().mainPhotoLinkId)
        assertEquals(1, summary.total)
        assertEquals(1, summary.imported)
        assertTrue(dao.pendingUpload(ZIP).isEmpty())
    }

    private companion object {
        const val ZIP = "zip-1"
    }
}
