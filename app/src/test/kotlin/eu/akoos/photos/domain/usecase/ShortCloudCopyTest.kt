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

import eu.akoos.photos.domain.entity.QueueSource
import eu.akoos.photos.domain.entity.SyncState
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which backups are Drive copies short of their device file, and how one is queued to be backed up again. */
class ShortCloudCopyTest {

    private val now = EDITOR_SHORT_COPIES_SINCE_MS + 100_000_000L

    private fun row(
        localHash: String = "h",
        originalHash: String? = null,
        backedUpAtMs: Long? = EDITOR_SHORT_COPIES_SINCE_MS + 1_000L,
        status: SyncStatus = SyncStatus.SYNCED,
        cloudFileId: String? = "L1",
    ) = SyncState(
        localUri = "content://media/external/images/media/1",
        cloudFileId = cloudFileId,
        localHash = localHash,
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = 1L,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = 2048L,
        originalHash = originalHash,
    )

    @Test
    fun `only a Drive size below the device file's proves a copy short`() {
        assertTrue(driveCopyShortOf(1_600L, 2_048L))
        assertFalse(driveCopyShortOf(2_048L, 2_048L))
        assertFalse(driveCopyShortOf(2_100L, 2_048L))
        assertFalse(driveCopyShortOf(0L, 2_048L))
        assertFalse(driveCopyShortOf(null, 2_048L))
        assertFalse(driveCopyShortOf(1_600L, 0L))
    }

    @Test
    fun `a hash stands for the device bytes only when the row recorded them as the bytes uploaded`() {
        // Recorded as the very bytes uploaded.
        assertTrue(hashStandsForDeviceBytes(row(originalHash = "H")))
        // A stripped or compressed upload: the hash is of other bytes.
        assertFalse(hashStandsForDeviceBytes(row(originalHash = "device")))
        // Nothing recorded apart: the upload may have been stripped under a setting changed since.
        assertFalse(hashStandsForDeviceBytes(row()))
        assertFalse(hashStandsForDeviceBytes(row(localHash = "", originalHash = "")))
    }

    @Test
    fun `the editor's copies backed up since the short uploads began are suspects`() {
        assertTrue(maybeShortEditorCopy(row(), "IMG_1_edit_20260801_101500.jpg", "image/jpeg", now))
        assertTrue(maybeShortEditorCopy(row(), "IMG_1_EDIT_20260801_101500.JPEG", "image/jpeg", now))
    }

    @Test
    fun `anything else is no suspect`() {
        val name = "IMG_1_edit_20260801_101500.jpg"
        assertFalse(maybeShortEditorCopy(row(), "IMG_1.jpg", "image/jpeg", now))
        assertFalse(maybeShortEditorCopy(row(), "IMG_1_edit_20260801_101500.png", "image/png", now))
        assertFalse(maybeShortEditorCopy(row(backedUpAtMs = EDITOR_SHORT_COPIES_SINCE_MS - 1L), name, "image/jpeg", now))
        // Backed up once the fixed editor ran.
        assertFalse(maybeShortEditorCopy(row(backedUpAtMs = now), name, "image/jpeg", now))
        assertFalse(maybeShortEditorCopy(row(backedUpAtMs = null), name, "image/jpeg", now))
        assertFalse(maybeShortEditorCopy(row(status = SyncStatus.LOCAL_ONLY), name, "image/jpeg", now))
        assertFalse(maybeShortEditorCopy(row(cloudFileId = null), name, "image/jpeg", now))
        assertFalse(maybeShortEditorCopy(row(localHash = ""), name, "image/jpeg", now))
    }

    @Test
    fun `a short copy's row is queued again with its hash kept and its pairing dropped`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true)
        val userId = UserId("u1")
        val r = row()

        repo.requeueAfterShortCopy(userId, r)

        coVerify {
            repo.upsert(
                r.copy(
                    status = SyncStatus.LOCAL_ONLY, cloudFileId = null, cloudHash = null,
                    backedUpAtMs = null, lastSyncSuccessMs = null,
                ),
                userId,
            )
        }
        coVerify { repo.markQueued(r.localUri, QueueSource.MANUAL, any()) }
        coVerify(exactly = 0) { repo.updateStatusAndDeleteLocal(any(), any()) }
    }

    @Test
    fun `an editor copy is short only when it declares fewer bytes than the file its hash names`() {
        val r = row(localHash = "abc")
        assertEquals(EditorCopyVerdict.SHORT, editorCopyVerdict(r, claimedBytes = 10_000L, deviceSha1 = "ABC", deviceBytes = 10_484L))
        assertEquals(EditorCopyVerdict.WHOLE, editorCopyVerdict(r, claimedBytes = 10_484L, deviceSha1 = "abc", deviceBytes = 10_484L))
        // The device file holds other bytes (a strip on upload), so the whole length is not known.
        assertEquals(EditorCopyVerdict.UNVERIFIABLE, editorCopyVerdict(r, claimedBytes = 10_000L, deviceSha1 = "other", deviceBytes = 10_484L))
        assertEquals(EditorCopyVerdict.UNRESOLVED, editorCopyVerdict(r, claimedBytes = null, deviceSha1 = "abc", deviceBytes = 10_484L))
        assertEquals(EditorCopyVerdict.UNRESOLVED, editorCopyVerdict(r, claimedBytes = 10_000L, deviceSha1 = null, deviceBytes = 10_484L))
    }

    @Test
    fun `a short copy is recorded with its replacement and its row queued again`() = runTest {
        val repo = mockk<SyncStateRepository>(relaxed = true)
        val store = InMemoryShortCloudCopyStore()
        val r = row()

        store.recordShortAndRequeue(repo, UserId("u1"), r)

        assertEquals(setOf("L1"), store.ids)
        assertEquals("L1", store.replacements[r.localUri])
        coVerify { repo.markQueued(r.localUri, QueueSource.MANUAL, any()) }
    }
}
