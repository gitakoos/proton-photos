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

import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase.Companion.deviceCopyVerdict
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase.DeviceCopyCheck
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The last check before free-up deletes a device copy: the file must still hold what was backed up.
 * A file another app edited in place keeps its uri and its pairing, so the sync row alone can read
 * backed up while the device holds an edit Drive never saw.
 */
class FreeUpSpaceDeviceCheckTest {

    private val backedUpAtMs = 1_700_000_000_000L

    private fun row(localHash: String = "abc", cloudHash: String? = null, sizeBytes: Long = 1000L) = SyncState(
        localUri = "content://media/external/images/media/1",
        cloudFileId = "cloud-1",
        localHash = localHash,
        cloudHash = cloudHash,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = backedUpAtMs,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = sizeBytes,
    )

    private val beforeBackupSec = backedUpAtMs / 1000L - 60L
    private val afterBackupSec = backedUpAtMs / 1000L + 60L

    @Test
    fun `the bytes recorded at backup are unchanged`() {
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(row(), 1000L, afterBackupSec, "abc", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `bytes Drive holds as they are are unchanged`() {
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(row(), 5L, afterBackupSec, "other", deviceOnDrive = true, driveHash = "d"),
        )
    }

    @Test
    fun `a file that could not be read is unknown`() {
        assertEquals(
            DeviceCopyCheck.UNKNOWN,
            deviceCopyVerdict(row(), 1000L, beforeBackupSec, null, deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `an edit that changed the size is changed`() {
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(row(), 1200L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `an edit of the same size written after the backup is changed`() {
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(row(), 1000L, afterBackupSec, "edited", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `an original kept beside a stripped upload, untouched since, is unchanged`() {
        // The row records the hash of the stripped bytes that went up, so the original never matches it.
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(row(), 1000L, beforeBackupSec, "original", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a row with no recorded hash is judged by size and modified time`() {
        val download = row(localHash = "")
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(download, 1000L, beforeBackupSec, "x", deviceOnDrive = false, driveHash = null),
        )
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(download, 1000L, afterBackupSec, "x", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `no modified time or no recorded size is unknown, not changed`() {
        assertEquals(
            DeviceCopyCheck.UNKNOWN,
            deviceCopyVerdict(row(), 1000L, 0L, "edited", deviceOnDrive = false, driveHash = null),
        )
        assertEquals(
            DeviceCopyCheck.UNKNOWN,
            deviceCopyVerdict(row(sizeBytes = 0L), 1000L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `a Drive copy that is no longer the one paired leaves the device copy unknown`() {
        assertEquals(
            DeviceCopyCheck.UNKNOWN,
            deviceCopyVerdict(row(cloudHash = "paired"), 1000L, beforeBackupSec, "abc", deviceOnDrive = false, driveHash = "new"),
        )
    }
}
