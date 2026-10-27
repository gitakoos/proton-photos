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

package eu.akoos.photos.worker

import androidx.work.NetworkType
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.domain.repository.FREE_UP_PENDING_OFFER_STALE_MS
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FreeUpPipelineWorker companion contracts: unique-name isolation, progress keys,
 * [FreeUpPipelineWorker.mapPipelineOutcome], and retry / consent-skip predicates.
 */
class FreeUpPipelineWorkerTest {

    @Test
    fun `manual and automatic unique names stay distinct`() {
        assertEquals("manual_free_up", ManualFreeUpWorker.UNIQUE_NAME)
        assertEquals("free_up_worker", FreeUpSpaceWorker.TAG)
        assertEquals("free_up_worker_oneshot", FreeUpSpaceWorker.NAME_ONESHOT)
        assertNotEquals(ManualFreeUpWorker.UNIQUE_NAME, FreeUpSpaceWorker.TAG)
        assertNotEquals(ManualFreeUpWorker.UNIQUE_NAME, FreeUpSpaceWorker.NAME_ONESHOT)
    }

    @Test
    fun `manual constraints require network only`() {
        val constraints = FreeUpPipelineWorker.manualConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
    }

    @Test
    fun `automatic constraints match the sweep gate set`() {
        val constraints = FreeUpPipelineWorker.automaticConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertEquals(FreeUpSpaceWorker.sweepConstraints().requiredNetworkType, constraints.requiredNetworkType)
    }

    @Test
    fun `progress key contract is phase plus counts only`() {
        assertEquals("phase", FreeUpPipelineWorker.KEY_PHASE)
        assertEquals("processed", FreeUpPipelineWorker.KEY_PROCESSED)
        assertEquals("total", FreeUpPipelineWorker.KEY_TOTAL)
        assertEquals("removed", FreeUpPipelineWorker.KEY_REMOVED)
        assertEquals("needs_permission_count", FreeUpPipelineWorker.KEY_NEEDS_PERMISSION_COUNT)
        assertEquals("verifying", FreeUpPipelineWorker.PHASE_VERIFYING)
        assertEquals("deleting", FreeUpPipelineWorker.PHASE_DELETING)
        assertEquals("awaiting_consent", FreeUpPipelineWorker.PHASE_AWAITING_CONSENT)
    }

    @Test
    fun `progress data never embeds URI lists or done alias`() {
        val data = FreeUpPipelineWorker.progressData(
            phase = FreeUpPipelineWorker.PHASE_DELETING,
            processed = 3,
            total = 10,
            removed = 2,
            needsPermissionCount = 1,
        )
        assertEquals(FreeUpPipelineWorker.PHASE_DELETING, data.getString(FreeUpPipelineWorker.KEY_PHASE))
        assertEquals(3, data.getInt(FreeUpPipelineWorker.KEY_PROCESSED, -1))
        assertEquals(10, data.getInt(FreeUpPipelineWorker.KEY_TOTAL, -1))
        assertEquals(2, data.getInt(FreeUpPipelineWorker.KEY_REMOVED, -1))
        assertEquals(1, data.getInt(FreeUpPipelineWorker.KEY_NEEDS_PERMISSION_COUNT, -1))
        assertFalse(data.keyValueMap.containsKey("done"))
        assertFalse(data.keyValueMap.keys.any { it.contains("uri", ignoreCase = true) })
    }

    @Test
    fun `automatic trigger protects downloads and ages by interval`() {
        val data = FreeUpPipelineWorker.automaticInputData(86_400_000L)
        assertEquals(
            FreeUpPipelineWorker.TRIGGER_AUTOMATIC,
            data.getString(FreeUpPipelineWorker.KEY_TRIGGER),
        )
        assertTrue(FreeUpPipelineWorker.protectDownloadedFor(FreeUpPipelineWorker.TRIGGER_AUTOMATIC))
        assertFalse(FreeUpPipelineWorker.protectDownloadedFor(FreeUpPipelineWorker.TRIGGER_MANUAL))
    }

    @Test
    fun `manual trigger uses max olderThan and does not protect downloads`() {
        val now = 1_750_000_000_000L
        assertEquals(
            Long.MAX_VALUE,
            FreeUpPipelineWorker.olderThanMsFor(FreeUpPipelineWorker.TRIGGER_MANUAL, now, 1L),
        )
        assertEquals(
            now - 86_400_000L,
            FreeUpPipelineWorker.olderThanMsFor(
                FreeUpPipelineWorker.TRIGGER_AUTOMATIC,
                now,
                86_400_000L,
            ),
        )
    }

