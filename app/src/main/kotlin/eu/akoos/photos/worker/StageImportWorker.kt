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
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.importer.ImportSourceFactory
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.domain.importer.ImportFailure
import eu.akoos.photos.domain.importer.ImportFailureKind
import eu.akoos.photos.domain.usecase.StageImportUseCase
import eu.akoos.photos.domain.usecase.StagePhase
import eu.akoos.photos.domain.usecase.StageProgress
import eu.akoos.photos.util.ImportEventLog
import java.util.UUID

/**
 * Stages a picked import `.zip` or folder into the review queue as a foreground service, so the pass survives the
 * picker screen closing AND a process kill. The heavy lifting lives in [StageImportUseCase]; this worker
 * is the durable shell around it: the notification and the throttled progress publish. No upload happens
 * here; on success the review screen (a later piece) observes the staged rows.
 *
 * Uniqueness ([uniqueName], keyed on the zip uri) with [ExistingWorkPolicy.KEEP], so double-enqueuing
 * the same zip does not run two stage passes over it at once.
 *
 * Input data: [KEY_ZIP_URI], the picked zip's or folder's `content://` uri string, which is also the run's zipId
 * (its stable identity across a kill and the key the staged rows are grouped under). No account (guest)
 * is a clean no-op: there is no Drive to import into later, so nothing is staged.
 */
