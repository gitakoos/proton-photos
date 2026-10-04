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

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import eu.akoos.photos.data.mlsync.MlDriveNaming

/*
 * The contract between the label sync worker and the controller the Labels settings read it through: what a
 * run is asked to do, how far it has come and how it ended, encoded into WorkManager's data and back. Pure,
 * so every encoding and the state mapping run in a plain JVM test. Progress and output carry enum names and
 * counts only.
 */

/** What a Save or Restore tap did. */
enum class LabelBackupStart {
    /** The job was handed to WorkManager; [LabelBackupController.state] follows it. */
    STARTED,

    /** A save or restore of the labels is already queued or running. */
    ALREADY_RUNNING,

    /** No signed-in account, or the Drive client cannot run on this device. */
    NOT_AVAILABLE,

    /** A save found no Labels folder in Drive. Nothing was started; the user is asked before one is created. */
    FOLDER_MISSING,
}

enum class LabelBackupOperation { SAVE, RESTORE }

/** The step a running job is on. */
enum class LabelBackupStep {
    /** Queued, or checking the account and the Drive folder. */
    PREPARING,

    /** Sealing the labels into a file. */
    ENCRYPTING,

    UPLOADING,

    /** Downloading the saved copy a restore was asked for. */
    DOWNLOADING,

    /** Bringing the downloaded copy's labels back. */
    MERGING,
}

/** How far a running job has come: the [step] it is on. */
data class LabelBackupProgress(val step: LabelBackupStep) {
    companion object {
        /** A job WorkManager has not started yet. */
        val QUEUED = LabelBackupProgress(LabelBackupStep.PREPARING)
    }
}

/** How a run ended. [LabelBackupOutcome.photos] and [LabelBackupOutcome.newLabels] say what it moved. */
enum class LabelBackupResult {
    /** The labels are in Drive. */
    SAVED,

    /** There are no labels, so nothing was saved. */
    NOTHING_TO_SAVE,

    /** The saved labels are merged in. */
    RESTORED,

    NOT_SIGNED_IN,

    /** The Drive client cannot run on this device. */
    NOT_CAPABLE,

    /** No key of this account opens the saved file. */
    WRONG_ACCOUNT,

    /** The saved file was written by a newer version of the app. */
    TOO_NEW,

    /** The saved file is cut short or altered. */
    DAMAGED,

    /** The file kept changing under the upload. */
    CONFLICT,

    /** The Labels folder is missing and the request did not carry the user's agreement to create it. */
    FOLDER_MISSING,

    FAILED,
}

/**
 * What a run moved. A save counts the labelled [photos] it kept and the [newLabels] it kept without a photo; a
 * restore the photos whose list changed and the labels without a photo that came in.
 */
data class LabelBackupOutcome(
    val operation: LabelBackupOperation,
    val result: LabelBackupResult,
    val photos: Int = 0,
    val newLabels: Int = 0,
)

/**
 * One saved copy of the labels in Drive, as the restore drawer lists it: which install saved it ([deviceLabel] is
 * its model name, [isThisDevice] says it is this one), when, the Drive revision a restore reads, and the labels it
 * would bring with the number of photos each is on.
 */
data class LabelSavedCopy(
    val deviceId: String,
    val deviceLabel: String,
    val isThisDevice: Boolean,
    val savedAtMillis: Long,
    val revisionUid: String,
    val labels: List<LabelCount>,
)

/** What the Labels settings page shows about the one running or finished job. */
sealed interface LabelBackupState {
    data object Idle : LabelBackupState

    data class Running(
        val operation: LabelBackupOperation,
        val progress: LabelBackupProgress = LabelBackupProgress.QUEUED,
    ) : LabelBackupState

    /** Ended; stays until [LabelBackupController.dismissResult]. */
    data class Finished(val runId: String, val outcome: LabelBackupOutcome) : LabelBackupState
}

/** What a run is asked to do. [userId] is the account the user started it for. */
sealed interface LabelSyncRequest {
    val userId: String
    val operation: LabelBackupOperation

    /** Upload this install's labels. The Labels folder is created only when [createFolderConfirmed]. */
    data class Save(override val userId: String, val createFolderConfirmed: Boolean) : LabelSyncRequest {
        override val operation: LabelBackupOperation get() = LabelBackupOperation.SAVE
    }

    /** Bring back the labels in the saved copy of install [deviceId], the Drive revision [revisionUid]. */
    data class Restore(
        override val userId: String,
        val deviceId: String,
        val revisionUid: String,
    ) : LabelSyncRequest {
        override val operation: LabelBackupOperation get() = LabelBackupOperation.RESTORE
    }
}

/** One entry of the unique work as the controller reads it from its [WorkInfo]. */
data class LabelSyncWorkSnapshot(
    val runId: String,
    val state: WorkInfo.State,
    val operation: LabelBackupOperation?,
    val progress: Data,
    val output: Data,
)

/**
 * The controller's state from the unique work's entries. A job still to finish reads as running, preferred over
 * any finished entry. A finished job reads as [LabelBackupState.Finished] only when this process started or
 * watched it ([watchedRunIds]) and it was not dismissed, so a result WorkManager kept from an earlier process
 * does not come back on every start. A cancelled job reads as idle: the user stopped it and expects nothing.
 */
