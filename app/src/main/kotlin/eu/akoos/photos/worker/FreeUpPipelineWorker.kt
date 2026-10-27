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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.MainActivity
import eu.akoos.photos.R
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.FreeUpPendingDeleteQueue
import eu.akoos.photos.domain.repository.isFreeUpPendingOfferStale
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.isOfflineError
import eu.akoos.photos.util.isTransientApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.proton.core.accountmanager.domain.AccountManager
import java.util.concurrent.TimeUnit

/**
 * Pure mapping of a [FreeUpSpaceUseCase.FreeUpResult] to WorkManager output, consent offer, and
 * shade notification decisions. Kept free of Android / WorkManager types so JVM tests can pin it.
 */
data class MappedPipelineOutcome(
    val phase: String,
    val processed: Int,
    val total: Int,
    val removed: Int,
    val needsPermissionCount: Int,
    /** URIs to [FreeUpPendingDeleteQueue.offer]; empty means do not offer. */
    val offerUris: List<String>,
    /** When true, post the fixed awaiting-consent notification for [needsPermissionCount]. */
    val postConsentNotification: Boolean,
)

/**
 * Shared free-up pipeline: chunked Drive verify + delete in one pass.
 *
 * Manual and automatic triggers share this worker class but use distinct unique work names,
 * constraints, and cancel entry points so stopping one mode never cancels the other.
 * Manual runs as expedited FGS; automatic never takes a foreground service.
 */
