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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.repository.drive.UploadXAttrMetadata
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Orchestration + safety test for [WriteSyncedPhotoMetadataUseCase]. A Synced edit replaces the cloud
 * copy with the already-edited device file and then TRASHES the original, so these pin the ordering
 * (upload → album re-add → re-pair → trash) and the invariant that the original is trashed ONLY after a
 * verified upload AND a full album re-add, and that any failure restores the row to SYNCED on the
 * original link without ever creating a duplicate. The byte-level xAttr build is stubbed by
 * [FakeExifRewriter]; the risk under test is the ordering, the album safety, and the sync-row state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WriteSyncedPhotoMetadataUseCaseTest {

    private val userId = UserId("test-user")
    private val originalCaptureSec = 1_600_000_000L
    private val newCaptureMs = 1_700_000_000_000L

    private lateinit var context: Context
    private lateinit var cloud: FakeDrivePhotoRepository
    private lateinit var sync: FakeSyncStateRepository
    private lateinit var accountManager: AccountManager
    private lateinit var rewriter: FakeExifRewriter
    private lateinit var useCase: WriteSyncedPhotoMetadataUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cloud = FakeDrivePhotoRepository()
        sync = FakeSyncStateRepository()
        accountManager = mockk()
        every { accountManager.getPrimaryUserId() } returns flowOf(userId)
        rewriter = FakeExifRewriter()
        useCase = WriteSyncedPhotoMetadataUseCase(context, cloud, accountManager, sync, rewriter)
    }

    private fun cloudPhoto(linkId: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share",
        volumeId = "vol1",
        captureTime = originalCaptureSec,
        displayName = "IMG_1234.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        thumbnailUrl = null,
        revisionId = "rev-$linkId",
    )

    private fun videoCloudPhoto(linkId: String, durationMs: Long? = null) = CloudPhoto(
        linkId = linkId,
        shareId = "share",
        volumeId = "vol1",
        captureTime = originalCaptureSec,
        displayName = "VID_1234.mp4",
        mimeType = "video/mp4",
        sizeBytes = 8192L,
        thumbnailUrl = null,
        revisionId = "rev-$linkId",
        durationMs = durationMs,
    )

    private fun syncedRow(uri: String, cloudId: String, localHash: String) = SyncState(
        localUri = uri,
        cloudFileId = cloudId,
        localHash = localHash,
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 0L,
        backedUpAtMs = 0L,
        sizeBytes = 0L,
    )

    /** Register readable bytes for [uri] on the real Robolectric resolver so computeSha1 resolves a
     *  real, non-null hash. Consumed once per invoke. Returns the bare SHA-1 hex of those bytes. */
    private fun registerContent(uri: String, bytes: ByteArray): String {
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(android.net.Uri.parse(uri), ByteArrayInputStream(bytes))
        return MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a synced correction uploads the edited file, re-adds every album, re-pairs the row, and trashes the original`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA", "albumB"))
        val deviceUri = "content://media/42"
        val expectedSha1 = registerContent(deviceUri, "edited-device-bytes".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        val newLinkId = cloud.uploadedLinkIds.single()
        assertNotEquals("the replacement is a new cloud link", "orig", newLinkId)

        // 1. The new link joined EVERY album the original was in.
        assertEquals(listOf(newLinkId), cloud.albumMembers("albumA"))
        assertEquals(listOf(newLinkId), cloud.albumMembers("albumB"))

        // 2. uploadFile ran before deleteFiles, and the original was trashed on full success.
        assertTrue(
            "upload must precede trash",
            cloud.callLog.indexOf("UPLOAD") < cloud.callLog.indexOf("DELETE"),
        )
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })

        // 3. The device file's own uri and the chosen capture time reached the upload; the content
        //    digest is the edited device bytes' hash (Drive's ContentHash derives from these).
        assertEquals(deviceUri, cloud.uploadedItems.single().uri)
        assertEquals(newCaptureMs, cloud.uploadedItems.single().dateTaken)
        assertEquals(expectedSha1, cloud.photos.value.first { it.linkId == newLinkId }.contentHash)

        // 4. The row re-paired to the new link with the fresh hash, queue cleared.
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(newLinkId, row.cloudFileId)
        assertEquals(expectedSha1, row.localHash)
        assertFalse("the editor queue intent must be cleared once paired", row.queued)
    }

    @Test
    fun `a failed upload restores the row to synced on the original link and never trashes`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        val deviceUri = "content://media/7"
        registerContent(deviceUri, "x".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        cloud.failUploadOnce = true

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertTrue("must report failure", result is WriteSyncedPhotoMetadataUseCase.Result.Failed)
        assertFalse("deleteFiles must never run", cloud.callLog.contains("DELETE"))
        assertTrue("original must survive", cloud.photos.value.any { it.linkId == "orig" })
        // The row is back to SYNCED on the ORIGINAL link with the ORIGINAL hash, queue cleared → no
        // selector re-uploads it, so no duplicate is ever created.
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals("orig", row.cloudFileId)
        assertEquals("old-hash", row.localHash)
        assertFalse(row.queued)
    }

    @Test
    fun `a cancellation mid-upload restores the row to synced on the original link and never trashes`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/8"
        registerContent(deviceUri, "c".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        cloud.cancelUploadOnce = true

        var thrown: Throwable? = null
        try {
            useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }

        // Cancellation propagates to the caller (never swallowed into a Result)...
        assertTrue("cancellation must propagate", thrown is kotlinx.coroutines.CancellationException)
        // ...but the row is restored first, so it is never left stuck at UPLOADING for the recovery to
        // re-upload as a duplicate: back to SYNCED on the ORIGINAL link, original hash, queue cleared.
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals("orig", row.cloudFileId)
        assertEquals("old-hash", row.localHash)
        assertFalse(row.queued)
        // The original cloud copy is untouched: no upload landed and no trash ran.
        assertFalse("deleteFiles must never run", cloud.callLog.contains("DELETE"))
        assertTrue("original must survive", cloud.photos.value.any { it.linkId == "orig" })
        assertTrue("no album membership should have landed", cloud.albumMembers("albumA").isEmpty())
    }

    @Test
    fun `a failed album re-add keeps the original, uploads a recoverable duplicate, and restores the row`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/9"
        registerContent(deviceUri, "y".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        cloud.failAddPhotosOnce = true

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertTrue("must report failure", result is WriteSyncedPhotoMetadataUseCase.Result.Failed)
        assertFalse("deleteFiles must never run", cloud.callLog.contains("DELETE"))
        assertTrue("original must survive", cloud.photos.value.any { it.linkId == "orig" })
        assertTrue("membership must not have landed", cloud.albumMembers("albumA").isEmpty())
        // The upload itself still happened (a recoverable duplicate is acceptable).
        assertEquals(1, cloud.uploadedItems.size)
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals("orig", row.cloudFileId)
        assertFalse(row.queued)
    }

    @Test
    fun `a transient album re-add failure is flagged transient`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/60"
        registerContent(deviceUri, "t".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        // A network-level album-add blip: the swallowed per-album miss must carry the transient signal
        // out through the re-add's aggregate Failed (the audit's exact "album blip" case).
        cloud.addPhotosErrorOnce = java.io.IOException("album add reset")

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertTrue("must report failure", result is WriteSyncedPhotoMetadataUseCase.Result.Failed)
        assertTrue(
            "a transient album blip must be flagged transient",
            (result as WriteSyncedPhotoMetadataUseCase.Result.Failed).transient,
        )
    }

    @Test
    fun `a permanent album re-add failure is not flagged transient`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/61"
        registerContent(deviceUri, "n".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        // failAddPhotosOnce throws an IllegalStateException: no network signal, so it stays permanent.
        cloud.failAddPhotosOnce = true

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertTrue("must report failure", result is WriteSyncedPhotoMetadataUseCase.Result.Failed)
        assertFalse(
            "a non-network album failure stays permanent",
            (result as WriteSyncedPhotoMetadataUseCase.Result.Failed).transient,
        )
    }

    @Test
    fun `an unverifiable album enumeration aborts before upload, leaves the row synced, and never trashes`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/16"
        registerContent(deviceUri, "u".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        cloud.failVerifiedAlbumIdsOnce = true

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertTrue("must report failure", result is WriteSyncedPhotoMetadataUseCase.Result.Failed)
        assertTrue("nothing may be uploaded", cloud.uploadedItems.isEmpty())
        assertFalse("deleteFiles must never run", cloud.callLog.contains("DELETE"))
        assertTrue("original must survive", cloud.photos.value.any { it.linkId == "orig" })
        // The enumeration throws before the row is ever marked UPLOADING, so it stays SYNCED on the
        // original link with the original hash: nothing stuck for the recovery to re-upload as a copy.
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals("orig", row.cloudFileId)
        assertEquals("old-hash", row.localHash)
        assertFalse(row.queued)
    }

    @Test
    fun `a date-only edit passes the new time to the rewriter and the upload item`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        val deviceUri = "content://media/11"
        registerContent(deviceUri, "d".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        val call = rewriter.calls.single()
        assertEquals(newCaptureMs, call.xAttrCaptureMs)
        assertEquals(LocationEdit.Unchanged, call.location)
        assertEquals(deviceUri, call.uri)
        assertEquals(newCaptureMs, cloud.uploadedItems.single().dateTaken)
    }

    @Test
    fun `a place-only edit keeps the original capture time and passes the place`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        val deviceUri = "content://media/12"
        registerContent(deviceUri, "p".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        val place = LocationEdit.Set(47.5, 19.05)

        val result = useCase(original, deviceUri, newCaptureMs = null, location = place)

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        val call = rewriter.calls.single()
        assertEquals("the xAttr keeps the original capture time", originalCaptureSec * 1000L, call.xAttrCaptureMs)
        assertEquals(place, call.location)
        // The upload item inherits the original capture time so the copy sorts where the original did.
        assertEquals(originalCaptureSec * 1000L, cloud.uploadedItems.single().dateTaken)
    }

    @Test
    fun `a combined date-and-place edit passes both to the rewriter`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        val deviceUri = "content://media/13"
        registerContent(deviceUri, "b".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        val place = LocationEdit.Set(10.0, 20.0)

        val result = useCase(original, deviceUri, newCaptureMs = newCaptureMs, location = place)

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        val call = rewriter.calls.single()
        assertEquals(newCaptureMs, call.xAttrCaptureMs)
        assertEquals(place, call.location)
        assertEquals(newCaptureMs, cloud.uploadedItems.single().dateTaken)
    }

    @Test
    fun `a text-only edit re-uploads the edited device file and trashes the original`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/15"
        val expectedSha1 = registerContent(deviceUri, "text-edited-device-bytes".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))

        val result = useCase(
            original, deviceUri,
            newCaptureMs = null, location = LocationEdit.Unchanged,
            artist = "Ansel Adams",
        )

        // A text-only edit is NOT NothingToDo: the device file (already carrying the tag) is re-uploaded.
        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        val newLinkId = cloud.uploadedLinkIds.single()
        assertEquals(deviceUri, cloud.uploadedItems.single().uri)
        // Only text changed, so the xAttr and the upload item keep the original capture time.
        assertEquals(originalCaptureSec * 1000L, cloud.uploadedItems.single().dateTaken)
        assertEquals(expectedSha1, cloud.photos.value.first { it.linkId == newLinkId }.contentHash)
        // The tag is NOT written again here (the device path already wrote it in place): the rewriter is
        // only asked for the xAttr, with the place left unchanged.
        assertEquals(LocationEdit.Unchanged, rewriter.calls.single().location)

        // Trash safety is unchanged: album re-added, upload precedes trash, original gone, row re-paired.
        assertEquals(listOf(newLinkId), cloud.albumMembers("albumA"))
        assertTrue(
            "upload must precede trash",
            cloud.callLog.indexOf("UPLOAD") < cloud.callLog.indexOf("DELETE"),
        )
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(newLinkId, row.cloudFileId)
        assertFalse("the editor queue intent must be cleared once paired", row.queued)
    }

    @Test
    fun `no date and an unchanged place does nothing`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        val deviceUri = "content://media/14"
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))

        val result = useCase(original, deviceUri, newCaptureMs = null, location = LocationEdit.Unchanged)

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.NothingToDo, result)
        assertTrue("nothing may be uploaded or trashed", cloud.callLog.isEmpty())
        assertTrue(cloud.photos.value.any { it.linkId == "orig" })
        assertNull("no rewrite on a no-op", rewriter.calls.firstOrNull())
    }

    @Test
    fun `a resume with a recorded new link skips upload, re-adds only the not-yet-joined albums, re-pairs, and trashes the original`() = runTest {
        val original = cloudPhoto("orig")
        cloud.add(original)
        // The original is in two albums; a prior run already uploaded the corrected device file and
        // joined it to albumA before the process was killed, so only albumB still needs the new link.
        cloud.albumIdsByPhoto = mapOf(
            "orig" to setOf("albumA", "albumB"),
            "existing-new-link" to setOf("albumA"),
        )
        val deviceUri = "content://media/50"
        val expectedSha1 = registerContent(deviceUri, "resumed-device-bytes".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))

        val result = useCase(
            original, deviceUri,
            newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged,
            resumeUploadedLinkId = "existing-new-link",
        )

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        // A resume never re-uploads: the corrected device file was already uploaded on the prior run.
        assertTrue("a resume must not read the device file for the xAttr", rewriter.calls.isEmpty())
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
        // The row is re-paired to the recorded new link with the fresh device hash, queue cleared.
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals("existing-new-link", row.cloudFileId)
        assertEquals(expectedSha1, row.localHash)
        assertFalse("the editor queue intent must be cleared once paired", row.queued)
    }

    @Test
    fun `a synced video date correction carries the real duration and dimensions and trashes the original`() = runTest {
        val original = videoCloudPhoto("orig")
        cloud.add(original)
        cloud.albumIdsByPhoto = mapOf("orig" to setOf("albumA"))
        val deviceUri = "content://media/video/70"
        registerContent(deviceUri, "edited-video-bytes".toByteArray())
        sync.seed(syncedRow(deviceUri, "orig", localHash = "old-hash"))
        // The rewriter reports the device video's real stream dims + duration (its xAttr video branch),
        // which the use case must thread onto the upload item so the replaced copy is a real video.
        val videoRewriter = FakeExifRewriter(
            UploadXAttrMetadata(displayWidth = 1920, displayHeight = 1080, durationMillis = 5_000L),
        )
        val videoUseCase = WriteSyncedPhotoMetadataUseCase(context, cloud, accountManager, sync, videoRewriter)

        val result = videoUseCase(original, deviceUri, newCaptureMs = newCaptureMs, location = LocationEdit.Unchanged)

        assertEquals(WriteSyncedPhotoMetadataUseCase.Result.Success, result)
        // The rewriter is asked only for the xAttr over the already-edited device file (video mime).
        val call = videoRewriter.calls.single()
        assertEquals("video/mp4", call.mimeType)
        assertEquals(deviceUri, call.uri)
        // The uploaded item is the device file, carrying the corrected date and the real duration + dims.
        val item = cloud.uploadedItems.single()
        assertEquals(deviceUri, item.uri)
        assertEquals(newCaptureMs, item.dateTaken)
        assertEquals(5_000L, item.duration)
        assertEquals(1920, item.width)
        assertEquals(1080, item.height)
        // Trash safety is unchanged for a video: album re-added, upload precedes trash, original gone, and
        // the row re-paired to the new link.
        assertEquals(listOf(cloud.uploadedLinkIds.single()), cloud.albumMembers("albumA"))
        assertTrue(
            "upload must precede trash",
            cloud.callLog.indexOf("UPLOAD") < cloud.callLog.indexOf("DELETE"),
        )
        assertTrue("original must be trashed", cloud.photos.value.none { it.linkId == "orig" })
        val row = sync.getByUri(deviceUri)!!
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(cloud.uploadedLinkIds.single(), row.cloudFileId)
    }

    /** Records the arguments each xAttr build receives and returns a canned xAttr, so the orchestration
     *  is exercised without a real Android EXIF read. */
    private class FakeExifRewriter(
        private val result: UploadXAttrMetadata = UploadXAttrMetadata(displayWidth = 4000, displayHeight = 3000),
    ) : CloudPhotoExifRewriter {

        data class Call(val uri: String, val mimeType: String, val xAttrCaptureMs: Long, val location: LocationEdit)

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
        ): UploadXAttrMetadata = result

        override fun xAttrFor(
            uri: String,
            mimeType: String,
            xAttrCaptureMs: Long,
            location: LocationEdit,
        ): UploadXAttrMetadata {
            calls += Call(uri, mimeType, xAttrCaptureMs, location)
            return result
        }
    }

    /**
     * Minimal in-memory [SyncStateRepository] for these tests. Only the members the use case touches are
     * real: [getByUri], [upsert] (mirroring the DAO's partial upsert that preserves the queue columns),
     * [markQueued], and [clearQueuedForSynced]. Every other member is an unused no-op / empty result.
     */
    private class FakeSyncStateRepository : eu.akoos.photos.domain.repository.SyncStateRepository {

        private val rows = mutableMapOf<String, SyncState>()

        fun seed(state: SyncState) { rows[state.localUri] = state }

        override suspend fun getByUri(localUri: String): SyncState? = rows[localUri]

        override suspend fun upsert(state: SyncState, userId: UserId) {
            // The real DAO's upsert replaces the domain columns but never the queue columns, so a
            // round-tripped SyncState (whose mapper drops queued/queueSource) leaves an existing row's
            // queue intent intact. Model that so markQueued / clearQueuedForSynced own the queue flag.
            val prev = rows[state.localUri]
            rows[state.localUri] = state.copy(
                queued = prev?.queued ?: false,
                queueSource = prev?.queueSource,
            )
        }

        override suspend fun markQueued(localUri: String, source: String, at: Long) {
            rows[localUri]?.let { rows[localUri] = it.copy(queued = true, queueSource = source) }
        }

        override suspend fun clearQueuedForSynced(localUri: String) {
            rows[localUri]?.let { rows[localUri] = it.copy(queued = false, queueSource = null) }
        }

        // ── Unused members: never reached by the use case under test ─────────────────────
        override fun observeAll(userId: UserId): Flow<List<SyncState>> =
            MutableStateFlow(emptyList<SyncState>()).asStateFlow()
        override fun countPendingUploads(userId: UserId): Flow<Int> =
            MutableStateFlow(0).asStateFlow()
        override suspend fun upsertAll(states: List<SyncState>, userId: UserId) {}
        override suspend fun updateDomainColumnsIfNotSyncedWithCloud(state: SyncState, userId: UserId): Int = 0
        override suspend fun demoteToLocalIfCloudIdMatches(localUri: String, expectedCloudId: String): Int = 0
        override suspend fun updateStatusAndDeleteLocal(localUri: String, newStatus: SyncStatus) {}
        override suspend fun getByCloudId(cloudFileId: String): SyncState? = null
        override suspend fun cloudPairedLinkIds(userId: UserId, localUris: List<String>): Map<String, String> = emptyMap()
        override suspend fun claimForUpload(localUri: String): Int = 0
        override suspend fun resetStaleUploadingClaims() {}
        override suspend fun getSyncedBefore(userId: UserId, timestampMs: Long): List<SyncState> = emptyList()
        override suspend fun getVaulted(userId: UserId): List<SyncState> = emptyList()
        override suspend fun cloudIdsWithLivePairing(userId: UserId): Set<String> = emptySet()
        override suspend fun clearHiddenForCloudId(cloudFileId: String) {}
        override suspend fun deleteLocalOnlyByUris(localUris: List<String>) {}
        override suspend fun delete(localUri: String) {}
        override suspend fun clearQueued(localUri: String) {}
        override suspend fun getQueueSource(localUri: String): String? = rows[localUri]?.queueSource
        override suspend fun clearManualQueue(): Int = 0
    }
}
