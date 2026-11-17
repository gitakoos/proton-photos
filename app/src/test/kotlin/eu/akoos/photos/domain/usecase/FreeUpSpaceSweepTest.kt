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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.api.dto.LinkFileActiveRevisionDto
import eu.akoos.photos.data.api.dto.LinkFilePropertiesDto
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.data.api.dto.LinkPhotoDto
import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.repository.DriveCopyXAttr
import eu.akoos.photos.domain.repository.FreeUpUncheckedStore
import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The scheduled sweep deletes a device copy only for a photo whose Drive link is confirmed active
 * right now. The sync state a row carries can be stale: reconcile stamps the cloud state as checked
 * even when no listing walk ran before it, so a photo already gone from Drive can still read SYNCED,
 * and deleting its device copy would destroy the last one. A lookup that cannot be answered must
 * delete nothing.
 */
class FreeUpSpaceSweepTest {

    private val userId = UserId("u1")
    private val volumeId = "vol-1"
    private lateinit var repo: SyncStateRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var linkDetailHelpers: LinkDetailHelpers
    private lateinit var resolver: ContentResolver
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var useCase: FreeUpSpaceUseCase
    private val unchecked = InMemoryFreeUpUncheckedStore()
    private val shortCopies = InMemoryShortCloudCopyStore()
    private val claimed = FakeClaimedSizes()

    private fun eligible(i: Int) = SyncState(
        localUri = "content://media/external/images/media/$i",
        cloudFileId = "cloud-$i",
        localHash = "hash-$i",
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = 1L,
        sizeBytes = 1024L,
        // The device bytes the backup read, as every row backed up since they are recorded holds them.
        originalHash = "hash-$i",
    )

