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

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import eu.akoos.photos.data.db.dao.PendingMetadataEditDao
import eu.akoos.photos.data.db.entity.PendingCloudCompressEntity
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.CloudCompressQueue
import eu.akoos.photos.domain.usecase.CloudPhotoReplacement
import eu.akoos.photos.domain.usecase.CompressCloudPhotoUseCase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import me.proton.core.domain.entity.UserId

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
    private val transferCenter: TransferCenter,
    private val queue: CloudCompressQueue,
    private val metadataEdits: PendingMetadataEditDao,
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
        val startedAt = System.currentTimeMillis()
        val done = AtomicInteger(0)
        val compressed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val saved = AtomicLong(0)
        val kept = AtomicBoolean(false)
        var total = dao.count()
        runCatching { setForeground(foregroundInfo(0, total, null)) }
            .onFailure { Log.w(TAG, "setForeground failed: ${it.message}") }
        // Listed on the Activity screen beside the uploads, where Cancel does what the notification's does.
        val transferId = transferCenter.start(
            TransferCenter.Kind.UPLOAD,
            total = total,
            name = context.getString(R.string.cloud_compress_title),
            onCancel = { queue.cancel() },
        )

        fun publish(fraction: Float?) {
            runCatching { setForegroundAsync(foregroundInfo(done.get(), total, fraction)) }
            runCatching { transferCenter.progress(transferId, done.get()) }
        }

        suspend fun advance() {
            done.incrementAndGet()
            publish(null)
        }

        /** Keeps [row] for a later run; [failedTry] counts it as one of the photo's own failures. */
        suspend fun keep(row: PendingCloudCompressEntity, failedTry: Boolean) {
            kept.set(true)
            dao.markTried(row.linkId, runId, if (failedTry) row.attempts + 1 else row.attempts)
        }

        suspend fun compressClaimed(row: PendingCloudCompressEntity) {
            val photo = driveRepo.observePhotosByLinkIds(listOf(row.linkId)).first().firstOrNull()
            if (photo == null) {
                // Trashed or deleted elsewhere, another account's, or already replaced by a run stopped just
                // before it dropped the row: nothing left to compress.
                dao.delete(row.linkId)
                skipped.incrementAndGet()
                advance()
                return
            }
            val result = compress(
                photo = photo,
                onCompressProgress = { publish(it) },
                resumeUploadedLinkId = row.newLinkId,
                onUploaded = { link -> dao.setNewLinkId(row.linkId, link) },
                resumeCarriedOver = row.carriedOver,
                onCarriedOver = { dao.setCarriedOver(row.linkId) },
            )
            when (result) {
                is CompressCloudPhotoUseCase.Result.Compressed -> {
                    compressed.incrementAndGet()
                    saved.addAndGet(result.savedBytes)
                    // A date or place edit saved while this ran follows the photo to its smaller copy.
                    runCatching { metadataEdits.retarget(row.linkId, result.newLinkId) }
                    dao.delete(row.linkId)
                }
                CompressCloudPhotoUseCase.Result.Skipped -> {
                    skipped.incrementAndGet()
                    dao.delete(row.linkId)
                }
                is CompressCloudPhotoUseCase.Result.Failed -> {
                    Log.w(TAG, "compress failed for ${row.linkId}: ${result.reason}")
                    val action = compressFailureAction(
                        transient = result.transient,
                        attemptsBefore = row.attempts,
                        copyLinkId = result.copyLinkId,
                        carriedOver = result.carriedOver || row.carriedOver,
                    )
                    when (action) {
                        CompressFailureAction.KEEP -> keep(row, failedTry = true)
                        CompressFailureAction.ROLL_BACK_AND_DROP, CompressFailureAction.DROP -> {
                            // Given up. A copy on Drive that nothing moved to yet goes to the trash, so the
                            // photo is back to just its original; one that holds the original's labels and
                            // faces stays beside it, where nothing is lost.
                            val copy = result.copyLinkId
                            if (action == CompressFailureAction.ROLL_BACK_AND_DROP && copy != null) {
                                runCatching { driveRepo.deleteFiles(UserId(row.userId), listOf(copy)) }
                                    .onFailure { Log.w(TAG, "rollback of copy $copy failed: ${it.message}") }
                            }
                            failed.incrementAndGet()
                            dao.delete(row.linkId)
                        }
                    }
                }
            }
            advance()
        }

        suspend fun process(row: PendingCloudCompressEntity) {
            // Marked running before the stop check: a Cancel sets the stop before it reads this set, so a
            // photo is either dropped here or kept by the Cancel, never dropped under a running compression.
            running.add(row.linkId)
            try {
                // A Cancel drops the photos not started yet. One already uploaded is finished, so no photo is
                // left on Drive twice.
                if (row.newLinkId == null && row.enqueuedAt <= stopRequestedAt.value) {
                    dao.delete(row.linkId)
                    return
                }
                // A date or place edit of the same photo replaces it too: this one waits while that edit is
                // queued or running, so the two never both upload a copy.
                if (metadataEdits.has(row.linkId) || !CloudPhotoReplacement.Claims.tryClaim(row.linkId)) {
                    // Held for a day already: each later run counts against the photo, so an edit that never
                    // finishes cannot keep it waiting, and this worker retrying, for good.
                    val stale = heldTooLong(row.enqueuedAt, System.currentTimeMillis())
                    if (stale && row.attempts + 1 >= MAX_PHOTO_ATTEMPTS) {
                        failed.incrementAndGet()
                        dao.delete(row.linkId)
                    } else {
                        keep(row, failedTry = stale)
                    }
                    advance()
                    return
                }
                try {
                    compressClaimed(row)
                } finally {
                    CloudPhotoReplacement.Claims.release(row.linkId)
                }
            } finally {
                running.remove(row.linkId)
            }
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
                                    runCatching {
                                        if (row.attempts + 1 < MAX_PHOTO_ATTEMPTS) {
                                            keep(row, failedTry = true)
                                        } else {
                                            failed.incrementAndGet()
                                            dao.delete(row.linkId)
                                        }
                                    }
                                    done.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            // A Cancel during this run leaves every photo not finished as it was, and the summary says so.
            val stoppedHere = stopRequestedAt.value >= startedAt
            val left = leftAsTheyWere(total, compressed.get(), skipped.get(), failed.get(), stoppedHere)
            postSummary(compressed.get(), left, saved.get())
            if (kept.get()) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "compress drain failed", e)
            if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            transferCenter.finish(transferId)
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
    private fun postSummary(compressed: Int, left: Int, savedBytes: Long) {
        if (compressed + left == 0) return
        ensureChannel(context)
        val text = context.getString(
            R.string.cloud_compress_done,
            compressed,
            Formatter.formatShortFileSize(context, savedBytes),
        ) + if (left > 0) " " + context.getString(R.string.cloud_compress_done_left, left) else ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.cloud_compress_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        runCatching { NotificationManagerCompat.from(context).notify(NotificationIds.CLOUD_COMPRESS_DONE, notification) }
    }

    companion object {
        /** Drains of this process inside doWork right now, so a cancel knows whether one is running. */
        internal val activeDrains = MutableStateFlow(0)

        /** When the last Cancel was asked for: rows queued by then and not started are dropped, not run. */
        internal val stopRequestedAt = MutableStateFlow(0L)

        /** The photos being compressed right now, which a Cancel lets finish. */
        internal val running: MutableSet<String> = ConcurrentHashMap.newKeySet()


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

/**
 * How many photos of a run are left as they were: the skipped and failed ones, and after a Cancel during the
 * run also every photo it did not finish. Pure.
 */
internal fun leftAsTheyWere(total: Int, compressed: Int, skipped: Int, failed: Int, stopped: Boolean): Int =
    if (stopped) (total - compressed).coerceAtLeast(skipped + failed) else skipped + failed

/** Failed tries of one photo before it is given up. */
internal const val MAX_PHOTO_ATTEMPTS = 5

/** How long a date or place edit of the same photo may hold a compression before the waits count as tries. */
internal const val HELD_LIMIT_MS = 24 * 60 * 60 * 1000L

/** Whether a compression queued at [enqueuedAt] has waited on another replacement of its photo for too long. */
internal fun heldTooLong(enqueuedAt: Long, now: Long): Boolean = now - enqueuedAt > HELD_LIMIT_MS

/** What the drain does with a photo whose compression failed. */
internal enum class CompressFailureAction { KEEP, ROLL_BACK_AND_DROP, DROP }

/**
 * A transient failure inside the photo's own budget of [MAX_PHOTO_ATTEMPTS] keeps it for a later run. Past
 * that, or on a permanent failure, the photo is given up: a copy already on Drive is taken back only while
 * nothing moved to it ([carriedOver] false), so the photo is left with its original alone. Pure.
 */
internal fun compressFailureAction(
    transient: Boolean,
    attemptsBefore: Int,
    copyLinkId: String?,
    carriedOver: Boolean,
): CompressFailureAction = when {
    transient && attemptsBefore + 1 < MAX_PHOTO_ATTEMPTS -> CompressFailureAction.KEEP
    copyLinkId != null && !carriedOver -> CompressFailureAction.ROLL_BACK_AND_DROP
    else -> CompressFailureAction.DROP
}
