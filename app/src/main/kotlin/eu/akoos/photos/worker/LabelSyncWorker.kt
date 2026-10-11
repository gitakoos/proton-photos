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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.akoos.photos.R
import eu.akoos.photos.data.autosave.AutoSaveCoordinator
import eu.akoos.photos.data.autosave.AutoSaveKind
import eu.akoos.photos.data.autosave.AutoSaveRunResult
import eu.akoos.photos.data.label.LabelBackupOperation
import eu.akoos.photos.data.label.LabelBackupOutcome
import eu.akoos.photos.data.label.LabelBackupProgress
import eu.akoos.photos.data.label.LabelBackupResult
import eu.akoos.photos.data.label.LabelBackupRunner
import eu.akoos.photos.data.label.LabelSyncRequest
import eu.akoos.photos.data.label.LabelSyncWorkData
import eu.akoos.photos.data.mlsync.MlSyncSignOut
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.sdk.SdkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one label sync job, a save or a restore of the labels in the Drive "Labels" folder, as a foreground
 * worker with an ongoing notification and a Cancel, so it goes on with the screen closed. The request comes from
 * the input data ([LabelSyncWorkData.input]), progress goes out through setProgress, and the run ends with output
 * of names and counts only ([LabelSyncWorkData.output]). [eu.akoos.photos.data.label.LabelBackupController]
 * starts the jobs and reads their state, and [eu.akoos.photos.data.label.WorkLabelWriteGate] keeps the labels
 * from being edited while one is queued or running.
 *
 * One job at a time: every request is enqueued as the unique work [UNIQUE_WORK_NAME] under
 * [ExistingWorkPolicy.KEEP]. A sign-out stops the run before anything else ([MlSyncSignOut]), and a request made
 * for an account that is not the signed-in one ends as [LabelBackupResult.NOT_SIGNED_IN].
 */
