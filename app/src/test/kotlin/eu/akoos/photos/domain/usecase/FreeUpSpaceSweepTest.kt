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
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
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
    private lateinit var useCase: FreeUpSpaceUseCase

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
        useCase = FreeUpSpaceUseCase(context, repo, cloudRepo, linkDetailHelpers)
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
        every { resolver.delete(any(), any(), any()) } returns 1
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `the sweep reclaims the rows Drive confirms and keeps the ones it does not`() = runTest {
        val rows = (1..3).map { eligible(it) }
        coEvery { repo.getSyncedBefore(userId, any()) } returns rows
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } returns mapOf(
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
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } throws IOException("offline")

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
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any()) } returns emptyMap()

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `a sweep with no candidates asks Drive nothing`() = runTest {
        coEvery { repo.getSyncedBefore(userId, any()) } returns emptyList()

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(0, (result as FreeUpSpaceUseCase.FreeUpResult.Done).freed)
        coVerify(exactly = 0) { linkDetailHelpers.batchFetchLinkDetails(any(), any(), any()) }
    }
}
