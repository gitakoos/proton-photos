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

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf

/*
 * The contract between the ML sync worker and the facade the UI reads it through: what a run is asked to
 * do, how far it has come and how it ended, encoded into WorkManager's data and back. Pure, so every
 * encoding and the facade's state mapping run in a plain JVM test.
 *
 * The progress and the output carry non-identifying values only: enum names, counts and class names.
 * Account ids, revision uids and install ids appear in the input alone, which is the request the user made.
 */

/** What an ML sync run does. */
enum class MlSyncOperation {
    /** Seals this install's face and search snapshots and uploads each under this install's own name. */
    SAVE,

    /** Downloads the snapshots the user picked from the Drive listing and imports them here. */
    RESTORE,
}

/** The step a running ML sync is on. */
enum class MlSyncPhase {
    /** Queued, or checking the account and the Drive folder. */
    PREPARING,

    /** Writing a snapshot of this device's data into a sealed file. */
    EXPORTING,

    UPLOADING,

    DOWNLOADING,

    IMPORTING,

    /** Handing the indexing walks back once the imports are done. */
    FINISHING,
}

/** How one snapshot file fared in a run. */
enum class MlSyncPartStatus {
    /** Uploaded, or imported whole. */
    DONE,

    /** Save: this device holds nothing of the kind another device could use, so nothing was uploaded. */
    EMPTY,

    /** Restore of faces from another face model: only the names were taken, to return to this device's own
     *  faces as the library is scanned. */
    NAMES_ONLY,

    /** Restore of search data from another embedding model: nothing was imported, and this device indexes
     *  those photos itself. */
    MODEL_MISMATCH,

    /** Restore: no key of this account opens the snapshot. */
    WRONG_ACCOUNT,

    /** Restore: written by a newer version of the app. */
    TOO_NEW,

    /** Restore: cut short, altered, or not a snapshot of the expected kind. */
    DAMAGED,

    /** Save: the file kept changing under the upload, the retry included. */
    CONFLICT,

    /** Restore: storage is too full for the import, before it began or while it wrote. */
    NO_SPACE,

    /** Storage, the network, the account's keys or the database failed. */
    FAILED,
    ;

    /** Finished as asked: a names-only or model-mismatched restore took what it could and nothing broke. */
    val finished: Boolean
        get() = this == DONE || this == NAMES_ONLY || this == MODEL_MISMATCH

    /** Something went wrong with this file. [EMPTY] is neither finished nor failed. */
    val failed: Boolean
        get() = !finished && this != EMPTY
}

/** How a run ended overall, the value a caller branches on first; the part statuses carry the detail. */
enum class MlSyncResult {
    /** Every file finished; a file with nothing to save counts as finished. */
    SUCCESS,

    /** Some files finished and some failed. */
    PARTIAL,

    /** Save: this device holds no face or search data another device could use. */
    NOTHING_TO_SAVE,

    /** No signed-in account, or the account signed out or changed while the run was going. */
    NOT_SIGNED_IN,

    /** The Drive SDK cannot run on this device. */
    NOT_CAPABLE,

    /** Save: the ML data folder does not exist and the request did not carry the user's agreement to create
     *  it, or it vanished during the run. Never a failure: the section asks whether to create the folder. */
    FOLDER_MISSING,

    /** The request could not be read: an unknown operation, no account, or no file to restore. */
    INVALID_REQUEST,

    /** Restore: storage is too full for an import. The run ends at that file, as every later one and a
     *  retry would meet the same; the files imported before it stay. */
    NO_SPACE,

    /** Nothing finished. */
    FAILED,
}

/**
 * One Drive snapshot a restore reads: its kind, the revision to download, when the server stored it, and
 * the install its name carries ([deviceId]), which the snapshot's own header has to name as well.
 */
data class MlRestoreFile(
    val kind: MlDriveFileKind,
    val revisionUid: String,
    val serverModifiedAtMs: Long,
    val deviceId: String,
)

/**
 * What a run is asked to do, as the worker's input data carries it. [userId] is the account the user
 * started the run for: a run that finds another account signed in does nothing.
 */
sealed interface MlSyncRequest {
    val userId: String
    val operation: MlSyncOperation

    /** Upload this install's snapshots. The ML data folder is created only when [createFolderConfirmed]. */
    data class Save(
        override val userId: String,
        val createFolderConfirmed: Boolean,
    ) : MlSyncRequest {
        override val operation: MlSyncOperation
            get() = MlSyncOperation.SAVE
    }

