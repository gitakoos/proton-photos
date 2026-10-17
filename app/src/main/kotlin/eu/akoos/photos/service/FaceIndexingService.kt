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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.R
import eu.akoos.photos.data.face.FaceIndexingProgress
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.face.FaceIndexingState
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.util.FaceDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/**
 * Foreground host for the one-time initial face-indexing walk. The walk itself lives in
 * [FaceIndexingScheduler]; this service only keeps the app process alive so a large first pass runs to
 * completion after the user swipes the app from Recents, and shows its progress in a LOW notification.
 *
 * It never owns the work. On start it triggers the walk through the scheduler's own entry and then
 * observes [FaceIndexingScheduler.progress]; the scheduler's single-flight guard collapses that trigger
 * into a walk already running (e.g. one the gallery started), so the service can only ever host and
 * report, never spawn a second walk. Health gating (battery, heat, power saver, interaction) stays in
 * the scheduler; this service does not touch it and holds no wakelock, the foreground service being the
 * sanctioned host that keeps the CPU available.
 *
 * The walk legitimately parks while the device is a poor state for heavy work, which shows here as the
 * progress count standing still. The notification then reads a waiting line rather than a frozen count,
 * and if no progress lands within [GRACE_STOP_MS] the service stops itself and lets a later launch or
 * trigger resume, so a park never holds an idle foreground service forever. A walk that is not running at
 * all has nothing to wait for: paused (by the user, or by a restore from Drive standing it down), finished,
 * idle or without its model, it ends the service and its notification at once ([faceServiceHostsWalk]), and
 * the scheduler starts the service again with the next large walk.
 */
@AndroidEntryPoint
class FaceIndexingService : Service() {

    @Inject lateinit var scheduler: FaceIndexingScheduler

    /** Service-scoped scope for the trigger and the progress watcher; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards the one-time trigger + observe, so repeat starts (pass re-kicks, sticky restarts) do not
     *  stack watchers or extra triggers on the same instance. */
    private var started = false

    /** Held around every notification post and around [stopping]'s write, so no post from the watcher's
     *  thread can land once the stop has begun and outlive the service as a plain ongoing notification. */
    private val postLock = Any()

