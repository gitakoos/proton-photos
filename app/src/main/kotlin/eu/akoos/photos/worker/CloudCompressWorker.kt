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

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.PendingCloudCompressDao
import eu.akoos.photos.data.db.entity.PendingCloudCompressEntity
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.CompressCloudPhotoUseCase
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Works through the queue of photos and videos on Drive waiting to be compressed in place, as a foreground
 * service so a long batch survives the screen going off and the app closing. The queue is the
 * `pending_cloud_compress` table: read a slice at a time ([SLICE]), so a selection of tens of thousands never
 * sits in memory, and a row leaves it only once its photo is done for good (compressed, skipped, gone, or
 * failed past the retry budget). A kill mid-photo leaves the row, and the copy's link recorded on it, so the
 * next run finishes that photo instead of starting it again.
 *
 * Each photo is one atomic replacement ([CompressCloudPhotoUseCase] over the shared cloud replacement): the
 * original is only moved to the Drive trash once its smaller copy is uploaded and back in every album.
 */
@HiltWorker
class CloudCompressWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val dao: PendingCloudCompressDao,
    private val compress: CompressCloudPhotoUseCase,
    private val driveRepo: DrivePhotoRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        activeDrains.update { it + 1 }
        try {
            return drain()
        } finally {
            activeDrains.update { it - 1 }
        }
    }

    private suspend fun drain(): Result {
        if (dao.count() == 0) return Result.success()
        val runId = UUID.randomUUID().toString()
        val done = AtomicInteger(0)
        val compressed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val saved = AtomicLong(0)
        val kept = AtomicBoolean(false)
        var total = dao.count()
        runCatching { setForeground(foregroundInfo(0, total, null)) }
            .onFailure { Log.w(TAG, "setForeground failed: ${it.message}") }

        fun publish(fraction: Float?) {
            runCatching { setForegroundAsync(foregroundInfo(done.get(), total, fraction)) }
        }

        suspend fun process(row: PendingCloudCompressEntity) {
            val photo = driveRepo.observePhotosByLinkIds(listOf(row.linkId)).first().firstOrNull()
            if (photo == null) {
                // Trashed or deleted elsewhere, or another account's: nothing left to compress.
                dao.delete(row.linkId)
                skipped.incrementAndGet()
                done.incrementAndGet()
                publish(null)
                return
            }
            val result = compress(
                photo = photo,
                onCompressProgress = { publish(it) },
                resumeUploadedLinkId = row.newLinkId,
                onUploaded = { link -> dao.setNewLinkId(row.linkId, link) },
            )
            val drop = when (result) {
                is CompressCloudPhotoUseCase.Result.Compressed -> {
                    compressed.incrementAndGet()
                    saved.addAndGet(result.savedBytes)
                    true
                }
                CompressCloudPhotoUseCase.Result.Skipped -> {
                    skipped.incrementAndGet()
                    true
                }
                is CompressCloudPhotoUseCase.Result.Failed -> {
                    Log.w(TAG, "compress failed for ${row.linkId}: ${result.reason}")
                    when (metadataDrainDecision(result.transient, runAttemptCount)) {
                        MetadataDrainDecision.KEEP_AND_RETRY -> {
                            kept.set(true)
                            false
                        }
                        else -> {
                            failed.incrementAndGet()
                            true
                        }
                    }
                }
            }
            if (drop) dao.delete(row.linkId) else dao.markTried(row.linkId, runId)
            done.incrementAndGet()
            publish(null)
        }

        return try {
            val gate = Semaphore(CONCURRENCY)
            while (true) {
                val slice = dao.nextSlice(runId, SLICE)
                if (slice.isEmpty()) break
                // A batch queued while this one runs grows the total, so the bar stays honest.
                total = maxOf(total, done.get() + dao.count())
                coroutineScope {
                    slice.map { row ->
                        async {
                            gate.withPermit {
                                // One photo's trouble stays that photo's: it is kept for the next run, and
                                // the rest of the slice goes on.
                                try {
                                    process(row)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "compress of ${row.linkId} threw: ${e.message}")
                                    kept.set(true)
                                    runCatching { dao.markTried(row.linkId, runId) }
                                    done.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            postSummary(compressed.get(), skipped.get(), failed.get(), saved.get())
            if (kept.get()) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "compress drain failed", e)
            if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.CLOUD_COMPRESS) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    private fun foregroundInfo(done: Int, total: Int, fraction: Float?): ForegroundInfo {
        ensureChannel(context)
        val cancel = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CloudCompressCancelReceiver::class.java).setAction(CloudCompressCancelReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.cloud_compress_progress, done, total) +
            (fraction?.let { " · ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: "")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.cloud_compress_title))
            .setContentText(text)
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.CLOUD_COMPRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.CLOUD_COMPRESS, notification)
        }
    }

    /** What the run did, left in the shade once the progress post is gone. */
    private fun postSummary(compressed: Int, skipped: Int, failed: Int, savedBytes: Long) {
        if (compressed + skipped + failed == 0) return
        ensureChannel(context)
        val text = context.getString(
            R.string.cloud_compress_done,
            compressed,
            Formatter.formatShortFileSize(context, savedBytes),
        ) + if (skipped + failed > 0) " " + context.getString(R.string.cloud_compress_done_left, skipped + failed) else ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.cloud_compress_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NotificationIds.CLOUD_COMPRESS_DONE, notification) }
    }

    companion object {
        /** Drains of this process inside doWork right now, so a cancel can wait for the run to stop. */
        internal val activeDrains = MutableStateFlow(0)

        const val TAG = "cloud_compress_worker"
        const val UNIQUE_NAME = "cloud_compress"
        const val CHANNEL_ID = "cloud_compress"

        /** Runs that may end in a failure of the drain itself before it stops asking to be retried; the
         *  rows stay queued for the next time compression is asked for. */
        private const val MAX_RUN_ATTEMPTS = 5

        /** Rows read per slice. */
        private const val SLICE = 20

        /** Photos at once: two overlap one's download with another's upload; video transcodes run one at
         *  a time behind the upload compression's own gate. */
        private const val CONCURRENCY = 2

        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.cloud_compress_title),
                description = context.getString(R.string.cloud_compress_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = false,
            )
        }

        /** Starts the drain, or lets the running one pick up the new rows. On Wi-Fi only when the back-up
         *  is set to Wi-Fi only, since each photo is downloaded and uploaded again. */
        fun enqueue(context: Context, wifiOnly: Boolean) {
            ensureChannel(context)
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudCompressWorker>()
                    .addTag(TAG)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .setRequiresStorageNotLow(true)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
