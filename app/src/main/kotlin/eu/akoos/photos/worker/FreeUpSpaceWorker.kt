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
import kotlinx.coroutines.flow.first
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import java.util.concurrent.TimeUnit

@HiltWorker
class FreeUpSpaceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val accountManager: AccountManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = accountManager.getPrimaryUserId().first() ?: return Result.failure()
        val intervalMs = inputData.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
        val olderThanMs = System.currentTimeMillis() - intervalMs
        return try {
            freeUpSpace(userId, olderThanMs)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "free_up_worker"
        const val KEY_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 0L

        fun schedule(workManager: WorkManager, wifiOnly: Boolean = true, intervalMs: Long = 0L) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<FreeUpSpaceWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(
                    androidx.work.Data.Builder()
                        .putLong(KEY_INTERVAL_MS, intervalMs)
                        .build()
                )
                .addTag(TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(TAG)
        }
    }
}
