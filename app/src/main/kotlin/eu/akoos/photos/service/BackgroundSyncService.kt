package eu.akoos.photos.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Long-running foreground service that keeps the app process alive on aggressive OEMs
 * (Samsung One UI, MIUI, ColorOS, etc.) so a [ContentObserver] on MediaStore can fire
 * the upload pipeline within seconds of a new photo arriving — even after the user has
 * swiped the app out of Recents.
 *
 * Why this exists: WorkManager's content-URI trigger + 15-min periodic safety net does
 * NOT survive Samsung's swipe-from-recents kill. The OS refuses to bind to
 * JobScheduler-fired services after a task kill, which means a freshly-taken photo can
 * sit on disk for hours before the next periodic fire (or until the user re-opens the
 * app). Google Photos solves the same problem with a persistent foreground service —
 * this is our equivalent.
 *
 * Notification is intentionally plain (LOW importance, no sound/vibration, no heads-up)
 * because it's permanent and the user should be able to ignore it. The notification is
 * also distinct from [SyncWorker]'s upload-progress channel so users can mute one
 * without affecting the other.
 *
 * Lifecycle:
 *  - [onCreate]   → promote to foreground, register content observers
 *  - [onStartCommand] → idempotent; returns START_STICKY so the OS restarts us
 *  - [onDestroy]  → unregister observer, cancel pending debounce
 */
class BackgroundSyncService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { fireSync() }

    /** Service-scoped coroutine scope for [fireSync] — avoids the previous main-thread
     *  `runBlocking` DataStore read which could stall the UI thread on slow flash. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var observer: ContentObserver? = null

    /** Tracks whether SyncWorker is currently RUNNING. When true the BG notification swaps
     *  to the silent "minimised" state so users see only one upload-progress notification
     *  (SyncWorker's own foreground one) instead of two overlapping ones. */
    @Volatile private var workerRunning = false
    private val workInfoObserver = Observer<List<WorkInfo>> { infos ->
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        if (running != workerRunning) {
            workerRunning = running
            // Update is wrapped in runCatching so a denied POST_NOTIFICATIONS permission
            // doesn't crash — Android 13+ requires the runtime grant, but the BG service
            // is best-effort anyway and the notification is informational.
            @android.annotation.SuppressLint("MissingPermission")
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Promote to foreground IMMEDIATELY — Android 8+ kills the service within ~5s if
        // we don't, and on Android 12+ a delayed startForeground() throws ForegroundServiceStartNotAllowedException.
        ensureChannel(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        registerObserver()
        // Track whether SyncWorker is currently running. While it's running, suppress the
        // "Watching for new photos" subtitle on our own notification so the user doesn't
        // see two overlapping upload notifications and think the upload stopped.
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagLiveData(SyncWorker.TAG)
            .observeForever(workInfoObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: if the service is already running and someone calls start() again,
        // we just stay running. The observer was registered in onCreate the first time.
        // START_STICKY = OS restarts us after a process kill with a null intent. Combined
        // with onCreate registering the observer, this means the service self-heals after
        // OOM kills, Doze releases, and OEM "memory cleaner" sweeps.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        observer?.let {
            runCatching { contentResolver.unregisterContentObserver(it) }
        }
        observer = null
        runCatching {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosByTagLiveData(SyncWorker.TAG)
                .removeObserver(workInfoObserver)
        }
        scope.cancel()
    }

    /**
     * Registers a single [ContentObserver] for both images and videos. notifyForDescendants=true
     * is important — camera writes hit child URIs (e.g. content://media/external/images/media/12345)
     * and we want to catch those, not just the table root.
     */
    private fun registerObserver() {
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Debounce — a single camera write often fires multiple notify events
                // (one for the table, one for the row, sometimes one per side-car). Without
                // debounce we'd kick the worker 3-5 times for a single photo.
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants
            obs,
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            obs,
        )
        observer = obs
    }

    /**
     * Called after the debounce window expires. Reads the wifiOnly preference and hands
     * off to the existing one-shot upload runner — we deliberately do NOT duplicate the
     * upload pipeline, just kick the worker.
     *
     * Runs on the service-scoped IO dispatcher so the DataStore cold-cache read doesn't
     * block the main thread (the prior `runBlocking` cost up to ~100ms on slow flash).
     * Losing one kick is harmless — the next MediaStore change or the periodic safety
     * net will pick the photos up.
     */
    private fun fireSync() {
        scope.launch {
            try {
                val wifiOnly = applicationContext.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
                SyncWorker.runNow(applicationContext, wifiOnly)
            } catch (t: Throwable) {
                Log.w(TAG, "fireSync failed: ${t.message}")
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(getString(R.string.bg_sync_service_title))
        // Suppress the "Watching for new photos" subtitle while SyncWorker is uploading —
        // its own foreground notification carries the live progress, and two overlapping
        // "we are doing something with photos" notifications confused users into thinking
        // the upload had stopped mid-batch.
        .setContentText(
            if (workerRunning) null
            else getString(R.string.bg_sync_service_text)
        )
        .setOngoing(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val TAG = "bg_sync_service"
        const val CHANNEL_ID = "bg_sync_service"
        // Distinct from SyncWorker (4243) and AlbumDownloadWorker (4242) so the foreground
        // notifications don't collide.
        const val NOTIFICATION_ID = 4244

        /** 5-second debounce — camera bursts (10 photos in 2 seconds) coalesce into one sync run. */
        private const val DEBOUNCE_MS = 5_000L

        /**
         * Idempotently starts the service as foreground. Repeated calls are cheap — the
         * OS routes them through onStartCommand and we just stay running. Safe to call
         * from MainActivity.onCreate, BootCompletedReceiver, and SettingsViewModel.
         */
        fun start(context: Context) {
            val intent = Intent(context, BackgroundSyncService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                // ForegroundServiceStartNotAllowedException on Android 12+ when we try to
                // start from a non-allowed context (rare for our call sites, but defensive).
                Log.w(TAG, "start failed: ${it.message}")
            }
        }

        /** Stops the service. Idempotent — no-op if not running. */
        fun stop(context: Context) {
            val intent = Intent(context, BackgroundSyncService::class.java)
            runCatching { context.stopService(intent) }
        }

        /**
         * Lazily creates the background-sync notification channel. Idempotent. Pre-Oreo
         * devices have no channel concept so this returns immediately. Distinct from
         * SyncWorker's channel — users may want to mute this permanent notification
         * while still seeing per-upload progress, or vice versa.
         */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.bg_sync_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.bg_sync_service_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
