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
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.ImportStagedEntity
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.importer.ImportDriveMatcher
import eu.akoos.photos.data.importer.ImportSource
import eu.akoos.photos.data.importer.TakeoutZipReader
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.upload.UploadCompressionStep
import eu.akoos.photos.data.upload.compression.VideoUploadCompression
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.compression.CompressionOutcome
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.util.AlbumListEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The import's compression and its duplicate guards, on the real upload pass over a small archive and a
 * real database. The compression itself is replaced by a step that writes a smaller file, and the Drive
 * upload by a recorder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportCompressionUploadDbTest {

    private data class Sent(val item: LocalMediaItem, val sha1: String, val uploadUri: String, val link: String)

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var step: UploadCompressionStep
    private lateinit var links: LinkDetailHelpers
    private lateinit var writer: WriteLocalPhotoMetadataUseCase
    private lateinit var useCase: ImportTakeoutUseCase
    private val sent = mutableListOf<Sent>()
    private val compressed = mutableListOf<File>()
    private val userId = UserId("user-1")

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        runBlocking {
            ctx.settingsDataStore.edit {
                it.clear()
                it[SettingsKeys.COMPRESS_ON_UPLOAD] = true
            }
        }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = mockk<DrivePhotoRepository>(relaxed = true)
        every { repo.cloudContentHash(any()) } answers { "hash-${firstArg<String>()}" }
        coEvery { repo.getVolumeId(any()) } returns "vol-1"
        coEvery {
            repo.uploadFileLinked(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            synchronized(sent) {
                val link = "link-${sent.size + 1}"
                sent += Sent(secondArg(), thirdArg(), arg(3), link)
                link
            }
        }
        step = mockk()
        every { step.compressesVideo(any(), any()) } returns false
        every { step.photo(any(), any(), any(), any()) } answers {
            val out = File.createTempFile("compressed_", ".jpg", ctx.cacheDir)
                .apply { writeBytes("small ${compressed.size + 1}".toByteArray()) }
                .also { compressed += it }
            UploadCompressionStep.PhotoResult(out, CompressionOutcome.COMPRESSED, null, 100L, false)
        }
        links = mockk()
        coEvery { links.batchFetchLinkDetails(any(), any(), any(), any()) } answers {
            thirdArg<List<String>>().associateWith { BatchLinkDto(link = LinkCoreDto(linkId = it, type = 2, state = 1)) }
        }
        writer = mockk(relaxed = true)
        useCase = ImportTakeoutUseCase(
            context = ctx,
            drivePhotoRepository = repo,
            writeLocalPhotoMetadataUseCase = writer,
            importStagedDao = db.importStagedDao(),
            importHistoryDao = db.importHistoryDao(),
            importUploadedDao = db.importUploadedDao(),
            importAlbumMemberDao = db.importAlbumMemberDao(),
            cloudAlbumDao = db.cloudAlbumDao(),
            photoListingDao = db.photoListingDao(),
            albumListEvents = AlbumListEventBus(),
            appDatabase = db,
            compressionStep = step,
            driveMatcher = ImportDriveMatcher(repo, db.photoListingDao(), db.importUploadedDao(), links),
        )
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking { ctx.settingsDataStore.edit { it.clear() } }
    }

    private fun bytesOf(name: String) = "bytes of $name".toByteArray()

    private fun zipOf(vararg names: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytesOf(name))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun row(name: String, liveOf: String? = null, description: String? = null) = ImportStagedEntity(
        zipId = ZIP, entryName = name, title = name.substringAfterLast('/'), dateMs = 1_700_000_000_000L,
        sizeBytes = 20, uploaded = false, stagedAt = 1L, liveOf = liveOf, description = description,
    )

    private suspend fun upload(zip: ByteArray, runId: String = "run-1") = useCase.uploadStaged(
        userId = userId, zipId = ZIP, runId = runId, albumMode = ImportAlbumMode.NONE,
        source = ImportSource(TakeoutZipReader { ByteArrayInputStream(zip) }, "export.zip") { zip.size.toLong() },
        onProgress = {},
    )

    @Test
    fun `a compressed heic goes up as a jpeg and the ledger keeps both digests`() = runBlocking {
        db.importStagedDao().upsert(listOf(row("Trip/IMG_1.heic", description = "Beach")))

        val summary = upload(zipOf("Trip/IMG_1.heic"))

        val one = sent.single()
        val compressedBytes = "small 1".toByteArray()
        assertEquals("IMG_1.jpg", one.item.displayName)
        assertEquals("image/jpeg", one.item.mimeType)
        assertEquals(compressedBytes.size.toLong(), one.item.sizeBytes)
        assertEquals(sha1(compressedBytes), one.sha1)
        assertTrue("the compressed file is sent", one.uploadUri.contains("compressed_"))
        assertTrue("the item keeps the extracted original as its source", one.item.uri.contains("import_"))
        val ledger = db.importUploadedDao().pendingByRun("run-1").single()
        assertEquals(sha1(compressedBytes), ledger.sha1)
        assertEquals(sha1(bytesOf("Trip/IMG_1.heic")), ledger.sourceSha1)
        assertEquals(userId.id, ledger.userId)
        assertEquals(1, summary.imported)
        coVerify { writer.writeDescriptiveText(match { it.contains("compressed_") }, any(), any(), any()) }
        assertTrue("the compressed temp is gone", compressed.none { it.exists() })
    }

    @Test
    fun `neither half of a Live Photo is compressed`() = runBlocking {
        every { step.compressesVideo(any(), any()) } returns true
        db.importStagedDao().upsert(
            listOf(row("Trip/IMG_1.jpg"), row("Trip/IMG_1.mp4", liveOf = "Trip/IMG_1.jpg"), row("Trip/solo.jpg")),
        )

        upload(zipOf("Trip/IMG_1.jpg", "Trip/IMG_1.mp4", "Trip/solo.jpg"))

        verify(exactly = 1) { step.photo(any(), any(), any(), any()) }
        coVerify(exactly = 0) { step.video(any(), any(), any(), any(), any()) }
        val still = sent.single { it.item.displayName == "IMG_1.jpg" }
        val video = sent.single { it.item.displayName == "IMG_1.mp4" }
        assertEquals(sha1(bytesOf("Trip/IMG_1.jpg")), still.sha1)
        assertEquals(sha1(bytesOf("Trip/IMG_1.mp4")), video.sha1)
        assertTrue(sent.single { it.item.displayName == "solo.jpg" }.uploadUri.contains("compressed_"))
    }

    @Test
    fun `a compressed video goes up as an mp4`() = runBlocking {
        every { step.compressesVideo(any(), any()) } returns true
        coEvery { step.video(any(), any(), any(), any(), any()) } answers {
            val out = File.createTempFile("videocompress_", ".mp4", ctx.cacheDir).apply { writeBytes("small clip".toByteArray()) }
            VideoUploadCompression.Attempt(out, CompressionOutcome.COMPRESSED, null)
        }
        db.importStagedDao().upsert(listOf(row("Trip/clip.mov")))

        upload(zipOf("Trip/clip.mov"))

        val one = sent.single()
        assertEquals("clip.mp4", one.item.displayName)
        assertEquals("video/mp4", one.item.mimeType)
        assertEquals(sha1("small clip".toByteArray()), one.sha1)
    }

    @Test
    fun `staging and running the same archive again uploads nothing`() = runBlocking {
        val zip = zipOf("Trip/a.jpg", "Trip/b.heic")
        val rows = listOf(row("Trip/a.jpg"), row("Trip/b.heic"))
        db.importStagedDao().upsert(rows)
        assertEquals(2, upload(zip).imported)

        // What a fresh stage of the same archive leaves behind: the same rows, none of them uploaded.
        db.importStagedDao().clearForZip(ZIP)
        db.importStagedDao().upsert(rows)
        val again = upload(zip, runId = "run-2")

        assertEquals("nothing more is sent", 2, sent.size)
        assertEquals(0, again.imported)
        assertEquals(2, again.alreadyInDrive)
        assertEquals(
            sent.map { it.link }.toSet(),
            db.importUploadedDao().pendingByRun("run-2").map { it.linkId }.toSet(),
        )
    }

    @Test
    fun `a captioned photo whose archive bytes are in Drive is not uploaded again`() = runBlocking {
        val name = "Trip/IMG_5.jpg"
        // The caption write changes the bytes, as it does on a device.
        coEvery { writer.writeDescriptiveText(any(), any(), any(), any()) } coAnswers {
            File(URI(firstArg<String>())).appendBytes("caption".toByteArray())
            MetadataWriteResult.Success
        }
        db.photoListingDao().insertAll(
            listOf(
                PhotoListingEntity(
                    linkId = "link-backup", shareId = "s", volumeId = "vol-1", userId = userId.id, captureTime = 1L,
                    displayName = "IMG_5.jpg", mimeType = "image/jpeg", sizeBytes = 20, revisionId = "r",
                    contentHash = "hash-${sha1(bytesOf(name))}",
                ),
            ),
        )
        db.importStagedDao().upsert(listOf(row(name, description = "Sunset")))

        val summary = upload(zipOf(name))

        coVerify { writer.writeDescriptiveText(any(), any(), any(), any()) }
        assertTrue("nothing is sent", sent.isEmpty())
        assertEquals(1, summary.alreadyInDrive)
        assertEquals("link-backup", db.importUploadedDao().pendingByRun("run-1").single().linkId)
    }

    private companion object {
        const val ZIP = "zip-1"
    }
}