    private fun linkAt(linkId: String, state: Int) =
        BatchLinkDto(link = LinkCoreDto(linkId = linkId, type = 2, state = state))

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        repo = mockk(relaxed = true)
        cloudRepo = mockk(relaxed = true)
        linkDetailHelpers = mockk(relaxed = true)
        localRepo = mockk(relaxed = true)
        useCase = FreeUpSpaceUseCase(context, repo, cloudRepo, linkDetailHelpers, localRepo, unchecked, shortCopies, claimed)
        // By default every device file is exactly what was backed up: its size, and the SHA-1 its row recorded.
        coEvery { localRepo.fileStamp(any()) } answers { deviceFile(firstArg(), sizeBytes = 1024L) }
        coEvery { localRepo.sha1(any()) } answers { "hash-" + firstArg<String>().substringAfterLast('/') }
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
        every { resolver.delete(any(), any(), any()) } returns 1
    }

    /** What the device reports for the file at [uri]; the uri only names which file the stub stands for. */
    @Suppress("UNUSED_PARAMETER")
    private fun deviceFile(uri: String, sizeBytes: Long, modifiedSec: Long = 0L) = DeviceFileStamp(sizeBytes, modifiedSec)

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `the sweep reclaims the rows Drive confirms and keeps the ones it does not`() = runTest {
        val rows = (1..3).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            "cloud-1" to linkAt("cloud-1", state = 1),
            "cloud-2" to linkAt("cloud-2", state = 2),
        )

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 1) { repo.updateStatusAndDeleteLocal(rows[0].localUri, SyncStatus.CLOUD_ONLY) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(rows[1].localUri, any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(rows[2].localUri, any()) }
        verify(exactly = 1) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `a lookup that fails deletes nothing`() = runTest {
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(eligible(1), eligible(2))
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } throws IOException("offline")

        var thrown: IOException? = null
        try {
            useCase.sweepConfirmed(userId, Long.MAX_VALUE)
        } catch (e: IOException) {
            thrown = e
        }

        assertTrue("the failure must reach the caller so the sweep is retried", thrown != null)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
    }

    @Test
    fun `a photo gone from Drive is never reclaimed on the strength of its sync row`() = runTest {
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(eligible(1))
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns emptyMap()

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `each slice of 50 is checked just before it is deleted`() = runTest {
        val rows = (1..120).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        val events = mutableListOf<String>()
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } answers {
            val ids = thirdArg<List<String>>()
            events += "check ${ids.size}"
            ids.associateWith { linkAt(it, state = 1) }
        }
        every { resolver.delete(any(), any(), any()) } answers { events += "delete"; 1 }

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(120, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        assertEquals(listOf("check 50", "check 50", "check 20"), events.filter { it.startsWith("check") })
        // check, its 50 deletes, the next check: no slice is deleted before its own check.
        val checks = events.withIndex().filter { it.value.startsWith("check") }.map { it.index }
        assertEquals(listOf(0, 51, 102), checks)
    }

    @Test
    fun `a slice whose check fails keeps what earlier slices freed and touches nothing after`() = runTest {
        val rows = (1..120).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        var calls = 0
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } answers {
            calls++
            if (calls == 2) throw IOException("rate limited")
            thirdArg<List<String>>().associateWith { linkAt(it, state = 1) }
        }

        var thrown: IOException? = null
        try {
            useCase.sweepConfirmed(userId, Long.MAX_VALUE)
        } catch (e: IOException) {
            thrown = e
        }

        assertTrue(thrown != null)
        verify(exactly = 50) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `a copy the app may not delete is reported as skipped where there is no system dialog`() = runTest {
        // A plain JVM run reports SDK_INT 0, below Android 11.
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(eligible(1), eligible(2))
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            "cloud-1" to linkAt("cloud-1", state = 1),
            "cloud-2" to linkAt("cloud-2", state = 1),
        )
        every { resolver.delete(any(), any(), any()) } returnsMany listOf(1, 0)

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(FreeUpSpaceUseCase.FreeUpResult.Done(freed = 1, skipped = 1), result)
    }

    @Test
    fun `no system dialog carries more than one request's worth`() {
        val (batch, rest) = FreeUpSpaceUseCase.splitForConsent((1..4_500).toList())
        assertEquals(FreeUpSpaceUseCase.DELETE_REQUEST_MAX_URIS, batch.size)
        assertEquals(1, batch.first())
        assertEquals(2_500, rest.size)
        assertEquals(2_001, rest.first())
        assertEquals(listOf(1, 2) to emptyList<Int>(), FreeUpSpaceUseCase.splitForConsent(listOf(1, 2)))
    }

    @Test
    fun `a sweep with no candidates asks Drive nothing`() = runTest {
        coEvery { repo.getSyncedBefore(userId, any()) } returns emptyList()

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 0) { linkDetailHelpers.batchFetchLinkDetails(any(), any(), any(), any()) }
    }

    @Test
    fun `a photo edited in place since its backup is kept and queued to be backed up again`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, sizeBytes = 2048L)
        coEvery { localRepo.sha1(row.localUri) } returns "edited"

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
        coVerify {
            repo.upsert(
                match {
                    it.localUri == row.localUri && it.status == SyncStatus.LOCAL_ONLY &&
                        it.cloudFileId == null && it.backedUpAtMs == null && it.localHash == "edited"
                },
                userId,
            )
        }
        coVerify { repo.markQueued(row.localUri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `a device file that cannot be read is kept and left as it is`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.sha1(row.localUri) } returns null

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { repo.upsert(any(), any()) }
        coVerify(exactly = 0) { repo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `a device file whose bytes Drive holds as they are is reclaimed`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            "cloud-1" to BatchLinkDto(
                link = LinkCoreDto(linkId = "cloud-1", type = 2, state = 1),
                photo = LinkPhotoDto(contentHash = "drive-hash"),
            ),
        )
        coEvery { localRepo.sha1(row.localUri) } returns "rewritten-by-this-app"
        every { cloudRepo.cloudContentHash("rewritten-by-this-app") } returns "drive-hash"

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
    }

    /** A row backed up from stripped bytes: its hash is the upload's, the original's is recorded beside it. */
    private fun strippedOriginal(i: Int) = eligible(i).copy(localHash = "uploaded-$i", originalHash = "original-$i")

    @Test
    fun `an original kept beside a stripped upload and unchanged since is reclaimed`() = runTest {
        val row = strippedOriginal(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.sha1(row.localUri) } returns "original-1"

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 1) { repo.updateStatusAndDeleteLocal(row.localUri, SyncStatus.CLOUD_ONLY) }
    }

    @Test
    fun `an original edited in place with its size and time kept is kept and queued to be backed up again`() = runTest {
        val row = strippedOriginal(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        // Same size as recorded and written long before the backup: only the bytes differ.
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, sizeBytes = 1024L, modifiedSec = 0L)
        coEvery { localRepo.sha1(row.localUri) } returns "edited"

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
        coVerify {
            repo.upsert(
                match {
                    it.localUri == row.localUri && it.status == SyncStatus.LOCAL_ONLY &&
                        it.cloudFileId == null && it.backedUpAtMs == null && it.localHash == "edited"
                },
                userId,
            )
        }
        coVerify { repo.markQueued(row.localUri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `the pending delete check passes an unchanged original and drops an edited one`() = runTest {
        // confirmForDeletion is what the stored delete queue is checked with before the trash dialog.
        val same = strippedOriginal(1)
        val edited = strippedOriginal(2)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            "cloud-1" to linkAt("cloud-1", state = 1),
            "cloud-2" to linkAt("cloud-2", state = 1),
        )
        coEvery { localRepo.sha1(same.localUri) } returns "original-1"
        coEvery { localRepo.sha1(edited.localUri) } returns "edited"

        val confirmed = useCase.confirmForDeletion(userId, listOf(same, edited))

        assertEquals(listOf(same), confirmed)
        coVerify { repo.markQueued(edited.localUri, QueueSource.MANUAL, any()) }
        coVerify(exactly = 0) { repo.markQueued(same.localUri, any(), any()) }
    }

    // ── Copies that cannot be checked ─────────────────────────────────────────────────────────────────

    /** A download from before its saved bytes were recorded: the stamp grew it past the Drive size. */
    private fun oldDownload(i: Int) =
        eligible(i).copy(localHash = "", sizeBytes = 1000L, backedUpAtMs = 5_000_000L, originalHash = null)

    @Test
    fun `a copy that cannot be checked is kept, reported, and left out of the next list`() = runTest {
        val row = oldDownload(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, 1100L, modifiedSec = 100L)
        coEvery { localRepo.sha1(row.localUri) } returns "stamped"

        val result = useCase.verifyAndReclaim(userId, useCase.candidates(userId, Long.MAX_VALUE))

        assertEquals(FreeUpSpaceUseCase.FreeUpResult.Done(freed = 0, unchecked = 1), result)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { repo.upsert(any(), any()) }
        coVerify(exactly = 0) { repo.markQueued(any(), any(), any()) }
        assertEquals(DeviceFileStamp(1100L, 100L), unchecked.entries[row.localUri]?.stamp)
        assertEquals(
            "not listed again while the file is as it was",
            emptyList<SyncState>(),
            useCase.candidates(userId, Long.MAX_VALUE),
        )
    }

    @Test
    fun `a kept copy whose row was recorded again comes back with the same file`() = runTest {
        val row = oldDownload(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, 1100L, modifiedSec = 100L)
        coEvery { localRepo.sha1(row.localUri) } returns "stamped"
        useCase.verifyAndReclaim(userId, useCase.candidates(userId, Long.MAX_VALUE))
        assertTrue(row.localUri in unchecked.entries)

        val recorded = row.copy(originalHash = "stamped")
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(recorded)

        assertEquals(listOf(recorded), useCase.candidates(userId, Long.MAX_VALUE))
        assertTrue(row.localUri !in unchecked.entries)
    }

    @Test
    fun `a kept copy whose file was written since comes back and is checked again`() = runTest {
        val row = oldDownload(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        unchecked.mark(row.localUri, FreeUpUncheckedStore.Kept(DeviceFileStamp(1100L, 100L), "any"))
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, 1100L, modifiedSec = 9_000L)

        assertEquals(listOf(row), useCase.candidates(userId, Long.MAX_VALUE))
        assertTrue(row.localUri !in unchecked.entries)

        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.sha1(row.localUri) } returns "edited"

        val result = useCase.verifyAndReclaim(userId, listOf(row))

        assertEquals("written after the backup, so it goes up again", FreeUpSpaceUseCase.FreeUpResult.Done(0), result)
        coVerify { repo.markQueued(row.localUri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `a same-size edit of a row with no original hash is queued to be backed up again`() = runTest {
        val row = eligible(1).copy(backedUpAtMs = 5_000_000L, originalHash = null)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, 1024L, modifiedSec = 9_000L)
        coEvery { localRepo.sha1(row.localUri) } returns "edited"

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(FreeUpSpaceUseCase.FreeUpResult.Done(0), result)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify { repo.markQueued(row.localUri, QueueSource.MANUAL, any()) }
        assertTrue("never left in the list unchecked", unchecked.entries.isEmpty())
    }

    @Test
    fun `an unreadable copy is reported but checked again on the next run`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.sha1(row.localUri) } returns null

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(FreeUpSpaceUseCase.FreeUpResult.Done(freed = 0, unchecked = 1), result)
        assertTrue(unchecked.entries.isEmpty())
        assertEquals(listOf(row), useCase.candidates(userId, Long.MAX_VALUE))
    }

    @Test
    fun `the pending delete check drops a copy it cannot check and leaves it out of the list`() = runTest {
        val row = oldDownload(1)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.fileStamp(row.localUri) } returns deviceFile(row.localUri, 1100L, modifiedSec = 100L)
        coEvery { localRepo.sha1(row.localUri) } returns "stamped"

        assertEquals(emptyList<SyncState>(), useCase.confirmForDeletion(userId, listOf(row)))
        assertTrue(row.localUri in unchecked.entries)
    }

    // ── Drive copies shorter than the device file ────────────────────────────────────────────────────

    /** A Drive link whose content hash claims [sha1]'s bytes and whose stored size is [size]. */
    private fun claimingLink(linkId: String, sha1: String, size: Long): BatchLinkDto {
        every { cloudRepo.cloudContentHash(sha1) } returns "drive-$sha1"
        return BatchLinkDto(
            link = LinkCoreDto(linkId = linkId, type = 2, state = 1, size = size),
            photo = LinkPhotoDto(contentHash = "drive-$sha1"),
        )
    }

    @Test
    fun `a device file whose Drive copy stores fewer bytes than it holds is kept and queued again`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        // The hash names the whole 1024-byte file; Drive stores 540 bytes, the editor's short copies' shape.
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to claimingLink("cloud-1", "hash-1", size = 540L))

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
        assertEquals(setOf("cloud-1"), shortCopies.ids)
        coVerify {
            repo.upsert(
                match { it.localUri == row.localUri && it.status == SyncStatus.LOCAL_ONLY && it.cloudFileId == null },
                userId,
            )
        }
        coVerify { repo.markQueued(row.localUri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `a device file paired to a Drive copy recorded as short is kept whatever the hashes say`() = runTest {
        val row = eligible(1)
        shortCopies.ids += "cloud-1"
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        // Hash and size both read whole: only the record knows the copy lacks the end of the file.
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to claimingLink("cloud-1", "hash-1", size = 4096L))

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
        coVerify { repo.markQueued(row.localUri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `a device file whose Drive copy stores at least its bytes is reclaimed`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to claimingLink("cloud-1", "hash-1", size = 1200L))

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        assertTrue(shortCopies.ids.isEmpty())
    }

    @Test
    fun `an original kept beside a smaller stripped upload is not read as short`() = runTest {
        val row = strippedOriginal(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        // Drive holds the stripped bytes: smaller, and hashed apart from the device file's.
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to claimingLink("cloud-1", "uploaded-1", size = 600L))
        coEvery { localRepo.sha1(row.localUri) } returns "original-1"

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        assertTrue(shortCopies.ids.isEmpty())
    }

    /** A device file found under [name], as the device reports it. */
    private fun named(row: SyncState, name: String) = eu.akoos.photos.domain.entity.LocalMediaItem(
        uri = row.localUri, dateTaken = 1000L, displayName = name, mimeType = "image/jpeg", sizeBytes = 1024L,
        bucketName = "Camera",
    )

    @Test
    fun `until the look for short editor copies ran, an editor copy backed up in the window is kept and queued`() = runTest {
        shortCopies.checked = false
        val inWindow = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L
        val edit = eligible(1).copy(backedUpAtMs = inWindow)
        val plain = eligible(2).copy(backedUpAtMs = inWindow)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(edit, plain)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            "cloud-1" to linkAt("cloud-1", state = 1),
            "cloud-2" to linkAt("cloud-2", state = 1),
        )
        coEvery { localRepo.queryByUri(edit.localUri) } returns named(edit, "IMG_1_edit_20260801_101500.jpg")
        coEvery { localRepo.queryByUri(plain.localUri) } returns named(plain, "IMG_2.jpg")
        // The edit's upload declared the size from before its EXIF write.
        claimed.sizes["cloud-1"] = 1024L - 484L

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 1) { repo.updateStatusAndDeleteLocal(plain.localUri, SyncStatus.CLOUD_ONLY) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(edit.localUri, any()) }
        assertEquals(setOf("cloud-1"), shortCopies.ids)
        assertEquals("cloud-1", shortCopies.replacements[edit.localUri])
        coVerify { repo.markQueued(edit.localUri, QueueSource.MANUAL, any()) }
        assertTrue(("cloud-1" to true) in claimed.asked)
    }

    @Test
    fun `until the look ran, an editor copy that declares the whole file is reclaimed`() = runTest {
        shortCopies.checked = false
        val edit = eligible(1).copy(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(edit)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.queryByUri(edit.localUri) } returns named(edit, "IMG_1_edit_20260801_101500.jpg")
        claimed.sizes["cloud-1"] = 1024L

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        assertTrue(shortCopies.ids.isEmpty())
    }

    @Test
    fun `until the look ran, an editor copy whose declared size cannot be read is kept and not queued`() = runTest {
        shortCopies.checked = false
        val edit = eligible(1).copy(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.queryByUri(edit.localUri) } returns named(edit, "IMG_1_edit_20260801_101500.jpg")

        assertTrue(useCase.confirmForDeletion(userId, listOf(edit)).isEmpty())
        coVerify(exactly = 0) { repo.markQueued(any(), any(), any()) }
        // Not set aside until the file changes: the next run reads the size again.
        assertTrue(unchecked.entries.isEmpty())
    }

    @Test
    fun `an editor copy whose device file is not the uploaded bytes is kept for good and never queued`() = runTest {
        shortCopies.checked = false
        val edit = eligible(1).copy(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.queryByUri(edit.localUri) } returns named(edit, "IMG_1_edit_20260801_101500.jpg")
        coEvery { localRepo.sha1(edit.localUri) } returns "stripped-apart"
        claimed.sizes["cloud-1"] = 900L

        assertTrue(useCase.confirmForDeletion(userId, listOf(edit)).isEmpty())
        assertEquals(setOf("cloud-1"), shortCopies.unverifiableIds)
        coVerify(exactly = 0) { repo.markQueued(any(), any(), any()) }

        // Recorded so, it stays kept once the look has run too.
        shortCopies.checked = true
        assertTrue(useCase.confirmForDeletion(userId, listOf(edit)).isEmpty())
    }

    @Test
    fun `the declared size decides over Drive's stored size`() = runTest {
        val short = eligible(1)
        val whole = eligible(2)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(short, whole)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            // Stored size reads whole, the declared size short.
            "cloud-1" to claimingLink("cloud-1", "hash-1", size = 5000L),
            // Stored size reads short, the declared size whole.
            "cloud-2" to claimingLink("cloud-2", "hash-2", size = 500L),
        )
        claimed.sizes["cloud-1"] = 600L
        claimed.sizes["cloud-2"] = 1024L

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(1, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 1) { repo.updateStatusAndDeleteLocal(whole.localUri, SyncStatus.CLOUD_ONLY) }
        assertEquals(setOf("cloud-1"), shortCopies.ids)
        // The routine check reads only the listing's cached XAttr.
        assertTrue(claimed.asked.all { !it.second })
    }

    @Test
    fun `the pending delete check keeps a suspect editor copy the same way`() = runTest {
        shortCopies.checked = false
        val edit = eligible(1).copy(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.queryByUri(edit.localUri) } returns named(edit, "IMG_1_edit_20260801_101500.jpg")
        claimed.sizes["cloud-1"] = 600L

        val check = useCase.confirmPendingDeletes(userId, listOf(edit))

        assertTrue(check.deletable.isEmpty())
        assertEquals(listOf(edit), check.afterWholeBackup)
        coVerify { repo.markQueued(edit.localUri, QueueSource.MANUAL, any()) }
    }

    @Test
    fun `until the look ran, a file that cannot be looked up is kept unchecked`() = runTest {
        shortCopies.checked = false
        val row = eligible(1).copy(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.queryByUri(row.localUri) } returns null

        assertTrue(useCase.confirmForDeletion(userId, listOf(row)).isEmpty())
        coVerify(exactly = 0) { repo.markQueued(any(), any(), any()) }
    }

    @Test
    fun `the verdict reads a short Drive copy as short before anything else that is read`() {
        val row = eligible(1)
        assertEquals(
            FreeUpSpaceUseCase.DeviceCopyCheck.UNCHANGED,
            FreeUpSpaceUseCase.deviceCopyVerdict(row, 1024L, 0L, "hash-1", true, "drive-hash-1"),
        )
        assertEquals(
            FreeUpSpaceUseCase.DeviceCopyCheck.SHORT_ON_DRIVE,
            FreeUpSpaceUseCase.deviceCopyVerdict(row, 1024L, 0L, "hash-1", true, "drive-hash-1", driveShort = true),
        )
        assertEquals(
            FreeUpSpaceUseCase.DeviceCopyCheck.UNREADABLE,
            FreeUpSpaceUseCase.deviceCopyVerdict(row, 1024L, 0L, null, false, null, driveShort = true),
        )
    }

    @Test
    fun `the declared size is read from the XAttr the batch lookup returned`() = runTest {
        val row = eligible(1)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(row)
        every { cloudRepo.cloudContentHash("hash-1") } returns "drive-hash-1"
        val link = BatchLinkDto(
            link = LinkCoreDto(
                linkId = "cloud-1", type = 2, state = 1, size = 5000L,
                parentLinkId = "root", nodeKey = "nk", nodePassphrase = "np",
                fileProperties = LinkFilePropertiesDto(activeRevision = LinkFileActiveRevisionDto(id = "r1", xAttr = "armored")),
            ),
            photo = LinkPhotoDto(contentHash = "drive-hash-1"),
        )
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf("cloud-1" to link)
        claimed.sizes["cloud-1"] = 600L

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        assertEquals(setOf("cloud-1"), shortCopies.ids)
        assertEquals(
            DriveCopyXAttr("armored", "nk", "np", "root", volumeId),
            claimed.freshGiven["cloud-1"],
        )
    }

    @Test
    fun `the pending delete check names an editor copy it could not tell apart for a later check`() = runTest {
        shortCopies.checked = false
        val edit = eligible(1).copy(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L)
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))
        coEvery { localRepo.queryByUri(edit.localUri) } returns named(edit, "IMG_1_edit_20260801_101500.jpg")

        val check = useCase.confirmPendingDeletes(userId, listOf(edit))

        assertTrue(check.deletable.isEmpty())
        assertTrue(check.afterWholeBackup.isEmpty())
        assertEquals(listOf(edit), check.checkAgain)
    }
}
