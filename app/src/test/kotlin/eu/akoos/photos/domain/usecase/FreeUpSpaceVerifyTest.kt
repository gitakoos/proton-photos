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
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Pins the safety decision the manual "Free up space" now makes before deleting anything: only a
 * device copy whose Drive link is confirmed active RIGHT NOW is reclaimed. The old path walked the
 * whole volume; [FreeUpSpaceUseCase.verifyActiveBackups] instead checks the on-device candidates'
 * links in one batched call and keeps only the ones the server returns at State == 1.
 *
 * The invariant is stricter than the [FreeUpSpaceUseCaseTest] eligibility gate: a candidate is kept
 * ONLY on a live State == 1 answer, and dropped (left on the device) when its link is trashed, absent
 * from the answer, or has no cloudFileId to check. A transient lookup failure must PROPAGATE so the
 * caller aborts and nothing is deleted against an unconfirmed batch. No Android, no network: a mocked
 * repo supplies the volume id and a mocked batch helper supplies the answer.
 */
class FreeUpSpaceVerifyTest {

    private val userId = UserId("u1")
    private val volumeId = "vol-1"
    private lateinit var cloudRepo: DrivePhotoRepository
    private lateinit var linkDetailHelpers: LinkDetailHelpers
    private lateinit var useCase: FreeUpSpaceUseCase

    /** A SYNCED, backed-up candidate carrying [linkId] as its cloud link (null = never paired). */
    private fun candidate(linkId: String?) = SyncState(
        localUri = "content://media/external/images/media/${linkId ?: "none"}",
        cloudFileId = linkId,
        localHash = "hash-${linkId ?: "none"}",
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = 1L,
        sizeBytes = 1024L,
    )

    /** A batch answer for [linkId] at the given Link.State (1 = active, anything else = not active). */
    private fun linkAt(linkId: String, state: Int) =
        BatchLinkDto(link = LinkCoreDto(linkId = linkId, type = 2, state = state))

    @Before
    fun setUp() {
        cloudRepo = mockk(relaxed = true)
        linkDetailHelpers = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val syncStateRepo = mockk<SyncStateRepository>(relaxed = true)
        useCase = FreeUpSpaceUseCase(context, syncStateRepo, cloudRepo, linkDetailHelpers)
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
    }

    @Test
    fun `a candidate whose link is active is kept`() = runTest {
        val row = candidate("cloud-1")
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 1))

        val verified = useCase.verifyActiveBackups(userId, listOf(row))

        assertEquals(listOf(row.localUri), verified.map { it.localUri })
    }

    @Test
    fun `a candidate whose link is trashed is dropped`() = runTest {
        // State 2 is a trashed link: present on the server but not a copy the sweep may act on.
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } returns
            mapOf("cloud-1" to linkAt("cloud-1", state = 2))

        val verified = useCase.verifyActiveBackups(userId, listOf(candidate("cloud-1")))

        assertTrue("a trashed link must not be reclaimed", verified.isEmpty())
    }

    @Test
    fun `a candidate absent from the answer is dropped`() = runTest {
        // A link the server omits (404 / gone) never appears in the map, so it stays on the device.
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } returns emptyMap()

        val verified = useCase.verifyActiveBackups(userId, listOf(candidate("cloud-1")))

        assertTrue("an unconfirmed link must not be reclaimed", verified.isEmpty())
    }

    @Test
    fun `a candidate with no cloud link is dropped and never looked up`() = runTest {
        val verified = useCase.verifyActiveBackups(userId, listOf(candidate(null)))

        assertTrue("a row with no cloud link cannot be verified, so it is dropped", verified.isEmpty())
        // A null-link-only set short-circuits before any network call.
        coVerify(exactly = 0) { linkDetailHelpers.batchFetchLinkDetails(any(), any(), any()) }
    }

    @Test
    fun `only the active links survive a mixed batch`() = runTest {
        val rows = listOf(candidate("a"), candidate("b"), candidate("c"), candidate(null))
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } returns mapOf(
            "a" to linkAt("a", state = 1),   // active  -> kept
            "b" to linkAt("b", state = 2),   // trashed -> dropped
            // "c" absent                     // gone    -> dropped
            // null-link "none"               // no id   -> dropped
        )

        val verified = useCase.verifyActiveBackups(userId, rows)

        assertEquals(listOf("a"), verified.mapNotNull { it.cloudFileId })
    }

    @Test
    fun `a transient verification failure propagates so the sweep aborts`() = runTest {
        // batchFetchLinkDetails re-throws a 429 / 5xx / network error rather than returning a short
        // map; verifyActiveBackups must not swallow it, or the caller would delete against an
        // unconfirmed batch.
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } throws
            IOException("simulated 429/5xx transient error")

        val propagated: Throwable? = try {
            useCase.verifyActiveBackups(userId, listOf(candidate("cloud-1")))
            null
        } catch (e: IOException) {
            e
        }

        assertNotNull("a transient verification error must propagate, not be swallowed", propagated)
    }
}