    /** Set once the service is stopping; guarded by [postLock]. */
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        // First, ahead of injection: see startForegroundEarly.
        promoteEarly()
        super.onCreate()
        ensureChannel(this)
    }

    private fun promoteEarly() {
        ensureChannel(this)
        startForegroundEarly(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.face_index_service_title))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop action: pause the walk so it stays off until the user resumes it from the face recognition
        // screen, then drop the service.
        if (intent?.action == ACTION_STOP) {
            scope.launch { runCatching { scheduler.setPaused(true) } }
            stopIndexingService()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_USER_ID)?.let { lastUserId = it }
        // A start that only hosts a grouping the scheduler is running must not begin a walk of its own: that walk
        // would publish its own progress over the grouping's, and settling to Idle would end this host mid-grouping.
        val triggersWalk = intent?.getBooleanExtra(EXTRA_TRIGGERS_WALK, true) ?: true

        // Promote immediately: Android kills the service within seconds otherwise, and on Android 12+ a
        // background start cannot foreground at all, so fall back to stopping rather than crash-looping.
        val foregrounded = try {
            startForegroundWith(scheduler.progress.value)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground denied (background start): ${e.message}")
            false
        }
        if (!foregrounded) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Note the foreground start once per session (a re-kick does not reset it), so the copied
        // diagnostics can show how long this dataSync service has run against the platform's daily budget.
        FaceDiagnostics.recordFgsStart()

        if (!started) {
            started = true
            // Trigger through the scheduler's own entry; the single-flight guard makes a redundant kick a
            // no-op, so this only starts a walk when none is running (e.g. a sticky restart) and otherwise
            // just hosts the one already going.
            // A guest walk carries the local sentinel, never a fabricated UserId (that would poison the
            // per-UserId flow + crypto caches), so map it back to a null userId for indexAll.
            lastUserId?.takeIf { triggersWalk }?.let { uid ->
                scope.launch {
                    runCatching {
                        scheduler.indexAll(if (uid == PhotoLocationEntity.LOCAL_USER) null else UserId(uid))
                    }
                }
            }
            observeProgress()
        }
        return START_STICKY
    }

    /**
     * Android 15 caps how long a `dataSync` foreground service runs within a rolling day and calls this
     * once the budget is spent, giving the service seconds to stop before the platform kills it. Stop
     * cleanly; the walk is resumable and a later launch or trigger picks it up.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Record the platform hitting the dataSync daily budget, so the copied diagnostics can confirm or
        // rule out the 6h cap as the reason a large scan stopped short.
        FaceDiagnostics.recordFgsTimeout()
        Log.w(TAG, "dataSync foreground budget exhausted; stopping")
        stopIndexingService()
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(postLock) { stopping = true }
        scope.cancel()
        // Whatever still carries this service's id goes with it.
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        // The foreground session ended, so the diagnostics runtime reads "off" until the next start.
        FaceDiagnostics.recordFgsStop()
    }

    /**
     * Poll the scheduler's progress and keep the notification honest: a moving count reads "N of M", a
     * count that has stood still past [STALE_AFTER_MS] (a health park) reads the waiting line, and a walk
     * that is not running (paused, finished, idle, or waiting for its model) stops the service at once.
     * Polling rather than collecting the flow, because a park emits nothing, yet the service still has to
     * notice the stall to switch text and to stop itself after the grace window. A grouping that works with
     * the count standing still beats a heartbeat instead ([faceHostSawProgress]), which counts as progress.
     */
    private fun observeProgress() {
        scope.launch {
            var lastIndexed = -1
            var lastHeartbeat = 0L
            var lastAdvanceAt = SystemClock.elapsedRealtime()
            var lastText: String? = null
            while (isActive) {
                val p = scheduler.progress.value
                if (!faceServiceHostsWalk(p.state)) {
                    stopIndexingService()
                    return@launch
                }
                val now = SystemClock.elapsedRealtime()
                if (faceHostSawProgress(p, lastIndexed, lastHeartbeat)) {
                    lastIndexed = maxOf(lastIndexed, p.indexed)
                    lastHeartbeat = maxOf(lastHeartbeat, p.heartbeat)
                    lastAdvanceAt = now
                }
                val staleFor = now - lastAdvanceAt
                if (staleFor >= GRACE_STOP_MS) {
                    stopIndexingService()
                    return@launch
                }
                val advancing = p.state == FaceIndexingState.Running && p.total > 0 && staleFor < STALE_AFTER_MS
                val text = if (advancing) {
                    getString(R.string.face_index_service_progress, p.indexed, p.total)
                } else {
                    getString(R.string.face_index_service_waiting)
                }
                if (text != lastText) {
                    lastText = text
                    postNotification(buildNotification(advancing, p.indexed, p.total, text))
                }
                delay(POLL_MS)
            }
        }
    }

    private fun startForegroundWith(p: FaceIndexingProgress) {
        val advancing = p.state == FaceIndexingState.Running && p.total > 0
        // A walk that is not running gets no line at all: the watcher ends the service on its first look,
        // and a paused walk is not waiting to continue.
        val text = when {
            advancing -> getString(R.string.face_index_service_progress, p.indexed, p.total)
            faceServiceHostsWalk(p.state) -> getString(R.string.face_index_service_waiting)
            else -> null
        }
        val notification = buildNotification(advancing, p.indexed, p.total, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(advancing: Boolean, indexed: Int, total: Int, text: String?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.face_index_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.face_index_service_stop),
                stopPendingIntent(),
            )
        // Determinate bar while the count moves; indeterminate while parked, so the bar never reads as a
        // frozen position.
        if (advancing) builder.setProgress(total, indexed.coerceIn(0, total), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun postNotification(notification: Notification) {
        synchronized(postLock) {
            // A stop has begun: a post now would outlive the service it describes.
            if (stopping) return
            // Wrapped: a denied POST_NOTIFICATIONS grant must not crash the update, the notification being
            // informational and the service best-effort.
            @android.annotation.SuppressLint("MissingPermission")
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, FaceIndexingService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Ends the service and its notification. Called from the main thread (the Stop action, the platform's
     * timeout) as well as from the watcher's, so the stop first shuts [postNotification] out, and then also
     * cancels the id, in case a post landed in between and some OEMs keep it past the foreground removal.
     */
    private fun stopIndexingService() {
        synchronized(postLock) { stopping = true }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    companion object {
        const val TAG = "face_index_service"
        const val CHANNEL_ID = "face_indexing"
        const val NOTIFICATION_ID = NotificationIds.FACE_INDEXING
        const val ACTION_STOP = "eu.akoos.photos.action.STOP_FACE_INDEX"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_TRIGGERS_WALK = "triggers_walk"

        /** How often the watcher samples progress. */
        private const val POLL_MS = 2_000L

        /** No forward progress for this long flips the notification to the waiting line. Covers a health
         *  park, which the scheduler reports as Running with a count that no longer moves. */
        private const val STALE_AFTER_MS = 15_000L

        /** No forward progress for this long stops the service, so a long park never holds an idle
         *  foreground service; a later launch or trigger resumes the walk. */
        private const val GRACE_STOP_MS = 120_000L

        /** The last account a start carried, so a sticky restart with a null intent still has a userId to
         *  resume with while the process lives. */
        @Volatile private var lastUserId: String? = null

        /** Idempotently start the host for [userId]; [triggersWalk] is false when only a grouping is hosted and no
         *  walk should begin. Repeated calls route through onStartCommand and stay
         *  running; a background-start refusal on Android 12+ is swallowed since the caller cannot know it
         *  is foreground. */
        fun start(context: Context, userId: UserId?, triggersWalk: Boolean = true) {
            lastUserId = userId?.id ?: PhotoLocationEntity.LOCAL_USER
            val intent = Intent(context, FaceIndexingService::class.java)
                .putExtra(EXTRA_USER_ID, userId?.id ?: PhotoLocationEntity.LOCAL_USER)
                .putExtra(EXTRA_TRIGGERS_WALK, triggersWalk)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "start failed: ${it.message}")
            }
        }

        /** Lazily creates the face-indexing notification channel. Idempotent, and distinct from the
         *  backup channel so a user can mute one without the other. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.face_index_service_channel_name),
                description = context.getString(R.string.face_index_service_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }
    }
}

/**
 * Whether the foreground host stays up for a walk in [state]: only a running walk has anything to host, a
 * health park included, as the scheduler reports it running. A paused walk does not continue on its own, so
 * it never shows as waiting to continue; it ends the host and its notification like a finished, idle or
 * model-less one. Pure so it is unit-tested without Android.
 */
internal fun faceServiceHostsWalk(state: FaceIndexingState): Boolean = state == FaceIndexingState.Running

/**
 * Whether the host saw the work it hosts move since its last look, which keeps it from stopping itself: the
 * scanned count climbed past [lastIndexed], or the heartbeat a grouping beats while that count stands still
 * (a batch placed, a closing step under way) climbed past [lastHeartbeat]. Pure so it is unit-tested without
 * Android.
 */
internal fun faceHostSawProgress(progress: FaceIndexingProgress, lastIndexed: Int, lastHeartbeat: Long): Boolean =
    progress.indexed > lastIndexed || progress.heartbeat > lastHeartbeat
