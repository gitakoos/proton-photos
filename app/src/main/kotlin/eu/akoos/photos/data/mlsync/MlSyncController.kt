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

package eu.akoos.photos.data.mlsync

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.sdk.SdkGate
import eu.akoos.photos.di.AppScope
import eu.akoos.photos.worker.MlSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a Save or Restore tap did. */
enum class MlSyncStart {
    /** The job was handed to WorkManager; [MlSyncController.state] follows it. */
    STARTED,

    /** Another ML sync job is queued or running, so nothing new was started. */
    ALREADY_RUNNING,

    /** The user is not the signed-in account, or the Drive SDK cannot run on this device. */
    NOT_AVAILABLE,

    /** A restore named no file. */
    NO_FILES,

    /** A save found no ML data folder in Drive. Nothing was started; the user is asked before one is created. */
    FOLDER_MISSING,
}

/**
 * The settings screen's way into the Drive copy of the machine-learning data: what the ML data folder holds,
 * a save or a restore started as the one [MlSyncWorker] job, and that job's state.
 *
 * Account-only, as the data lives in the account's Drive and is sealed to its address key: [isAvailable] is
 * false for a guest, and every call refuses a user that is not the signed-in account. Nothing here creates
 * the Drive folder: a save looks it up before any job exists and answers [MlSyncStart.FOLDER_MISSING] when
 * there is none, and asks the worker to create it only once the caller says the user agreed.
 */
