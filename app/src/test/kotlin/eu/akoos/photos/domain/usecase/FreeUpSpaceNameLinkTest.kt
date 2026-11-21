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
import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
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

/**
 * A device copy linked to its Drive photo by a name rule alone is never counted as backed up: free-up
 * does not list it, and the deletion check keeps it and reports it as a copy it could not check.
 */
class FreeUpSpaceNameLinkTest {

    private val userId = UserId("u1")
    private val volumeId = "vol-1"
    private lateinit var repo: SyncStateRepository
    private lateinit var linkDetailHelpers: LinkDetailHelpers
    private lateinit var resolver: ContentResolver
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var useCase: FreeUpSpaceUseCase
    private val unchecked = InMemoryFreeUpUncheckedStore()

    /** A row whose recorded bytes match the device file exactly, so only the link mark can keep it. */
    private fun row(i: Int, basis: Int) = SyncState(
        localUri = "content://media/external/images/media/$i",
        cloudFileId = "cloud-$i",
        localHash = "hash-$i",
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = 1L,
        sizeBytes = 1024L,
        originalHash = "hash-$i",
        linkBasis = basis,
    )

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        resolver = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        repo = mockk(relaxed = true)
        val cloudRepo = mockk<DrivePhotoRepository>(relaxed = true)
        linkDetailHelpers = mockk(relaxed = true)
        localRepo = mockk(relaxed = true)
        useCase = FreeUpSpaceUseCase(context, repo, cloudRepo, linkDetailHelpers, localRepo, unchecked, InMemoryShortCloudCopyStore())
        coEvery { localRepo.fileStamp(any()) } returns DeviceFileStamp(1024L, 0L)
        coEvery { localRepo.sha1(any()) } answers { "hash-" + firstArg<String>().substringAfterLast('/') }
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
        coEvery { linkDetailHelpers.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } answers {
            thirdArg<List<String>>().associateWith { BatchLinkDto(link = LinkCoreDto(linkId = it, type = 2, state = 1)) }
        }
        every { resolver.delete(any(), any(), any()) } returns 1
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `a copy linked by name is not listed, and kept when handed to the deletion`() = runTest {
        val proven = row(1, LinkBasis.PROVEN)
        val byName = row(2, LinkBasis.NAME)
        val rewritten = row(3, LinkBasis.NAME_REWRITTEN)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(proven, byName, rewritten)
        assertEquals(listOf(proven), useCase.candidates(userId, Long.MAX_VALUE))

        val result = useCase.verifyAndReclaim(userId, listOf(proven, byName, rewritten))

        assertEquals(FreeUpSpaceUseCase.FreeUpResult.Done(freed = 1, unchecked = 2), result)
        coVerify(exactly = 1) { repo.updateStatusAndDeleteLocal(proven.localUri, SyncStatus.CLOUD_ONLY) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(byName.localUri, any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(rewritten.localUri, any()) }
        verify(exactly = 1) { resolver.delete(any(), any(), any()) }
        coVerify(exactly = 0) { localRepo.sha1(byName.localUri) }
        coVerify(exactly = 0) { repo.markQueued(byName.localUri, any(), any()) }
        assertTrue(byName.localUri in unchecked.entries)
        assertTrue(rewritten.localUri in unchecked.entries)
    }

    @Test
    fun `the scheduled sweep never deletes a copy linked by name`() = runTest {
        val byName = row(1, LinkBasis.NAME)
        coEvery { repo.getSyncedBefore(userId, any()) } returns listOf(byName)

        val result = useCase.sweepConfirmed(userId, Long.MAX_VALUE)

        assertEquals(FreeUpSpaceUseCase.FreeUpResult.Done(freed = 0), result)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `the pending delete check drops a copy linked by name`() = runTest {
        val byName = row(1, LinkBasis.NAME_REWRITTEN)
        val proven = row(2, LinkBasis.PROVEN)

        assertEquals(listOf(proven), useCase.confirmForDeletion(userId, listOf(byName, proven)))
        assertTrue(byName.localUri in unchecked.entries)
    }
}
