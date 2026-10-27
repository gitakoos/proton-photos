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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * Pins chunked single-pass [FreeUpSpaceUseCase.runPipeline]: inactive links are never deleted;
 * a failed Drive fetch deletes nothing in that chunk; cancel between chunks keeps finished work;
 * API &lt; R never returns [NeedsPermission].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FreeUpSpaceVerifyTest {

    private val userId = UserId("u1")
    private val volumeId = "vol-1"
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var linkDetailHelpers: LinkDetailHelpers
    private lateinit var syncStateRepo: SyncStateRepository
    private lateinit var localMediaRepo: LocalMediaRepository
    private lateinit var useCase: FreeUpSpaceUseCase

    private fun candidate(linkId: String?, localHash: String = "hash-${linkId ?: "none"}") = SyncState(
        localUri = "content://media/external/images/media/${linkId ?: "none"}",
        cloudFileId = linkId,
        localHash = localHash,
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = 1L,
        sizeBytes = 1024L,
    )

    private fun linkAt(linkId: String, state: Int) =
        BatchLinkDto(link = LinkCoreDto(linkId = linkId, type = 2, state = state))

    private fun deletedAll(uris: List<String>) = LocalMediaDeleteResult(
        deleted = uris,
        missing = emptyList(),
        needsPermission = emptyList(),
    )

    @Before
    fun setUp() {
        cloudRepo = mockk(relaxed = true)
        linkDetailHelpers = mockk(relaxed = true)
        syncStateRepo = mockk(relaxed = true)
        localMediaRepo = mockk(relaxed = true)
        useCase = FreeUpSpaceUseCase(
            syncStateRepo,
            cloudRepo,
            linkDetailHelpers,
            localMediaRepo,
        )
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } coAnswers {
            deletedAll(firstArg())
        }
    }

    @Test
    fun `inactive link is never deleted`() = runTest {
        val row = candidate("cloud-1")
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns listOf(row)
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } returns mapOf("cloud-1" to linkAt("cloud-1", state = 2))

        val result = useCase.runPipeline(userId, Long.MAX_VALUE)

        val done = result as FreeUpSpaceUseCase.FreeUpResult.Done
        assertEquals(1, done.checked)
        assertEquals(0, done.freed)
        coVerify(exactly = 0) { localMediaRepo.deleteDeviceCopies(any()) }
        coVerify(exactly = 0) { syncStateRepo.updateStatusAndDeleteLocal(any<List<String>>(), any()) }
    }

    @Test
    fun `pending consent verifier keeps only URIs whose current Drive link is active`() = runTest {
        val activeUri = "content://media/external/images/media/1"
        val inactiveUri = "content://media/external/images/media/2"
        val missingUri = "content://media/external/images/media/3"
        val requested = linkedSetOf(activeUri, inactiveUri, missingUri)
        coEvery {
            syncStateRepo.cloudPairedLinkIds(userId, requested.toList())
        } returns mapOf(
            activeUri to "cloud-active",
            inactiveUri to "cloud-inactive",
        )
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(
                userId,
                volumeId,
                listOf("cloud-active", "cloud-inactive"),
            )
        } returns mapOf(
            "cloud-active" to linkAt("cloud-active", state = 1),
            "cloud-inactive" to linkAt("cloud-inactive", state = 2),
        )

        val verified = useCase.verifyPendingConsentBatch(userId, requested)

        assertEquals(linkedSetOf(activeUri), verified)
    }

    @Test
    fun `pending consent verifier propagates an uncertain Drive lookup`() = runTest {
        val uri = "content://media/external/images/media/1"
        coEvery {
            syncStateRepo.cloudPairedLinkIds(userId, listOf(uri))
        } returns mapOf(uri to "cloud-1")
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, listOf("cloud-1"))
        } throws RuntimeException("lookup failed")

        val failure = runCatching {
            useCase.verifyPendingConsentBatch(userId, linkedSetOf(uri))
        }.exceptionOrNull()

        assertTrue(failure is RuntimeException)
    }

    @Test
    fun `a failed chunk deletes nothing`() = runTest {
        val rows = listOf(candidate("a"), candidate("b"))
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns rows
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } throws RuntimeException("chunk failed")

        runCatching { useCase.runPipeline(userId, Long.MAX_VALUE) }

        coVerify(exactly = 0) { localMediaRepo.deleteDeviceCopies(any()) }
        coVerify(exactly = 0) { syncStateRepo.updateStatusAndDeleteLocal(any<List<String>>(), any()) }
    }

    @Test
    fun `offline lookup throws so the worker can retry`() = runTest {
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns listOf(candidate("cloud-1"))
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } throws UnknownHostException("no route")

        val propagated: Throwable? = try {
            useCase.runPipeline(userId, Long.MAX_VALUE)
            null
        } catch (e: UnknownHostException) {
            e
        }

        assertNotNull("offline must propagate for WorkManager retry", propagated)
        coVerify(exactly = 0) { localMediaRepo.deleteDeviceCopies(any()) }
    }

    @Test
    fun `cancel between chunks keeps finished deletes and skips the rest`() = runTest {
        val rows = (1..51).map { candidate("cloud-$it") }
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns rows
        var fetches = 0
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } answers {
            fetches++
            @Suppress("UNCHECKED_CAST")
            val ids = args[2] as List<String>
            ids.associateWith { linkAt(it, state = 1) }
        }

        try {
            useCase.runPipeline(userId, Long.MAX_VALUE) { progress ->
                if (progress.processed >= FreeUpSpaceUseCase.VERIFY_CHUNK_SIZE &&
                    progress.phase == FreeUpSpaceUseCase.PipelinePhase.Deleting
                ) {
                    throw CancellationException("cancel after first verify+delete chunk")
                }
            }
        } catch (_: CancellationException) {
            // expected — cooperative cancel between Drive chunks
        }

        assertEquals(1, fetches)
        coVerify(exactly = 1) {
            localMediaRepo.deleteDeviceCopies(
                match<List<String>> { it.size == FreeUpSpaceUseCase.VERIFY_CHUNK_SIZE },
            )
        }
        coVerify {
            syncStateRepo.updateStatusAndDeleteLocal(
                match<List<String>> { it.size == FreeUpSpaceUseCase.VERIFY_CHUNK_SIZE },
                SyncStatus.CLOUD_ONLY,
            )
        }
    }

    @Test
    @Config(sdk = [29])
    fun `API below R never returns NeedsPermission and counts survivors as skipped`() = runTest {
        val rows = listOf(candidate("cloud-1"), candidate("cloud-2"))
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns rows
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val ids = args[2] as List<String>
            ids.associateWith { linkAt(it, state = 1) }
        }
        coEvery { localMediaRepo.deleteDeviceCopies(any()) } returns LocalMediaDeleteResult(
            deleted = emptyList(),
            missing = emptyList(),
            needsPermission = rows.map { it.localUri },
        )

        val result = useCase.runPipeline(userId, Long.MAX_VALUE)

        assertTrue(result is FreeUpSpaceUseCase.FreeUpResult.Done)
        val done = result as FreeUpSpaceUseCase.FreeUpResult.Done
        assertEquals(2, done.checked)
        assertEquals(0, done.freed)
        assertEquals(2, done.skipped)
    }

    @Test
    fun `runPipeline reports verifying then deleting phases`() = runTest {
        val rows = (1..3).map { candidate("cloud-$it") }
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns rows
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val ids = args[2] as List<String>
            ids.associateWith { linkAt(it, state = 1) }
        }

        val phases = mutableListOf<FreeUpSpaceUseCase.PipelinePhase>()
        val result = useCase.runPipeline(userId, Long.MAX_VALUE) { progress ->
            if (phases.lastOrNull() != progress.phase) phases += progress.phase
        }

        assertEquals(
            listOf(
                FreeUpSpaceUseCase.PipelinePhase.Verifying,
                FreeUpSpaceUseCase.PipelinePhase.Deleting,
            ),
            phases,
        )
        val done = result as FreeUpSpaceUseCase.FreeUpResult.Done
        assertEquals(3, done.checked)
        assertEquals(3, done.freed)
    }

    @Test
    fun `runPipeline automatic eligibility excludes empty-hash downloads`() = runTest {
        val uploaded = candidate("cloud-1", localHash = "hash-1")
        val downloaded = candidate("cloud-2", localHash = "")
        coEvery { syncStateRepo.getSyncedBefore(userId, any()) } returns listOf(uploaded, downloaded)
        coEvery {
            linkDetailHelpers.batchFetchLinkDetailsStrict(userId, volumeId, any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val ids = args[2] as List<String>
            ids.associateWith { linkAt(it, state = 1) }
        }

        useCase.runPipeline(userId, Long.MAX_VALUE, protectDownloaded = true)

        coVerify {
            localMediaRepo.deleteDeviceCopies(listOf(uploaded.localUri))
        }
        coVerify {
            syncStateRepo.updateStatusAndDeleteLocal(listOf(uploaded.localUri), SyncStatus.CLOUD_ONLY)
        }
        coVerify(exactly = 0) {
            localMediaRepo.deleteDeviceCopies(
                match<List<String>> { downloaded.localUri in it },
            )
        }
    }
}
