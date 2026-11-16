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

    private fun row(
        localHash: String = "abc",
        cloudHash: String? = null,
        sizeBytes: Long = 1000L,
        originalHash: String? = null,
    ) = SyncState(
        localUri = "content://media/external/images/media/1",
        cloudFileId = "cloud-1",
        localHash = localHash,
        cloudHash = cloudHash,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = backedUpAtMs,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = sizeBytes,
        originalHash = originalHash,
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
    fun `a file that could not be read is unreadable`() {
        assertEquals(
            DeviceCopyCheck.UNREADABLE,
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
    fun `no modified time or no recorded size is unchecked, not changed`() {
        assertEquals(
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(row(), 1000L, 0L, "edited", deviceOnDrive = false, driveHash = null),
        )
        assertEquals(
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(row(sizeBytes = 0L), 1000L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `a Drive copy that is no longer the one paired leaves the device copy unchecked`() {
        assertEquals(
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(row(cloudHash = "paired"), 1000L, beforeBackupSec, "abc", deviceOnDrive = false, driveHash = "new"),
        )
    }

    // An original kept beside a stripped or compressed upload: the row's hash is the upload's, and
    // originalHash is the device file's own as the backup read it.
    private fun stripped() = row(localHash = "uploaded", originalHash = "original")

    @Test
    fun `an original whose bytes are the ones the backup read is unchanged`() {
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(stripped(), 1000L, afterBackupSec, "original", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            "the hash compares in either case",
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(row(localHash = "uploaded", originalHash = "ABC"), 1000L, afterBackupSec, "abc", false, "d"),
        )
    }

    @Test
    fun `an original edited in place with its size and time kept is changed`() {
        // Size and modified time say nothing changed; only the bytes tell.
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(stripped(), 1000L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a recorded original hash outranks size and time, which are not read for it`() {
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(stripped(), 0L, 0L, "edited", deviceOnDrive = false, driveHash = null),
        )
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(stripped(), 0L, 0L, "original", deviceOnDrive = false, driveHash = null),
        )
    }

    @Test
    fun `an original that cannot be read is unreadable even with its hash recorded`() {
        assertEquals(
            DeviceCopyCheck.UNREADABLE,
            deviceCopyVerdict(stripped(), 1000L, beforeBackupSec, null, deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a row with no original hash keeps the size and time rule`() {
        // A row backed up before the original hash was recorded.
        val old = row(localHash = "uploaded", originalHash = null)
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(old, 1000L, beforeBackupSec, "original", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(old, 1000L, afterBackupSec, "original", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `the device holding the uploaded bytes is unchanged whatever the original hash`() {
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(stripped(), 1000L, afterBackupSec, "uploaded", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a Drive copy no longer the one paired stays unchecked with an original hash recorded`() {
        val paired = row(localHash = "uploaded", cloudHash = "paired", originalHash = "original")
        assertEquals(
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(paired, 1000L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = "new"),
        )
    }

    // ── Rows backed up before the original hash was recorded ───────────────────────────────────────

    @Test
    fun `a same-size edit of an old row is changed once the modified time is read`() {
        // The edit keeps the size; before the modified time was read this stayed undecided forever.
        val old = row(localHash = "uploaded", originalHash = null)
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(old, 1000L, afterBackupSec, "edited", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `an uploaded row at another size is changed even with no modified time`() {
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(row(), 1200L, 0L, "edited", deviceOnDrive = false, driveHash = null),
        )
    }

    // ── Bytes this app wrote itself ─────────────────────────────────────────────────────────────────

    /** A download whose saved copy took the date and place the app stamped into it, as recorded now. */
    private fun download(originalHash: String? = "stamped") =
        row(localHash = "", sizeBytes = 1100L, originalHash = originalHash)

    @Test
    fun `a download the app stamped with its place and date is unchanged whatever its modified time`() {
        for (modified in listOf(beforeBackupSec, afterBackupSec, 0L)) {
            assertEquals(
                DeviceCopyCheck.UNCHANGED,
                deviceCopyVerdict(download(), 1100L, modified, "stamped", deviceOnDrive = false, driveHash = "d"),
            )
        }
    }

    @Test
    fun `a same-size edit of a stamped download is changed`() {
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(download(), 1100L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `an older download whose stamp grew the file is unchecked, not changed`() {
        // Its row recorded the Drive size before the stamp wrote the place in; nothing was written since.
        val old = row(localHash = "", sizeBytes = 1000L, originalHash = null)
        assertEquals(
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(old, 1100L, beforeBackupSec, "stamped", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            "a write after the backup still tells",
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(old, 1100L, afterBackupSec, "edited", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a metadata write the app recorded after the backup is unchanged`() {
        val written = row(localHash = "abc", originalHash = "after-metadata-write")
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(written, 1040L, afterBackupSec, "after-metadata-write", false, driveHash = "d"),
        )
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(written, 1040L, afterBackupSec, "edited", false, driveHash = "d"),
        )
    }

    @Test
    fun `a mirror rewrite is unchanged and a same-size edit after it is changed`() {
        // The mirror wrote stripped bytes to the device; the row records them as its hash and as its original.
        val mirrored = row(localHash = "mirrored", originalHash = "mirrored")
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(mirrored, 1000L, afterBackupSec, "mirrored", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(mirrored, 1000L, beforeBackupSec, "edited", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a mirror whose bytes could not be read back is judged by its hash and then its modified time`() {
        val mirrored = row(localHash = "uploaded", originalHash = null)
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(mirrored, 1000L, beforeBackupSec, "uploaded", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            DeviceCopyCheck.CHANGED,
            deviceCopyVerdict(mirrored, 1000L, afterBackupSec, "edited", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a file an older version linked by name and size alone is unchecked unless written as it was linked`() {
        val relinked = row(localHash = "", originalHash = null)
        val longBefore = backedUpAtMs / 1000L - 30L * 24 * 3600
        assertEquals(
            "a different photo of the same name and size is never taken for backed up",
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(relinked, 1000L, longBefore, "other-photo", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            "the photo's own bytes still read as backed up",
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(relinked, 1000L, longBefore, "drive-bytes", deviceOnDrive = true, driveHash = "d"),
        )
        assertEquals(
            "a copy written as its row was linked is the download itself",
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(relinked, 1000L, beforeBackupSec, "stamped", deviceOnDrive = false, driveHash = "d"),
        )
    }

    @Test
    fun `a row hash adopted from the file before device bytes were recorded counts only for a file written as it was stamped`() {
        val adopted = row(localHash = "device", originalHash = null)
        val longBefore = backedUpAtMs / 1000L - 30L * 24 * 3600
        assertEquals(
            DeviceCopyCheck.UNCHECKED,
            deviceCopyVerdict(adopted, 1000L, longBefore, "device", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(adopted, 1000L, beforeBackupSec, "device", deviceOnDrive = false, driveHash = "d"),
        )
        assertEquals(
            "with the device bytes recorded the hash decides",
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(row(localHash = "device", originalHash = "orig"), 1000L, longBefore, "device", false, "d"),
        )
        assertEquals(
            "bytes Drive holds need no modified time",
            DeviceCopyCheck.UNCHANGED,
            deviceCopyVerdict(adopted, 1000L, longBefore, "device", deviceOnDrive = true, driveHash = "d"),
        )
    }
}
