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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.db.AppDatabase
import eu.akoos.photos.data.db.entity.ImportUploadedEntity
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.compression.CompressionSettings
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import java.io.IOException

/**
 * The one place the import decides that a photo is already in Drive. Runs on a real database with Drive's
 * content-hash derivation and the link fetch replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportDriveMatcherTest {

    private lateinit var db: AppDatabase
    private lateinit var links: LinkDetailHelpers
    private lateinit var matcher: ImportDriveMatcher
    private val user = UserId("user-1")
    private val captureMs = 1_700_000_000_000L
    private val off = CompressionSettings()
    private val photosOn = CompressionSettings(photos = true)
    private val videosOn = CompressionSettings(videos = true)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = mockk<DrivePhotoRepository>()
        every { repo.cloudContentHash(any()) } answers { "hash-${firstArg<String>()}" }
        coEvery { repo.getVolumeId(any()) } returns "vol-1"
        links = mockk()
        coEvery { links.batchFetchLinkDetails(any(), any(), any(), any()) } returns emptyMap()
        matcher = ImportDriveMatcher(repo, db.photoListingDao(), db.importUploadedDao(), links)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun listed(
        linkId: String,
        name: String = "other.jpg",
        captureSec: Long = 1L,
        contentHash: String? = null,
        userId: String = user.id,
    ) = db.photoListingDao().insertAll(
        listOf(
            PhotoListingEntity(
                linkId = linkId, shareId = "s", volumeId = "vol-1", userId = userId, captureTime = captureSec,
                displayName = name, mimeType = "image/jpeg", sizeBytes = 10, revisionId = "r",
                contentHash = contentHash,
            ),
        ),
    )

    private suspend fun ledger(linkId: String, raw: String, userId: String? = user.id, undone: Boolean = false) =
        db.importUploadedDao().insert(
            ImportUploadedEntity(runId = "run-0", linkId = linkId, sha1 = "sent", sourceSha1 = raw, userId = userId, undone = undone),
        )

    private suspend fun find(
        sha1s: List<String> = listOf("raw"),
        name: String = "IMG_1.jpg",
        mime: String = "image/jpeg",
        settings: CompressionSettings = off,
        strip: Boolean = false,
        confirm: Boolean = true,
    ) = matcher.find(
        user,
        ImportDriveMatcher.Entry(sha1s, "raw", name, mime, captureMs),
        settings,
        strip,
        confirm,
    )

    private fun live(linkId: String, state: Int) = BatchLinkDto(link = LinkCoreDto(linkId = linkId, type = 2, state = state))

    @Test
    fun `the exact bytes of any key are a hit`() = runBlocking {
        listed("link-stamped", contentHash = "hash-stamped")
        assertEquals("link-stamped", find(sha1s = listOf("raw", "stamped")))
        listed("link-raw", contentHash = "hash-raw")
        assertEquals("link-raw", find(sha1s = listOf("raw", "stamped")))
        assertNull(find(sha1s = listOf("unknown")))
    }

    @Test
    fun `another account's photo is not a hit`() = runBlocking {
        listed("link-1", contentHash = "hash-raw", userId = "user-2")
        assertNull(find())
    }

    @Test
    fun `a ledger hit needs the same account and a link that was not undone`() = runBlocking {
        listed("link-mine")
        ledger("link-other", "raw", userId = "user-2")
        ledger("link-undone", "raw", undone = true)
        listed("link-other")
        listed("link-undone")
        assertNull(find())

        ledger("link-mine", "raw")
        assertEquals("link-mine", find())
    }

    @Test
    fun `a ledger link the library does not hold counts while Drive reports it live`() = runBlocking {
        ledger("link-1", "raw")
        coEvery { links.batchFetchLinkDetails(user, "vol-1", listOf("link-1"), any()) } returns
            mapOf("link-1" to live("link-1", 1))
        assertEquals("link-1", find())

        coEvery { links.batchFetchLinkDetails(any(), any(), any(), any()) } returns mapOf("link-1" to live("link-1", 2))
        assertNull("a trashed link is not a hit", find())

        coEvery { links.batchFetchLinkDetails(any(), any(), any(), any()) } returns emptyMap()
        assertNull("a link Drive no longer has is not a hit", find())
    }

    @Test
    fun `a failed link check throws instead of guessing`() = runBlocking {
        ledger("link-1", "raw")
        coEvery { links.batchFetchLinkDetails(any(), any(), any(), any()) } throws IOException("offline")
        val thrown = runCatching { find() }.exceptionOrNull()
        assertTrue(thrown is ImportLinkCheckException)
    }

    @Test
    fun `at stage time any recorded link counts without asking Drive`() = runBlocking {
        ledger("link-1", "raw")
        assertEquals("link-1", find(confirm = false))
        coVerify(exactly = 0) { links.batchFetchLinkDetails(any(), any(), any(), any()) }
    }

    @Test
    fun `a same-name twin of the same second counts only while the backup rewrites the bytes`() = runBlocking {
        listed("link-twin", name = "IMG_1.jpg", captureSec = captureMs / 1000)
        assertNull("compression off", find(settings = off))
        assertEquals("link-twin", find(settings = photosOn))
        assertEquals("strip rewrites the bytes too", "link-twin", find(strip = true))
        assertNull("video compression does not rewrite a photo", find(settings = videosOn))
    }

    @Test
    fun `a twin in another second or under another name is not a hit`() = runBlocking {
        listed("link-twin", name = "IMG_1.jpg", captureSec = captureMs / 1000 + 1)
        listed("link-named", name = "IMG_9.jpg", captureSec = captureMs / 1000)
        assertNull(find(settings = photosOn))
    }

    @Test
    fun `the twin is found under the name the re-encode gives it`() = runBlocking {
        listed("link-jpg", name = "IMG_2.JPG", captureSec = captureMs / 1000)
        listed("link-mp4", name = "clip.mp4", captureSec = captureMs / 1000)

        assertEquals("link-jpg", find(name = "IMG_2.HEIC", mime = "image/heic", settings = photosOn))
        assertNull(find(name = "IMG_2.HEIC", mime = "image/heic", settings = off))
        assertEquals("link-mp4", find(name = "clip.mov", mime = "video/quicktime", settings = videosOn))
        assertNull("photo compression leaves a video's name", find(name = "clip.mov", mime = "video/quicktime", settings = photosOn))
    }
}