    /**
     * Import [files] under [mode]. [faceIndexingPausedByUser] is whether the user had paused face indexing
     * when the restore was started: the switch on, and no ML sync run holding it ([MlSyncFacePause]). It is
     * read then rather than when the run begins, because the run pauses that walk itself: a run WorkManager
     * repeats after a process death would otherwise take the pause the killed run left behind for the user's
     * own and never resume the walk.
     */
    data class Restore(
        override val userId: String,
        val files: List<MlRestoreFile>,
        val mode: FaceSnapshotMode,
        val faceIndexingPausedByUser: Boolean,
    ) : MlSyncRequest {
        override val operation: MlSyncOperation
            get() = MlSyncOperation.RESTORE
    }
}

/** How far a run has come: the [phase] of the file of [kind] it is on, with [done] of [total] files handled. */
data class MlSyncProgress(
    val phase: MlSyncPhase,
    val kind: MlDriveFileKind?,
    val done: Int,
    val total: Int,
) {
    companion object {
        /** A run WorkManager has not started yet. */
        val QUEUED = MlSyncProgress(MlSyncPhase.PREPARING, kind = null, done = 0, total = 0)
    }
}

/**
 * What a run moved, in counts only. A save counts what it uploaded, a restore what it imported: [faces] are
 * the face rows written or updated, [people] the named people, [scanMarkers] the photos marked as scanned,
 * [searchEmbeddings] the search rows, [searchKept] the photos whose own search row a restore kept, and
 * [filesDone] of [filesTotal] the snapshot files that wrote something.
 */
data class MlSyncCounts(
    val faces: Int = 0,
    val people: Int = 0,
    val scanMarkers: Int = 0,
    val searchEmbeddings: Int = 0,
    val searchKept: Int = 0,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
)

/**
 * How a run ended. [faces] and [search] are the statuses of the two kinds of file, null for a kind the run
 * did not handle. [failure] names what failed first as a class name or an [MlDriveResult.Failed] cause, never
 * a message, a name, a path or an id.
 */
data class MlSyncOutcome(
    val operation: MlSyncOperation?,
    val result: MlSyncResult,
    val faces: MlSyncPartStatus? = null,
    val search: MlSyncPartStatus? = null,
    val counts: MlSyncCounts = MlSyncCounts(),
    val failure: String? = null,
)

/** What the facade reports about the one ML sync job. */
sealed interface MlSyncState {
    /** Nothing running, and no result waiting to be shown. */
    data object Idle : MlSyncState

    /** A job is queued or running. */
    data class Running(val operation: MlSyncOperation?, val progress: MlSyncProgress) : MlSyncState

    /** A job this process started or watched has ended; it stays until dismissed. */
    data class Finished(val runId: String, val outcome: MlSyncOutcome) : MlSyncState
}

/** One entry of the unique work as the facade reads it from its [WorkInfo]. */
data class MlSyncWorkSnapshot(
    val runId: String,
    val state: WorkInfo.State,
    val operation: MlSyncOperation?,
    val progress: Data,
    val output: Data,
)

/**
 * The facade's state from the unique work's entries. A job still to finish reads as running, preferred over
 * any finished entry. A finished job reads as [MlSyncState.Finished] only when this process started or
 * watched it ([watchedRunIds]) and it was not dismissed, so a result WorkManager kept from an earlier process
 * does not come back on every start. A cancelled job reads as idle: the user stopped it and expects nothing.
 */
internal fun mlSyncState(
    works: List<MlSyncWorkSnapshot>,
    watchedRunIds: Set<String>,
    dismissedRunIds: Set<String>,
): MlSyncState {
    works.firstOrNull { !it.state.isFinished }?.let { active ->
        val progress = if (active.state == WorkInfo.State.RUNNING) {
            MlSyncWorkData.progress(active.progress)
        } else {
            MlSyncProgress.QUEUED
        }
        return MlSyncState.Running(active.operation ?: MlSyncWorkData.operation(active.progress), progress)
    }
    val finished = works.firstOrNull {
        it.state != WorkInfo.State.CANCELLED && it.runId in watchedRunIds && it.runId !in dismissedRunIds
    } ?: return MlSyncState.Idle
    return MlSyncState.Finished(finished.runId, MlSyncWorkData.outcome(finished.output, finished.operation))
}

/**
 * The keys and the encoding of the worker's input, progress and output data. Every decoder tolerates what an
 * older or newer build may have written: an unknown value never throws, and where a default has to be
 * chosen it is the one that changes nothing on this device.
 */