    @Test
    fun `pipeline phases map to WorkInfo wire names`() {
        assertEquals(
            FreeUpPipelineWorker.PHASE_VERIFYING,
            FreeUpPipelineWorker.phaseWireName(FreeUpSpaceUseCase.PipelinePhase.Verifying),
        )
        assertEquals(
            FreeUpPipelineWorker.PHASE_DELETING,
            FreeUpPipelineWorker.phaseWireName(FreeUpSpaceUseCase.PipelinePhase.Deleting),
        )
    }

    @Test
    fun `mapPipelineOutcome always offers and notifies on NeedsPermission`() {
        val mapped = FreeUpPipelineWorker.mapPipelineOutcome(
            FreeUpSpaceUseCase.FreeUpResult.NeedsPermission(
                checked = 3,
                freed = 1,
                localUris = listOf("content://a", "content://b"),
            ),
        )
        assertEquals(FreeUpPipelineWorker.PHASE_AWAITING_CONSENT, mapped.phase)
        assertEquals(3, mapped.processed)
        assertEquals(3, mapped.total)
        assertEquals(1, mapped.removed)
        assertEquals(2, mapped.needsPermissionCount)
        assertEquals(listOf("content://a", "content://b"), mapped.offerUris)
        assertTrue(mapped.postConsentNotification)
    }

    @Test
    fun `mapPipelineOutcome Done does not offer or notify`() {
        val mapped = FreeUpPipelineWorker.mapPipelineOutcome(
            FreeUpSpaceUseCase.FreeUpResult.Done(
                checked = 5,
                freed = 4,
                skipped = 1,
            ),
        )
        assertEquals(FreeUpPipelineWorker.PHASE_DELETING, mapped.phase)
        assertEquals(5, mapped.processed)
        assertEquals(5, mapped.total)
        assertEquals(4, mapped.removed)
        assertEquals(0, mapped.needsPermissionCount)
        assertTrue(mapped.offerUris.isEmpty())
        assertFalse(mapped.postConsentNotification)
    }

    @Test
    fun `automatic skip only for fresh non-empty consent offers`() {
        val now = 1_750_000_000_000L
        assertFalse(
            "empty queue must not skip",
            FreeUpPipelineWorker.shouldSkipAutomaticForPendingConsent(
                queueNonEmpty = false,
                offeredAtMs = null,
                nowMs = now,
            ),
        )
        assertTrue(
            "fresh non-empty offer must skip",
            FreeUpPipelineWorker.shouldSkipAutomaticForPendingConsent(
                queueNonEmpty = true,
                offeredAtMs = now - 1L,
                nowMs = now,
            ),
        )
        assertFalse(
            "offer at 15m must not skip",
            FreeUpPipelineWorker.shouldSkipAutomaticForPendingConsent(
                queueNonEmpty = true,
                offeredAtMs = now - FREE_UP_PENDING_OFFER_STALE_MS,
                nowMs = now,
            ),
        )
        assertFalse(
            "offer past 15m must not skip",
            FreeUpPipelineWorker.shouldSkipAutomaticForPendingConsent(
                queueNonEmpty = true,
                offeredAtMs = now - FREE_UP_PENDING_OFFER_STALE_MS - 60_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun `retry requires attempt room and transient or offline`() {
        assertTrue(
            FreeUpPipelineWorker.shouldRetry(
                runAttemptCount = 0,
                transient = true,
                offline = false,
            ),
        )
        assertTrue(
            FreeUpPipelineWorker.shouldRetry(
                runAttemptCount = 2,
                transient = false,
                offline = true,
            ),
        )
        assertFalse(
            "permanent errors must not retry even under the attempt cap",
            FreeUpPipelineWorker.shouldRetry(
                runAttemptCount = 0,
                transient = false,
                offline = false,
            ),
        )
        assertFalse(
            "transient past the attempt cap must not retry forever",
            FreeUpPipelineWorker.shouldRetry(
                runAttemptCount = FreeUpPipelineWorker.MAX_ATTEMPTS,
                transient = true,
                offline = true,
            ),
        )
    }

    @Test
    fun `manual foreground and shared action notification ids stay distinct`() {
        assertTrue(NotificationIds.MANUAL_FREE_UP !in NotificationIds.ALBUM_DOWNLOAD_RANGE)
        assertTrue(NotificationIds.FREE_UP_ACTION_REQUIRED !in NotificationIds.ALBUM_DOWNLOAD_RANGE)
        assertNotEquals(NotificationIds.MANUAL_FREE_UP, NotificationIds.FREE_UP_ACTION_REQUIRED)
    }
}
