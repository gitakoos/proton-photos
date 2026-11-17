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

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.domain.usecase.DisplayPairRepair
import eu.akoos.photos.domain.usecase.PAIR_REPAIR_TIME_LIMIT_MS
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One run of [DisplayPairRepair], queued the moment an account's Drive listing completes for the first
 * time, as after a reinstall or a new sign-in. The device files a reinstall left unlinked then pair with
 * their Drive photos within the minute the listing lands instead of at the next [CachePruneWorker] pass,
 * up to 30 minutes later. The periodic pass carries on with whatever this run's budget left.
 */
@HiltWorker
class DisplayPairRepairWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val displayPairRepair: DisplayPairRepair,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            withTimeoutOrNull(PAIR_REPAIR_TIME_LIMIT_MS) { displayPairRepair.runForPrimaryUser() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "display pair repair failed: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "display_pair_repair_once"
        private const val TAG = "display_pair_repair"

        /** The one-off request: no constraints, so it starts as soon as WorkManager can run it. */
        internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DisplayPairRepairWorker>().build()

        /** Queues the one run. A run already queued or running stays, and this request is dropped. */
        fun enqueueOnce(workManager: WorkManager) {
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
        }
    }
}
