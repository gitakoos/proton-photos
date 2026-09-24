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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one judgement behind reconcile's gone-file steps, the demotion of a SYNCED row to CLOUD_ONLY and
 * the drop of a queued LOCAL_ONLY row with its upload intent: a file counts as gone only on a scan that
 * saw the whole device, found something, and missed the file, and only for a row that was in the table
 * before that scan began.
 */
class ReconcileGoneFileGuardTest {

    private val scanStart = 1_000_000L
    private val onDevice = setOf("content://media/external/images/media/1")
    private val missing = "content://media/external/images/media/2"

    private fun row(
        uri: String = missing,
        attempt: Long = scanStart - 5_000L,
        success: Long? = null,
        queuedAt: Long? = null,
    ) = SyncState(
        localUri = uri,
        cloudFileId = "link",
        localHash = "",
        cloudHash = null,
        status = SyncStatus.SYNCED,
        lastSyncAttemptMs = attempt,
        lastSyncSuccessMs = success,
        backedUpAtMs = null,
        sizeBytes = 1L,
        queued = queuedAt != null,
        queueSource = queuedAt?.let { QueueSource.MANUAL },
        queuedAt = queuedAt,
    )

    // ── the scan-start guard ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a row whose every stamp predates the scan was written before it`() {
        assertFalse(writtenSinceScan(row(success = scanStart - 1, queuedAt = scanStart - 1), scanStart))
        assertFalse(writtenSinceScan(row(attempt = 0L), scanStart))
    }

    @Test
    fun `any one stamp at or after the scan start marks the row as written since`() {
        assertTrue("a download marked SYNCED mid-pass", writtenSinceScan(row(success = scanStart + 10), scanStart))
        assertTrue("a back-up-now queued mid-pass", writtenSinceScan(row(queuedAt = scanStart + 10), scanStart))
        assertTrue("a row created mid-pass", writtenSinceScan(row(attempt = scanStart + 10), scanStart))
        assertTrue("the same millisecond is as unknown as a later one", writtenSinceScan(row(success = scanStart), scanStart))
    }

    // ── the gone-file judgement ──────────────────────────────────────────────────────────────────

    @Test
    fun `a file missing from a complete scan is gone for a row that predates it`() {
        assertTrue(deviceFileGone(row(), scanComplete = true, deviceUris = onDevice, scanStartMs = scanStart))
    }

    @Test
    fun `an incomplete scan proves nothing, so nothing is gone`() {
        assertFalse(deviceFileGone(row(), scanComplete = false, deviceUris = onDevice, scanStartMs = scanStart))
    }

    @Test
    fun `an empty scan proves nothing even when it calls itself complete`() {
        assertFalse(deviceFileGone(row(), scanComplete = true, deviceUris = emptySet(), scanStartMs = scanStart))
    }

    @Test
    fun `a file the scan returned is not gone`() {
        assertFalse(
            deviceFileGone(row(uri = onDevice.single()), scanComplete = true, deviceUris = onDevice, scanStartMs = scanStart),
        )
    }

    @Test
    fun `a row written since the scan began is left for the next pass`() {
        assertFalse(
            deviceFileGone(row(success = scanStart + 1), scanComplete = true, deviceUris = onDevice, scanStartMs = scanStart),
        )
        assertFalse(
            deviceFileGone(row(queuedAt = scanStart + 1), scanComplete = true, deviceUris = onDevice, scanStartMs = scanStart),
        )
    }
}
