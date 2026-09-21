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
import dagger.hilt.android.AndroidEntryPoint
import eu.akoos.photos.R
import eu.akoos.photos.data.gif.GifExporter
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.presentation.gifmaker.GIF_EDGE_STANDARD
import eu.akoos.photos.presentation.gifmaker.GifAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground host for the GIF maker's encode-and-save. The screen pops the moment Save is tapped, so the
 * work cannot live in the view model; this service runs [GifExporter.export] on its own scope and keeps the
 * process alive so the encode (and any cloud upload) survives the app being backgrounded or swiped from
 * Recents. It shows a LOW ongoing progress notification while it runs, torn down on completion, and reports
 * the transfer to the [TransferCenter] (avatar ring + Activity screen) exactly like the editors' save.
 *
 * All parameters arrive as Intent extras through [start]. [START_NOT_STICKY] because a killed export must
 * not silently restart with stale extras; a later Save re-issues the intent instead.
 */
@AndroidEntryPoint
class GifExportService : Service() {

    @Inject lateinit var exporter: GifExporter
    @Inject lateinit var transferCenter: TransferCenter

    /** Service-scoped scope for the encode + save; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourceUri = intent?.getStringExtra(EXTRA_SOURCE_URI)
        if (sourceUri == null) {
            stopGifService()
            return START_NOT_STICKY
        }
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val aspect = runCatching {
            GifAspect.valueOf(intent.getStringExtra(EXTRA_ASPECT) ?: GifAspect.ORIGINAL.name)
        }.getOrDefault(GifAspect.ORIGINAL)
        val zoom = intent.getFloatExtra(EXTRA_ZOOM, 1f)
        val panX = intent.getFloatExtra(EXTRA_PAN_X, 0f)
        val panY = intent.getFloatExtra(EXTRA_PAN_Y, 0f)
        val maxEdgePx = intent.getIntExtra(EXTRA_MAX_EDGE_PX, GIF_EDGE_STANDARD)
        val isCloud = intent.getBooleanExtra(EXTRA_IS_CLOUD, false)
        val dateTakenMs = intent.getLongExtra(EXTRA_DATE_TAKEN_MS, System.currentTimeMillis())
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "video"

        // Promote immediately: Android kills the service within seconds otherwise, and on Android 12+ a
        // background start cannot foreground at all, so fall back to stopping rather than crash-looping.
        val foregrounded = try {
            startForegroundWithProgress()
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground denied (background start): ${e.message}")
            false
        }
        if (!foregrounded) {
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            // Track the save on the Activity monitor + avatar ring for its duration; the source uri is the
            // row's video-frame thumbnail. finish() runs in the finally so the ring clears on any outcome.
            val tid = transferCenter.start(
                TransferCenter.Kind.UPLOAD,
                total = 1,
                name = getString(R.string.gif_maker_progress),
                items = listOf(sourceUri),
            )
            try {
                val savedUri = exporter.export(
                    sourceUri, startMs, endMs, aspect, zoom, panX, panY, maxEdgePx, isCloud, dateTakenMs, displayName,
                )
                transferCenter.progress(tid, 1)
                transferCenter.log(
                    TransferCenter.Kind.UPLOAD, count = 1,
                    name = getString(R.string.gif_maker_saved), uris = listOf(savedUri),
                )
            } catch (t: Throwable) {
                // A cancellation here is scope teardown (the service is stopping), not a save failure, so
                // re-throw it rather than posting a spurious failure. Any other failure surfaces a
                // dismissible notification so the save never vanishes silently.
                if (t is CancellationException) throw t
                postTerminalFailure()
            } finally {
                transferCenter.finish(tid)
                stopGifService()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 caps how long a `dataSync` foreground service runs within a rolling day and calls this once
     * the budget is spent, giving the service seconds to stop before the platform kills it. Stop cleanly; the
     * scope's cancellation aborts the encode and a later Save re-issues the export.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground budget exhausted; stopping")
        stopGifService()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun startForegroundWithProgress() {
        val notification = buildProgressNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** The ongoing, indeterminate "creating GIF" post shown for the encode's duration. */
    private fun buildProgressNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.gif_maker_progress))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /**
     * Replaces the ongoing progress post with a one-shot, dismissible failure notice under
     * [DONE_NOTIFICATION_ID], its own id so [stopGifService]'s stopForeground(REMOVE) tears down only the
     * ongoing progress post and leaves this one standing, exactly like IMPORT vs IMPORT_DONE.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun postTerminalFailure() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.editor_save_failed))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Wrapped: a denied POST_NOTIFICATIONS grant must not crash the teardown, the notice being
        // informational and the service best-effort.
        runCatching { NotificationManagerCompat.from(this).notify(DONE_NOTIFICATION_ID, notification) }
    }

    private fun stopGifService() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        const val TAG = "gif_export_service"
        const val CHANNEL_ID = "gif_export"
        const val NOTIFICATION_ID = NotificationIds.GIF_EXPORT
        const val DONE_NOTIFICATION_ID = NotificationIds.GIF_EXPORT_DONE

        private const val EXTRA_SOURCE_URI = "source_uri"
        private const val EXTRA_START_MS = "start_ms"
        private const val EXTRA_END_MS = "end_ms"
        private const val EXTRA_ASPECT = "aspect"
        private const val EXTRA_ZOOM = "zoom"
        private const val EXTRA_PAN_X = "pan_x"
        private const val EXTRA_PAN_Y = "pan_y"
        private const val EXTRA_MAX_EDGE_PX = "max_edge_px"
        private const val EXTRA_IS_CLOUD = "is_cloud"
        private const val EXTRA_DATE_TAKEN_MS = "date_taken_ms"
        private const val EXTRA_DISPLAY_NAME = "display_name"

        /** Starts the export as a foreground service. The aspect travels as its [Enum.name] and is parsed
         *  back with [GifAspect.valueOf]. A background-start refusal on Android 12+ is swallowed since the
         *  caller (a UI Save tap) cannot know whether it still counts as foreground. */
        fun start(
            context: Context,
            sourceUri: String,
            startMs: Long,
            endMs: Long,
            aspect: GifAspect,
            zoom: Float,
            panX: Float,
            panY: Float,
            maxEdgePx: Int,
            isCloud: Boolean,
            dateTakenMs: Long,
            displayName: String,
        ) {
            val intent = Intent(context, GifExportService::class.java)
                .putExtra(EXTRA_SOURCE_URI, sourceUri)
                .putExtra(EXTRA_START_MS, startMs)
                .putExtra(EXTRA_END_MS, endMs)
                .putExtra(EXTRA_ASPECT, aspect.name)
                .putExtra(EXTRA_ZOOM, zoom)
                .putExtra(EXTRA_PAN_X, panX)
                .putExtra(EXTRA_PAN_Y, panY)
                .putExtra(EXTRA_MAX_EDGE_PX, maxEdgePx)
                .putExtra(EXTRA_IS_CLOUD, isCloud)
                .putExtra(EXTRA_DATE_TAKEN_MS, dateTakenMs)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "start failed: ${it.message}")
            }
        }

        /** Lazily creates the GIF-export notification channel. Idempotent, LOW importance and silent so the
         *  ongoing progress post stays out of the way. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.gif_export_channel_name),
                description = context.getString(R.string.gif_export_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }
    }
}
