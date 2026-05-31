/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic, battery-friendly sweeper that runs [PhotoDownloadService.pruneStaleFullResCache]
 * even while the app process is dead. Covers the "user opened the app days ago, OS killed
 * the process, comes back later" gap that the foreground prune in [App.onCreate] and
 * [PhotoViewerViewModel.onCleared] cannot reach.
 *
 * Constraints — [NetworkType.CONNECTED] + [Constraints.Builder.setRequiresBatteryNotLow]:
 *  - Connectivity gate matches the offline-grace semantics of the prune routine itself,
 *    so we never wipe locally-cached blobs when the user could not re-download them.
 *  - Battery-not-low avoids waking the device for cache hygiene when the user is in a
 *    "must squeeze every drop" state; OS also defers under Doze regardless.
 *
 * Interval is fixed at 30 minutes — same horizon as [PhotoDownloadService.FULLRES_TTL_MS],
 * so a missed wake-up still keeps the worst-case stale-blob window at ~1 h.
 */
@HiltWorker
class CachePruneWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // pruneStaleFullResCache already short-circuits when networkAvailable=false.
        // The CONNECTED constraint below means we should normally have a network here
        // when this runs, but pass true explicitly because WorkManager only guarantees
        // *some* network type matches the constraint at start, not that it lasts.
        eu.akoos.photos.data.repository.drive.PhotoDownloadService.pruneStaleFullResCache(
            context,
            networkAvailable = true,
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "cache_prune_periodic"
        private const val INTERVAL_MINUTES = 30L

        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // Battery-friendly: don't burn cycles if user has low battery; OS
                // can also defer if device is in Doze.
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CachePruneWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            // UPDATE so a constraint or interval change in a future release replaces the
            // previously-registered request instead of stacking duplicates.
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
