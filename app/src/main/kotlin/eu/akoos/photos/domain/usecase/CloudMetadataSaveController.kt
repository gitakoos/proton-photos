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

package eu.akoos.photos.domain.usecase

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.db.dao.PendingMetadataEditDao
import eu.akoos.photos.data.db.entity.PendingMetadataEditEntity
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.worker.MetadataEditWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import javax.inject.Inject
import javax.inject.Singleton

/** One resolved metadata replacement: [photo] rewritten with [newCaptureMs] (null leaves the date as it
 *  is), [location] (leave / clear / set), and the descriptive text tags [description] / [artist] /
 *  [copyright] (null leaves that tag unchanged, an empty string clears it). Resolved when the editor
 *  action fires, run on confirm. [deviceUri] is set for a SYNCED photo (a device file exists): the
 *  replacement uploads that already edited device file and re-pairs its sync row, rather than downloading
 *  the cloud original. Null means a cloud-only photo. */
data class CloudWorkItem(
    val photo: CloudPhoto,
    val newCaptureMs: Long?,
    val location: LocationEdit,
    val deviceUri: String? = null,
    val description: String? = null,
    val artist: String? = null,
    val copyright: String? = null,
)

/**
 * App-scoped owner of the cloud metadata save batch. The editor snapshots its staged edits and hands
 * them here; this persists them to the [PendingMetadataEditDao] queue and kicks [MetadataEditWorker],
 * so the corrected copies keep uploading after the editor screen closes AND survive a process kill.
 *
 * The batch runs in the worker as a foreground service, tracked by the Activity transfer UI like a
 * normal upload. [ui] is the extra live view a progress drawer reads, derived from the worker's
 * [WorkInfo]; dismissing it hides the drawer without stopping the work.
 */