object MlSyncWorkData {
    internal const val KEY_OPERATION = "op"
    internal const val KEY_USER_ID = "userId"
    internal const val KEY_CREATE_FOLDER = "createFolder"
    internal const val KEY_MODE = "mode"
    internal const val KEY_FACE_INDEXING_PAUSED = "faceIndexingPaused"
    internal const val KEY_FILE_KINDS = "fileKinds"
    internal const val KEY_FILE_REVISIONS = "fileRevisions"
    internal const val KEY_FILE_TIMES = "fileTimes"
    internal const val KEY_FILE_DEVICES = "fileDevices"

    internal const val KEY_PHASE = "phase"
    internal const val KEY_KIND = "kind"
    internal const val KEY_DONE = "done"
    internal const val KEY_TOTAL = "total"

    internal const val KEY_RESULT = "result"
    internal const val KEY_FACES_STATUS = "facesStatus"
    internal const val KEY_SEARCH_STATUS = "searchStatus"
    internal const val KEY_FAILURE = "failure"
    internal const val KEY_FACES = "faces"
    internal const val KEY_PEOPLE = "people"
    internal const val KEY_SCAN_MARKERS = "scanMarkers"
    internal const val KEY_SEARCH_EMBEDDINGS = "searchEmbeddings"
    internal const val KEY_SEARCH_KEPT = "searchKept"
    internal const val KEY_FILES_DONE = "filesDone"
    internal const val KEY_FILES_TOTAL = "filesTotal"

    private const val OPERATION_TAG_PREFIX = "ml_sync_op_"

    /** A class name, or a constant of the same shape: nothing a message, a path, an address or an id fits. */
    private val FAILURE_NAME = Regex("[A-Za-z_][A-Za-z0-9_$.]{0,79}")

    /** The worker's input data for [request]. */
    fun input(request: MlSyncRequest): Data = when (request) {
        is MlSyncRequest.Save -> workDataOf(
            KEY_OPERATION to request.operation.name,
            KEY_USER_ID to request.userId,
            KEY_CREATE_FOLDER to request.createFolderConfirmed,
        )
        is MlSyncRequest.Restore -> workDataOf(
            KEY_OPERATION to request.operation.name,
            KEY_USER_ID to request.userId,
            KEY_MODE to request.mode.name,
            KEY_FACE_INDEXING_PAUSED to request.faceIndexingPausedByUser,
            KEY_FILE_KINDS to request.files.map { it.kind.name }.toTypedArray(),
            KEY_FILE_REVISIONS to request.files.map { it.revisionUid }.toTypedArray(),
            KEY_FILE_TIMES to request.files.map { it.serverModifiedAtMs }.toLongArray(),
            KEY_FILE_DEVICES to request.files.map { it.deviceId }.toTypedArray(),
        )
    }

    /**
     * The request in [data], or null when it cannot be run: an unknown operation, no account id, or restore
     * file lists that do not line up. A restore file of an unknown kind, with a blank revision or without a
     * well-formed install id is dropped. An unknown mode reads as [FaceSnapshotMode.Merge], and a missing
     * folder answer as not confirmed, so a misread request never discards data here or creates a folder
     * unasked.
     */
    fun request(data: Data): MlSyncRequest? {
        val userId = data.getString(KEY_USER_ID)?.takeIf { it.isNotBlank() } ?: return null
        return when (operation(data)) {
            MlSyncOperation.SAVE -> MlSyncRequest.Save(userId, data.getBoolean(KEY_CREATE_FOLDER, false))
            MlSyncOperation.RESTORE -> MlSyncRequest.Restore(
                userId = userId,
                files = restoreFiles(data) ?: return null,
                mode = enumOrNull<FaceSnapshotMode>(data.getString(KEY_MODE)) ?: FaceSnapshotMode.Merge,
                faceIndexingPausedByUser = data.getBoolean(KEY_FACE_INDEXING_PAUSED, false),
            )
            null -> null
        }
    }

    /** The operation named in [data], which an input, a progress and an output all carry. */
    fun operation(data: Data): MlSyncOperation? = enumOrNull<MlSyncOperation>(data.getString(KEY_OPERATION))

    private fun restoreFiles(data: Data): List<MlRestoreFile>? {
        val kinds = data.getStringArray(KEY_FILE_KINDS) ?: return null
        val revisions = data.getStringArray(KEY_FILE_REVISIONS) ?: return null
        val times = data.getLongArray(KEY_FILE_TIMES) ?: return null
        val devices = data.getStringArray(KEY_FILE_DEVICES) ?: return null
        if (kinds.size != revisions.size || kinds.size != times.size || kinds.size != devices.size) return null
        return kinds.indices.mapNotNull { i ->
            val kind = enumOrNull<MlDriveFileKind>(kinds[i]) ?: return@mapNotNull null
            val revisionUid = revisions[i]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val deviceId = devices[i]?.takeIf { MlDriveNaming.isDeviceId(it) } ?: return@mapNotNull null
            MlRestoreFile(kind, revisionUid, times[i], deviceId)
        }
    }