@HiltWorker
class LabelSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountManager: AccountManager,
    private val sdkGate: SdkGate,
    private val runner: LabelBackupRunner,
    private val signOut: MlSyncSignOut,
    private val autoSave: AutoSaveCoordinator,
) : CoroutineWorker(context, params) {

    /** Read from the input on its own, so the notification knows it even for a request that is unreadable. */
    private val operation: LabelBackupOperation? by lazy { LabelSyncWorkData.operation(inputData) }

    /** When this run began and how it ended, so the automatic save can record a finished save. */
    private var runStartedMs = 0L
    private var lastOutcome: LabelBackupOutcome? = null

    /** Tracked whole, so a sign-out can stop the run and wait for it to end. */
    override suspend fun doWork(): Result = signOut.track {
        runStartedMs = System.currentTimeMillis()
        try {
            runRequest()
        } finally {
            withContext(NonCancellable) { recordAutoSave() }
        }
    }

    /** Tells the automatic save how this run went, whoever started it, so it records a finished save and plans the next. */
    private suspend fun recordAutoSave() {
        val outcome = lastOutcome
        val result = if (outcome?.operation != LabelBackupOperation.SAVE) {
            AutoSaveRunResult.NEITHER
        } else {
            when (outcome.result) {
                LabelBackupResult.SAVED, LabelBackupResult.NOTHING_TO_SAVE -> AutoSaveRunResult.SAVED
                LabelBackupResult.FAILED, LabelBackupResult.CONFLICT, LabelBackupResult.FOLDER_MISSING ->
                    AutoSaveRunResult.FAILED
                else -> AutoSaveRunResult.NEITHER
            }
        }
        try {
            autoSave.saveEnded(AutoSaveKind.LABELS, runStartedMs, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "automatic save not told: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private suspend fun runRequest(): Result {
        val request = LabelSyncWorkData.request(inputData)
            ?: return finish(LabelBackupOutcome(operation ?: LabelBackupOperation.SAVE, LabelBackupResult.FAILED))
        val userId = UserId(request.userId)
        if (isSignedIn(userId) != true) return finish(outcomeOf(request, LabelBackupResult.NOT_SIGNED_IN))
        if (!sdkGate.isCapable) return finish(outcomeOf(request, LabelBackupResult.NOT_CAPABLE))

        bestEffort("foreground") { setForeground(foregroundInfo()) }
        return try {
            val outcome = withContext(Dispatchers.IO) {
                whileSignedIn(userId) { runner.run(userId, request, ::report) }
            }
            finish(outcome ?: outcomeOf(request, LabelBackupResult.NOT_SIGNED_IN))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "${request.operation} ran out of memory")
            finish(outcomeOf(request, LabelBackupResult.FAILED))
        } catch (e: Exception) {
            Log.w(TAG, "${request.operation} failed: ${e.javaClass.simpleName}")
            finish(outcomeOf(request, LabelBackupResult.FAILED))
        } finally {
            // WorkManager drops the foreground post on exit, but some OEMs leave it behind.
            runCatching { NotificationManagerCompat.from(applicationContext).cancel(NotificationIds.LABEL_SYNC) }
        }
    }

    private fun outcomeOf(request: LabelSyncRequest, result: LabelBackupResult) =
        LabelBackupOutcome(request.operation, result)

    /**
     * Runs [block] while [userId] stays the signed-in account and returns its result, or null once a sign-out
     * cancelled it. A cancellation of the worker itself still propagates.
     */
    private suspend fun <T : Any> whileSignedIn(userId: UserId, block: suspend () -> T): T? = coroutineScope {
        val signedOut = AtomicBoolean(false)
        val work = async { block() }
        val watcher = launch {
            accountManager.getPrimaryUserId().first { it != userId }
            signedOut.set(true)
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (!signedOut.get() || !isActive) throw e
            Log.i(TAG, "account changed, run stopped")
            null
        } finally {
            watcher.cancel()
        }
    }

    /** Whether [userId] is the signed-in account and not signing out, or null when the account state cannot be read. */
    private suspend fun isSignedIn(userId: UserId): Boolean? = try {
        accountManager.getPrimaryUserId().first() == userId && !signOut.isSigningOut(userId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun report(progress: LabelBackupProgress) {
        val op = operation ?: return
        bestEffort("progress") { setProgress(LabelSyncWorkData.progress(op, progress)) }
    }

    private fun finish(outcome: LabelBackupOutcome): Result {
        lastOutcome = outcome
        Log.i(TAG, "${outcome.operation} ended: ${outcome.result}")
        val output = LabelSyncWorkData.output(outcome)
        return when (outcome.result) {
            LabelBackupResult.SAVED, LabelBackupResult.RESTORED, LabelBackupResult.NOTHING_TO_SAVE,
            LabelBackupResult.FOLDER_MISSING -> Result.success(output)
            else -> Result.failure(output)
        }
    }

    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what not updated: ${e.javaClass.simpleName}")
        }
    }

    /** The ongoing notification: the operation's title over an indeterminate bar, with a Cancel that stops this worker. */
    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        ensureChannel(context)
        val restoring = operation == LabelBackupOperation.RESTORE
        val title = if (restoring) R.string.label_sync_restore_title else R.string.label_sync_save_title
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (restoring) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()
        // The type has to match the manifest's SystemForegroundService entry (dataSync) on Android 10+.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.LABEL_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.LABEL_SYNC, notification)
        }
    }

    companion object {
        const val TAG = "label_sync_worker"
        const val UNIQUE_WORK_NAME = "label_sync"
        const val CHANNEL_ID = "label_sync"

        /** Lazily creates the label sync notification channel. Idempotent. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.label_sync_channel_name),
                description = context.getString(R.string.label_sync_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }

        /**
         * Enqueues [request] as the one label sync job and returns its id. Under [ExistingWorkPolicy.KEEP] a job
         * already queued or running stays and this request is dropped. The operation also rides as a tag, so a
         * reader knows it while the job is still queued.
         */
        fun enqueue(context: Context, request: LabelSyncRequest): UUID {
            ensureChannel(context)
            val work = OneTimeWorkRequestBuilder<LabelSyncWorker>()
                .setInputData(LabelSyncWorkData.input(request))
                .addTag(TAG)
                .addTag(LabelSyncWorkData.operationTag(request.operation))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work)
            return work.id
        }
    }
}
