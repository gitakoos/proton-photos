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
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.R
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.common.GalleryDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * Runs one save-to-device batch that [GalleryDownloads] kept on disk. As background work it outlives
 * the screen that started it and the app process too: when Android ends the process, the work runs
 * again once it can and the batch goes on from the photos that were not done yet.
 *
 * Shares the album download's notification channel and its opt-out: while the notification is on the
 * work runs in the foreground with progress and a Cancel action. Cancelling it, from that action, from
 * the Activity screen or by a sign-out, drops the batch; any other stop keeps it for the next run.
 */
@HiltWorker
class PhotoSaveWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val downloads: GalleryDownloads,
) : CoroutineWorker(context, params) {

    private val notificationId: Int = NotificationIds.albumDownload(id.hashCode())

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val showNotification = context.settingsDataStore.data.first()[SettingsKeys.NOTIFY_ALBUM_DOWNLOAD] != false
        if (showNotification) {
            runCatching { setForeground(buildForegroundInfo(done = 0, total = 0)) }
                .onFailure { Log.w(TAG, "setForeground failed: ${it.message}") }
        }
        return try {
            val ran = downloads.runSaveBatch(
                batchId,
                // The Activity screen's cancel drops the batch itself before it stops the work, so the
                // drop never depends on how the stop is reported.
                cancelBatch = {
                    downloads.discardSaveBatch(batchId)
                    WorkManager.getInstance(context).cancelWorkById(id)
                },
            ) { progress ->
                if (showNotification) {
                    runCatching { setForeground(buildForegroundInfo(progress.done, progress.total)) }
                }
            }
            if (ran == GalleryDownloads.SaveRun.FINISHED) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            // Only a cancel the app asked for (the notification's Cancel, a sign-out) ends the batch. A
            // stop by the system (a time limit, a constraint) keeps it on disk, and the next run goes on
            // from where this one stopped.
            withContext(NonCancellable) {
                val cancelled = (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
                    ) ||
                    runCatching {
                        WorkManager.getInstance(context).getWorkInfoByIdFlow(id).first()?.state ==
                            WorkInfo.State.CANCELLED
                    }.getOrDefault(false)
                if (cancelled) downloads.discardSaveBatch(batchId)
            }
            throw e
        } finally {
            runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(done = 0, total = 0)

    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        AlbumDownloadWorker.ensureChannel(context)
        val notification = NotificationCompat.Builder(context, AlbumDownloadWorker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.activity_downloading))
            .apply {
                if (total > 0) setContentText(context.getString(R.string.album_download_notification_progress, done, total))
            }
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(AlbumDownloadWorker.GROUP_KEY)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.album_download_notification_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val TAG = "photo_save_worker"
        private const val KEY_BATCH_ID = "batchId"

        fun uniqueName(batchId: String) = "photo_save_$batchId"

        /** Queues the batch [batchId]; a second call for the same batch leaves the first one running. */
        fun enqueue(context: Context, batchId: String) {
            AlbumDownloadWorker.ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<PhotoSaveWorker>()
                .setInputData(workDataOf(KEY_BATCH_ID to batchId))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(batchId), ExistingWorkPolicy.KEEP, request)
        }
    }
}
