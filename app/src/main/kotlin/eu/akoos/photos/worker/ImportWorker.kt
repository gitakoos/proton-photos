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
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.ImportStagedDao
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.domain.importer.ImportAlbumMode
import eu.akoos.photos.domain.usecase.ImportProgress
import eu.akoos.photos.domain.usecase.ImportSummary
import eu.akoos.photos.domain.usecase.ImportTakeoutUseCase
import java.io.IOException
import java.util.UUID

/**
 * Uploads the review-kept entries of a picked import `.zip` into the primary account's Drive Photos as
 * a foreground service, so the run survives the picker screen closing AND a process kill. The heavy
 * lifting lives in [ImportTakeoutUseCase]; this worker is the durable shell around it: the notification
 * and the progress publish.
 *
 * Resume: the use case marks each entry's staged row uploaded the moment its upload returns and sends
 * only the rows still pending, so a killed run that WorkManager restarts continues where it stopped
 * without resending anything already sent.
 *
 * Uniqueness ([uniqueName], keyed on the zip uri) with [ExistingWorkPolicy.KEEP] so double-enqueuing
 * the same zip does not run two imports over it at once.
 *
 * Input data: [KEY_ZIP_URI], the picked zip's `content://` uri string, which is also the run's [zipId]
 * (its stable identity across a kill). No account (guest) is a clean no-op: there is no Drive to import
 * into, so the worker returns success without touching anything.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val importTakeoutUseCase: ImportTakeoutUseCase,
    private val importStagedDao: ImportStagedDao,
    private val accountManager: AccountManager,
) : CoroutineWorker(context, params) {

    // Wall-clock of the last progress publish, throttling any mid-upload byte tick so it does not hammer
    // WorkManager's progress store and the notification manager. The uploads run in parallel, so publish
    // can be entered from several coroutines at once; the racing read/write here is benign because every
    // progress the upload loop emits is an entry boundary (phase null), which always publishes, and the
    // throttle that reads this gates only mid-upload ticks, which the loop does not emit.
    private var lastPublishMs = 0L

    override suspend fun doWork(): Result {
        val zipUri = inputData.getString(KEY_ZIP_URI)
        if (zipUri.isNullOrEmpty()) {
            Log.w(TAG, "no zip uri supplied")
            return Result.failure()
        }
        val uri = Uri.parse(zipUri)
        // The uri IS the persistable identity of this import, so it doubles as the resume ledger key.
        val zipId = zipUri

        // Guest / no primary account: there is no Drive to import into, so this is a clean no-op rather
        // than a crash. firstOrNull covers both an empty flow and a null primary user.
        val userId = accountManager.getPrimaryUserId().firstOrNull()
            ?: run {
                Log.d(TAG, "no primary account; nothing to import")
                return Result.success()
            }

        // The run id is chosen at confirm time and carried in input data, so a kill and restart of this
        // work resumes into the same upload ledger. The fallback keeps a stale enqueue that predates the
        // key from crashing; it opens a fresh ledger rather than failing.
        val runId = inputData.getString(KEY_RUN_ID) ?: UUID.randomUUID().toString()

        // The album-reconstruction choice made at confirm time, carried in input data. An unknown or
        // missing value falls back to NONE, so a stale enqueue that predates the key just skips albums.
        val albumMode = inputData.getString(KEY_ALBUM_MODE)
            ?.let { runCatching { ImportAlbumMode.valueOf(it) }.getOrNull() }
            ?: ImportAlbumMode.NONE

        // Promote to foreground within the start-of-work window WorkManager allows before it treats the
        // promote call as an ANR-style timeout. Total 0 shows an indeterminate bar until the zip scan
        // (pass 1) has counted the media entries.
        runCatching { setForeground(buildForegroundInfo(done = 0, total = 0)) }
            .onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }

        var terminalPosted = false
        return try {
            // The use case bridges the synchronous zip callbacks to suspend work via runBlocking, so it
            // MUST run on a background dispatcher, never the main-safe worker default alone.
            val summary = withContext(Dispatchers.IO) {
                importTakeoutUseCase.uploadStaged(
                    userId = userId,
                    zipId = zipId,
                    runId = runId,
                    fileName = resolveFileName(uri),
                    albumMode = albumMode,
                    openZip = {
                        applicationContext.contentResolver.openInputStream(uri)
                            ?: throw IOException("cannot open zip input stream for $uri")
                    },
                    onProgress = { progress -> publish(progress) },
                )
            }

            // The run finished: drop its resolved staged rows (uploaded or excluded) so a later open of
            // the screen does not re-adopt the archive from an excluded-only leftover and strand the user
            // on a stale Done or a review of only the removed entries. A still-failed row is left in place
            // for a resume.
            importStagedDao.clearResolved(zipId)

            Log.d(TAG, "import done: $summary")
            postDone(summary)
            terminalPosted = true
            Result.success(
                workDataOf(
                    KEY_RESULT_TOTAL to summary.total,
                    KEY_RESULT_IMPORTED to summary.imported,
                    KEY_RESULT_ALREADY to summary.alreadyInDrive,
                    KEY_RESULT_SKIPPED to summary.skipped,
                    KEY_RESULT_FAILED to summary.failed,
                    KEY_RESULT_ALBUMS to summary.albumsCreated,
                    KEY_RESULT_ADDED to summary.photosAddedToAlbums,
                    KEY_RESULT_RUN_ID to runId,
                ),
            )
        } catch (e: CancellationException) {
            // Cooperative cancellation (the notification's Cancel action or the in-app Stop). The rows the
            // run already marked uploaded let a later re-pick skip what was sent. Record a partial history
            // row off the cancellable path so the stopped run still shows in Recent imports with the photos
            // it managed to send, tappable and undoable, rather than disappearing.
            Log.d(TAG, "import cancelled")
            withContext(NonCancellable) {
                runCatching { importTakeoutUseCase.recordPartialHistory(runId, zipId, resolveFileName(uri)) }
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            Result.failure()
        } finally {
            // WorkManager auto-dismisses the foreground notification when the worker exits, but some
            // OEMs leave the ongoing post behind; clear it unless a terminal summary already replaced it.
            if (!terminalPosted) {
                runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.IMPORT) }
            }
        }
    }

    /**
     * The display name to record the run under: the provider's own name for [uri] where it exposes one
     * (the picker's `content://` document), else the uri's last path segment, else the raw uri string.
     * Best-effort and never fatal, so a provider that answers nothing still yields a usable label.
     */
    private fun resolveFileName(uri: Uri): String {
        val fromProvider = runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { row ->
                if (!row.moveToFirst()) return@use null
                val idx = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) row.getString(idx) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: uri.toString()
    }

    /**
     * Publishes one [ImportProgress] to WorkManager (for a ViewModel observing [androidx.work.WorkInfo])
     * and refreshes the notification. An entry boundary (phase null: an entry just finished and the done
     * count advanced) always publishes so every completed entry is seen; a mid-upload byte tick is
     * throttled to [PROGRESS_THROTTLE_MS]. Uses the non-suspend `*Async` variants so a call made from
     * inside the use case's runBlocking bridge does not block that thread.
     */
    private fun publish(progress: ImportProgress) {
        val now = System.currentTimeMillis()
        val entryBoundary = progress.phase == null
        if (!entryBoundary && now - lastPublishMs < PROGRESS_THROTTLE_MS) return
        lastPublishMs = now

        runCatching {
            setProgressAsync(
                workDataOf(
                    KEY_DONE to progress.done,
                    KEY_TOTAL to progress.total,
                    KEY_CURRENT_NAME to progress.currentName,
                    KEY_PHASE to (progress.phase?.name ?: ""),
                    KEY_PHASE_DONE_BYTES to progress.phaseDoneBytes,
                    KEY_PHASE_TOTAL_BYTES to progress.phaseTotalBytes,
                ),
            )
        }
        runCatching { setForegroundAsync(buildForegroundInfo(done = progress.done, total = progress.total)) }
    }

    /** Replaces the ongoing progress post with a one-shot summary under [NotificationIds.IMPORT_DONE],
     *  its own id so WorkManager's teardown of the foreground post does not take the summary with it. */
    private fun postDone(summary: ImportSummary) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.import_notification_done_title))
            .setContentText(
                context.getString(
                    R.string.import_notification_done_text,
                    summary.imported,
                    summary.skipped,
                    summary.failed,
                ),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.IMPORT) }
        runCatching { NotificationManagerCompat.from(context).notify(NotificationIds.IMPORT_DONE, notification) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(done = 0, total = 0)

    /**
     * Builds the [ForegroundInfo] with the import notification at the current progress. Idempotently
     * registers the notification channel on Android 8+.
     */
    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val title = context.getString(R.string.import_notification_title)
        val content = context.getString(R.string.import_notification_progress, done, total)
        val cancelLabel = context.getString(R.string.cancel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(content)
            // Indeterminate while the zip scan has not counted the entries yet; determinate afterwards.
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Cancel action: createCancelPendingIntent(id) cooperatively cancels THIS worker by its UUID,
            // so doWork's CancellationException catch handles teardown.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                cancelLabel,
                cancelIntent,
            )
            .build()

        // Android 10+ requires the foreground-service type bitmask matching the manifest's
        // SystemForegroundService entry (dataSync), or setForeground silently fails on Q+.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.IMPORT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.IMPORT, notification)
        }
    }

    companion object {
        const val TAG = "import_worker"
        const val CHANNEL_ID = "import"

        /** Input: the picked zip's content uri string, which is also the resume ledger's zipId. */
        const val KEY_ZIP_URI = "zipUri"

        /** Input: the run id minted at confirm time, grouping this run's uploaded-photo undo ledger. */
        const val KEY_RUN_ID = "runId"

        /** Input: the [ImportAlbumMode] name choosing how the run treats the export's albums. */
        const val KEY_ALBUM_MODE = "albumMode"

        // WorkManager progress data keys, read by an in-app import progress surface.
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_NAME = "currentName"
        const val KEY_PHASE = "phase"
        const val KEY_PHASE_DONE_BYTES = "phaseDoneBytes"
        const val KEY_PHASE_TOTAL_BYTES = "phaseTotalBytes"

        // Output data keys, carried on a successful result so the outcome can be reported in-app.
        const val KEY_RESULT_TOTAL = "resultTotal"
        const val KEY_RESULT_IMPORTED = "resultImported"
        const val KEY_RESULT_ALREADY = "resultAlready"
        const val KEY_RESULT_SKIPPED = "resultSkipped"
        const val KEY_RESULT_FAILED = "resultFailed"
        const val KEY_RESULT_ALBUMS = "resultAlbums"
        const val KEY_RESULT_ADDED = "resultAdded"
        const val KEY_RESULT_RUN_ID = "resultRunId"

        // How often the mid-upload byte ticks refresh progress; entry boundaries bypass this.
        private const val PROGRESS_THROTTLE_MS = 500L

        /** Lazily creates the import notification channel. Idempotent. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.import_channel_name),
                description = context.getString(R.string.import_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = true,
            )
        }

        /**
         * Enqueues the import of [zipUri] under [runId] in [albumMode] and returns the request id.
         * Unique-work keyed on the uri with [ExistingWorkPolicy.KEEP], so enqueuing the same zip while it
         * is already importing keeps the running work (and its original [runId] and mode) rather than
         * starting a second one. Observe progress via
         * `WorkManager.getWorkInfosForUniqueWork(uniqueName(zipUri))`, robust across a KEEP that returns an
         * id whose request is not the one actually running.
         */
        fun enqueue(
            context: Context,
            zipUri: String,
            runId: String,
            albumMode: ImportAlbumMode = ImportAlbumMode.NONE,
        ): UUID {
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ZIP_URI to zipUri,
                        KEY_RUN_ID to runId,
                        KEY_ALBUM_MODE to albumMode.name,
                    ),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(zipUri),
                ExistingWorkPolicy.KEEP,
                request,
            )
            return request.id
        }

        fun uniqueName(zipUri: String) = "import_$zipUri"
    }
}