@HiltWorker
class FreeUpPipelineWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val accountManager: AccountManager,
    private val deviceHealth: DeviceHealthPolicy,
    private val pendingDeleteBridge: FreeUpPendingDeleteQueue,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_MANUAL
        val isManual = trigger == TRIGGER_MANUAL
        val intervalMs = inputData.getLong(KEY_INTERVAL_MS, FreeUpSpaceWorker.NO_INTERVAL_MS)

        val userId = accountManager.getPrimaryUserId().first()
            ?: return if (isManual) Result.success() else Result.failure()

        if (!isManual) {
            if (deviceHealth.thermallyThrottled()) return Result.success()
            val autoFreeUp = context.settingsDataStore.data.first()[SettingsKeys.AUTO_FREE_UP] ?: false
            if (FreeUpSpaceWorker.shouldSkipWhenAutoFreeUpDisabled(autoFreeUp)) {
                Log.d(TAG, "Skipping automatic pipeline: AUTO_FREE_UP is off")
                return Result.success()
            }
            if (!FreeUpSpaceWorker.isUsableInterval(intervalMs)) {
                Log.w(TAG, "Skipping automatic pipeline: unusable interval ($intervalMs)")
                return Result.failure()
            }
            val nowMs = System.currentTimeMillis()
            val verifiedAtMs =
                context.settingsDataStore.data.first()[SettingsKeys.cloudVerifiedAtKey(userId.id)]
            if (!FreeUpSpaceWorker.isCloudStateFreshEnough(verifiedAtMs, nowMs)) {
                Log.w(TAG, "Skipping automatic pipeline: cloud state stale ($verifiedAtMs)")
                return Result.success()
            }
            val queueNonEmpty = pendingDeleteBridge.uris.value.isNotEmpty()
            if (shouldSkipAutomaticForPendingConsent(
                    queueNonEmpty,
                    pendingDeleteBridge.offeredAtMs,
                    nowMs,
                )
            ) {
                Log.d(TAG, "Skipping automatic pipeline: consent queue non-empty")
                return Result.success()
            }
            if (queueNonEmpty) {
                // Stale in-memory offer — clear so automatic can re-verify Drive.
                pendingDeleteBridge.settleAllPending(false)
            }
        }

        val olderThanMs = olderThanMsFor(trigger, System.currentTimeMillis(), intervalMs)
        val protectDownloaded = protectDownloadedFor(trigger)

        if (isManual) {
            runCatching {
                setForeground(
                    buildForegroundInfo(
                        notificationId = NotificationIds.MANUAL_FREE_UP,
                        phase = PHASE_VERIFYING,
                        processed = 0,
                        total = 0,
                        removed = 0,
                        needsPermissionCount = 0,
                    ),
                )
            }.onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }
        }

        return try {
            val onProgress: suspend (FreeUpSpaceUseCase.PipelineProgress) -> Unit = { progress ->
                publishProgress(
                    isManual = isManual,
                    phase = phaseWireName(progress.phase),
                    processed = progress.processed,
                    total = progress.total,
                    removed = progress.removed,
                    needsPermissionCount = 0,
                )
            }

            val result = if (isManual) {
                freeUpSpace.runPipeline(userId, olderThanMs, protectDownloaded, onProgress)
            } else {
                val skipped = freeUpSpace.tryRunPipeline(
                    userId,
                    olderThanMs,
                    protectDownloaded,
                    onProgress,
                )
                if (skipped == null) {
                    Log.d(TAG, "Skipping automatic pipeline: mutex busy (manual in progress)")
                    return Result.success()
                }
                skipped
            }

            val mapped = mapPipelineOutcome(result)
            if (mapped.phase == PHASE_AWAITING_CONSENT) {
                publishProgress(
                    isManual = isManual,
                    phase = mapped.phase,
                    processed = mapped.processed,
                    total = mapped.total,
                    removed = mapped.removed,
                    needsPermissionCount = mapped.needsPermissionCount,
                )
            }
            if (mapped.offerUris.isNotEmpty()) {
                pendingDeleteBridge.offer(mapped.offerUris)
            }
            if (mapped.postConsentNotification) {
                postAwaitingConsentNotification(mapped.needsPermissionCount)
            }
            Result.success(
                outputData(
                    phase = mapped.phase,
                    processed = mapped.processed,
                    total = mapped.total,
                    removed = mapped.removed,
                    needsPermissionCount = mapped.needsPermissionCount,
                ),
            )
        } catch (e: CancellationException) {
            Log.d(TAG, "free-up pipeline cancelled (trigger=$trigger)")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "free-up pipeline failed: ${e.message}")
            if (!isManual && FreeUpSpaceWorker.isPermanentSweepFailure(e)) {
                return Result.failure()
            }
            if (shouldRetry(
                    runAttemptCount = runAttemptCount,
                    transient = isTransientApiError(e),
                    offline = isOfflineError(e),
                )
            ) {
                Result.retry()
            } else {
                Result.failure()
            }
        } finally {
            if (isManual) {
                runCatching {
                    NotificationManagerCompat.from(context).cancel(NotificationIds.MANUAL_FREE_UP)
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(
            notificationId = NotificationIds.MANUAL_FREE_UP,
            phase = PHASE_VERIFYING,
            processed = 0,
            total = 0,
            removed = 0,
            needsPermissionCount = 0,
        )

    private suspend fun publishProgress(
        isManual: Boolean,
        phase: String,
        processed: Int,
        total: Int,
        removed: Int,
        needsPermissionCount: Int,
    ) {
        runCatching {
            setProgress(progressData(phase, processed, total, removed, needsPermissionCount))
        }
        if (!isManual) return
        // Await setForeground so the shade cannot race ahead of WorkInfo progress; Async
        // variants returned before the update landed and left late ticks dropped.
        runCatching {
            setForeground(
                buildForegroundInfo(
                    NotificationIds.MANUAL_FREE_UP,
                    phase,
                    processed,
                    total,
                    removed,
                    needsPermissionCount,
                ),
            )
        }
    }

    private fun buildForegroundInfo(
        notificationId: Int,
        phase: String,
        processed: Int,
        total: Int,
        removed: Int,
        needsPermissionCount: Int,
    ): ForegroundInfo {
        ensureChannel(context)
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val title = when (phase) {
            PHASE_DELETING -> context.getString(R.string.free_up_delete_notification_title)
            PHASE_AWAITING_CONSENT ->
                context.getString(R.string.free_up_awaiting_consent_notification_title)
            else -> context.getString(R.string.free_up_verify_notification_title)
        }
        val content = when {
            phase == PHASE_AWAITING_CONSENT -> {
                val count = needsPermissionCount.coerceAtLeast(1)
                context.resources.getQuantityString(
                    R.plurals.free_up_awaiting_consent_notification_text,
                    count,
                    count,
                )
            }
            phase == PHASE_DELETING && total > 0 -> context.getString(
                R.string.free_up_progress,
                processed,
                total,
                removed,
            )
            total > 0 -> context.getString(R.string.free_up_verify_progress, processed, total)
            else -> context.getString(R.string.free_up_verifying)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(
                total.coerceAtLeast(1),
                processed.coerceAtMost(total.coerceAtLeast(1)),
                total <= 0 || phase == PHASE_AWAITING_CONSENT,
            )
            .setOngoing(phase != PHASE_AWAITING_CONSENT)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cancel),
                cancelIntent,
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun postAwaitingConsentNotification(uriCount: Int) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_FREE_UP, true)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            NotificationIds.FREE_UP_ACTION_REQUIRED,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.free_up_awaiting_consent_notification_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.free_up_awaiting_consent_notification_text,
                    uriCount,
                    uriCount,
                ),
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NotificationIds.FREE_UP_ACTION_REQUIRED, notification)
        }
    }

    companion object {
        const val TAG = "free_up_pipeline_worker"
        const val CHANNEL_ID = "free_up_pipeline"

        const val KEY_TRIGGER = "trigger"
        const val KEY_INTERVAL_MS = FreeUpSpaceWorker.KEY_INTERVAL_MS

        const val KEY_PHASE = "phase"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_REMOVED = "removed"
        const val KEY_NEEDS_PERMISSION_COUNT = "needs_permission_count"

        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_AUTOMATIC = "automatic"

        const val PHASE_VERIFYING = "verifying"
        const val PHASE_DELETING = "deleting"
        const val PHASE_AWAITING_CONSENT = "awaiting_consent"

        const val MAX_ATTEMPTS = 3

        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.free_up_verify_channel_name),
                description = context.getString(R.string.free_up_verify_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = false,
            )
        }

        fun protectDownloadedFor(trigger: String): Boolean = trigger == TRIGGER_AUTOMATIC

        fun olderThanMsFor(trigger: String, nowMs: Long, intervalMs: Long): Long =
            if (trigger == TRIGGER_MANUAL) Long.MAX_VALUE else nowMs - intervalMs

        fun phaseWireName(phase: FreeUpSpaceUseCase.PipelinePhase): String = when (phase) {
            FreeUpSpaceUseCase.PipelinePhase.Verifying -> PHASE_VERIFYING
            FreeUpSpaceUseCase.PipelinePhase.Deleting -> PHASE_DELETING
        }

        /**
         * Automatic sweeps must not run while a prior NeedsPermission offer is still fresh —
         * otherwise hourly work would keep succeeding against a stuck consent queue.
         * Stale offers (see [isFreeUpPendingOfferStale]) are not skipped; callers clear them
         * via [FreeUpPendingDeleteQueue.settleAllPending] and proceed.
         */
        fun shouldSkipAutomaticForPendingConsent(
            queueNonEmpty: Boolean,
            offeredAtMs: Long?,
            nowMs: Long,
        ): Boolean = queueNonEmpty && !isFreeUpPendingOfferStale(offeredAtMs, nowMs)

        /**
         * Retry only while under the attempt cap **and** the failure is transient or offline.
         * Caps WorkManager retries so a permanent error cannot spin forever.
         */
        fun shouldRetry(
            runAttemptCount: Int,
            transient: Boolean,
            offline: Boolean,
            maxAttempts: Int = MAX_ATTEMPTS,
        ): Boolean = runAttemptCount < maxAttempts && (transient || offline)

        /**
         * Maps a pipeline result to progress keys, consent offer, and notification decisions.
         * Always offers on [FreeUpSpaceUseCase.FreeUpResult.NeedsPermission] (no process-UI gate).
         */
        fun mapPipelineOutcome(result: FreeUpSpaceUseCase.FreeUpResult): MappedPipelineOutcome =
            when (result) {
                is FreeUpSpaceUseCase.FreeUpResult.NeedsPermission -> MappedPipelineOutcome(
                    phase = PHASE_AWAITING_CONSENT,
                    processed = result.checked,
                    total = result.checked,
                    removed = result.freed,
                    needsPermissionCount = result.localUris.size,
                    offerUris = result.localUris,
                    postConsentNotification = true,
                )
                is FreeUpSpaceUseCase.FreeUpResult.Done -> MappedPipelineOutcome(
                    phase = PHASE_DELETING,
                    processed = result.checked,
                    total = result.checked,
                    removed = result.freed,
                    needsPermissionCount = 0,
                    offerUris = emptyList(),
                    postConsentNotification = false,
                )
            }

        fun manualConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun automaticConstraints(): Constraints = FreeUpSpaceWorker.sweepConstraints()

        fun manualInputData(): Data = workDataOf(KEY_TRIGGER to TRIGGER_MANUAL)

        fun automaticInputData(intervalMs: Long): Data = Data.Builder()
            .putString(KEY_TRIGGER, TRIGGER_AUTOMATIC)
            .putLong(KEY_INTERVAL_MS, intervalMs)
            .build()

        fun progressData(
            phase: String,
            processed: Int,
            total: Int,
            removed: Int,
            needsPermissionCount: Int,
        ): Data = workDataOf(
            KEY_PHASE to phase,
            KEY_PROCESSED to processed,
            KEY_TOTAL to total,
            KEY_REMOVED to removed,
            KEY_NEEDS_PERMISSION_COUNT to needsPermissionCount,
        )

        fun outputData(
            phase: String,
            processed: Int,
            total: Int,
            removed: Int,
            needsPermissionCount: Int,
        ): Data = progressData(phase, processed, total, removed, needsPermissionCount)

        fun enqueueManual(context: Context) {
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<FreeUpPipelineWorker>()
                .addTag(ManualFreeUpWorker.TAG)
                .setConstraints(manualConstraints())
                .setInputData(manualInputData())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ManualFreeUpWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelManual(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(ManualFreeUpWorker.UNIQUE_NAME)
        }

        fun scheduleAutomatic(workManager: WorkManager, intervalMs: Long) {
            val request = PeriodicWorkRequestBuilder<FreeUpPipelineWorker>(1, TimeUnit.HOURS)
                .setConstraints(automaticConstraints())
                .setInputData(automaticInputData(intervalMs))
                .addTag(FreeUpSpaceWorker.TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(
                FreeUpSpaceWorker.TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun runAutomaticNow(workManager: WorkManager, intervalMs: Long) {
            val request = OneTimeWorkRequestBuilder<FreeUpPipelineWorker>()
                .setConstraints(automaticConstraints())
                .setInputData(automaticInputData(intervalMs))
                .addTag(FreeUpSpaceWorker.TAG)
                .build()
            workManager.enqueueUniqueWork(
                FreeUpSpaceWorker.NAME_ONESHOT,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun cancelAutomatic(workManager: WorkManager) {
            for (name in FreeUpSpaceWorker.uniqueWorkNamesToCancel()) {
                workManager.cancelUniqueWork(name)
            }
        }
    }
}