@Singleton
class MlSyncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val sdkGate: SdkGate,
    private val driveStore: MlDriveStore,
    private val faceSnapshotImporter: FaceSnapshotImporter,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    @AppScope private val appScope: CoroutineScope,
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** Jobs this process started or saw queued or running; only their results are reported. */
    private val watchedRunIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val dismissedRunIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The one job's state, from its [WorkInfo]: [MlSyncState.Running] with its progress while it is queued or
     * running, then [MlSyncState.Finished] with its outcome until [dismissResult]. A result kept from before
     * this process started reads as [MlSyncState.Idle].
     */
    val state: StateFlow<MlSyncState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(MlSyncWorker.UNIQUE_WORK_NAME),
        dismissedRunIds,
    ) { infos, dismissed ->
        val works = infos.map { it.toSnapshot() }
        works.filterNot { it.state.isFinished }.forEach { watchedRunIds += it.runId }
        mlSyncState(works, watchedRunIds, dismissed)
    }.stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MlSyncState.Idle)

    /** Whether the Drive copy can be used at all: a signed-in account on a device the Drive SDK runs on. */
    fun isAvailable(userId: UserId?): Boolean = userId != null && sdkGate.isCapable

    /**
     * The snapshots in the ML data folder, one per install and kind, newest first; this install's own are
     * included and flagged. An empty list when the folder does not exist yet. The listing never creates it.
     */
    suspend fun listRemote(userId: UserId): MlDriveResult<List<MlRemoteSnapshot>> {
        if (!isSignedIn(userId)) return MlDriveResult.Failed(NOT_SIGNED_IN)
        val files = when (val listed = driveStore.list(userId)) {
            is MlDriveResult.Ok -> listed.value
            is MlDriveResult.Failed -> return listed
            MlDriveResult.NotCapable -> return MlDriveResult.NotCapable
            MlDriveResult.FolderMissing -> return MlDriveResult.FolderMissing
            MlDriveResult.Conflict -> return MlDriveResult.Conflict
        }
        val ownDeviceId = try {
            driveStore.deviceId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MlDriveResult.Failed(e.javaClass.simpleName)
        }
        val picks = MlDriveNaming.selectForImport(files, ownDeviceId, includeOwnDevice = true)
        return MlDriveResult.Ok(remoteSnapshots(picks, ownDeviceId, driveStore.deviceLabel))
    }

    /**
     * Whether a restore has to ask Merge or Replace ([restoreNeedsChoice]): it does unless this device is
     * fresh, with no face of a cloud photo and no names waiting to be put back.
     */
    suspend fun restoreNeedsModeChoice(userId: UserId): Boolean = faceSnapshotImporter.needsModeChoice(userId)

    /**
     * Starts a save of this install's face and search snapshots. [createFolderConfirmed] says the user agreed
     * to create the ML data folder. Without it the folder is looked up first ([saveStep]), and when there is
     * none yet nothing starts: the answer is [MlSyncStart.FOLDER_MISSING], so no job runs and no notification
     * shows before the user is asked. A job that still finds no folder ends as [MlSyncResult.FOLDER_MISSING].
     */
    suspend fun save(userId: UserId, createFolderConfirmed: Boolean): MlSyncStart {
        if (!createFolderConfirmed) {
            blocked(userId)?.let { return it }
            when (saveStep(driveStore.folder(userId, createIfMissing = false))) {
                MlSaveStep.ASK_TO_CREATE_FOLDER -> return MlSyncStart.FOLDER_MISSING
                MlSaveStep.NOT_CAPABLE -> return MlSyncStart.NOT_AVAILABLE
                MlSaveStep.START -> Unit
            }
        }
        return start(userId) { MlSyncRequest.Save(userId.id, createFolderConfirmed) }
    }

    /**
     * Starts a restore of [files], picked from [listRemote], under [mode]: the user's choice when
     * [restoreNeedsModeChoice] asked for one, else [FaceSnapshotMode.Replace]. Whether the user had paused the
     * face walk is read now ([faceIndexingPausedByUser]): a pause an earlier run still holds is not theirs.
     */
    suspend fun restore(userId: UserId, files: List<MlRemoteSnapshot>, mode: FaceSnapshotMode): MlSyncStart {
        if (files.isEmpty()) return MlSyncStart.NO_FILES
        return start(userId) {
            val pause = MlSyncFacePause.read(context)
            MlSyncRequest.Restore(
                userId = userId.id,
                files = files.map { MlRestoreFile(it.kind, it.revisionUid, it.serverModifiedAtMs, it.deviceId) },
                mode = mode,
                faceIndexingPausedByUser = faceIndexingPausedByUser(pause?.switchOn, pause?.held),
            )
        }
    }

    /**
     * Stops the job. A face import in progress rolls back, a search import keeps the chunks it has committed
     * (restoring again completes it), and a restore hands the indexing walks back; a restore retry cancelled
     * before WorkManager ran it has its face pause handed back here ([handBackAfterCancel]).
     */
    fun cancel() {
        workManager.cancelUniqueWork(MlSyncWorker.UNIQUE_WORK_NAME)
        appScope.launch { handBackAfterCancel() }
    }

    /** Hides a finished job's outcome, so [state] reads idle until the next job starts. */
    fun dismissResult() {
        val finished = state.value as? MlSyncState.Finished ?: return
        dismissedRunIds.update { it + finished.runId }
    }

    private suspend fun start(userId: UserId, request: suspend () -> MlSyncRequest): MlSyncStart {
        blocked(userId)?.let { return it }
        watchedRunIds += MlSyncWorker.enqueue(context, request()).toString()
        return MlSyncStart.STARTED
    }

    /** Why no job can start for [userId] right now, or null when one may. */
    private suspend fun blocked(userId: UserId): MlSyncStart? {
        if (!isAvailable(userId) || !isSignedIn(userId)) return MlSyncStart.NOT_AVAILABLE
        val busy = workManager.getWorkInfosForUniqueWorkFlow(MlSyncWorker.UNIQUE_WORK_NAME).first()
            .any { !it.state.isFinished }
        return if (busy) MlSyncStart.ALREADY_RUNNING else null
    }

    private suspend fun isSignedIn(userId: UserId): Boolean = accountManager.getPrimaryUserId().first() == userId

    /**
     * Hands the face walk back once a cancel has left no ML sync job while the pause is still held. A job that
     * was running hands back itself as it stops; a retry WorkManager had queued after a process death, cancelled
     * before it ran, never would, and the walk would stay paused with nothing left to resume it. Doing it beside
     * a job that is winding down only asks for a walk that is coming anyway.
     */
    private suspend fun handBackAfterCancel() {
        try {
            withTimeoutOrNull(CANCEL_SETTLE_TIMEOUT_MS) {
                workManager.getWorkInfosForUniqueWorkFlow(MlSyncWorker.UNIQUE_WORK_NAME)
                    .first { infos -> infos.none { !it.state.isFinished } }
            } ?: return
            if (MlSyncFacePause.read(context)?.held != true) return
            MlSyncFacePause.lift(context)
            accountManager.getPrimaryUserId().first()?.let { faceIndexingScheduler.requestIndex(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "face walk not handed back after a cancel: ${e.javaClass.simpleName}")
        }
    }

    private fun WorkInfo.toSnapshot() = MlSyncWorkSnapshot(
        runId = id.toString(),
        state = state,
        operation = MlSyncWorkData.operationFromTags(tags),
        progress = progress,
        output = outputData,
    )

    private companion object {
        const val TAG = "MlSyncController"

        /** [listRemote]'s failure cause for a user that is not the signed-in account. */
        const val NOT_SIGNED_IN = "NotSignedIn"

        const val STOP_TIMEOUT_MS = 5_000L

        /** How long a cancel waits for WorkManager to report no ML sync job before it looks at the face pause. */
        const val CANCEL_SETTLE_TIMEOUT_MS = 10_000L
    }
}

