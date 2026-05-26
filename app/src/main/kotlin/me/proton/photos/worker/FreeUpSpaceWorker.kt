package me.proton.photos.worker

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
import me.proton.photos.domain.usecase.FreeUpSpaceUseCase
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
