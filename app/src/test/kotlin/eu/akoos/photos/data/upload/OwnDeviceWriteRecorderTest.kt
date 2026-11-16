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

package eu.akoos.photos.data.upload

import eu.akoos.photos.domain.entity.DeviceFileStamp
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The app's own rewrite of a device photo is recorded as backed up only when the bytes it rewrote were:
 * the ones the row recorded, or, on a row backed up before device bytes were recorded (the original kept
 * beside a stripped upload), bytes free-up would itself have taken for backed up. Anything else stays a
 * change to back up again.
 */
class OwnDeviceWriteRecorderTest {

    private val uri = "content://media/external/images/media/1"
    private val backedUpAtMs = 1_700_000_000_000L
    private val syncRepo = mockk<SyncStateRepository>(relaxed = true)
    private val cloud = mockk<DrivePhotoRepository> { every { cloudContentHash(any()) } returns null }
    private val recorder = OwnDeviceWriteRecorder(syncRepo, cloud)

    private fun row(localHash: String, originalHash: String? = null) = SyncState(
        localUri = uri, cloudFileId = "cloud-1", localHash = localHash, cloudHash = "drive",
        status = SyncStatus.SYNCED, lastSyncAttemptMs = backedUpAtMs, lastSyncSuccessMs = backedUpAtMs,
        backedUpAtMs = backedUpAtMs, sizeBytes = 1000L, originalHash = originalHash,
    )

    private val beforeBackupSec = backedUpAtMs / 1000L - 3600L
    private val afterBackupSec = backedUpAtMs / 1000L + 3600L

    @Test
    fun `a rewrite of the device bytes the row recorded is recorded`() = runTest {
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "uploaded", originalHash = "orig")

        recorder.record(OwnDeviceWrite(uri, "ORIG", DeviceFileStamp(1000L, afterBackupSec), "written"))

        coVerify { syncRepo.recordOwnDeviceWrite(uri, "ORIG", "written") }
    }

    @Test
    fun `a row hash with no device bytes recorded counts only for a file written as the row was stamped`() = runTest {
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "device")
        val longBefore = backedUpAtMs / 1000L - 30L * 24 * 3600

        recorder.record(OwnDeviceWrite(uri, "device", DeviceFileStamp(1000L, longBefore), "written"))
        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWriteOverRow(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWrite(any(), any(), any()) }

        val asStamped = backedUpAtMs / 1000L - 60L
        recorder.record(OwnDeviceWrite(uri, "device", DeviceFileStamp(1000L, asStamped), "written"))
        coVerify { syncRepo.recordOwnDeviceWriteOverRow(uri, "written", "device", backedUpAtMs) }
    }

    @Test
    fun `a metadata edit of an original kept beside a stripped upload is recorded`() = runTest {
        // Backed up before device bytes were recorded: the row holds only the stripped upload's hash.
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "stripped-upload")

        recorder.record(OwnDeviceWrite(uri, "original", DeviceFileStamp(1000L, beforeBackupSec), "written"))

        coVerify { syncRepo.recordOwnDeviceWriteOverRow(uri, "written", "stripped-upload", backedUpAtMs) }
    }

    @Test
    fun `a rewrite of such an original edited elsewhere since the backup is not recorded`() = runTest {
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "stripped-upload")

        recorder.record(OwnDeviceWrite(uri, "edited", DeviceFileStamp(1000L, afterBackupSec), "written"))
        recorder.record(OwnDeviceWrite(uri, "edited", DeviceFileStamp(1200L, beforeBackupSec), "written"))
        recorder.record(OwnDeviceWrite(uri, "edited", null, "written"))

        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWriteOverRow(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWrite(any(), any(), any()) }
    }

    @Test
    fun `a rewrite of a file a row recorded other bytes for is not recorded`() = runTest {
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "uploaded", originalHash = "orig")

        recorder.record(OwnDeviceWrite(uri, "edited", DeviceFileStamp(1000L, beforeBackupSec), "written"))

        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWriteOverRow(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWrite(any(), any(), any()) }
    }

    @Test
    fun `a file an older version linked by name and size alone is not recorded`() = runTest {
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "")
        val longBefore = backedUpAtMs / 1000L - 30L * 24 * 3600

        recorder.record(OwnDeviceWrite(uri, "other-photo", DeviceFileStamp(1000L, longBefore), "written"))

        coVerify(exactly = 0) { syncRepo.recordOwnDeviceWriteOverRow(any(), any(), any(), any()) }
    }

    @Test
    fun `bytes Drive holds as they are count as backed up`() = runTest {
        coEvery { syncRepo.getByUri(uri) } returns row(localHash = "")
        every { cloud.cloudContentHash("drive-bytes") } returns "drive"
        val longBefore = backedUpAtMs / 1000L - 30L * 24 * 3600

        recorder.record(OwnDeviceWrite(uri, "drive-bytes", DeviceFileStamp(1000L, longBefore), "written"))

        coVerify { syncRepo.recordOwnDeviceWriteOverRow(uri, "written", "", backedUpAtMs) }
    }
}
