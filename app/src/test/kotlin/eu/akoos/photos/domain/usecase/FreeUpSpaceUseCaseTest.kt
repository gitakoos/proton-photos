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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the single safety decision of the free-up-space path, extracted from
 * [FreeUpSpaceUseCase.isEligibleForReclamation]: whether a sync-state row's DEVICE copy may be
 * reclaimed. The invariant pinned here is that a device file is deleted only for a photo whose cloud
 * copy is confirmed - a SYNCED row carrying a real backedUpAtMs stamp older than the cutoff - and
 * never for a LOCAL_ONLY, CLOUD_ONLY, UPLOADING, or HIDDEN row, nor a SYNCED row that was merely
 * name/size-paired to a cloud photo without ever being uploaded (null backedUpAtMs). No Android, no
 * Context, no ContentResolver, no MediaStore, no DAO: plain JVM assertions on the inputs.
 */
class FreeUpSpaceUseCaseTest {

    private val cutoffMs = 1_000L

    private fun state(status: SyncStatus, backedUpAtMs: Long?, localHash: String = "hash-1") = SyncState(
        localUri = "uri://photo",
        cloudFileId = if (status == SyncStatus.SYNCED) "cloud-1" else null,
        localHash = localHash,
        cloudHash = null,
        status = status,
        lastSyncAttemptMs = 0L,
        lastSyncSuccessMs = null,
        backedUpAtMs = backedUpAtMs,
        sizeBytes = 1024L,
    )

    @Test
    fun `free-up-space reclaims a synced photo backed up before the cutoff`() {
        assertTrue(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.SYNCED, backedUpAtMs = 500L), cutoffMs,
            )
        )
    }

    @Test
    fun `free-up-space never reclaims a photo that was never backed up`() {
        // A SYNCED row with no backedUpAtMs is only name/size-paired to a cloud photo, never uploaded.
        assertFalse(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.SYNCED, backedUpAtMs = null), cutoffMs,
            )
        )
    }

    @Test
    fun `free-up-space never reclaims a local-only photo`() {
        assertFalse(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.LOCAL_ONLY, backedUpAtMs = 500L), cutoffMs,
            )
        )
    }

    @Test
    fun `free-up-space never reclaims a cloud-only row`() {
        assertFalse(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.CLOUD_ONLY, backedUpAtMs = 500L), cutoffMs,
            )
        )
    }

    @Test
    fun `free-up-space never reclaims an in-flight upload`() {
        assertFalse(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.UPLOADING, backedUpAtMs = 500L), cutoffMs,
            )
        )
    }

    @Test
    fun `free-up-space never reclaims a hidden photo`() {
        assertFalse(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.HIDDEN, backedUpAtMs = 500L), cutoffMs,
            )
        )
    }

    @Test
    fun `the automatic sweep never reclaims a downloaded or restored photo carrying an empty hash`() {
        // A download, or a delete the user undid, re-links the row SYNCED with a real backedUpAtMs but
        // an empty localHash: a copy the user placed on the device, which the background sweep leaves be.
        assertFalse(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.SYNCED, backedUpAtMs = 500L, localHash = ""), cutoffMs,
                protectDownloaded = true,
            )
        )
    }

    @Test
    fun `the manual button still reclaims a downloaded copy carrying an empty hash`() {
        // A deliberate "free up space" tap reclaims every backed-up copy, downloads included, as before.
        assertTrue(
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.SYNCED, backedUpAtMs = 500L, localHash = ""), cutoffMs,
                protectDownloaded = false,
            )
        )
    }

    @Test
    fun `free-up-space leaves a synced photo backed up at or after the cutoff`() {
        // The cutoff is strict (backedUpAtMs < cutoff), matching the DAO's getSyncedBefore query.
        assertFalse(
            "at the cutoff is not before it",
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.SYNCED, backedUpAtMs = cutoffMs), cutoffMs,
            )
        )
        assertFalse(
            "after the cutoff is not eligible",
            FreeUpSpaceUseCase.isEligibleForReclamation(
                state(SyncStatus.SYNCED, backedUpAtMs = 1_500L), cutoffMs,
            )
        )
    }

    @Test
    fun `no non-synced status is ever eligible even with a stamp before the cutoff`() {
        for (status in SyncStatus.values()) {
            if (status == SyncStatus.SYNCED) continue
            assertFalse(
                "status $status must never be reclaimable",
                FreeUpSpaceUseCase.isEligibleForReclamation(state(status, backedUpAtMs = 500L), cutoffMs),
            )
        }
    }
}
