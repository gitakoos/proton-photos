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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.R
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
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
 *  - `albumName: String` — local folder name (`DCIM/<albumName>/`) and notification title
 *  - `photoLinkIdsFile: String` — path to a cache file with the cloud photo IDs, one per line.
 *    Spilled to a file because the list can exceed WorkManager's 10 KB input-data limit; the
 *    worker reads it on start and deletes it.
 *  - `photoCount: Int` — number of IDs, for the initial progress total
 *  - `userIdString: String` — for `UserId(userIdString)`
 */
@HiltWorker
class AlbumDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val driveRepo: DrivePhotoRepository,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
) : CoroutineWorker(context, params) {

    // Per-run notification id so two concurrent album downloads each keep their own entry in the
    // shade instead of overwriting each other. Derived from this worker's UUID and confined to the
    // range NotificationIds reserves for album downloads, which no fixed id may enter.
    private val notificationId: Int = NotificationIds.albumDownload(id.hashCode())

    override suspend fun doWork(): Result {
        val albumName = inputData.getString(KEY_ALBUM_NAME).orEmpty()
        val userIdString = inputData.getString(KEY_USER_ID_STRING).orEmpty()
        // The id list is spilled to a cache file (it can be far larger than WorkManager's 10 KB
        // input-data ceiling). Read it back, then drop the file — the list now lives in memory.
        val listFile = inputData.getString(KEY_PHOTO_LINK_IDS_FILE)?.let { File(it) }
        val linkIds = listFile?.takeIf { it.exists() }
            ?.readLines()?.filter { it.isNotBlank() }
            .orEmpty()
        if (linkIds.isEmpty() || userIdString.isEmpty()) {
            Log.w(TAG, "Missing input: linkIds=${linkIds.size} userId=${userIdString.isNotEmpty()}")
            listFile?.delete()
            return Result.failure()
        }
        val userId = UserId(userIdString)

        // Album-download notification opt-out, read once. When off the worker runs in the
        // background (no foreground promotion, no notification); the in-app album screen still
        // tracks progress via setProgress below.
        val showNotification = context.settingsDataStore.data.first()[SettingsKeys.NOTIFY_ALBUM_DOWNLOAD] != false

        // Initial foreground info so the notification appears within the 5s window WorkManager
        // gives us before declaring an ANR-style timeout on the promote-to-foreground call.
        // Total starts at linkIds.size as a sane upper bound; the actual photo list is loaded
        // from the DB next (some linkIds may not be present yet — we still keep the same total
        // so the bar reaches 100% on completion).
        if (showNotification) {
            runCatching {
                setForeground(buildForegroundInfo(albumName, done = 0, total = linkIds.size))
            }.onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }
        }

        var transferId: Long? = null
        return try {
            // Load CloudPhoto entities for the requested linkIds. `observePhotosByLinkIds`
            // returns the current DB snapshot as its first emission — we just take that and
            // move on (no need to keep collecting, the photos won't mutate during this run).
            val photos = driveRepo.observePhotosByLinkIds(linkIds).first()
            if (photos.isEmpty()) {
                Log.w(TAG, "No photos found in DB for linkIds=${linkIds.size}")
                return Result.failure()
            }
            // Register as an active transfer so the home-screen avatar reflects the album download.
            transferId = transferCenter.start(
                eu.akoos.photos.data.transfer.TransferCenter.Kind.DOWNLOAD, photos.size, albumName,
            )

            val savedUris = java.util.concurrent.ConcurrentLinkedQueue<String>()
            // First saved photo, kept as a FIXED cover so the album row's thumbnail does not
            // flicker from photo to photo as the download proceeds.
            var coverUri: String? = null
            val result = downloadPhotos.downloadCloudPhotos(
                userId, photos, albumName,
                onSaved = { savedUris.add(it); if (coverUri == null) coverUri = it },
            ) { progress ->
                transferId?.let { transferCenter.progress(it, progress.done) }
                // Publish progress to WorkManager so the in-app album screen can show a progress
                // ring + cancel without depending on the notification (works even when the
                // notification is opted out).
                runCatching {
                    setProgress(workDataOf(
                        KEY_PROGRESS_DONE to progress.done,
                        KEY_PROGRESS_TOTAL to progress.total,
                        KEY_ALBUM_NAME to albumName,
                        KEY_COVER_URI to (coverUri ?: ""),
                    ))
                }
                // Best-effort notification refresh. setForeground throws if the worker was
                // already cancelled (Android 14 + WorkManager 2.9); swallow that and let
                // the CancellationException from the cooperative cancel bubble out instead.
                if (showNotification) {
                    runCatching {
                        setForeground(buildForegroundInfo(albumName, progress.done, progress.total))
                    }
                }
            }

            Log.d(TAG, "Album '$albumName' download done: $result")
            transferCenter.log(
                eu.akoos.photos.data.transfer.TransferCenter.Kind.DOWNLOAD,
                result.total - result.failed,
                albumName,
                uris = savedUris.toList(),
            )
            // Counts travel out with the result so the album screen can report the outcome. The
            // in-app progress ring disappears on any terminal state, which on its own reads the
            // same whether every photo saved or none did.
            Result.success(
                workDataOf(
                    KEY_RESULT_SAVED to (result.total - result.failed),
                    KEY_RESULT_FAILED to result.failed,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation (the user tapped "Cancel" on the notification).
            // WorkManager treats this as cancellation, not failure — the unique-work entry
            // is removed and the system notification is auto-dismissed.
            Log.d(TAG, "Album '$albumName' download cancelled by user")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Album '$albumName' download failed", e)
            Result.failure()
        } finally {
            // Clear the active-transfer entry so the avatar stops showing this download.
            transferId?.let { transferCenter.finish(it) }
            // WorkManager auto-dismisses the foreground notification when the worker exits, but on
            // some OEMs the ongoing notification lingers. Nothing else can replace it either: the id
            // belongs to this run's UUID, so a later download posts elsewhere and a stale
            // "X of Y downloaded" would sit in the shade for good.
            runCatching {
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
            // Drop the spilled id-list file once the run ends. If the process is killed mid-run
            // the finally is skipped and the file survives, so WorkManager's re-run can resume.
            listFile?.delete()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val albumName = inputData.getString(KEY_ALBUM_NAME).orEmpty()
        val total = inputData.getInt(KEY_PHOTO_COUNT, 0)
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
            // Group concurrent album downloads so the shade clusters them; combined with the
            // per-run [notificationId] both downloads stay visible instead of overwriting.
            .setGroup(GROUP_KEY)
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
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val TAG = "album_download_worker"
        const val CHANNEL_ID = "album_download"
        // Shared group key so multiple concurrent album-download notifications cluster together.
        const val GROUP_KEY = "album_downloads"

        const val KEY_ALBUM_NAME = "albumName"
        const val KEY_PHOTO_LINK_IDS_FILE = "photoLinkIdsFile"
        const val KEY_PHOTO_COUNT = "photoCount"
        const val KEY_USER_ID_STRING = "userIdString"
        // WorkManager progress data keys — read by AlbumDetailViewModel to drive the in-app ring.
        const val KEY_PROGRESS_DONE = "progressDone"
        const val KEY_PROGRESS_TOTAL = "progressTotal"
        const val KEY_COVER_URI = "coverUri"
        // Output data keys, carried on a successful result so the outcome can be reported in-app.
        const val KEY_RESULT_SAVED = "resultSaved"
        const val KEY_RESULT_FAILED = "resultFailed"

        /** Lazily creates the album-download notification channel. Idempotent. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.album_download_channel_name),
                description = context.getString(R.string.album_download_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = false,
            )
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

            // WorkManager input Data is capped at 10 KB. A large album's id array blows past it
            // (each id is ~90 bytes, so ~110 photos is the ceiling) and Data.Builder.build() then
            // throws, crashing the caller. Spill the id list to a per-album cache file and hand the
            // worker just the path + a count, so album size never bounds the download. The worker
            // reads and deletes the file on start.
            val listFile = File(context.cacheDir, "album_dl_${albumLinkId.hashCode()}.txt")
            runCatching { listFile.writeText(photoLinkIds.joinToString("\n")) }
                .onFailure { Log.w(TAG, "Failed to spill album id list: ${it.message}") }

            val data = workDataOf(
                KEY_ALBUM_NAME to albumName,
                KEY_PHOTO_LINK_IDS_FILE to listFile.absolutePath,
                KEY_PHOTO_COUNT to photoLinkIds.size,
                KEY_USER_ID_STRING to userIdString,
            )

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

        fun uniqueName(albumLinkId: String) = "album_download_$albumLinkId"
    }
}
