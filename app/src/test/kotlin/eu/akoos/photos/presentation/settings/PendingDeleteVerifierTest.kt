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

package eu.akoos.photos.presentation.settings

import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The stored delete queue is checked again right before the system trash dialog: a URI whose Drive
 * copy is gone or whose device file changed since it was queued never reaches the request, and so is
 * never marked cloud-only on approval.
 */
class PendingDeleteVerifierTest {

    private val userId = UserId("u1")
    private lateinit var repo: SyncStateRepository
    private lateinit var freeUp: FreeUpSpaceUseCase
    private lateinit var verifier: PendingDeleteVerifier

    private fun row(i: Int, status: SyncStatus = SyncStatus.SYNCED, backedUpAtMs: Long? = 1L) = SyncState(
        localUri = "content://media/external/images/media/$i",
        cloudFileId = "cloud-$i",
        localHash = "hash-$i",
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = 1024L,
    )

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        freeUp = mockk(relaxed = true)
        verifier = PendingDeleteVerifier(repo, freeUp)
    }

    @Test
    fun `only the URIs the check confirms go into the request`() = runTest {
        val rows = (1..3).map { row(it) }
        val uris = rows.map { it.localUri }.toSet()
        coEvery { repo.cloudPairedLinkIds(userId, any()) } returns rows.associate { it.localUri to it.cloudFileId!! }
        coEvery { repo.getByUris(any()) } returns rows
        // Row 2's Drive copy was trashed elsewhere; row 3's device file was edited.
        coEvery { freeUp.confirmForDeletion(userId, rows) } returns listOf(rows[0])

        val outcome = verifier.verify(userId, uris)

        assertEquals(setOf(rows[0].localUri), outcome.confirmed)
        assertEquals(setOf(rows[1].localUri, rows[2].localUri), outcome.failed)
    }

    @Test
    fun `a URI of another account, or one no longer backed up, is never checked or asked for`() = runTest {
        val mine = row(1)
        val notBackedUp = row(2, backedUpAtMs = null)
        val cloudOnly = row(3, status = SyncStatus.CLOUD_ONLY)
        val foreign = row(4)
        val uris = setOf(mine, notBackedUp, cloudOnly, foreign).map { it.localUri }.toSet()
        coEvery { repo.cloudPairedLinkIds(userId, any()) } returns
            listOf(mine, notBackedUp, cloudOnly).associate { it.localUri to it.cloudFileId!! }
        coEvery { repo.getByUris(any()) } returns listOf(mine, notBackedUp, cloudOnly)
        coEvery { freeUp.confirmForDeletion(userId, listOf(mine)) } returns listOf(mine)

        val outcome = verifier.verify(userId, uris)

        assertEquals(setOf(mine.localUri), outcome.confirmed)
        assertEquals(uris - mine.localUri, outcome.failed)
        coVerify(exactly = 1) { freeUp.confirmForDeletion(userId, listOf(mine)) }
    }

    @Test
    fun `a Drive lookup that fails reaches the caller, which then asks for nothing`() = runTest {
        val rows = listOf(row(1))
        coEvery { repo.cloudPairedLinkIds(userId, any()) } returns mapOf(rows[0].localUri to "cloud-1")
        coEvery { repo.getByUris(any()) } returns rows
        coEvery { freeUp.confirmForDeletion(userId, rows) } throws IOException("offline")

        var thrown: IOException? = null
        try {
            verifier.verify(userId, setOf(rows[0].localUri))
        } catch (e: IOException) {
            thrown = e
        }

        assertTrue(thrown != null)
    }
}
