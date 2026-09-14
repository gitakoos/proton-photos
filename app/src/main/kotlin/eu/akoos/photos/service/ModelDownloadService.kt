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

package eu.akoos.photos.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.R
import eu.akoos.photos.data.face.FaceEmbeddingModelManager
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.face.FaceModelAssets
import eu.akoos.photos.data.face.FaceModelManager
import eu.akoos.photos.data.face.FaceModelPreparation
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.ocr.OcrModelComponent
import eu.akoos.photos.data.ocr.OcrModelManager
import eu.akoos.photos.data.ocr.OcrModelOutcome
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.semantic.SemanticIndexingScheduler
import eu.akoos.photos.data.semantic.SemanticModelAssets
import eu.akoos.photos.data.semantic.SemanticModelManager
import eu.akoos.photos.data.semantic.SemanticModelPreparation
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.util.ModelDownloadKind
import eu.akoos.photos.util.ModelDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject

/**
 * The one foreground host for the on-device model downloads (face grouping, text recognition, and photo
 * search). Each feature is opt-in and its models are fetched only on consent; this service owns that
 * fetch so it survives the user leaving the settings screen and shows a progress notification, rather
 * than dying with a screen-scoped coroutine. The photo-search model is several hundred megabytes, which
 * is what made the missing background host visible; the smaller face and text models ride the same path.
 *
 * It never owns the resolve/verify logic: it calls the same per-rail managers the rest of the app uses
 * ([FaceModelManager] + [FaceEmbeddingModelManager], [OcrModelManager], [SemanticModelManager]), reports
 * their progress into the shared [ModelDownloadState] the settings rows read, and on a verified download
 * turns the feature on and kicks its indexer. A second feature enabled while one is downloading waits on
 * a mutex, so the two downloads run one after another and their sessions never overlap; each start
 * retires its own start id, so the service stays up until the last one finishes and is torn down without
 * a queue to race. The download consent flag is set here, since reaching the service means the user
 * accepted the prompt.
 *
 * Android 15 caps a `dataSync` service's daily runtime; [onTimeout] stops cleanly and the download
 * resumes on the next trigger (each manager verifies and promotes by rename, so a re-run only fetches
 * what has not landed).
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject lateinit var downloadState: ModelDownloadState
    @Inject lateinit var faceIndexingScheduler: FaceIndexingScheduler
    @Inject lateinit var semanticIndexingScheduler: SemanticIndexingScheduler
    @Inject lateinit var accountManager: AccountManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One download at a time: a second start blocks here until the first finishes, so the two ONNX model
     *  sets are never fetched at once and the notification only ever shows one download's progress. */
    private val workMutex = Mutex()

    /** How many bytes had landed when the notification text was last rewritten, to coalesce the
     *  per-chunk progress callbacks. Reset at the start of each kind. */
    private var lastNotifiedBytes = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDownloadService()
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { ModelDownloadKind.valueOf(it) }.getOrNull() }
        if (kind == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Promote immediately: Android kills the service within seconds otherwise, and on Android 12+ a
        // background start cannot foreground at all, so fall back to stopping rather than crash-looping.
        val foregrounded = try {
            startForegroundNotification(kind, buildNotification(kind, 0L, totalFor(kind)))
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground denied (background start): ${e.message}")
            false
        }
        if (!foregrounded) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        downloadState.running(kind, 0L, totalFor(kind))
        // Each start runs its download under the shared mutex (so two never overlap) and then retires its
        // own start id. The platform stops the service only once the most recent start has retired, so a
        // second download requested while the first runs is neither dropped nor torn down early, with no
        // hand-rolled queue to race.
        scope.launch {
            try {
                workMutex.withLock { process(kind) }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 caps how long a `dataSync` foreground service runs within a rolling day and calls this
     * once the budget is spent. Stop cleanly; the download is resumable and a later trigger picks it up.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground budget exhausted; stopping")
        stopDownloadService()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // Any download still marked in-flight cannot finish now, so clear it; otherwise its settings row
        // would stay wedged at a frozen bar, since the shared state is a process-lived singleton.
        downloadState.clearActive()
    }

    private suspend fun process(kind: ModelDownloadKind) {
        val userId = runCatching { accountManager.getPrimaryUserId().first() }.getOrNull()
        val total = totalFor(kind)
        // Reaching here means the user accepted the prompt, so record the consent the managers also check.
        runCatching { settingsDataStore.edit { it[consentKeyFor(kind)] = true } }
        lastNotifiedBytes = -1L
        downloadState.running(kind, 0L, total)
        postNotification(buildNotification(kind, 0L, total))

        val onProgress: (Long) -> Unit = { done ->
            downloadState.running(kind, done, total)
            // Coalesce the per-chunk callbacks so the shade is not rewritten on every 64 KB.
            if (done - lastNotifiedBytes >= NOTIFY_STEP_BYTES || done >= total) {
                lastNotifiedBytes = done
                postNotification(buildNotification(kind, done, total))
            }
        }

        val ready = try {
            runDownload(kind, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "download for $kind failed: ${t.message}")
            false
        }

        if (ready) {
            enableAndTrigger(kind, userId)
            downloadState.done(kind)
        } else {
            downloadState.failed(kind)
        }
    }

    /** Fetch the kind's model set through the same managers the rest of the app uses. */
    private suspend fun runDownload(kind: ModelDownloadKind, onProgress: (Long) -> Unit): Boolean =
        when (kind) {
            ModelDownloadKind.FACE -> {
                val detectorReady = FaceModelManager(this)
                    .prepare(onProgress = { onProgress(it) }) is FaceModelPreparation.Ready
                // The larger embedder is fetched only when the detector landed; its bytes carry on from the
                // detector's size so the bar climbs across both toward the quoted total.
                detectorReady && FaceEmbeddingModelManager(this)
                    .prepare(onProgress = { onProgress(FaceModelAssets.MODEL.sizeBytes + it) }) is FaceModelPreparation.Ready
            }
            ModelDownloadKind.OCR -> {
                // A short two-file fetch with no byte total, so it runs the notification's spinner.
                val manager = OcrModelManager(this)
                listOf(OcrModelComponent.Detection, OcrModelComponent.Recognition)
                    .all { manager.ensure(it) is OcrModelOutcome.Ready }
            }
            ModelDownloadKind.SEMANTIC ->
                SemanticModelManager(this)
                    .ensureDownloaded(onProgress = { onProgress(it) }) is SemanticModelPreparation.Ready
        }

    /** Turn the feature on and kick its indexer, so a verified download flows straight into use. */
    private suspend fun enableAndTrigger(kind: ModelDownloadKind, userId: me.proton.core.domain.entity.UserId?) {
        when (kind) {
            ModelDownloadKind.FACE -> {
                runCatching { settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = true } }
                faceIndexingScheduler.requestIndex(userId)
            }
            ModelDownloadKind.OCR ->
                runCatching { settingsDataStore.edit { it[SettingsKeys.OCR_ENABLED] = true } }
            ModelDownloadKind.SEMANTIC -> {
                runCatching { settingsDataStore.edit { it[SettingsKeys.SEMANTIC_ENABLED] = true } }
                // Fire-and-forget on the scheduler's own scope (like the face branch), so marking the
                // download done and tearing the notification down does not wait out the whole first
                // indexing pass. requestIndex clears any pause and starts the walk.
                semanticIndexingScheduler.requestIndex(userId)
            }
        }
    }

    private fun startForegroundNotification(kind: ModelDownloadKind, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(kind: ModelDownloadKind, done: Long, total: Long): Notification {
        val determinate = total > 0L
        val text = if (determinate) {
            getString(R.string.model_download_progress, formatBytes(done.coerceIn(0L, total)), formatBytes(total))
        } else {
            getString(R.string.model_download_preparing)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(titleResFor(kind)))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.model_download_stop),
                stopPendingIntent(),
            )
        if (determinate) {
            builder.setProgress(BAR_MAX, ((done.toDouble() / total) * BAR_MAX).toInt().coerceIn(0, BAR_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun postNotification(notification: Notification) {
        @android.annotation.SuppressLint("MissingPermission")
        val unused = runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, ModelDownloadService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopDownloadService() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun totalFor(kind: ModelDownloadKind): Long = when (kind) {
        ModelDownloadKind.FACE -> FaceModelAssets.TOTAL_DOWNLOAD_BYTES
        ModelDownloadKind.OCR -> 0L
        ModelDownloadKind.SEMANTIC -> SemanticModelAssets.TOTAL_DOWNLOAD_BYTES
    }

    private fun titleResFor(kind: ModelDownloadKind): Int = when (kind) {
        ModelDownloadKind.FACE -> R.string.model_download_title_face
        ModelDownloadKind.OCR -> R.string.model_download_title_ocr
        ModelDownloadKind.SEMANTIC -> R.string.model_download_title_semantic
    }

    private fun consentKeyFor(kind: ModelDownloadKind): Preferences.Key<Boolean> = when (kind) {
        ModelDownloadKind.FACE -> SettingsKeys.FACE_MODEL_DOWNLOAD_ALLOWED
        ModelDownloadKind.OCR -> SettingsKeys.OCR_MODEL_DOWNLOAD_ALLOWED
        ModelDownloadKind.SEMANTIC -> SettingsKeys.SEMANTIC_MODEL_DOWNLOAD_ALLOWED
    }

    companion object {
        const val TAG = "model_download_service"
        const val CHANNEL_ID = "model_downloads"
        val NOTIFICATION_ID = NotificationIds.MODEL_DOWNLOAD
        const val ACTION_STOP = "eu.akoos.photos.action.STOP_MODEL_DOWNLOAD"
        const val EXTRA_KIND = "kind"

        /** Progress-bar resolution: a fixed maximum the byte total maps onto, so the bar is smooth
         *  regardless of file size. */
        private const val BAR_MAX = 1000

        /** Rewrite the notification only after this many more bytes land, so a big download does not
         *  churn the shade on every read. */
        private const val NOTIFY_STEP_BYTES = 1_000_000L

        /**
         * Start (or queue) a download of [kind]'s model set. The service resolves the current account
         * itself for the enable and index kick, so this is a plain fire-and-forget. Safe to call
         * repeatedly; a redundant start collapses into the running foreground session.
         */
        fun start(context: Context, kind: ModelDownloadKind) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_KIND, kind.name)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "start failed: ${it.message}")
            }
        }

        /** Lazily create the model-download notification channel. Idempotent, and distinct from the
         *  indexing and backup channels so a user can mute one without the others. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.model_download_channel_name),
                description = context.getString(R.string.model_download_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }
    }
}
