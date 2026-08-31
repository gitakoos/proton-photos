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
import androidx.work.BackoffPolicy
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.PendingMetadataEditDao
import eu.akoos.photos.data.db.entity.PendingMetadataEditEntity
import eu.akoos.photos.data.notification.NotificationIds
import eu.akoos.photos.data.notification.ensureNotificationChannel
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.CloudSavePhase
import eu.akoos.photos.domain.usecase.WriteCloudPhotoMetadataUseCase
import eu.akoos.photos.domain.usecase.WriteSyncedPhotoMetadataUseCase

/** What the drain should do with a row whose use case reported a failure. */
enum class MetadataDrainDecision { DELETE_PERMANENT, KEEP_AND_RETRY, GIVE_UP_AFTER_MAX }

/**
 * Keep-or-drop for a failed metadata-edit row, decided purely from the failure's transience and the
 * worker's attempt count so it can be pinned by a plain JVM test. A permanent failure is dropped at
 * once; a transient one (a network / IO / rate-limit blip) is held for another WorkManager attempt
 * until [maxAttempts] is reached, after which it is given up and surfaced like a permanent failure so a
 * stuck edit cannot loop forever. [maxAttempts] of 3 mirrors [FreeUpSpaceWorker].
 */
internal fun metadataDrainDecision(
    transient: Boolean,
    runAttemptCount: Int,
    maxAttempts: Int = 3,
): MetadataDrainDecision = when {
    !transient -> MetadataDrainDecision.DELETE_PERMANENT
    runAttemptCount < maxAttempts -> MetadataDrainDecision.KEEP_AND_RETRY
    else -> MetadataDrainDecision.GIVE_UP_AFTER_MAX
}

/** The terminal shapes a drained row can take, unified across the synced and cloud use cases so the
 *  keep-or-delete decision reads the same for both. */
private sealed interface RowResult {
    data object Updated : RowResult
    data object Nothing : RowResult
    data class Failed(val transient: Boolean) : RowResult
}

/**
 * Drains the persisted cloud/synced metadata-edit queue as a foreground service, so a batch of
 * corrected re-uploads survives the editor closing AND a process kill. The queue is the DB table
 * behind [PendingMetadataEditDao]; this worker carries no input data.
 *
 * Each row re-fetches its photo by linkId, then runs the SAME dispatch as the in-memory
 * `CloudMetadataSaveController`: the synced device-file replacement when a deviceUri is set, the
 * cloud download-and-rewrite otherwise. A successful or permanently-failed row is deleted once its use
 * case returns; a transient failure (a network / rate-limit blip) is kept and the run ends in retry so
 * WorkManager re-drains it with backoff, until an attempt budget is reached and it is dropped like a
 * permanent failure. A kill mid-item likewise leaves the row in place for the re-run. The drain
 * re-queries after each pass, so a second batch appended while this one runs is handled by the same run.
 *
 * Unique work ([UNIQUE_NAME]) under a single FIXED notification id, since only one drain runs at a time.
 * A LOW-importance notification shows per-item progress and a Cancel action that cooperatively cancels
 * this worker by its UUID.
 */
