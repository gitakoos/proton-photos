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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins ManualFreeUpWorker companion contracts that do not need Robolectric: CONNECTED constraint
 * and a unique notification id outside the album-download range.
 */
class ManualFreeUpWorkerTest {

    @Test
    fun `manual constraints require a connected network`() {
        val constraints = FreeUpPipelineWorker.manualConstraints()
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
    }

    @Test
    fun `manual free-up notification id is unique and outside the album range`() {
        assertTrue(NotificationIds.MANUAL_FREE_UP !in NotificationIds.ALBUM_DOWNLOAD_RANGE)
        assertTrue(NotificationIds.FREE_UP_ACTION_REQUIRED !in NotificationIds.ALBUM_DOWNLOAD_RANGE)
        assertNotEquals(NotificationIds.MANUAL_FREE_UP, NotificationIds.FREE_UP_ACTION_REQUIRED)
        assertNotEquals(NotificationIds.MANUAL_FREE_UP, NotificationIds.DELETE_CONSENT)
        assertNotEquals(NotificationIds.MANUAL_FREE_UP, NotificationIds.METADATA_EDIT)
    }

    @Test
    fun `AUTO_FREE_UP and thermal gates do not appear on the manual companion surface`() {
        // Manual free-up must not reuse FreeUpSpaceWorker's skip helpers: uniqueness of the
        // unique name + CONNECTED-only constraints is the regression lock here.
        assertEquals("manual_free_up", ManualFreeUpWorker.UNIQUE_NAME)
        assertEquals("manual_free_up_worker", ManualFreeUpWorker.TAG)
        assertEquals(NetworkType.CONNECTED, FreeUpPipelineWorker.manualConstraints().requiredNetworkType)
        assertFalse(FreeUpPipelineWorker.manualConstraints().requiresBatteryNotLow())
    }

    @Test
    fun `manual enqueue targets the shared pipeline with a distinct unique name`() {
        assertEquals(
            FreeUpPipelineWorker.TRIGGER_MANUAL,
            FreeUpPipelineWorker.manualInputData().getString(FreeUpPipelineWorker.KEY_TRIGGER),
        )
        assertFalse(FreeUpPipelineWorker.protectDownloadedFor(FreeUpPipelineWorker.TRIGGER_MANUAL))
        assertNotEquals(ManualFreeUpWorker.UNIQUE_NAME, FreeUpSpaceWorker.TAG)
    }
}
