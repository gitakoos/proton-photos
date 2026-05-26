package me.proton.photos.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Advances the widget to its next photo on each run.
 * Scheduled as a PeriodicWorkRequest whose period matches the user's chosen interval.
 * An initial one-time request fires immediately after the widget is first configured.
 */
class PhotoWidgetUpdateWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        if (appWidgetId == -1) return Result.failure()
        PhotoWidgetUpdater.update(context, appWidgetId)
        return Result.success()
    }

    companion object {
        const val KEY_WIDGET_ID = "app_widget_id"

        /** Unique periodic work tag for a given widget instance. */
        private fun periodicTag(id: Int) = "widget_periodic_$id"

        /** Unique one-time work tag for a given widget instance. */
        private fun oneTimeTag(id: Int) = "widget_once_$id"

        /**
         * Schedule a one-time immediate update (called right after config or [onUpdate]).
         */
        fun enqueueImmediate(context: Context, appWidgetId: Int) {
            val request = OneTimeWorkRequestBuilder<PhotoWidgetUpdateWorker>()
                .setInputData(workDataOf(KEY_WIDGET_ID to appWidgetId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(oneTimeTag(appWidgetId), ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Schedule (or replace) a periodic update whose interval is read from the
         * widget's stored interval preference. Falls back to 60 minutes if unset.
         */
        fun enqueueOrReplace(context: Context, appWidgetId: Int, intervalMinutes: Int = 60) {
            val clampedInterval = intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<PhotoWidgetUpdateWorker>(
                clampedInterval, TimeUnit.MINUTES,
            )
                .setInputData(workDataOf(KEY_WIDGET_ID to appWidgetId))
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                periodicTag(appWidgetId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Cancel all scheduled work for the given widget instance. */
        fun cancel(context: Context, appWidgetId: Int) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(periodicTag(appWidgetId))
            wm.cancelUniqueWork(oneTimeTag(appWidgetId))
        }
    }
}