@HiltWorker
class MetadataEditWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val dao: PendingMetadataEditDao,
    private val writeCloud: WriteCloudPhotoMetadataUseCase,
    private val writeSynced: WriteSyncedPhotoMetadataUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val transferCenter: TransferCenter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The queue is the source of truth. An empty queue is a valid no-op run: APPEND_OR_REPLACE can
        // enqueue a second run after the first already drained everything.
        val initialRows = dao.getAll()
        val initialTotal = initialRows.size
        if (initialTotal == 0) {
            return Result.success(workDataOf(KEY_RESULT_UPDATED to 0, KEY_RESULT_FAILED to 0))
        }

        // Promote to foreground within the 5s window WorkManager allows before it treats the
        // promote-to-foreground call as an ANR-style timeout.
        runCatching { setForeground(buildForegroundInfo(done = 0, total = initialTotal)) }
            .onFailure { Log.w(TAG, "setForeground initial failed: ${it.message}") }

        // Track the drain as an active transfer WITH a name and one thumbnail per photo, so the Activity
        // screen lists the batch's photos and its real count rather than a bare row. A synced row's
        // device uri loads directly; a cloud-only row falls back to its linkId-keyed cached thumbnail, or
        // a blank that still draws a placeholder row. No decrypt or photo fetch here, just a cache probe.
        val items = initialRows.map { row ->
            row.deviceUri ?: run {
                val thumb = java.io.File(context.cacheDir, "thumbnails/thumb_${row.linkId}.jpg")
                if (thumb.exists()) android.net.Uri.fromFile(thumb).toString() else ""
            }
        }
        // Each photo carries its OWN status keyed by its linkId, so the Activity rows read what THAT
        // photo is doing (Preparing / Uploading / Finishing), not one shared phase; all start Queued and
        // drop off as they finish.
        val itemKeys = initialRows.map { it.linkId }
        val queuedLabel = context.getString(R.string.upload_status_queued)
        val transferId = transferCenter.start(
            TransferCenter.Kind.UPLOAD,
            total = initialTotal,
            name = context.getString(R.string.metadata_edit_notification_title),
            items = items,
            itemKeys = itemKeys,
            itemStatus = initialRows.associate { it.linkId to queuedLabel },
        )

        val updated = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val done = AtomicInteger(0)
        val total = AtomicInteger(initialTotal)
        // Set by any row held for a WorkManager retry, so the drain can end in Result.retry().
        val kept = AtomicBoolean(false)

        // Best-effort progress publish reused by the phase callback (non-suspend) and the per-item
        // advance. setProgressAsync / setForegroundAsync are the non-suspend variants, so this is safe
        // to call from the use cases' non-suspend onPhase callback and from several rows at once; it
        // reads the atomic counters so every publish shows a consistent, non-decreasing snapshot.
        fun publish(phase: CloudSavePhase) {
            val d = done.get()
            val t = total.get()
            runCatching { setProgressAsync(workDataOf(KEY_DONE to d, KEY_TOTAL to t, KEY_PHASE to phase.name)) }
            runCatching { setForegroundAsync(buildForegroundInfo(done = d, total = t)) }
        }

        // The phase in the same words the save drawer shows, for one photo's Activity row.
        fun phaseLabel(phase: CloudSavePhase): String = context.getString(
            when (phase) {
                CloudSavePhase.PREPARING -> R.string.metadata_editor_cloud_phase_preparing
                CloudSavePhase.UPLOADING -> R.string.metadata_editor_cloud_phase_uploading
                CloudSavePhase.FINISHING -> R.string.metadata_editor_cloud_phase_finishing
            },
        )

        // One photo's full, ATOMIC unit of work: fetch it, run the same dispatch as the sequential
        // drain (synced device-file replacement when a deviceUri is set, cloud download-and-rewrite
        // otherwise), then delete the row ONLY after the use case returns a terminal result. Each use
        // case still trashes the original only after its replacement is verified and re-added, so
        // running several of these at once never weakens the per-photo safety; it only overlaps their
        // network I/O. Each cloud item downloads to a per-photo working file, so the copies never clash.
        suspend fun processRow(row: PendingMetadataEditEntity) {
            val photo = driveRepo.observePhotosByLinkIds(listOf(row.linkId)).first().firstOrNull()
            if (photo == null) {
                // The listing row is gone (trashed or deleted elsewhere). Drop the pending edit so it
                // never leaves an undrainable row, and advance.
                dao.deleteByLinkId(row.linkId)
                transferCenter.setItemStatus(transferId, row.linkId, null)
                Log.w(TAG, "listing row gone for ${row.linkId}; dropped pending edit")
                done.incrementAndGet()
                runCatching { transferCenter.progress(transferId, done.get()) }
                publish(CloudSavePhase.FINISHING)
                return
            }

            val item = row.toWorkItem(photo)
            // Publish the batch progress AND this photo's own Activity-row status, so each row shows
            // what THAT photo is doing rather than one shared phase.
            val onPhase: (CloudSavePhase) -> Unit = { ph ->
                publish(ph)
                transferCenter.setItemStatus(transferId, row.linkId, phaseLabel(ph))
            }

            // The same dispatch as CloudMetadataSaveController.runItem: a synced item (a device file
            // exists) uploads the edited device file and re-pairs; a cloud-only item downloads and
            // rewrites. Both Results collapse to one shape so the keep-or-delete decision reads the same,
            // and a Failed's transient bit is carried through to it. resumeUploadedLinkId carries a prior
            // run's already-uploaded link (null on the first run); onUploaded persists the new link onto
            // this row the moment the upload returns, so a kill before the re-add and trash resumes from
            // that link instead of re-uploading a second copy.
            val result: RowResult = if (item.deviceUri != null) {
                when (
                    val r = writeSynced(
                        item.photo, item.deviceUri, item.newCaptureMs, item.location,
                        item.description, item.artist, item.copyright, onPhase,
                        resumeUploadedLinkId = row.newLinkId,
                        onUploaded = { link -> dao.setNewLinkId(row.linkId, link) },
                    )
                ) {
                    WriteSyncedPhotoMetadataUseCase.Result.Success -> RowResult.Updated
                    WriteSyncedPhotoMetadataUseCase.Result.NothingToDo -> RowResult.Nothing
                    is WriteSyncedPhotoMetadataUseCase.Result.Failed -> RowResult.Failed(r.transient)
                }
            } else {
                when (
                    val r = writeCloud(
                        item.photo, item.newCaptureMs, item.location,
                        item.description, item.artist, item.copyright, onPhase,
                        resumeUploadedLinkId = row.newLinkId,
                        onUploaded = { link -> dao.setNewLinkId(row.linkId, link) },
                    )
                ) {
                    WriteCloudPhotoMetadataUseCase.Result.Success -> RowResult.Updated
                    WriteCloudPhotoMetadataUseCase.Result.NothingToDo -> RowResult.Nothing
                    is WriteCloudPhotoMetadataUseCase.Result.Failed -> RowResult.Failed(r.transient)
                }
            }

            // Keep-or-delete. Success and nothing-to-do delete the row as before. A failure consults the
            // pure decision: a permanent failure, or a transient one past the attempt budget, deletes AND
            // counts as failed (surfaced to the user); a transient failure still inside the budget KEEPS
            // the row and flags the run for a WorkManager retry, where the newLinkId resume token makes
            // the re-attempt idempotent. A process kill mid-item likewise leaves the row undeleted, so
            // the re-run re-drains it; pre-deleting would drop the edit on a kill.
            val deleteRow = when (result) {
                RowResult.Updated -> {
                    updated.incrementAndGet()
                    true
                }
                RowResult.Nothing -> true
                is RowResult.Failed -> when (metadataDrainDecision(result.transient, runAttemptCount)) {
                    MetadataDrainDecision.DELETE_PERMANENT,
                    MetadataDrainDecision.GIVE_UP_AFTER_MAX -> {
                        failed.incrementAndGet()
                        true
                    }
                    MetadataDrainDecision.KEEP_AND_RETRY -> {
                        kept.set(true)
                        false
                    }
                }
            }
            if (deleteRow) dao.deleteByLinkId(row.linkId)
            transferCenter.setItemStatus(transferId, row.linkId, null)
            done.incrementAndGet()
            runCatching { transferCenter.progress(transferId, done.get()) }
            publish(CloudSavePhase.FINISHING)
        }

        return try {
            val gate = Semaphore(CONCURRENCY)
            // Every linkId already attempted this run. A KEPT row (a transient failure held for a retry)
            // stays in the DB, so without this filter getAll() would re-serve it every pass and spin it
            // in a tight in-run loop; its retry belongs to a fresh WorkManager run, not this one.
            val attemptedThisRun = mutableSetOf<String>()
            // Re-query after each pass so rows a second batch appends mid-drain are drained by this run.
            // Within a pass, up to CONCURRENCY rows run at once so their downloads and uploads overlap,
            // while each row stays fully atomic. The pass awaits all its rows before re-querying, so an
            // appended batch is picked up on the next pass.
            while (true) {
                val rows = dao.getAll().filter { it.linkId !in attemptedThisRun }
                if (rows.isEmpty()) break
                // Grow the visible total when a later batch added rows, so the bar stays honest.
                total.set(maxOf(total.get(), done.get() + rows.size))

                coroutineScope {
                    rows.map { row -> async { gate.withPermit { processRow(row) } } }.awaitAll()
                }
                // Mark every processed row attempted regardless of outcome, so the next pass serves only
                // rows an appended batch newly added, never a kept row again.
                rows.forEach { attemptedThisRun.add(it.linkId) }
            }
            // A kept row means a transient failure is waiting for another attempt: ask WorkManager to
            // reschedule (with backoff). The persisted rows re-drain next run and the newLinkId resume
            // token keeps that idempotent. Otherwise the run drained cleanly; carry the counts for the UI.
            if (kept.get()) {
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_RESULT_UPDATED to updated.get(), KEY_RESULT_FAILED to failed.get()))
            }
        } catch (e: CancellationException) {
            // Cooperative cancellation (the Cancel action, or the controller's cancel path). WorkManager
            // treats it as cancelled. The remaining rows are left untouched here: the controller's cancel
            // path clears the queue, and a plain cancelled run would otherwise re-enqueue and re-drain.
            Log.d(TAG, "metadata edit drain cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "metadata edit drain failed", e)
            Result.failure()
        } finally {
            transferCenter.finish(transferId)
            // WorkManager auto-dismisses the foreground notification on exit, but some OEMs leave the
            // ongoing post behind; clear it explicitly since this fixed id is only ever this worker's.
            runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.METADATA_EDIT) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(done = 0, total = 0)

    /**
     * Builds the [ForegroundInfo] with the metadata-edit notification at the current progress.
     * Idempotently registers the notification channel on Android 8+.
     */
    private fun buildForegroundInfo(done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)

        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val title = context.getString(R.string.metadata_edit_notification_title)
        val content = context.getString(R.string.metadata_edit_notification_progress, done, total)
        val cancelLabel = context.getString(R.string.cancel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(content)
            // Indeterminate when the total is unknown; determinate bar otherwise.
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Cancel action: WorkManager.createCancelPendingIntent(id) cooperatively cancels THIS worker
            // by its UUID, so doWork's CancellationException catch handles teardown.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                cancelLabel,
                cancelIntent,
            )
            .build()

        // Android 10+ requires the foreground-service type bitmask matching the manifest's <service>
        // entry, or setForeground silently fails on Q+ (or starts then is killed on Android 14).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationIds.METADATA_EDIT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationIds.METADATA_EDIT, notification)
        }
    }

    companion object {
        const val TAG = "metadata_edit_worker"
        const val UNIQUE_NAME = "metadata_edit"
        const val CHANNEL_ID = "metadata_edit"

        // How many rows drain at once. Overlaps each photo's download and upload while keeping every
        // photo atomic; kept small so a handful of full-res transfers do not swamp the network.
        const val CONCURRENCY = 4

        // WorkManager progress data keys, read by an in-app progress surface.
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_PHASE = "phase"
        // Output data keys, carried on a successful result so the outcome can be reported in-app.
        const val KEY_RESULT_UPDATED = "resultUpdated"
        const val KEY_RESULT_FAILED = "resultFailed"

        /** Lazily creates the metadata-edit notification channel. Idempotent. */
        fun ensureChannel(context: Context) {
            ensureNotificationChannel(
                context,
                id = CHANNEL_ID,
                name = context.getString(R.string.metadata_edit_channel_name),
                description = context.getString(R.string.metadata_edit_channel_desc),
                importance = NotificationManager.IMPORTANCE_LOW,
                silent = false,
            )
        }

        /**
         * Enqueues the unique metadata-edit drain. No input data: the queue lives in the DB.
         * [ExistingWorkPolicy.APPEND_OR_REPLACE] so a second batch enqueued while one runs is still
         * drained (either appended to the running chain or replacing a finished one).
         */
        fun enqueue(context: Context) {
            ensureChannel(context)
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MetadataEditWorker>()
                    .addTag(TAG)
                    // A run that kept a transient-failed row returns Result.retry(); back the reschedule
                    // off so a persistent network blip is not hammered.
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
