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

package eu.akoos.photos.data.label

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.mlsync.DriveDataFolder
import eu.akoos.photos.data.mlsync.MlDriveResult
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.sdk.SdkGate
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.worker.LabelSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Labels settings page's way into the Drive copy of the labels, kept under My files / Photos for Proton /
 * Labels: one sealed file per install, replaced on every save. [listCopies] opens each install's saved copy so the
 * user can see when it was saved and what it holds, and a restore brings one chosen copy back
 * ([LabelSnapshotMerge.restoredList]). A save or restore runs as the one [LabelSyncWorker] job, in the
 * background with an ongoing notification and a Cancel, and [state] follows it.
 *
 * Account-only, as the file lives in the account's Drive and is sealed to its key: [isAvailable] is false for a
 * guest. The labels themselves work without it. Nothing here creates the Drive folder unasked: a save looks it
 * up before any job exists and answers [LabelBackupStart.FOLDER_MISSING] when there is none, and asks the worker
 * to create it only once the caller says the user agreed. While a job is queued or running the labels cannot be
 * edited ([LabelWriteGate]).
 */
@Singleton
class LabelBackupController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val sdkGate: SdkGate,
    private val driveStore: MlDriveStore,
    private val runner: LabelBackupRunner,
    @AppScope private val appScope: CoroutineScope,
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** Jobs this process started or saw queued or running; only their results are reported. */
    private val watchedRunIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val dismissedRunIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The one job's state, from its [WorkInfo]: [LabelBackupState.Running] with its step while it is queued or
     * running, then [LabelBackupState.Finished] with its outcome until [dismissResult]. A result kept from before
     * this process started reads as [LabelBackupState.Idle].
     */
    val state: StateFlow<LabelBackupState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(LabelSyncWorker.UNIQUE_WORK_NAME),
        dismissedRunIds,
    ) { infos, dismissed ->
        val works = infos.map { it.toSnapshot() }
        works.filterNot { it.state.isFinished }.forEach { watchedRunIds += it.runId }
        labelSyncState(works, watchedRunIds, dismissed)
    }.stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LabelBackupState.Idle)

    /** Whether the Drive copy can be used at all: a signed-in account on a device the Drive client runs on. */
    fun isAvailable(userId: UserId?): Boolean = userId != null && sdkGate.isCapable

    /**
     * Starts a save. [createFolderConfirmed] says the user agreed to create the Labels folder; without it a
     * missing folder only asks ([LabelBackupStart.FOLDER_MISSING]) and no job starts.
     */
    suspend fun save(userId: UserId, createFolderConfirmed: Boolean): LabelBackupStart {
        blocked(userId)?.let { return it }
        if (!createFolderConfirmed) {
            when (val folder = driveStore.folder(userId, createIfMissing = false, area = DriveDataFolder.LABELS)) {
                is MlDriveResult.Ok -> if (folder.value == null) return LabelBackupStart.FOLDER_MISSING
                MlDriveResult.FolderMissing -> return LabelBackupStart.FOLDER_MISSING
                MlDriveResult.NotCapable -> return LabelBackupStart.NOT_AVAILABLE
                MlDriveResult.Conflict, is MlDriveResult.Failed -> Unit
            }
        }
        return start(LabelSyncRequest.Save(userId.id, createFolderConfirmed))
    }

    /** The saved copies the restore drawer lists, newest first; nothing is written. */
    suspend fun listCopies(userId: UserId): LabelBackupRunner.Copies {
        if (!isAvailable(userId) || accountManager.getPrimaryUserId().first() != userId) {
            return LabelBackupRunner.Copies.Failed
        }
        return runner.listCopies(userId)
    }

    /** Starts the restore of [copy], one of [listCopies]. */
    suspend fun restore(userId: UserId, copy: LabelSavedCopy): LabelBackupStart {
        blocked(userId)?.let { return it }
        return start(LabelSyncRequest.Restore(userId.id, copy.deviceId, copy.revisionUid))
    }

    /** Stops the job. A cancelled restore keeps what it has already brought back; a save leaves the copy in Drive as it was. */
    fun cancel() {
        workManager.cancelUniqueWork(LabelSyncWorker.UNIQUE_WORK_NAME)
    }

    /** Hides a finished job's outcome, so [state] reads idle until the next job starts. */
    fun dismissResult() {
        val finished = state.value as? LabelBackupState.Finished ?: return
        dismissedRunIds.update { it + finished.runId }
    }

    private fun start(request: LabelSyncRequest): LabelBackupStart {
        watchedRunIds += LabelSyncWorker.enqueue(context, request).toString()
        return LabelBackupStart.STARTED
    }

    /** Why no job can start for [userId] right now, or null when one may. */
    private suspend fun blocked(userId: UserId): LabelBackupStart? {
        if (!isAvailable(userId) || accountManager.getPrimaryUserId().first() != userId) {
            return LabelBackupStart.NOT_AVAILABLE
        }
        val busy = workManager.getWorkInfosForUniqueWorkFlow(LabelSyncWorker.UNIQUE_WORK_NAME).first()
            .any { !it.state.isFinished }
        return if (busy) LabelBackupStart.ALREADY_RUNNING else null
    }

    private fun WorkInfo.toSnapshot() = LabelSyncWorkSnapshot(
        runId = id.toString(),
        state = state,
        operation = LabelSyncWorkData.operationFromTags(tags),
        progress = progress,
        output = outputData,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
