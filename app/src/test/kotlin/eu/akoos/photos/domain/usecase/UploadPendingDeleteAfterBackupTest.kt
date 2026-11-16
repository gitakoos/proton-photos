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

import eu.akoos.photos.domain.usecase.UploadPendingUseCase.DeleteRecheck
import eu.akoos.photos.domain.usecase.UploadPendingUseCase.Removal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-value coverage for the single safety decision of the delete-after-backup path, extracted from
 * [UploadPendingUseCase.resolveDeleteAfterBackupRemoval]: what happens to a photo's DEVICE copy once
 * its Drive copy is committed. The invariants pinned here are that the device copy is touched only
 * when the user opted in AND the upload actually succeeded, and that from R the removal is
 * recoverable (system trash) while below R - which has no system trash - it stays a permanent
 * removal. No Android, no Context, no ContentResolver, no MediaStore, no DAO: plain JVM assertions
 * on the inputs.
 */
class UploadPendingDeleteAfterBackupTest {

    private companion object {
        const val API_O = 26
        const val API_P = 28
        const val API_Q = 29
        const val API_R = 30
        const val API_U = 34
    }

    private fun removal(sdkInt: Int, deleteLocalAfterBackup: Boolean = true, uploadSucceeded: Boolean = true) =
        UploadPendingUseCase.resolveDeleteAfterBackupRemoval(
            sdkInt = sdkInt,
            deleteLocalAfterBackup = deleteLocalAfterBackup,
            uploadSucceeded = uploadSucceeded,
        )

    @Test
    fun `delete-after-backup trashes the device copy on R`() {
        // R is where the system trash arrives, so the device copy becomes recoverable from here on.
        assertEquals(Removal.TRASH, removal(API_R))
    }

    @Test
    fun `delete-after-backup trashes the device copy above R`() {
        assertEquals(Removal.TRASH, removal(API_U))
    }

    @Test
    fun `delete-after-backup removes the device copy outright below R`() {
        // No system trash exists pre-R, so the only available removal is a permanent one.
        assertEquals(Removal.PERMANENT, removal(API_O))
        assertEquals(Removal.PERMANENT, removal(API_P))
        assertEquals(Removal.PERMANENT, removal(API_Q))
    }

    @Test
    fun `the setting off never touches the device copy`() {
        for (sdkInt in listOf(API_O, API_Q, API_R, API_U)) {
            assertEquals(
                "sdk $sdkInt must keep the device copy when the setting is off",
                Removal.NONE,
                removal(sdkInt, deleteLocalAfterBackup = false),
            )
        }
    }

    @Test
    fun `a failed upload never touches the device copy`() {
        // Removing here would cost the user the only copy of the photo: there is no Drive copy.
        for (sdkInt in listOf(API_O, API_Q, API_R, API_U)) {
            assertEquals(
                "sdk $sdkInt must keep the device copy when the upload failed",
                Removal.NONE,
                removal(sdkInt, uploadSucceeded = false),
            )
        }
    }

    @Test
    fun `a failed upload wins over the setting being on`() {
        assertEquals(Removal.NONE, removal(API_R, deleteLocalAfterBackup = true, uploadSucceeded = false))
    }

    @Test
    fun `nothing is removed when the setting is off and the upload failed`() {
        assertEquals(Removal.NONE, removal(API_R, deleteLocalAfterBackup = false, uploadSucceeded = false))
    }

    @Test
    fun `the device file still holding the bytes the upload read may be removed`() {
        assertEquals(DeleteRecheck.DELETE, UploadPendingUseCase.deleteAfterBackupRecheck("abc", "abc"))
        assertEquals(DeleteRecheck.DELETE, UploadPendingUseCase.deleteAfterBackupRecheck("ABC", "abc"))
    }

    @Test
    fun `a device file changed since the upload read it is kept`() {
        assertEquals(DeleteRecheck.CHANGED, UploadPendingUseCase.deleteAfterBackupRecheck("abc", "edited"))
    }

    @Test
    fun `a device file that cannot be read now is kept`() {
        assertEquals(DeleteRecheck.UNREADABLE, UploadPendingUseCase.deleteAfterBackupRecheck("abc", null))
    }

    @Test
    fun `nothing to compare against keeps the device file`() {
        // A mirror rewrite whose bytes could not be read back leaves no expected hash.
        assertEquals(DeleteRecheck.UNREADABLE, UploadPendingUseCase.deleteAfterBackupRecheck(null, "abc"))
        assertEquals(DeleteRecheck.UNREADABLE, UploadPendingUseCase.deleteAfterBackupRecheck("", "abc"))
    }

    @Test
    fun `the original hash is the upload's own when the device bytes went up as they are`() {
        assertEquals(
            "upload",
            UploadPendingUseCase.recordedOriginalHash(
                deviceRewritten = false, sentDeviceBytes = true, uploadHash = "upload", sourceHash = null,
            ),
        )
    }

    @Test
    fun `the original hash is the one read before a strip or compression`() {
        assertEquals(
            "source",
            UploadPendingUseCase.recordedOriginalHash(
                deviceRewritten = false, sentDeviceBytes = false, uploadHash = "stripped", sourceHash = "source",
            ),
        )
    }

    @Test
    fun `no original hash is recorded once a mirror rewrote the device file`() {
        for (sent in listOf(true, false)) {
            assertEquals(
                null,
                UploadPendingUseCase.recordedOriginalHash(
                    deviceRewritten = true, sentDeviceBytes = sent, uploadHash = "u", sourceHash = "s",
                ),
            )
        }
    }

    @Test
    fun `before the delete the device file is compared with the original hash when there is one`() {
        assertEquals(
            "orig",
            UploadPendingUseCase.expectedDeviceHashBeforeDelete(
                originalHash = "orig", deviceRewritten = false, sentDeviceBytes = false,
                uploadHash = "u", rewrittenHash = null,
            ),
        )
    }

    @Test
    fun `after a mirror rewrite the device file is compared with the bytes the mirror wrote`() {
        assertEquals(
            "mirror",
            UploadPendingUseCase.expectedDeviceHashBeforeDelete(
                originalHash = null, deviceRewritten = true, sentDeviceBytes = false,
                uploadHash = "u", rewrittenHash = "mirror",
            ),
        )
        assertEquals(
            "unreadable mirror bytes leave nothing to compare against",
            null,
            UploadPendingUseCase.expectedDeviceHashBeforeDelete(
                originalHash = null, deviceRewritten = true, sentDeviceBytes = false,
                uploadHash = "u", rewrittenHash = null,
            ),
        )
        assertEquals(
            "an in-place strip uploads the rewritten device file itself",
            "u",
            UploadPendingUseCase.expectedDeviceHashBeforeDelete(
                originalHash = null, deviceRewritten = true, sentDeviceBytes = true,
                uploadHash = "u", rewrittenHash = null,
            ),
        )
    }
}
