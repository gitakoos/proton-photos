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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.CloudPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Orchestration + safety test for [WriteCloudPhotoMetadataUseCase]. The pipeline TRASHES the cloud
 * original, so these pin the ordering (upload before trash) and the guard that the original is trashed
 * ONLY after the new link has joined every album the original was in. The byte-level EXIF write is
 * stubbed by [RecordingExifRewriter]; the risk being tested is the ordering and the album safety, both
 * exercised against the shared [FakeDrivePhotoRepository].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WriteCloudPhotoMetadataUseCaseTest {

    private val userId = UserId("test-user")

    private lateinit var context: Context
    private lateinit var cloud: FakeDrivePhotoRepository
    private lateinit var accountManager: AccountManager
    private lateinit var rewriter: RecordingExifRewriter
    private lateinit var useCase: WriteCloudPhotoMetadataUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cloud = FakeDrivePhotoRepository()
        accountManager = mockk()
        every { accountManager.getPrimaryUserId() } returns flowOf(userId)
        rewriter = RecordingExifRewriter()
        useCase = WriteCloudPhotoMetadataUseCase(context, cloud, accountManager, rewriter)
    }

    private fun cloudPhoto(linkId: String, captureSec: Long = 1_600_000_000L) = CloudPhoto(
        linkId = linkId,
        shareId = "share",
        volumeId = "vol1",
        captureTime = captureSec,
        displayName = "IMG_1234.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev-$linkId",
    )

    @Test
    fun `a correction re-adds the new link to every album, keeps the name, and trashes the original`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA", "albumB"))
        val newCaptureMs = 1_700_000_000_000L

        val result = useCase(original, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertEquals(WriteCloudPhotoMetadataUseCase.Result.Success, result)
        val newLinkId = cloud.uploadedLinkIds.single()

        // 1. The new link joined EVERY album the original was a member of.
        assertEquals(listOf(newLinkId), cloud.albumMembers("albumA"))
        assertEquals(listOf(newLinkId), cloud.albumMembers("albumB"))

        // 2. uploadFile ran before deleteFiles, and the original was trashed on full success.
        assertTrue(
            "upload must precede trash",
            cloud.callLog.indexOf("UPLOAD") < cloud.callLog.indexOf("DELETE"),
        )
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })

        // 4. The chosen capture time reached the uploaded item, and the name was preserved.
        assertEquals(newCaptureMs, cloud.uploadedItems.single().dateTaken)
        assertEquals("IMG_1234.jpg", cloud.uploadedItems.single().displayName)
    }

    @Test
    fun `a failed album re-add leaves the original in place and reports failure`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        cloud.failAddPhotosOnce = true

        val result = useCase(original, newCaptureMs = 1_700_000_000_000L, location = LocationEdit.Unchanged)

        // Safety invariant 2: any album re-add miss means the original stays put.
        assertTrue("must report failure", result is WriteCloudPhotoMetadataUseCase.Result.Failed)
        assertTrue("original must NOT be trashed", cloud.photos.value.any { it.linkId == "orig" })
        assertFalse("deleteFiles must never run", cloud.callLog.contains("DELETE"))
        assertTrue("membership must not have landed", cloud.albumMembers("albumA").isEmpty())
        // The upload itself still happened (a recoverable duplicate is acceptable).
        assertEquals(1, cloud.uploadedItems.size)
    }

    @Test
    fun `an unverifiable album enumeration aborts before upload and never trashes`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        cloud.failVerifiedAlbumIdsOnce = true

        val result = useCase(original, newCaptureMs = 1_700_000_000_000L, location = LocationEdit.Unchanged)

        // The album set could not be verified complete, so the save fails BEFORE any upload and the
        // original is left completely untouched: a partial album read can never silently drop the photo.
        assertTrue("must report failure", result is WriteCloudPhotoMetadataUseCase.Result.Failed)
        assertTrue("nothing may be uploaded", cloud.uploadedItems.isEmpty())
        assertFalse("deleteFiles must never run", cloud.callLog.contains("DELETE"))
        assertTrue("original must survive", cloud.photos.value.any { it.linkId == "orig" })
    }

    @Test
    fun `a photo in no album is corrected and trashed`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)

        val result = useCase(original, newCaptureMs = 1_700_000_000_000L, location = LocationEdit.Unchanged)

        assertEquals(WriteCloudPhotoMetadataUseCase.Result.Success, result)
        assertEquals(1, cloud.uploadedItems.size)
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })
        assertTrue(cloud.callLog.contains("DELETE"))
    }

    @Test
    fun `no date and an unchanged place does nothing`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)

        val result = useCase(original, newCaptureMs = null, location = LocationEdit.Unchanged)

        assertEquals(WriteCloudPhotoMetadataUseCase.Result.NothingToDo, result)
        assertTrue("nothing may be uploaded or trashed", cloud.callLog.isEmpty())
        assertTrue(cloud.photos.value.any { it.linkId == "orig" })
    }

    @Test
    fun `a place-only edit keeps the original capture time and passes the edit to the rewriter`() = runTest {
        val original = cloudPhoto("orig", captureSec = 1_600_000_000L)
        cloud.add(original)
        val place = LocationEdit.Set(47.5, 19.05)

        val result = useCase(original, newCaptureMs = null, location = place)

        assertEquals(WriteCloudPhotoMetadataUseCase.Result.Success, result)
        val call = rewriter.calls.single()
        assertNull("date tags must not be rewritten on a place-only edit", call.writeCaptureMs)
        assertEquals("the xAttr keeps the original capture time", 1_600_000_000_000L, call.xAttrCaptureMs)
        assertEquals(place, call.location)
        // The upload item inherits the original capture time so the copy sorts where the original did.
        assertEquals(1_600_000_000_000L, cloud.uploadedItems.single().dateTaken)
    }

    @Test
    fun `a text-only edit rewrites the working file, uploads, and trashes the original`() = runTest {
        val original = cloudPhoto("orig", captureSec = 1_600_000_000L)
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))

        val result = useCase(
            original,
            newCaptureMs = null,
            location = LocationEdit.Unchanged,
            artist = "Ansel Adams",
        )

        // A text-only edit is NOT NothingToDo: the working file is rewritten and the corrected copy uploaded.
        assertEquals(WriteCloudPhotoMetadataUseCase.Result.Success, result)
        val call = rewriter.calls.single()
        assertNull("date tags must not be rewritten on a text-only edit", call.writeCaptureMs)
        assertEquals(LocationEdit.Unchanged, call.location)
        assertEquals("the artist reaches the rewriter", "Ansel Adams", call.artist)
        assertNull("an untouched tag is left null", call.description)
        assertNull("an untouched tag is left null", call.copyright)
        // The original capture time is inherited so the corrected copy sorts where the original did.
        assertEquals("the xAttr keeps the original capture time", 1_600_000_000_000L, call.xAttrCaptureMs)
        assertEquals(1_600_000_000_000L, cloud.uploadedItems.single().dateTaken)

        // Trash safety is unchanged: the new link joined the album, upload precedes trash, original gone.
        assertEquals(listOf(cloud.uploadedLinkIds.single()), cloud.albumMembers("albumA"))
        assertTrue(
            "upload must precede trash",
            cloud.callLog.indexOf("UPLOAD") < cloud.callLog.indexOf("DELETE"),
        )
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })
    }

    @Test
    fun `a resume with a recorded new link skips upload, re-adds only the not-yet-joined albums, and trashes the original`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        // The original is in two albums; a prior run already uploaded the corrected copy and joined it
        // to albumA before the process was killed, so only albumB still needs the new link.
        cloud.albumIdsByPhoto = mapOf(
            "orig" to setOf("albumA", "albumB"),
            "existing-new-link" to setOf("albumA"),
        )

        val result = useCase(
            original,
            newCaptureMs = 1_700_000_000_000L,
            location = LocationEdit.Unchanged,
            resumeUploadedLinkId = "existing-new-link",
        )

        assertEquals(WriteCloudPhotoMetadataUseCase.Result.Success, result)
        // A resume never re-uploads: the corrected copy already exists.
        assertTrue("a resume must not download or rewrite", rewriter.calls.isEmpty())
        assertTrue("a resume must not upload a second copy", cloud.uploadedItems.isEmpty())
        assertTrue(cloud.uploadedLinkIds.isEmpty())
        // Only the album the new link had NOT yet joined is re-added; the already-joined one is skipped.
        assertEquals(listOf("existing-new-link"), cloud.albumMembers("albumB"))
        assertTrue("an already-joined album is not re-added", cloud.albumMembers("albumA").isEmpty())
        assertTrue(cloud.callLog.contains("ADD:albumB"))
        assertFalse("albumA must not be re-added", cloud.callLog.contains("ADD:albumA"))
        // The original is trashed once the remaining album has joined.
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })
        assertTrue(cloud.callLog.contains("DELETE"))
    }

    /** Records the arguments each rewrite receives and returns a canned xAttr, so the orchestration is
     *  exercised without a real Android EXIF write. */
    private class RecordingExifRewriter(
        private val result: UploadXAttrMetadata = UploadXAttrMetadata(displayWidth = 4000, displayHeight = 3000),
    ) : CloudPhotoExifRewriter {

        data class Call(
            val mimeType: String,
            val writeCaptureMs: Long?,
            val xAttrCaptureMs: Long,
            val location: LocationEdit,
            val description: String?,
            val artist: String?,
            val copyright: String?,
        )

        val calls = mutableListOf<Call>()

        override fun rewrite(
            file: File,
            mimeType: String,
            writeCaptureMs: Long?,
            xAttrCaptureMs: Long,
            location: LocationEdit,
            description: String?,
            artist: String?,
            copyright: String?,
        ): UploadXAttrMetadata {
            calls += Call(mimeType, writeCaptureMs, xAttrCaptureMs, location, description, artist, copyright)
            return result
        }

        override fun xAttrFor(
            uri: String,
            mimeType: String,
            xAttrCaptureMs: Long,
            location: LocationEdit,
        ): UploadXAttrMetadata = result
    }
}
