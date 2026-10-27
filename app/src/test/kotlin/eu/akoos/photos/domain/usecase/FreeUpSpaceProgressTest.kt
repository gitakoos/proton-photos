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

import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaDeleteResult
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for what the Free up space screen shows against what the single-pass pipeline does.
 *
 * [FreeUpSpaceUseCase.candidates] is the shared query the grid and [runPipeline] both use.
 * Progress stays monotonic and ends on the eligible total; permission survivors and missing-URI
 * repair stay classified correctly on API 30+.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FreeUpSpaceProgressTest {

    private val userId = UserId("u1")
    private val volumeId = "vol-1"
    private lateinit var repo: SyncStateRepository
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var linkDetailHelpers: LinkDetailHelpers
    private lateinit var localMediaRepo: LocalMediaRepository
    private lateinit var useCase: FreeUpSpaceUseCase

    /** Eligible by [FreeUpSpaceUseCase.isEligibleForReclamation]: SYNCED with a real stamp. */
    private fun eligible(i: Int, localHash: String = "hash-$i") = SyncState(
        localUri = "content://media/external/images/media/$i",
        cloudFileId = "cloud-$i",
        localHash = localHash,
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = 1L,
        sizeBytes = 1024L,
    )

    private fun linkAt(linkId: String) =
        BatchLinkDto(link = LinkCoreDto(linkId = linkId, type = 2, state = 1))

    private fun stubActiveLinks() {
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val ids = args[2] as List<String>
            ids.associateWith { linkAt(it) }
        }
    }

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        cloudRepo = mockk(relaxed = true)
        linkDetailHelpers = mockk(relaxed = true)
        localMediaRepo = mockk(relaxed = true)
        useCase = FreeUpSpaceUseCase(
            repo,
            cloudRepo,
            linkDetailHelpers,
            localMediaRepo,
        )
        stubActiveLinks()
    }

    @Test
    fun `the sweep reclaims exactly the rows the screen was shown`() = runTest {
        val rows = (1..5).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
            LocalMediaDeleteResult(firstArg(), emptyList(), emptyList())
        }

        val listed = useCase.candidates(userId, Long.MAX_VALUE)
        val result = useCase.runPipeline(userId, Long.MAX_VALUE)

        assertEquals(rows.map { it.localUri }, listed.map { it.localUri })
        val done = result as FreeUpSpaceUseCase.FreeUpResult.Done
        assertEquals(rows.size, done.checked)
        assertEquals(rows.size, done.freed)
        val batchSlot = slot<List<String>>()
        coVerify(exactly = 1) {
            repo.updateStatusAndDeleteLocal(capture(batchSlot), SyncStatus.CLOUD_ONLY)
        }
        assertEquals(listed.map { it.localUri }.toSet(), batchSlot.captured.toSet())
    }

    @Test
    fun `SYNCED rows with blank or null cloudFileId are absent from candidates`() = runTest {
        val withId = eligible(1)
        val nullId = eligible(2).copy(cloudFileId = null)
        val blankId = eligible(3).copy(cloudFileId = "   ")
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(withId, nullId, blankId)

        val listed = useCase.candidates(userId, Long.MAX_VALUE)

        assertEquals(listOf(withId.localUri), listed.map { it.localUri })
    }

    @Test
    fun `progress starts at zero, never goes backwards, and ends on the total`() = runTest {
        val rows = (1..45).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
            LocalMediaDeleteResult(firstArg(), emptyList(), emptyList())
        }

        val reports = mutableListOf<Pair<Int, Int>>()
        useCase.runPipeline(userId, Long.MAX_VALUE) { progress ->
            reports += progress.processed to progress.total
        }

        assertEquals(0 to rows.size, reports.first())
        assertEquals(rows.size to rows.size, reports.last())
        assertTrue("every report carries the same total", reports.all { it.second == rows.size })
        assertTrue(
            "progress must not go backwards: $reports",
            reports.zipWithNext().all { (a, b) -> b.first >= a.first },
        )
        assertTrue("no report may exceed the total", reports.all { it.first <= rows.size })
    }

    @Test
    fun `a photo the system refuses stays NeedsPermission when the MediaStore row still exists`() =
        runTest {
            val rows = (1..3).map { eligible(it) }
            coEvery { repo.getSyncedBefore(userId, any()) } returns rows
            coEvery { localMediaRepo.deleteDeviceCopies(any()) } returns LocalMediaDeleteResult(
                deleted = emptyList(),
                missing = emptyList(),
                needsPermission = rows.map { it.localUri },
            )

            val result = useCase.runPipeline(userId, Long.MAX_VALUE)

            assertTrue(
                "expected NeedsPermission, got $result",
                result is FreeUpSpaceUseCase.FreeUpResult.NeedsPermission,
            )
            val needs = result as FreeUpSpaceUseCase.FreeUpResult.NeedsPermission
            assertEquals(rows.size, needs.checked)
            assertEquals(0, needs.freed)
            assertEquals(rows.map { it.localUri }, needs.localUris)
            coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any<List<String>>(), any()) }
            coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any<String>(), any()) }
        }

    @Test
    fun `missing local URIs are repaired to CLOUD_ONLY without NeedsPermission`() = runTest {
        val rows = (1..3).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } returns LocalMediaDeleteResult(
            deleted = emptyList(),
            missing = rows.map { it.localUri },
            needsPermission = emptyList(),
        )

        val result = useCase.runPipeline(userId, Long.MAX_VALUE)

        val done = result as FreeUpSpaceUseCase.FreeUpResult.Done
        assertEquals(3, done.checked)
        assertEquals(3, done.freed)
        coVerify(exactly = 1) {
            repo.updateStatusAndDeleteLocal(rows.map { it.localUri }, SyncStatus.CLOUD_ONLY)
        }
    }

    @Test
    fun `direct deletes batch status on each verify chunk`() = runTest {
        val rows = (1..51).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        val batches = mutableListOf<List<String>>()
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
            LocalMediaDeleteResult(firstArg(), emptyList(), emptyList())
        }
        coEvery { repo.updateStatusAndDeleteLocal(any<List<String>>(), any()) } coAnswers {
            batches += firstArg<List<String>>()
        }

        useCase.runPipeline(userId, Long.MAX_VALUE)

        assertEquals(listOf(50, 1), batches.map { it.size })
        assertEquals(rows.map { it.localUri }, batches.flatten())
    }

    @Test
    fun `runPipeline automatic scope skips protected downloads while manual reclaims them`() =
        runTest {
            val uploaded = eligible(1, localHash = "hash-1")
            val downloaded = eligible(2, localHash = "")
            val rows = listOf(uploaded, downloaded)
            coEvery { repo.getSyncedBefore(userId, any()) } returns rows
            coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
                LocalMediaDeleteResult(firstArg(), emptyList(), emptyList())
            }

            useCase.runPipeline(userId, Long.MAX_VALUE, protectDownloaded = true)

            coVerify {
                repo.updateStatusAndDeleteLocal(listOf(uploaded.localUri), SyncStatus.CLOUD_ONLY)
            }
            coVerify(exactly = 0) {
                repo.updateStatusAndDeleteLocal(
                    match<List<String>> { downloaded.localUri in it },
                    any(),
                )
            }

            useCase.runPipeline(userId, Long.MAX_VALUE, protectDownloaded = false)
            coVerify {
                repo.updateStatusAndDeleteLocal(
                    match<List<String>> {
                        uploaded.localUri in it && downloaded.localUri in it
                    },
                    SyncStatus.CLOUD_ONLY,
                )
            }
        }

    @Test
    fun `cancel between chunks flushes finished work and skips the rest`() = runTest {
        val rows = (1..51).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        var deletes = 0
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
            val uris = firstArg<List<String>>()
            deletes += uris.size
            LocalMediaDeleteResult(uris, emptyList(), emptyList())
        }
        val batches = mutableListOf<List<String>>()
        coEvery { repo.updateStatusAndDeleteLocal(any<List<String>>(), any()) } coAnswers {
            batches += firstArg<List<String>>()
        }

        try {
            useCase.runPipeline(userId, Long.MAX_VALUE) { progress ->
                if (progress.processed >= FreeUpSpaceUseCase.VERIFY_CHUNK_SIZE &&
                    progress.phase == FreeUpSpaceUseCase.PipelinePhase.Deleting
                ) {
                    throw CancellationException("cancel after first chunk")
                }
            }
        } catch (_: CancellationException) {
            // expected — cooperative cancel between chunks
        }

        assertEquals(FreeUpSpaceUseCase.VERIFY_CHUNK_SIZE, deletes)
        assertEquals(listOf(FreeUpSpaceUseCase.VERIFY_CHUNK_SIZE), batches.map { it.size })
    }

    @Test
    fun `pipeline removed counter stays monotonic across mixed delete outcomes`() = runTest {
        val rows = (1..25).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        val success = rows.filterIndexed { index, _ -> index % 2 == 0 }
        val fail = rows.filterIndexed { index, _ -> index % 2 == 1 }
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
            val uris = firstArg<List<String>>()
            LocalMediaDeleteResult(
                deleted = uris.filter { it in success.map { s -> s.localUri }.toSet() },
                missing = emptyList(),
                needsPermission = uris.filter { it in fail.map { f -> f.localUri }.toSet() },
            )
        }

        val reports = mutableListOf<Triple<Int, Int, Int>>()
        val result = useCase.runPipeline(userId, Long.MAX_VALUE) { progress ->
            reports += Triple(progress.processed, progress.removed, progress.total)
        }

        val needs = result as FreeUpSpaceUseCase.FreeUpResult.NeedsPermission
        assertEquals(rows.size, needs.checked)
        assertEquals(success.size, needs.freed)
        assertEquals(fail.map { it.localUri }, needs.localUris)
        assertEquals(0 to rows.size, reports.first().first to reports.first().third)
        assertEquals(rows.size, reports.last().first)
        assertTrue(
            "processed must not go backwards: $reports",
            reports.zipWithNext().all { (a, b) -> b.first >= a.first },
        )
        assertTrue(
            "removed must not go backwards: $reports",
            reports.zipWithNext().all { (a, b) -> b.second >= a.second },
        )
        assertEquals(success.size, reports.last().second)
    }
}
