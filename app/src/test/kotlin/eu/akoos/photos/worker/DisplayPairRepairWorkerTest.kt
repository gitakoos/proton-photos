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

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import eu.akoos.photos.data.repository.drive.markListingEverComplete
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pair repair queued once, the moment an account's Drive listing completes for the first time. */
class DisplayPairRepairWorkerTest {

    private val key = booleanPreferencesKey("photo_listing_ever_complete_user_vol")

    @Test
    fun `only the first complete listing counts as the first`() {
        val prefs = mutablePreferencesOf()

        assertTrue("a fresh install has never listed", prefs.markListingEverComplete(key))
        assertEquals(true, prefs[key])
        assertFalse("every later listing finds the flag set", prefs.markListingEverComplete(key))
        assertEquals(true, prefs[key])
    }

    @Test
    fun `a flag stored false counts as never listed`() {
        val prefs = mutablePreferencesOf(key to false)

        assertTrue(prefs.markListingEverComplete(key))
    }

    @Test
    fun `the repair is queued as one unique unconstrained run that a second request does not replace`() {
        val workManager = mockk<WorkManager>(relaxed = true)
        val request = slot<OneTimeWorkRequest>()

        DisplayPairRepairWorker.enqueueOnce(workManager)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(DisplayPairRepairWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, capture(request))
        }
        assertEquals(DisplayPairRepairWorker::class.java.name, request.captured.workSpec.workerClassName)
        assertEquals("starts without waiting for a network", NetworkType.NOT_REQUIRED, request.captured.workSpec.constraints.requiredNetworkType)
        assertFalse(request.captured.workSpec.constraints.requiresBatteryNotLow())
        assertEquals("starts at once", 0L, request.captured.workSpec.initialDelay)
        assertFalse("never periodic", request.captured.workSpec.isPeriodic)
        assertEquals(BackoffPolicy.LINEAR, request.captured.workSpec.backoffPolicy)
    }

    @Test
    fun `a run that could not start is tried again a bounded number of times`() {
        val attempts = DisplayPairRepairWorker.MAX_ATTEMPTS
        for (attempt in 0 until attempts - 1) {
            assertEquals("attempt $attempt", ListenableWorker.Result.retry(), DisplayPairRepairWorker.resultFor(false, attempt))
        }
        assertEquals("the last attempt gives up", ListenableWorker.Result.success(), DisplayPairRepairWorker.resultFor(false, attempts - 1))
    }

    @Test
    fun `a run that started is done whatever it linked`() {
        assertEquals(ListenableWorker.Result.success(), DisplayPairRepairWorker.resultFor(true, 0))
        assertEquals(ListenableWorker.Result.success(), DisplayPairRepairWorker.resultFor(true, 3))
    }
}
