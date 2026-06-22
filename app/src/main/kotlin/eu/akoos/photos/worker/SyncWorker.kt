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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.util.Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.domain.entity.UserId
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.domain.usecase.UploadStatus
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val reconcile: ReconcileSyncStateUseCase,
    private val upload: UploadPendingUseCase,
    private val accountManager: AccountManager,
    private val cloudRepo: DrivePhotoRepository,
) : CoroutineWorker(context, params) {

    /**
     * Live progress numbers used to refresh the foreground notification. Updated by the
     * [UploadPendingUseCase.progress] collector below; read by [buildForegroundInfo] each time
     * we call [setForeground] so the bar reflects the latest done/total.
     */
    @Volatile private var doneCount = 0
    @Volatile private var totalCount = 0

    override suspend fun doWork(): Result = coroutineScope {
        // Promote to foreground IMMEDIATELY so the run survives the app being swiped from Recents
        // and the OS doesn't kill an upload partway through. The promotion MUST happen in
        // WorkManager's start-of-work grant window: promoting later (only once an upload starts)
        // is denied by the Android 12+ background-foreground-service rules on aggressive OEMs,
        // leaving the worker as a killable background task whose notification vanishes the moment
        // the app is closed. The notification shows "Checking…" until the batch size is known,
        // then upload progress, and is cancelled in `finally` when the run ends — so an idle
        // check only flashes it briefly. The always-on idle "watching" notification is separate
        // (BackgroundSyncService) and stays independently toggleable.
        runCatching {
            setForeground(buildForegroundInfo(doneCount, totalCount))
        }.onFailure { e ->
            Log.w(TAG, "setForeground initial failed: ${e.message}")
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        }

        val userId = accountManager.getPrimaryUserId().first()
            ?: return@coroutineScope Result.failure()

        // Collect upload progress in a sibling coroutine. Every event updates the notification
        // counters and re-pushes the ForegroundInfo so the user sees "X uploaded / Y pending"
        // tick forward in near-real-time. Cancelled implicitly when doWork() returns.
        val progressJob: Job = launch {
            upload.progress.collect { evt ->
                if (evt.status == UploadStatus.Idle) {
                    // Idle = batch finished. Mark progress full so the bar reaches 100%, but
                    // don't push a new foreground refresh — doWork is about to exit anyway,
                    // and pushing a stale notification can collide with WorkManager's
                    // auto-dismiss when the worker finishes.
                    doneCount = evt.totalCount
                    totalCount = evt.totalCount
                } else {
                    doneCount = evt.doneIdx
                    totalCount = evt.totalCount
                    runCatching {
                        setForeground(buildForegroundInfo(doneCount, totalCount))
                    }.onFailure { /* swallow — worker may already be cancelling */ }
                }
            }
        }

        try {
            // ONE reconcile + upload pass. Multi-stage trailing passes inside one worker run
            // cause 90-second "0 of 0 uploaded" notification loops when there's nothing to do.
            // The OS content-URI trigger (rearmed in finally) fires multiple times during a
            // camera burst — APPEND_OR_REPLACE coalesces them — so per-worker multi-pass adds
            // complexity without buying anything the OS isn't already giving.
            val pass = runSyncPass(userId, firstPass = true)
            val uploadFailed = pass.failed

            context.settingsDataStore.edit { it[SettingsKeys.LAST_SYNC_MS] = System.currentTimeMillis() }
            return@coroutineScope if (uploadFailed && runAttemptCount < 3) Result.retry()
            else if (uploadFailed) Result.failure()
            else Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "sync IO error — will retry", e)
            return@coroutineScope if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) {
            // Cancellation is a normal shutdown path — rethrow so WorkManager records the
            // run as cancelled instead of failed (the latter retries unnecessarily).
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "sync failed", e)
            return@coroutineScope Result.failure()
        } finally {
            progressJob.cancel()
            // WorkManager auto-dismisses the foreground notification when the worker exits,
            // but on some OEMs the sticky ongoing notification lingers — explicitly cancel it
            // so users don't see a stale "X of Y uploaded" after the batch completes.
            runCatching {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }
            // Content-URI trigger is OneTime; re-arm from finally so it keeps firing
            // after each capture.
            runCatching {
                val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
                scheduleContentObserver(context, wifiOnly)
            }
        }
    }

    /**
     * Runs a single reconcile+upload pass. Returns `true` if upload failed (so the caller can
     * track failure across multiple trailing passes), `false` on success or no-op.
     *
     * `firstPass = true` is informational only — both first and trailing calls do the same
     * refresh + reconcile + upload sequence. The trailing passes are still necessary even when
     * the first pass succeeded, because photos taken between pass 1's reconcile and the end
     * of pass 1's upload (or that had IS_PENDING=1 during pass 1's MediaStore query) won't
     * be in the pending list until pass 2 re-queries.
     */
    /** Outcome of a single sync pass. `attempted = 0` means reconcile saw no pending items —
     *  trailing passes can skip in that case to avoid the "0 of 0 uploaded" notification loop. */
    private data class PassResult(val failed: Boolean, val attempted: Int)

    private suspend fun runSyncPass(userId: UserId, firstPass: Boolean): PassResult {
        try {
            // Refresh cloud state first so deleted-cloud photos are removed from DB before reconcile.
            cloudRepo.refreshCloudPhotosIncremental(userId)
            reconcile(userId).collect {}
            // Upload errors must NOT silently disappear — swallowing them gives a "success"
            // verdict even when nothing was uploaded, leaving a stale "last sync time" with no
            // indication the backup is broken.
            var uploadFailed = false
            var attempted = 0
            try {
                val uploadResult = upload(userId) { isStopped }
                attempted = uploadResult.attempted
                // Stale-success guard: UploadPendingUseCase catches per-item exceptions and
                // keeps looping, so an "all N items threw" run reaches us with no exception.
                if (uploadResult.allFailed) {
                    Log.w(TAG, "upload tried ${uploadResult.attempted} items, 0 succeeded — retrying")
                    uploadFailed = true
                }
            } catch (_: NotImplementedError) {
                // Legacy code path: harmless, the use case can be a no-op in some builds.
            } catch (e: IOException) {
                Log.w(TAG, "upload IO error — will retry", e)
                uploadFailed = true
            } catch (e: Exception) {
                // CancellationException must propagate so structured concurrency cancels the
                // surrounding coroutineScope correctly; downgrading it to "upload failed"
                // would swallow the cancel and keep the worker running past its budget.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "upload failed — sync verdict downgraded", e)
                uploadFailed = true
            }
            return PassResult(uploadFailed, attempted)
        } catch (e: IOException) {
            // Re-throw on first pass so the outer catch handles retries; on trailing passes,
            // a transient network blip shouldn't abort the whole worker — log and move on.
            if (firstPass) throw e
            Log.w(TAG, "trailing pass IO error — continuing", e)
            return PassResult(failed = true, attempted = 0)
        }
    }

    /**
     * Required override for CoroutineWorker.setForeground / expedited work. WorkManager calls
     * this BEFORE doWork when promoting an expedited request. We forward to [buildForegroundInfo]
     * with zeroes — the real progress numbers get pushed via setForeground inside doWork.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(0, 0)

    /**
     * Builds a ForegroundInfo with the photo-backup notification at the current progress.
     * Idempotently registers the channel on Android 8+ via [ensureChannel].
     */
    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val title = context.getString(R.string.sync_worker_notification_title)
        // total = 0 means we don't know the batch size yet (worker just started, reconcile
        // hasn't run). Show a "Checking…" line instead of "0 of 0 uploaded" — the latter
        // makes the notification flicker between real progress and a confusing zero-state
        // whenever a follow-up worker fires right after a finished batch.
        val content = if (total <= 0) {
            context.getString(R.string.sync_worker_notification_checking)
        } else {
            context.getString(R.string.sync_worker_notification_progress, done, total)
        }
        val cancelLabel = context.getString(R.string.sync_worker_notification_cancel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            // Indeterminate when no batch is in flight yet (total == 0); determinate progress
            // bar once we know the size. coerceAtLeast(1) avoids divide-by-zero in the M3 bar.
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // DEFAULT (not LOW) matches the channel importance so the heads-up shows once
            // on the first emit, then setOnlyAlertOnce keeps the per-file progress quiet.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                cancelLabel,
                cancelIntent,
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "sync_worker"
        const val NAME_ONESHOT = "sync_worker_oneshot"
        const val TAG_ONESHOT = "sync_oneshot"
        const val CHANNEL_ID = "sync_worker"
        // Deliberately distinct from AlbumDownloadWorker.NOTIFICATION_ID (4242) so the two
        // foreground notifications can coexist when both workers are running.
        const val NOTIFICATION_ID = 4243

        /** WorkManager's hard floor — periodic work cannot fire faster than this. */
        const val MIN_INTERVAL_MINUTES = 15L

        /**
         * Lazily creates the photo-backup notification channel. Idempotent. Pre-Oreo devices
         * have no channel concept so this returns immediately. Distinct from AlbumDownloadWorker's
         * channel so the user can mute one without affecting the other.
         */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            // IMPORTANCE_LOW keeps the upload notification visible in the status bar but
            // suppresses the heads-up popup banner. A privacy-focused user base expects
            // background sync to be present-but-quiet; the earlier DEFAULT importance was
            // intrusive on every automatic upload — and our own manual "Sync now" path
            // already gives an immediate toast, so we don't need the heads-up for that
            // either. Existing installs migrate when the channel is recreated post-uninstall;
            // a user who wants the louder behaviour can flip it in system Settings → App
            // notifications, which is the canonical Android pattern.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_worker_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.sync_worker_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun schedule(workManager: WorkManager, wifiOnly: Boolean = true, intervalMinutes: Long = MIN_INTERVAL_MINUTES) {
            // CONNECTED regardless of wifiOnly: WorkManager's UNMETERED constraint would block any
            // metered Wi-Fi (hotspots, some routers). The Wi-Fi-vs-mobile decision is enforced in
            // UploadPendingUseCase via NetworkObserver.currentlyOnWifi(), which allows metered Wi-Fi.
            val networkType = NetworkType.CONNECTED
            val safeInterval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(safeInterval, TimeUnit.MINUTES)
                // batteryNotLow gates the periodic sync away from running while the
                // device is in the OS's "low battery" state (~15 %). Aligns with the
                // other periodic workers (FreeUpSpace, CachePrune) which already had
                // it. A user can still trigger a manual Sync now from Settings —
                // that uses runNow() below, which deliberately omits this constraint
                // because the user explicitly asked for it.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag(TAG)
                .build()
            // UPDATE (not KEEP) is essential when the user toggles the Wi-Fi-only setting:
            // with KEEP, WorkManager ignores the new Constraints because the unique-work
            // entry already exists, and the next periodic fire keeps the old network
            // constraint. That meant a Wi-Fi-only OFF→ON toggle could let one more
            // mobile-data upload slip through. UPDATE replaces the constraints + period
            // while preserving the existing work ID and run history.
            workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(workManager: WorkManager) = workManager.cancelUniqueWork(TAG)

        /**
         * OS-level MediaStore content-URI trigger via WorkManager. The system itself watches
         * the URIs even when our process is dead and wakes SyncWorker within seconds of a
         * MediaStore change. Re-armed by [doWork]'s finally block so each fire arms the
         * NEXT one.
         */
        fun scheduleContentObserver(context: Context, wifiOnly: Boolean = true) {
            // CONNECTED for both modes; the metered-Wi-Fi-aware Wi-Fi-only gate lives in
            // UploadPendingUseCase (see schedule()). wifiOnly stays in the signature for callers.
            val networkType = NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .addContentUriTrigger(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag(TAG_CONTENT_OBSERVER)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME_CONTENT_OBSERVER,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        const val NAME_CONTENT_OBSERVER = "sync_worker_content_observer"
        const val TAG_CONTENT_OBSERVER = "sync_content_observer"

        /**
         * Immediately enqueue a OneTime sync run so the user doesn't have to wait for the next
         * periodic fire (which can be up to 15 min away). The OneTime work uses the same
         * Wi-Fi-only constraint as periodic, then runs as a foreground service so the user
         * sees a notification while it works.
         *
         * Uniqueness ([NAME_ONESHOT]) + [ExistingWorkPolicy.APPEND_OR_REPLACE] means:
         *   - If no worker is running for [NAME_ONESHOT], the request runs immediately.
         *   - If one IS running, this request is APPENDED to run after the current one
         *     finishes. This is the key fix for camera bursts: photo #1 fires the observer,
         *     SyncWorker starts uploading. While it's mid-upload, photos #2, #3, #4 arrive.
         *     With the previous KEEP policy those kicks were silently dropped, and reconcile
         *     never re-queried MediaStore — the unfinished photos would sit until the next
         *     periodic fire OR the next foreground enter. APPEND_OR_REPLACE guarantees a
         *     second pass picks them up.
         *   - APPEND_OR_REPLACE (instead of plain APPEND) means we only ever queue ONE
         *     follow-up — a burst of 20 kicks doesn't queue 20 workers, it queues 1 that
         *     handles whatever pending state the burst left in MediaStore.
         */
        /**
         * Enqueue a one-shot sync run. The default [allowLowBattery] = false matches the
         * periodic schedule: don't drain the device when the OS already flagged battery
         * pressure. Pass `true` ONLY when the user explicitly triggered the run (Settings
         * → Sync now), where deferring to a higher battery state would feel broken.
         */
        fun runNow(context: Context, wifiOnly: Boolean = true, allowLowBattery: Boolean = false) {
            ensureChannel(context)
            // CONNECTED for both modes; the metered-Wi-Fi-aware Wi-Fi-only gate lives in
            // UploadPendingUseCase (see schedule()). wifiOnly stays in the signature for callers.
            val networkType = NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .apply { if (!allowLowBattery) setRequiresBatteryNotLow(true) }
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag(TAG_ONESHOT)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME_ONESHOT,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