@HiltWorker
class StageImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val stageImportUseCase: StageImportUseCase,
    private val importSourceFactory: ImportSourceFactory,
    private val accountManager: AccountManager,
) : CoroutineWorker(context, params) {

    // Wall-clock of the last progress publish, so the per-entry ticks are throttled instead of hammering
    // WorkManager's progress store and the notification manager. The pass is sequential, so this is only
    // ever touched from one coroutine.
    private var lastPublishMs = 0L

    override suspend fun doWork(): Result {
        val zipUri = inputData.getString(KEY_ZIP_URI)
        if (zipUri.isNullOrEmpty()) {
            Log.w(TAG, "no zip uri supplied")
            return Result.failure()
        }
        val uri = Uri.parse(zipUri)
        // The uri IS the persistable identity of this import, so it doubles as the staged rows' zipId.
        val zipId = zipUri

        // Guest / no primary account: there is no Drive to import into later, so this is a clean no-op
        // rather than a crash. firstOrNull covers both an empty flow and a null primary user.
        val userId = accountManager.getPrimaryUserId().firstOrNull()
        if (userId == null) {
            Log.d(TAG, "no primary account; nothing to stage")
            return Result.success()
        }

        // Promote to foreground within the start-of-work window WorkManager allows before it treats the
        // promote call as a timeout. Total 0 shows an indeterminate bar until the zip scan has counted
        // the media entries.
        runCatching { setForeground(buildForegroundInfo(null)) }
            .onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }

        return try {
            // The use case bridges the synchronous reader callbacks to suspend work via runBlocking, so it
            // MUST run on a background dispatcher.
            val result = withContext(Dispatchers.IO) {
                stageImportUseCase.stage(
                    userId = userId,
                    zipId = zipId,
                    source = importSourceFactory.open(uri),
                    onProgress = { progress -> publish(progress) },
                )
            }
            Log.d(TAG, "stage done: ${result.staged}")
            Result.success(
                workDataOf(
                    KEY_RESULT_STAGED to result.staged,
                    KEY_RESULT_ENDED_EARLY to result.endedEarly,
                    KEY_RESULT_READ_PERCENT to result.readPercent,
                ),
            )
        } catch (e: CancellationException) {
            // Cooperative cancellation (the notification's Cancel action, or the system stopping the work).
            // WorkManager records the run as cancelled; the rows written so far stay and the saved scan
            // lets the next attempt continue from them.
            Log.d(TAG, "stage cancelled")
            ImportEventLog.record(context.filesDir, "stage stopped" + stopReasonText())
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "stage failed", e)
            val kind = ImportFailure.kindOf(e)
            // The place the zip is read from can come back, and the work continues from what was staged, so
            // a source that stopped answering gets a couple of further attempts before it is reported.
            if (kind == ImportFailureKind.SOURCE_UNREADABLE && runAttemptCount < MAX_RETRIES) {
                ImportEventLog.record(context.filesDir, "stage retry attempt=${runAttemptCount + 1}")
                Result.retry()
            } else {
                ImportEventLog.record(context.filesDir, "stage gave up kind=${kind.name}")
                Result.failure(workDataOf(KEY_FAILURE_KIND to kind.name))
            }
        } finally {
            // WorkManager auto-dismisses the foreground notification when the worker exits, but some OEMs
            // leave the ongoing post behind; clear it.
            runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.IMPORT_STAGE) }
        }
    }

    /** Why the system stopped this work, where the platform says (API 31+), as a short log suffix. */
    private fun stopReasonText(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " reason=$stopReason" else ""

    /**
     * Publishes one progress tick to WorkManager (for a ViewModel observing [androidx.work.WorkInfo]) and
     * refreshes the notification. The first and last ticks always publish so start and completion are
     * seen; the middle ones are throttled to [PROGRESS_THROTTLE_MS]. Uses the non-suspend `*Async`
     * variants so a call made from inside the use case's runBlocking bridge does not block that thread.
     */
    private fun publish(progress: StageProgress) {
        val now = System.currentTimeMillis()
        val boundary = progress.phase == StagePhase.STAGE && (progress.done <= 0 || progress.done >= progress.total)
        if (!boundary && now - lastPublishMs < PROGRESS_THROTTLE_MS) return
        lastPublishMs = now

        runCatching {
            setProgressAsync(
                workDataOf(
                    KEY_DONE to progress.done,
                    KEY_TOTAL to progress.total,
                    KEY_PHASE to progress.phase.name,
                    KEY_BYTES_DONE to progress.bytesDone,
                    KEY_BYTES_TOTAL to progress.bytesTotal,
                ),
            )
        }
        runCatching { setForegroundAsync(buildForegroundInfo(progress)) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(null)

    /**
     * Builds the [ForegroundInfo] with the stage notification at the current progress. Idempotently
     * registers the notification channel on Android 8+.
     */
    private fun buildForegroundInfo(progress: StageProgress?): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val title = context.getString(R.string.import_stage_notification_title)
        val phase = progress?.phase ?: StagePhase.SCAN
        val bytesKnown = progress != null && progress.bytesTotal > 0
        val bytesPercent = if (bytesKnown) {
            (progress!!.bytesDone * 100 / progress.bytesTotal).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val content = when (phase) {
            StagePhase.SCAN ->
                if (bytesKnown) context.getString(R.string.import_scan_notification, bytesPercent)
                else context.getString(R.string.import_scanning)
            StagePhase.CATCH_UP -> context.getString(R.string.import_resuming)
            StagePhase.STAGE -> context.getString(R.string.import_stage_progress, progress?.done ?: 0, progress?.total ?: 0)
        }
        val cancelLabel = context.getString(R.string.cancel)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
        // Determinate by bytes while reading the archive, by entries while staging; indeterminate until
        // there is something to measure.
        when {
            phase == StagePhase.STAGE && (progress?.total ?: 0) > 0 ->
                builder.setProgress(progress!!.total, progress.done.coerceAtMost(progress.total), false)
            phase != StagePhase.STAGE && bytesKnown -> builder.setProgress(100, bytesPercent, false)
            else -> builder.setProgress(0, 0, true)
        }
        val notification = builder
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Cancel action: createCancelPendingIntent(id) cooperatively cancels THIS worker by its UUID,
            // so doWork's CancellationException catch handles teardown.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                cancelLabel,
                cancelIntent,
            )
            .build()

        // Android 10+ requires the foreground-service type bitmask matching the manifest's
        // SystemForegroundService entry (dataSync), or setForeground silently fails on Q+.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.IMPORT_STAGE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.IMPORT_STAGE, notification)
        }
    }

    companion object {
        const val TAG = "stage_import_worker"
        const val CHANNEL_ID = "import"

        /** Input: the picked zip's or folder's content uri string, which is also the staged rows' zipId. */
        const val KEY_ZIP_URI = "zipUri"

        // WorkManager progress data keys, read by an in-app staging progress surface.
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PHASE = "phase"
        const val KEY_BYTES_DONE = "bytesDone"
        const val KEY_BYTES_TOTAL = "bytesTotal"

        /** Output: how many entries the pass staged, carried on a successful result. */
        const val KEY_RESULT_STAGED = "resultStaged"

        /** Output: true when the archive stopped being readable partway and only its readable part was staged. */
        const val KEY_RESULT_ENDED_EARLY = "resultEndedEarly"

        /** Output: how far into the archive the read got, in percent, when it ended early. */
        const val KEY_RESULT_READ_PERCENT = "resultReadPercent"

        /** Output on failure: the [ImportFailureKind] name, so the screen can say what went wrong. */
        const val KEY_FAILURE_KIND = "failureKind"

        /** Further attempts after the zip's source stops answering, before the failure is reported. */
        private const val MAX_RETRIES = 2

        // How often the per-entry ticks refresh progress; the first and last tick bypass this.
        private const val PROGRESS_THROTTLE_MS = 500L

        /** Lazily creates the import notification channel, shared with the direct-import worker.
         *  Idempotent. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.import_channel_name),
                description = context.getString(R.string.import_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }

        /**
         * Enqueues the staging of [zipUri] and returns the request id. Unique-work keyed on the uri with
         * [ExistingWorkPolicy.KEEP], so enqueuing the same zip while it is already staging keeps the
         * running work rather than starting a second one. Observe progress via
         * `WorkManager.getWorkInfosForUniqueWork(uniqueName(zipUri))`, robust across a KEEP that returns
         * an id whose request is not the one actually running.
         */
        fun enqueue(context: Context, zipUri: String): UUID {
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<StageImportWorker>()
                .setInputData(workDataOf(KEY_ZIP_URI to zipUri))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(zipUri),
                ExistingWorkPolicy.KEEP,
                request,
            )
            return request.id
        }

        fun uniqueName(zipUri: String) = "stage_$zipUri"
    }
}