/**
 * How long a face import waits for a paused face walk to wind down, its photos in flight finished and its model
 * sessions put away, before importing anyway. The import fence keeps whatever that pass still writes off the
 * import, so the wait only spares the memory of both running at once.
 */
internal const val FACE_WALK_IDLE_TIMEOUT_MS = 2 * 60_000L

/**
 * The face walk's pause switch ([SettingsKeys.FACE_INDEXING_PAUSED]) as a face import uses it: an ML sync
 * restore, and the import of a saved face index in Settings. The switch is the user's, and an import also turns
 * it on to stand the walk down. Whenever an import does, the same write records that the app holds the pause
 * ([HELD]), and handing the walk back clears both in one write. So a pause an import leaves behind, killed
 * mid-import with nothing left to hand the walk back, never passes for one the user set: the next run, a cancel,
 * the next restore or import and the Resume on the face card all read it as the app's and hand the walk back.
 */
internal object MlSyncFacePause {
    private const val TAG = "MlSyncFacePause"

    /** Set only in the write that turns the switch on for a restore, and cleared with the switch. */
    private val HELD = booleanPreferencesKey("ml_sync_face_pause_held")

    /** The pause switch, and whether an ML sync run holds it. */
    data class State(val switchOn: Boolean, val held: Boolean)

    /** The switch and the hold as stored, or null when the store cannot be read. */
    suspend fun read(context: Context): State? = try {
        val prefs = context.settingsDataStore.data.first()
        State(switchOn = prefs[SettingsKeys.FACE_INDEXING_PAUSED] == true, held = prefs[HELD] == true)
    } catch (e: IOException) {
        Log.w(TAG, "face pause unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Turns the switch on for a restore's imports, unless it is on already, recording the hold in the same write.
     * Returns whether an ML sync run holds the pause afterwards: this call turned it on, or an earlier run's hold
     * is still there. False when the switch is the user's, or the store could not be written.
     */
    suspend fun hold(context: Context): Boolean = try {
        context.settingsDataStore.edit { prefs ->
            if (prefs[SettingsKeys.FACE_INDEXING_PAUSED] != true) {
                prefs[SettingsKeys.FACE_INDEXING_PAUSED] = true
                prefs[HELD] = true
            }
        }[HELD] == true
    } catch (e: IOException) {
        Log.w(TAG, "face pause not held: ${e.javaClass.simpleName}")
        false
    }

    /** Turns the switch off and clears the hold, in one write, so neither is ever left without the other. */
    suspend fun lift(context: Context) {
        try {
            context.settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.FACE_INDEXING_PAUSED] = false
                prefs.remove(HELD)
            }
        } catch (e: IOException) {
            Log.w(TAG, "face pause not lifted: ${e.javaClass.simpleName}")
        }
    }
}