internal fun labelSyncState(
    works: List<LabelSyncWorkSnapshot>,
    watchedRunIds: Set<String>,
    dismissedRunIds: Set<String>,
): LabelBackupState {
    works.firstOrNull { !it.state.isFinished }?.let { active ->
        val operation = active.operation ?: LabelSyncWorkData.operation(active.progress) ?: LabelBackupOperation.SAVE
        val progress = if (active.state == WorkInfo.State.RUNNING) {
            LabelSyncWorkData.progress(active.progress)
        } else {
            LabelBackupProgress.QUEUED
        }
        return LabelBackupState.Running(operation, progress)
    }
    val finished = works.firstOrNull {
        it.state != WorkInfo.State.CANCELLED && it.runId in watchedRunIds && it.runId !in dismissedRunIds
    } ?: return LabelBackupState.Idle
    return LabelBackupState.Finished(finished.runId, LabelSyncWorkData.outcome(finished.output, finished.operation))
}

/**
 * The keys and the encoding of the worker's input, progress and output data. Every decoder tolerates what an
 * older or newer build may have written: an unknown value never throws, and where a default has to be chosen it
 * is the one that changes nothing.
 */
object LabelSyncWorkData {
    internal const val KEY_OPERATION = "op"
    internal const val KEY_USER_ID = "userId"
    internal const val KEY_CREATE_FOLDER = "createFolder"

    internal const val KEY_DEVICE_ID = "deviceId"
    internal const val KEY_REVISION = "revision"

    internal const val KEY_STEP = "step"

    internal const val KEY_RESULT = "result"
    internal const val KEY_PHOTOS = "photos"
    internal const val KEY_NEW_LABELS = "newLabels"

    private const val OPERATION_TAG_PREFIX = "label_sync_op_"

    /** The worker's input data for [request]. */
    fun input(request: LabelSyncRequest): Data = when (request) {
        is LabelSyncRequest.Save -> workDataOf(
            KEY_OPERATION to request.operation.name,
            KEY_USER_ID to request.userId,
            KEY_CREATE_FOLDER to request.createFolderConfirmed,
        )
        is LabelSyncRequest.Restore -> workDataOf(
            KEY_OPERATION to request.operation.name,
            KEY_USER_ID to request.userId,
            KEY_DEVICE_ID to request.deviceId,
            KEY_REVISION to request.revisionUid,
        )
    }

    /**
     * The request in [data], or null when it cannot be run: an unknown operation, no account id, or a restore
     * without a well-formed install id and a revision. A missing folder answer reads as not confirmed, so a
     * misread request never creates a folder unasked.
     */
    fun request(data: Data): LabelSyncRequest? {
        val userId = data.getString(KEY_USER_ID)?.takeIf { it.isNotBlank() } ?: return null
        return when (operation(data)) {
            LabelBackupOperation.SAVE -> LabelSyncRequest.Save(userId, data.getBoolean(KEY_CREATE_FOLDER, false))
            LabelBackupOperation.RESTORE -> {
                val deviceId = data.getString(KEY_DEVICE_ID)?.takeIf { MlDriveNaming.isDeviceId(it) } ?: return null
                val revision = data.getString(KEY_REVISION)?.takeIf { it.isNotBlank() } ?: return null
                LabelSyncRequest.Restore(userId, deviceId, revision)
            }
            null -> null
        }
    }

    /** The operation named in [data], which an input, a progress and an output all carry. */
    fun operation(data: Data): LabelBackupOperation? = enumOrNull<LabelBackupOperation>(data.getString(KEY_OPERATION))

    /** The progress data of a run of [operation] that has come as far as [progress]. */
    fun progress(operation: LabelBackupOperation, progress: LabelBackupProgress): Data = workDataOf(
        KEY_OPERATION to operation.name,
        KEY_STEP to progress.step.name,
    )

    /** The progress in [data]; an unknown step reads as [LabelBackupStep.PREPARING]. */
    fun progress(data: Data): LabelBackupProgress =
        LabelBackupProgress(enumOrNull<LabelBackupStep>(data.getString(KEY_STEP)) ?: LabelBackupStep.PREPARING)

    /** The output data of a run that ended in [outcome]. */
    fun output(outcome: LabelBackupOutcome): Data = workDataOf(
        KEY_OPERATION to outcome.operation.name,
        KEY_RESULT to outcome.result.name,
        KEY_PHOTOS to outcome.photos,
        KEY_NEW_LABELS to outcome.newLabels,
    )

    /**
     * The outcome in [data], with [fallbackOperation] (read from the work's tags) where the data names none.
     * Output a run never wrote, as when the worker died on an unexpected exception, reads as
     * [LabelBackupResult.FAILED].
     */
    fun outcome(data: Data, fallbackOperation: LabelBackupOperation? = null): LabelBackupOutcome = LabelBackupOutcome(
        operation = operation(data) ?: fallbackOperation ?: LabelBackupOperation.SAVE,
        result = enumOrNull<LabelBackupResult>(data.getString(KEY_RESULT)) ?: LabelBackupResult.FAILED,
        photos = data.getInt(KEY_PHOTOS, 0).coerceAtLeast(0),
        newLabels = data.getInt(KEY_NEW_LABELS, 0).coerceAtLeast(0),
    )

    /** The tag a job of [operation] is enqueued with, so its operation is known before it runs. */
    fun operationTag(operation: LabelBackupOperation): String = OPERATION_TAG_PREFIX + operation.name

    /** The operation named by one of [tags], or null. */
    fun operationFromTags(tags: Collection<String>): LabelBackupOperation? = tags.firstNotNullOfOrNull { tag ->
        if (tag.startsWith(OPERATION_TAG_PREFIX)) {
            enumOrNull<LabelBackupOperation>(tag.removePrefix(OPERATION_TAG_PREFIX))
        } else {
            null
        }
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { value -> enumValues<E>().firstOrNull { it.name == value } }
}
