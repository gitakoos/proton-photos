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
import eu.akoos.photos.data.db.entity.ImportStagedEntity
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

    private data class Sent(val name: String, val mainPhotoLinkId: String?, val extraTags: Set<Int>, val link: String)

    private lateinit var db: AppDatabase
    private lateinit var useCase: ImportTakeoutUseCase
    private val sent = mutableListOf<Sent>()
    private val userId = UserId("user-1")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = mockk<DrivePhotoRepository>(relaxed = true)
        coEvery { repo.cloudContentHash(any()) } answers { "hash-${firstArg<String>()}" }
        coEvery {
            repo.uploadFileLinked(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val item = secondArg<LocalMediaItem>()
            synchronized(sent) {
                val link = "link-${sent.size + 1}"
                sent += Sent(item.displayName, arg(5), arg(6), link)
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
        )
    }

    @After
    fun tearDown() = db.close()

    private fun zipOf(vararg names: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("bytes of $name".toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun row(name: String, liveOf: String? = null, uploaded: Boolean = false, linkId: String? = null) =
        ImportStagedEntity(
            zipId = ZIP, entryName = name, title = name.substringAfterLast('/'), dateMs = 1_700_000_000_000L,
            sizeBytes = 20, uploaded = uploaded, stagedAt = 1L, liveOf = liveOf, linkId = linkId,
        )

    private suspend fun upload(zip: ByteArray) = useCase.uploadStaged(
        userId = userId, zipId = ZIP, runId = "run-1", fileName = "export.zip", albumMode = ImportAlbumMode.NONE,
        openZip = { ByteArrayInputStream(zip) }, onProgress = {},
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
        assertEquals(3, summary.total)
        assertEquals(3, summary.imported)
        assertTrue(db.importStagedDao().allForZip(ZIP).all { it.uploaded })
        assertEquals(still.link, db.importStagedDao().allForZip(ZIP).single { it.entryName == "Trip/IMG_1.jpg" }.linkId)
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

    private companion object {
        const val ZIP = "zip-1"
    }
}
