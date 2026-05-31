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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.R
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase

/**
 * Downloads an album's photos in the background, surviving view-exit and app close.
 *
 * Lifecycle: enqueued from `AlbumDetailViewModel.downloadAllPhotos/downloadSelectedPhotos`,
 * runs as a foreground service so Android doesn't kill it when the user navigates away
 * from the album screen. A LOW-importance notification shows per-file progress and a
 * Cancel action that calls `WorkManager.cancelWorkById`.
 *
 * Notification channel is `"album_download"`, registered eagerly here in `getForegroundInfo`
 * (idempotent — `createNotificationChannel` is a no-op for an already-existing channel id).
 *
 * Inputs (set via `workDataOf` in the enqueuer):
 *  - `albumName: String` — local folder name (`Pictures/<albumName>/`) and notification title
 *  - `photoLinkIds: Array<String>` — cloud photo IDs to download (loaded by the worker)
 *  - `userIdString: String` — for `UserId(userIdString)`
 */
@HiltWorker
class AlbumDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val driveRepo: DrivePhotoRepository,
    private val downloadPhotos: DownloadPhotosUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val albumName = inputData.getString(KEY_ALBUM_NAME).orEmpty()
        val linkIds = inputData.getStringArray(KEY_PHOTO_LINK_IDS)?.toList().orEmpty()
        val userIdString = inputData.getString(KEY_USER_ID_STRING).orEmpty()
        if (linkIds.isEmpty() || userIdString.isEmpty()) {
            Log.w(TAG, "Missing input: linkIds=${linkIds.size} userId=${userIdString.isNotEmpty()}")
            return Result.failure()
        }
        val userId = UserId(userIdString)

        // Initial foreground info so the notification appears within the 5s window WorkManager
        // gives us before declaring an ANR-style timeout on the promote-to-foreground call.
        // Total starts at linkIds.size as a sane upper bound; the actual photo list is loaded
        // from the DB next (some linkIds may not be present yet — we still keep the same total
        // so the bar reaches 100% on completion).
        runCatching {
            setForeground(buildForegroundInfo(albumName, done = 0, total = linkIds.size))
        }.onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }

        return try {
            // Load CloudPhoto entities for the requested linkIds. `observePhotosByLinkIds`
            // returns the current DB snapshot as its first emission — we just take that and
            // move on (no need to keep collecting, the photos won't mutate during this run).
            val photos = driveRepo.observePhotosByLinkIds(linkIds).first()
            if (photos.isEmpty()) {
                Log.w(TAG, "No photos found in DB for linkIds=${linkIds.size}")
                return Result.failure()
            }

            val result = downloadPhotos.downloadCloudPhotos(userId, photos, albumName) { progress ->
                // Best-effort progress refresh. setForeground throws if the worker was
                // already cancelled (Android 14 + WorkManager 2.9); swallow that and let
                // the CancellationException from the cooperative cancel bubble out instead.
                runCatching {
                    setForeground(buildForegroundInfo(albumName, progress.done, progress.total))
                }
            }

            Log.d(TAG, "Album '$albumName' download done: $result")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation (the user tapped "Cancel" on the notification).
            // WorkManager treats this as cancellation, not failure — the unique-work entry
            // is removed and the system notification is auto-dismissed.
            Log.d(TAG, "Album '$albumName' download cancelled by user")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Album '$albumName' download failed", e)
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val albumName = inputData.getString(KEY_ALBUM_NAME).orEmpty()
        val total = inputData.getStringArray(KEY_PHOTO_LINK_IDS)?.size ?: 0
        return buildForegroundInfo(albumName, done = 0, total = total)
    }

    /**
     * Builds the [ForegroundInfo] with the album-download notification at the current progress.
     * Idempotently registers the notification channel on Android 8+.
     */
    private fun buildForegroundInfo(albumName: String, done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val title = context.getString(R.string.album_download_notification_title, albumName)
        val content = context.getString(R.string.album_download_notification_progress, done, total)
        val cancelLabel = context.getString(R.string.album_download_notification_cancel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            // Indeterminate when total is unknown; determinate progress bar otherwise.
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Cancel action — calling WorkManager.createCancelPendingIntent(id) yields an
            // intent that cooperatively cancels THIS worker by its UUID, so doWork's
            // CancellationException catch above is what handles teardown.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                cancelLabel,
                cancelIntent,
            )
            .build()

        // Android 10+ requires the foreground-service type bitmask matching the manifest's
        // <service> entry. Without this, the worker silently fails to start on Q+ when
        // calling setForeground (or starts but is immediately killed on Android 14).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "album_download_worker"
        const val CHANNEL_ID = "album_download"
        const val NOTIFICATION_ID = 4242

        const val KEY_ALBUM_NAME = "albumName"
        const val KEY_PHOTO_LINK_IDS = "photoLinkIds"
        const val KEY_USER_ID_STRING = "userIdString"

        /**
         * Lazily creates the album-download notification channel. Idempotent — calling on
         * an already-registered channel is a no-op. Pre-Oreo devices have no channel concept
         * so this returns immediately.
         */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // getNotificationChannel returns null if absent — only create on first call.
            // Re-creating with the same id every time would silently reset user-changed
            // settings (e.g. importance), but createNotificationChannel honours existing
            // user prefs anyway. Still, gate on null to keep things explicit.
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.album_download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.album_download_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        /**
         * Enqueues a unique album-download work request. Per-album uniqueness ([uniqueName]
         * is keyed on `albumLinkId`) so spamming "Download all" doesn't queue duplicate
         * downloads; [ExistingWorkPolicy.REPLACE] swaps in the latest request which is
         * usually what the user wants (e.g. they selected a different set then re-tapped).
         */
        fun enqueue(
            context: Context,
            albumLinkId: String,
            albumName: String,
            photoLinkIds: List<String>,
            userIdString: String,
        ) {
            // Pre-create the channel even though the worker also calls ensureChannel — this
            // way it exists before the first notification fires, avoiding a brief gap on
            // first-ever album download.
            ensureChannel(context)

            val data = workDataOf(
                KEY_ALBUM_NAME to albumName,
                KEY_PHOTO_LINK_IDS to photoLinkIds.toTypedArray(),
                KEY_USER_ID_STRING to userIdString,
            )
            // Validate input size against WorkManager's MAX_DATA_BYTES (10 KB by default).
            // ~250 byte linkIds × ~40 IDs hits the limit. For a 5000-photo album the array
            // would be ~125 KB, which would throw at enqueue time. Detect that here and log
            // — the caller's already shown the "enqueued" UI, so silently truncating would
            // confuse the user.
            if (data.toByteArray().size > Data.MAX_DATA_BYTES) {
                Log.w(TAG, "Input data exceeds MAX_DATA_BYTES (${data.toByteArray().size}/${Data.MAX_DATA_BYTES}); download may fail")
            }

            val request = OneTimeWorkRequestBuilder<AlbumDownloadWorker>()
                .setInputData(data)
                .addTag(TAG)
                .addTag(uniqueName(albumLinkId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(albumLinkId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        private fun uniqueName(albumLinkId: String) = "album_download_$albumLinkId"
    }
}