    /** The progress data of a run of [operation] that has come as far as [progress]. */
    fun progress(operation: MlSyncOperation, progress: MlSyncProgress): Data = workDataOf(
        KEY_OPERATION to operation.name,
        KEY_PHASE to progress.phase.name,
        KEY_KIND to progress.kind?.name,
        KEY_DONE to progress.done,
        KEY_TOTAL to progress.total,
    )

    /** The progress in [data]; an unknown phase reads as [MlSyncPhase.PREPARING], and [MlSyncProgress.done]
     *  never exceeds the total. */
    fun progress(data: Data): MlSyncProgress {
        val total = data.getInt(KEY_TOTAL, 0).coerceAtLeast(0)
        return MlSyncProgress(
            phase = enumOrNull<MlSyncPhase>(data.getString(KEY_PHASE)) ?: MlSyncPhase.PREPARING,
            kind = enumOrNull<MlDriveFileKind>(data.getString(KEY_KIND)),
            done = data.getInt(KEY_DONE, 0).coerceIn(0, total),
            total = total,
        )
    }

    /** The output data of a run that ended in [outcome]: names, counts and a sanitised failure name. */
    fun output(outcome: MlSyncOutcome): Data = workDataOf(
        KEY_OPERATION to outcome.operation?.name,
        KEY_RESULT to outcome.result.name,
        KEY_FACES_STATUS to outcome.faces?.name,
        KEY_SEARCH_STATUS to outcome.search?.name,
        KEY_FAILURE to failureName(outcome.failure),
        KEY_FACES to outcome.counts.faces,
        KEY_PEOPLE to outcome.counts.people,
        KEY_SCAN_MARKERS to outcome.counts.scanMarkers,
        KEY_SEARCH_EMBEDDINGS to outcome.counts.searchEmbeddings,
        KEY_SEARCH_KEPT to outcome.counts.searchKept,
        KEY_FILES_DONE to outcome.counts.filesDone,
        KEY_FILES_TOTAL to outcome.counts.filesTotal,
    )

    /**
     * The outcome in [data], with [fallbackOperation] (read from the work's tags) where the data names none.
     * Output a run never wrote, as when the worker died on an unexpected exception, reads as
     * [MlSyncResult.FAILED]; a part status this build does not know reads as [MlSyncPartStatus.FAILED].
     */
    fun outcome(data: Data, fallbackOperation: MlSyncOperation? = null): MlSyncOutcome = MlSyncOutcome(
        operation = operation(data) ?: fallbackOperation,
        result = enumOrNull<MlSyncResult>(data.getString(KEY_RESULT)) ?: MlSyncResult.FAILED,
        faces = partStatus(data.getString(KEY_FACES_STATUS)),
        search = partStatus(data.getString(KEY_SEARCH_STATUS)),
        counts = MlSyncCounts(
            faces = count(data, KEY_FACES),
            people = count(data, KEY_PEOPLE),
            scanMarkers = count(data, KEY_SCAN_MARKERS),
            searchEmbeddings = count(data, KEY_SEARCH_EMBEDDINGS),
            searchKept = count(data, KEY_SEARCH_KEPT),
            filesDone = count(data, KEY_FILES_DONE),
            filesTotal = count(data, KEY_FILES_TOTAL),
        ),
        failure = failureName(data.getString(KEY_FAILURE)),
    )

    /** The tag a job of [operation] is enqueued with, so its operation is known before it runs. */
    fun operationTag(operation: MlSyncOperation): String = OPERATION_TAG_PREFIX + operation.name

    /** The operation named by one of [tags], or null. */
    fun operationFromTags(tags: Collection<String>): MlSyncOperation? = tags.firstNotNullOfOrNull { tag ->
        if (tag.startsWith(OPERATION_TAG_PREFIX)) {
            enumOrNull<MlSyncOperation>(tag.removePrefix(OPERATION_TAG_PREFIX))
        } else {
            null
        }
    }

    /** [value] when it has the shape of a class name, else null, so nothing identifying reaches the output. */
    fun failureName(value: String?): String? = value?.takeIf { FAILURE_NAME.matches(it) }

    private fun partStatus(value: String?): MlSyncPartStatus? =
        value?.let { enumOrNull<MlSyncPartStatus>(it) ?: MlSyncPartStatus.FAILED }

    private fun count(data: Data, key: String): Int = data.getInt(key, 0).coerceAtLeast(0)

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { value -> enumValues<E>().firstOrNull { it.name == value } }
}
