package eu.akoos.photos.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
        // Promote to foreground IMMEDIATELY so the OS doesn't kill us partway through upload,
        // and the user sees the work is happening. We can't return early without first
        // showing the notification when the worker was enqueued as expedited / foreground.
        runCatching {
            setForeground(buildForegroundInfo(doneCount, totalCount))
        }.onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }

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
            // Refresh cloud state first so deleted-cloud photos are removed from DB before reconcile.
            cloudRepo.refreshCloudPhotosIncremental(userId)
            reconcile(userId).collect {}
            // Upload errors must NOT silently disappear — the previous swallow gave a "success"
            // verdict even when nothing was uploaded, so the user saw a stale "last sync time"
            // with no idea the backup was broken. Log the error and let LAST_SYNC_MS still
            // advance (the reconcile work above DID succeed), but a non-IO upload exception
            // surfaces as a retryable failure so WorkManager re-runs sooner.
            var uploadFailed = false
            try {
                val uploadResult = upload(userId)
                // Stale-success guard: UploadPendingUseCase catches per-item exceptions and
                // keeps looping, so an "all N items threw" run reaches us with no exception.
                // Without this check, LAST_SYNC_MS would advance and the UI would say
                // "synced just now" even though zero photos uploaded.
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
                Log.e(TAG, "upload failed — sync verdict downgraded", e)
                uploadFailed = true
            }
            context.settingsDataStore.edit { it[SettingsKeys.LAST_SYNC_MS] = System.currentTimeMillis() }
            return@coroutineScope if (uploadFailed && runAttemptCount < 3) Result.retry()
            else if (uploadFailed) Result.failure()
            else Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "sync IO error — will retry", e)
            return@coroutineScope if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            return@coroutineScope Result.failure()
        } finally {
            progressJob.cancel()
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
        val content = context.getString(R.string.sync_worker_notification_progress, done, total)
        val cancelLabel = context.getString(R.string.sync_worker_notification_cancel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            // IMPORTANCE_DEFAULT (not LOW) so the user actually sees the upload notification
            // pop up in the status bar when an automatic background upload kicks off. LOW
            // showed only a silent icon, making the sync look inactive even while it was
            // running. DEFAULT still does NOT play a sound (setShowBadge(false) + default
            // sound settings), but the heads-up banner gives a clear "upload started" signal.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_worker_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.sync_worker_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun schedule(workManager: WorkManager, wifiOnly: Boolean = true, intervalMinutes: Long = MIN_INTERVAL_MINUTES) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val safeInterval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(safeInterval, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
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
         * Immediately enqueue a OneTime sync run so the user doesn't have to wait for the next
         * periodic fire (which can be up to 15 min away). The OneTime work uses the same
         * Wi-Fi-only constraint as periodic, then runs as a foreground service so the user
         * sees a notification while it works.
         *
         * Uniqueness ([NAME_ONESHOT]) + [ExistingWorkPolicy.KEEP] means concurrent kick-off
         * calls (e.g. gallery foreground + folder-settings change) coalesce into a single run.
         * If a periodic run is already in progress when this is called, KEEP also avoids
         * duplicating that work.
         */
        fun runNow(context: Context, wifiOnly: Boolean = true) {
            ensureChannel(context)
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .addTag(TAG_ONESHOT)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME_ONESHOT,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