@Singleton
class CloudMetadataSaveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PendingMetadataEditDao,
    private val accountManager: AccountManager,
    @eu.akoos.photos.di.AppScope private val appScope: CoroutineScope,
) {

    /** What a progress surface shows for the current batch. Null (see [ui]) means nothing to show,
     *  either never started or dismissed. */
    sealed interface SaveUi {
        data class Running(val done: Int, val total: Int, val failed: Int, val phase: CloudSavePhase) : SaveUi
        data class Done(val updated: Int, val failed: Int) : SaveUi
    }

    // Flipped by dismiss()/cancel() so a batch the user sent to the background never reappears when it
    // later reaches its Done state; reset by the next start(). Held in a flow so flipping it re-derives
    // [ui] to null at once.
    private val dismissedFlow = MutableStateFlow(false)

    // Run-ids watched while still active in THIS process. WorkManager keeps a finished unique work's
    // WorkInfo indefinitely, so a completed batch's terminal state would otherwise re-derive a Done
    // drawer on every cold start (a stale result resurrecting). A terminal state becomes a Done only
    // when its run was seen active here, i.e. it just finished, not one retained from a past process.
    private val seenActiveRunIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Device photos in the same selection were written in place the moment they were picked, so they
    // are already done. This is folded into every done/total the drawer shows, so a mixed edit reads as
    // one "Updated N" rather than only the Drive uploads. Reset by each start().
    @Volatile private var lastLocalUpdated: Int = 0

    private val workInfoFlow = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(MetadataEditWorker.UNIQUE_NAME)

    /** The drawer's live view, derived from the worker's [WorkInfo] and the queue's outstanding count. */
    val ui: StateFlow<SaveUi?> =
        combine(workInfoFlow, dao.observeCount(), dismissedFlow) { infos, pending, dismissed ->
            deriveUi(infos, pending, lastLocalUpdated, dismissed)
        }.stateIn(appScope, SharingStarted.WhileSubscribed(5000), null)

    /** Maps the worker's current [WorkInfo] to what the drawer shows. Picks the freshest entry, since
     *  APPEND_OR_REPLACE leaves a finished run as a terminal entry alongside a new one. [localUpdated]
     *  is folded into every count exactly as the in-place device edits already landed. */
    private fun deriveUi(
        infos: List<WorkInfo>,
        pendingCount: Int,
        localUpdated: Int,
        dismissed: Boolean,
    ): SaveUi? {
        if (dismissed) return null
        // Prefer an active run over a finished one: APPEND_OR_REPLACE can leave a terminal entry beside a
        // fresh running one, and the drawer should follow the live work, not a stale result.
        val info = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
        } ?: infos.lastOrNull() ?: return null
        val idKey = info.id.toString()
        // Remember a run watched while it was still active in this process, so its later terminal state
        // reads as a just-finished result. A terminal WorkInfo never seen active here was retained by
        // WorkManager from a previous process; surfacing it would re-pop the Done drawer on every cold
        // start (the "stuck Done" the durable queue would otherwise cause).
        if (!info.state.isFinished) seenActiveRunIds.add(idKey)
        return when (info.state) {
            WorkInfo.State.SUCCEEDED ->
                if (idKey !in seenActiveRunIds) null
                else SaveUi.Done(
                    updated = localUpdated + info.outputData.getInt(MetadataEditWorker.KEY_RESULT_UPDATED, 0),
                    failed = info.outputData.getInt(MetadataEditWorker.KEY_RESULT_FAILED, 0),
                )
            // A failed run carries no counts, so the outstanding queue is reported as failed, best-effort.
            WorkInfo.State.FAILED ->
                if (idKey !in seenActiveRunIds) null
                else SaveUi.Done(updated = localUpdated, failed = pendingCount)
            WorkInfo.State.CANCELLED -> null
            WorkInfo.State.RUNNING -> {
                val progress = info.progress
                val done = progress.getInt(MetadataEditWorker.KEY_DONE, 0)
                // The worker publishes its total once it starts; before then, derive it from what is
                // done plus what is still queued so the bar has an honest denominator.
                val total = progress.getInt(MetadataEditWorker.KEY_TOTAL, 0)
                    .let { if (it > 0) it else done + pendingCount }
                val phase = runCatching {
                    CloudSavePhase.valueOf(progress.getString(MetadataEditWorker.KEY_PHASE) ?: "")
                }.getOrDefault(CloudSavePhase.FINISHING)
                SaveUi.Running(
                    done = localUpdated + done,
                    total = localUpdated + total,
                    failed = 0,
                    phase = phase,
                )
            }
            // Queued but not yet running: show the device edits already done against the queued total.
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> SaveUi.Running(
                done = localUpdated,
                total = localUpdated + pendingCount,
                failed = 0,
                phase = CloudSavePhase.PREPARING,
            )
        }
    }

    /** Persists [work] to the durable queue and starts the drain worker, so the corrected copies keep
     *  uploading after the editor closes and survive a process kill. Persisting BEFORE the enqueue
     *  guarantees the worker finds its rows. [localUpdated] counts device edits already written in place,
     *  folded into the drawer's totals through [ui]. */
    fun start(work: List<CloudWorkItem>, localUpdated: Int = 0) {
        if (work.isEmpty()) return
        dismissedFlow.value = false
        lastLocalUpdated = localUpdated
        appScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            dao.upsertAll(
                work.map {
                    PendingMetadataEditEntity.fromWorkItem(it, userId.id, enqueuedAt = System.currentTimeMillis())
                },
            )
            MetadataEditWorker.enqueue(context)
        }
    }

    /** Hides the progress surface. The worker keeps draining the queue and the Activity transfer UI
     *  still tracks it; this clears only the drawer's own view. */
    fun dismiss() {
        dismissedFlow.value = true
    }

    /** Stops the drain and hides the progress surface. The queue is cleared, since a user cancel abandons
     *  the pending edits and a later enqueue must not resume them; any in-flight synced row self-heals
     *  through the existing sync_state recovery. */
    fun cancel() {
        dismissedFlow.value = true
        WorkManager.getInstance(context).cancelUniqueWork(MetadataEditWorker.UNIQUE_NAME)
        appScope.launch { dao.clear() }
    }
}
