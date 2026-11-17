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

package eu.akoos.photos.presentation.editor

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.api.dto.LinkPhotoDto
import eu.akoos.photos.data.db.dao.TestDatabase
import eu.akoos.photos.data.repository.SyncStateRepositoryImpl
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.entity.LinkBasis
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import eu.akoos.photos.domain.usecase.InMemoryFreeUpUncheckedStore
import eu.akoos.photos.domain.usecase.InMemoryShortCloudCopyStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device copy of an edit saved from a backed-up photo is backed up by the editor's own upload, a second
 * encoding of the edit. Free-up may delete that device copy only when its bytes are proven on Drive. The rows
 * are written through the real sync table, the way the editor writes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncedEditCopyFreeUpTest {

    private val userId = UserId("u1")
    private val volumeId = "vol-1"
    private val uri = "content://media/external/images/media/42"
    private val linkId = "edit-link"
    private val uploadSha1 = "a1".repeat(20)
    private val deviceSize = 844_290L

    /** The device copy was written at the save, a few seconds before its upload was in. */
    private val savedAtSec = 1_800_000_000L
    private val uploadedAtMs = savedAtSec * 1000L + 9_000L

    private lateinit var db: TestDatabase
    private lateinit var repo: SyncStateRepositoryImpl
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var resolver: ContentResolver
    private val shortCopies = InMemoryShortCloudCopyStore()
    private val unchecked = InMemoryFreeUpUncheckedStore()
    private lateinit var useCase: FreeUpSpaceUseCase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TestDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
        repo = SyncStateRepositoryImpl(db.syncStateDao())
        resolver = mockk(relaxed = true)
        every { resolver.delete(any(), any(), any()) } returns 1
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        val cloudRepo = mockk<DrivePhotoRepository>(relaxed = true)
        coEvery { cloudRepo.getVolumeId(userId) } returns volumeId
        every { cloudRepo.cloudContentHash(any()) } answers { "drive-" + firstArg<String>() }
        val links = mockk<LinkDetailHelpers>(relaxed = true)
        // Drive holds exactly the bytes the editor uploaded, whole.
        coEvery { links.batchFetchLinkDetails(userId, volumeId, any(), strict = true) } returns mapOf(
            linkId to BatchLinkDto(
                link = LinkCoreDto(linkId = linkId, type = 2, state = 1, size = deviceSize * 2),
                photo = LinkPhotoDto(contentHash = "drive-$uploadSha1"),
            ),
        )
        localRepo = mockk(relaxed = true)
        coEvery { localRepo.fileStamp(uri) } returns DeviceFileStamp(deviceSize, savedAtSec)
        useCase = FreeUpSpaceUseCase(context, repo, cloudRepo, links, localRepo, unchecked, shortCopies)
    }

    @After
    fun tearDown() = db.close()

    private fun deviceFile(name: String, sha1: String) {
        coEvery { localRepo.sha1(uri) } returns sha1
        coEvery { localRepo.queryByUri(uri) } returns LocalMediaItem(
            uri = uri, dateTaken = 1000L, displayName = name, mimeType = "image/jpeg", sizeBytes = deviceSize,
            bucketName = "Pictures",
        )
    }

    /** The editor's UPLOADING placeholder, written right after the device copy was saved. */
    private suspend fun placeholder(): SyncState {
        val row = SyncState(
            localUri = uri, cloudFileId = null, localHash = "", cloudHash = null,
            status = SyncStatus.UPLOADING, lastSyncAttemptMs = 1L, lastSyncSuccessMs = null,
            backedUpAtMs = null, sizeBytes = 0L,
        )
        repo.upsert(row, userId)
        return row
    }

    /** The SYNCED row the editor writes once its upload of the copy is in. */
    private fun SyncState.backedUp() = copy(
        cloudFileId = linkId, localHash = uploadSha1, sizeBytes = deviceSize, status = SyncStatus.SYNCED,
        lastSyncSuccessMs = uploadedAtMs, backedUpAtMs = uploadedAtMs,
    )

    private suspend fun storedRow(): SyncState = repo.getByUri(uri)!!

    @Test
    fun `an edited copy written as backed up while its bytes are not on Drive is no longer deleted`() = runTest {
        // A synced PNG edited and saved as a copy: the device copy was a PNG, the upload a JPEG, and the row
        // was written as an ordinary backup, as before this change.
        repo.upsert(placeholder().backedUp(), userId)
        deviceFile("Screenshot_1_edit_20261117_094842.png", sha1 = "b2".repeat(20))
        val row = storedRow()
        assertEquals(LinkBasis.PROVEN, row.linkBasis)
        assertNull(row.originalHash)

        // Size and time alone took it for the backed-up photo: the only copy of the PNG would have gone.
        assertEquals(
            FreeUpSpaceUseCase.DeviceCopyCheck.UNCHANGED,
            FreeUpSpaceUseCase.deviceCopyVerdict(row, deviceSize, savedAtSec, "b2".repeat(20), false, "drive-$uploadSha1"),
        )
        assertTrue(useCase.confirmForDeletion(userId, listOf(row)).isEmpty())
        assertTrue(uri in unchecked.entries)
    }

    @Test
    fun `an edited copy that holds the uploaded bytes is recorded with them and may be freed`() = runTest {
        // The run's case: a JPEG copy whose device file is the file the editor uploaded.
        recordSyncedEditCopy(repo, shortCopies, userId, provenDeviceSha1 = uploadSha1, backedUp = placeholder().backedUp())
        deviceFile("pfpt_ec_edit_20261117_094842.jpg", sha1 = uploadSha1)
        val row = storedRow()
        assertEquals(SyncStatus.SYNCED, row.status)
        assertEquals(uploadSha1, row.originalHash)
        assertTrue(shortCopies.unverifiableIds.isEmpty())

        assertEquals(listOf(row), useCase.candidates(userId, Long.MAX_VALUE))
        assertEquals(listOf(row), useCase.confirmForDeletion(userId, listOf(row)))
    }

    @Test
    fun `an edited copy whose upload was stripped of its metadata may be freed once that is proven`() = runTest {
        val deviceSha1 = "c3".repeat(20)
        recordSyncedEditCopy(repo, shortCopies, userId, provenDeviceSha1 = deviceSha1, backedUp = placeholder().backedUp())
        deviceFile("pfpt_ec_edit_20261117_094842.jpg", sha1 = deviceSha1)
        val row = storedRow()
        assertEquals(deviceSha1, row.originalHash)

        assertEquals(listOf(row), useCase.confirmForDeletion(userId, listOf(row)))
    }

    @Test
    fun `an edited copy whose bytes are not proven on Drive is kept for good, whatever its name`() = runTest {
        recordSyncedEditCopy(repo, shortCopies, userId, provenDeviceSha1 = null, backedUp = placeholder().backedUp())
        deviceFile("renamed.jpg", sha1 = "d4".repeat(20))
        val row = storedRow()
        assertEquals(SyncStatus.SYNCED, row.status)
        assertNull(row.originalHash)
        assertEquals(setOf(linkId), shortCopies.unverifiableIds)

        assertTrue(useCase.confirmForDeletion(userId, listOf(row)).isEmpty())
        assertTrue(uri in unchecked.entries)
    }

    @Test
    fun `the upload stands for the device copy only when the bytes match`() {
        var strippedRead = false
        assertTrue(editCopyOnDrive(uploadSha1, uploadSha1) { strippedRead = true; null })
        assertFalse("the strip is not run for bytes that already match", strippedRead)
        assertTrue(editCopyOnDrive(uploadSha1.uppercase(), uploadSha1) { null })
        assertTrue(editCopyOnDrive("e5".repeat(20), uploadSha1) { uploadSha1 })
        assertFalse(editCopyOnDrive("e5".repeat(20), uploadSha1) { "f6".repeat(20) })
        assertFalse(editCopyOnDrive("e5".repeat(20), uploadSha1) { null })
        assertFalse(editCopyOnDrive(null, uploadSha1) { uploadSha1 })
    }

    @Test
    fun `only an editor copy's name marks it`() {
        assertTrue(eu.akoos.photos.domain.usecase.isEditorCopyName("IMG_1_edit_20261117_094842.jpg"))
        assertTrue(eu.akoos.photos.domain.usecase.isEditorCopyName("Screenshot_edit_20261117_094842.PNG"))
        assertTrue(eu.akoos.photos.domain.usecase.isEditorCopyName("a_edit_20261117_094842.webp"))
        assertFalse(eu.akoos.photos.domain.usecase.isEditorCopyName("IMG_1_edit.jpg"))
        assertFalse(eu.akoos.photos.domain.usecase.isEditorCopyName("IMG_20261117_094842.jpg"))
    }
}
