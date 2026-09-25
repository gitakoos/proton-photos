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

/*
 * The decisions an ML sync run makes, kept pure so each runs in a plain JVM test: whether a save may start
 * or first asks for the Drive folder, which snapshot files a restore reads and in what order, when a save
 * retries an upload, how the files' statuses fold into the run's result, whose face pause a restore
 * keeps, whether a job started again after a crash still runs, and whether a snapshot is the file it was
 * picked as.
 */

/** What a Save tap does once the ML data folder has been looked up, before any job exists. */
internal enum class MlSaveStep {
    /** The folder is there, or the lookup could not tell: the save runs and resolves the folder itself. */
    START,

    /** No folder yet: the user is asked before one is created, and nothing runs until they agree. */
    ASK_TO_CREATE_FOLDER,

    /** The Drive SDK cannot run on this device. */
    NOT_CAPABLE,
}

/**
 * The step a Save tap takes after [MlDriveStore.folder] answered [folder] without creating anything. A missing
 * folder only ever asks, so the first save shows the folder question and never an error. A lookup that failed
 * starts the save anyway: the job looks again, and reports a failure that repeats as its own outcome, or asks
 * for the folder when it finds none.
 */
internal fun saveStep(folder: MlDriveResult<MlDriveFolder?>): MlSaveStep = when (folder) {
    is MlDriveResult.Ok -> if (folder.value == null) MlSaveStep.ASK_TO_CREATE_FOLDER else MlSaveStep.START
    MlDriveResult.FolderMissing -> MlSaveStep.ASK_TO_CREATE_FOLDER
    MlDriveResult.NotCapable -> MlSaveStep.NOT_CAPABLE
    MlDriveResult.Conflict, is MlDriveResult.Failed -> MlSaveStep.START
}

/** Files of one kind a restore reads at most. The request stays far below WorkManager's input size limit. */
internal const val MAX_RESTORE_FILES_PER_KIND = 8

/** Upload attempts a save makes per file: the first, and one retry after a conflict. */
internal const val MAX_UPLOAD_ATTEMPTS = 2

/** The kinds a save uploads and a restore imports, in the order it handles them (see [planRestore]). */
internal val ML_SYNC_KIND_ORDER = listOf(MlDriveFileKind.FACES, MlDriveFileKind.SEARCH)

/** One download and import of a restore. [replace] imports under Replace, otherwise under Merge. */
data class MlRestoreStep(val file: MlRestoreFile, val replace: Boolean)

/**
 * The downloads and imports a restore runs, in order.
 *
 * Which files: each revision once, whatever the request repeats; none with a blank revision; and at most
 * [MAX_RESTORE_FILES_PER_KIND] of a kind, the newest by the time the server stored them.
 *
 * Order: every face snapshot before any search snapshot. The names and curation a face snapshot carries are
 * the part of the machine-learning data no device can compute again, while search vectors can always be
 * rebuilt by indexing, so a restore that is cancelled, killed or fails on the search file has already put
 * back the part that matters most. The face file is also the smaller of the two, so that part lands sooner.
 * Within a kind the newest file goes first.
 *
 * Mode: the first file of each kind is imported under [mode] and every later one of that kind as a Merge. A
 * face Replace clears this device's cloud-photo faces before it writes, so a second Replace would throw the
 * first file's import away; a Merge only fills what the newer files left open.
 */
fun planRestore(files: List<MlRestoreFile>, mode: FaceSnapshotMode): List<MlRestoreStep> {
    val unique = files.filter { it.revisionUid.isNotBlank() }.distinctBy { it.revisionUid }
    return ML_SYNC_KIND_ORDER.flatMap { kind ->
        unique.asSequence()
            .filter { it.kind == kind }
            .sortedWith(compareByDescending<MlRestoreFile> { it.serverModifiedAtMs }.thenBy { it.revisionUid })
            .take(MAX_RESTORE_FILES_PER_KIND)
            .mapIndexed { index, file -> MlRestoreStep(file, replace = index == 0 && mode == FaceSnapshotMode.Replace) }
            .toList()
    }
}

/** What a save does after an upload attempt. */
internal sealed interface MlUploadDecision {
    data object Uploaded : MlUploadDecision

    data object Retry : MlUploadDecision

    /** This file ends as [status] with [failure] named; a non-null [endsRun] ends the whole run with it. */
    data class GiveUp(
        val status: MlSyncPartStatus,
        val endsRun: MlSyncResult?,
        val failure: String?,
    ) : MlUploadDecision
}

/**
 * What a save does after upload attempt [attempt] (counted from 1) ended in [result].
 *
 * A conflict means the file changed between the lookup of its active revision and the upload: a draft an
 * interrupted upload left open, or the name appearing meanwhile. It is retried once, and the retry looks
 * afresh, since [MlDriveStore.upload] resolves the folder, the file and its active revision on every call; a
 * second conflict ends the file as [MlSyncPartStatus.CONFLICT]. A missing folder, or a device the SDK cannot
 * run on, ends the whole run, as the next file would meet the same. Any other failure ends this file only.
 */
internal fun uploadDecision(result: MlDriveResult<*>, attempt: Int): MlUploadDecision = when (result) {
    is MlDriveResult.Ok -> MlUploadDecision.Uploaded
    MlDriveResult.Conflict -> if (attempt < MAX_UPLOAD_ATTEMPTS) {
        MlUploadDecision.Retry
    } else {
        MlUploadDecision.GiveUp(MlSyncPartStatus.CONFLICT, endsRun = null, failure = CONFLICT_FAILURE)
    }
    MlDriveResult.NotCapable ->
        MlUploadDecision.GiveUp(MlSyncPartStatus.FAILED, endsRun = MlSyncResult.NOT_CAPABLE, failure = null)
    MlDriveResult.FolderMissing ->
        MlUploadDecision.GiveUp(MlSyncPartStatus.FAILED, endsRun = MlSyncResult.FOLDER_MISSING, failure = null)
    is MlDriveResult.Failed ->
        MlUploadDecision.GiveUp(MlSyncPartStatus.FAILED, endsRun = null, failure = result.cause)
}

/** The failure name a conflict is reported under. */
internal const val CONFLICT_FAILURE = "Conflict"

/** Starts an ML sync job gets: the first, and two more after the process died under it. */
internal const val MAX_RUN_ATTEMPTS = 3

/** The failure name a job ends under once it has been started [MAX_RUN_ATTEMPTS] times. */
internal const val RETRY_LIMIT_FAILURE = "RetryLimit"

/**
 * Whether a job WorkManager starts with [runAttemptCount] (0 on its first start) still runs. The worker
 * never asks for a retry, so a later start only follows a process that died under the job, and a crash
 * that repeats, in the Drive SDK or an import, would otherwise start it again on every launch. Past
 * [MAX_RUN_ATTEMPTS] the job ends at once with a result instead.
 */
internal fun mlSyncRunAllowed(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RUN_ATTEMPTS

/**
 * Whether a snapshot whose header names the install [headerDeviceId] is the file it was picked as. A
 * restore picks each file by the install its Drive name carries ([expectedDeviceId]), so one whose header
 * names another install holds something else under that name and is refused. A file picked from storage
 * has no such name (null), and nothing is checked.
 */
internal fun snapshotFromExpectedDevice(headerDeviceId: String, expectedDeviceId: String?): Boolean =
    expectedDeviceId == null || headerDeviceId == expectedDeviceId

/**
 * A run's result from the status of every file it handled: [MlSyncResult.SUCCESS] when none failed and at
 * least one finished, [MlSyncResult.NOTHING_TO_SAVE] when every file was empty, [MlSyncResult.PARTIAL] when
 * some finished and some failed, and [MlSyncResult.FAILED] when none finished.
 */
internal fun foldResult(statuses: List<MlSyncPartStatus>): MlSyncResult {
    val anyFinished = statuses.any { it.finished }
    val anyFailed = statuses.any { it.failed }
    return when {
        anyFinished && anyFailed -> MlSyncResult.PARTIAL
        anyFinished -> MlSyncResult.SUCCESS
        anyFailed || statuses.isEmpty() -> MlSyncResult.FAILED
        else -> MlSyncResult.NOTHING_TO_SAVE
    }
}

/**
 * A kind's status after one more of its files ended as [next]: a finished file outranks an empty one, which
 * outranks a failed one, a full import outranks a names-only one, and of two failures the first is kept.
 */
internal fun mergeKindStatus(current: MlSyncPartStatus?, next: MlSyncPartStatus): MlSyncPartStatus {
    if (current == null) return next
    return if (statusRank(next) > statusRank(current)) next else current
}

private fun statusRank(status: MlSyncPartStatus): Int = when (status) {
    MlSyncPartStatus.DONE -> 4
    MlSyncPartStatus.NAMES_ONLY -> 3
    MlSyncPartStatus.MODEL_MISMATCH -> 2
    MlSyncPartStatus.EMPTY -> 1
    else -> 0
}

/**
 * Whether the face pause read as a restore is started is the user's own: the switch is on ([pauseSwitchOn])
 * and no ML sync run holds it ([heldByMlSync], see [MlSyncFacePause]). A pause an earlier restore turned on and
 * never handed back, as when its run was killed mid-import and its retry was cancelled or ended early, is the
 * app's, however long it has been on. So is one that cannot be read (null): the restore then hands the walk
 * back when it ends, since a walk left paused by mistake has no way back on its own.
 */
internal fun faceIndexingPausedByUser(pauseSwitchOn: Boolean?, heldByMlSync: Boolean?): Boolean =
    pauseSwitchOn == true && heldByMlSync == false

/**
 * Whether a restore that stood the face walk down hands it back with a fresh walk. It does when an ML sync run
 * holds the pause ([heldByMlSync]: this run turned it on, or found the hold an earlier run left), and when the
 * user had not paused the walk as the restore started ([pausedByUserAtStart]). Only a pause the user set
 * before the restore, and which no run has taken over since, stays.
 */
internal fun faceWalkResumes(heldByMlSync: Boolean, pausedByUserAtStart: Boolean): Boolean =
    heldByMlSync || !pausedByUserAtStart

/** The file status a face snapshot import ended in. */
internal fun FaceSnapshotImportResult.partStatus(): MlSyncPartStatus = when (this) {
    is FaceSnapshotImportResult.Imported -> MlSyncPartStatus.DONE
    is FaceSnapshotImportResult.NamesStaged -> MlSyncPartStatus.NAMES_ONLY
    FaceSnapshotImportResult.WrongAccount -> MlSyncPartStatus.WRONG_ACCOUNT
    FaceSnapshotImportResult.UnsupportedVersion -> MlSyncPartStatus.TOO_NEW
    FaceSnapshotImportResult.Unreadable -> MlSyncPartStatus.DAMAGED
    FaceSnapshotImportResult.NotEnoughSpace -> MlSyncPartStatus.NO_SPACE
}

/** The file status a search snapshot import ended in. */
internal fun SearchSnapshotImportResult.partStatus(): MlSyncPartStatus = when (this) {
    is SearchSnapshotImportResult.Imported -> MlSyncPartStatus.DONE
    is SearchSnapshotImportResult.SkippedModelMismatch -> MlSyncPartStatus.MODEL_MISMATCH
    is SearchSnapshotImportResult.Failed -> when (reason) {
        SearchImportFailure.WRONG_ACCOUNT -> MlSyncPartStatus.WRONG_ACCOUNT
        SearchImportFailure.TOO_NEW -> MlSyncPartStatus.TOO_NEW
        SearchImportFailure.DAMAGED, SearchImportFailure.NOT_A_SEARCH_SNAPSHOT -> MlSyncPartStatus.DAMAGED
        SearchImportFailure.NO_SPACE -> MlSyncPartStatus.NO_SPACE
        SearchImportFailure.NO_ACCOUNT, SearchImportFailure.ERROR -> MlSyncPartStatus.FAILED
    }
}

/**
 * What a run has done so far, folded into its outcome at the end: every file's status, the status of each
 * kind, the counts, and the name of the first failure.
 */
internal class MlSyncTally(private val operation: MlSyncOperation, filesTotal: Int) {
    private val statuses = ArrayList<MlSyncPartStatus>()
    private var faces: MlSyncPartStatus? = null
    private var search: MlSyncPartStatus? = null
    private var failure: String? = null

    var counts = MlSyncCounts(filesTotal = filesTotal)
        private set

    /** Records how one file of [kind] ended. [failureName] is kept only for the run's first failure. */
    fun record(kind: MlDriveFileKind, status: MlSyncPartStatus, failureName: String? = null) {
        statuses += status
        when (kind) {
            MlDriveFileKind.FACES -> faces = mergeKindStatus(faces, status)
            MlDriveFileKind.SEARCH -> search = mergeKindStatus(search, status)
        }
        if (status.failed && failure == null) failure = MlSyncWorkData.failureName(failureName)
        if (status == MlSyncPartStatus.DONE || status == MlSyncPartStatus.NAMES_ONLY) {
            counts = counts.copy(filesDone = counts.filesDone + 1)
        }
    }

    /** Adds what one file moved to the counts. */
    fun count(
        faces: Int = 0,
        people: Int = 0,
        scanMarkers: Int = 0,
        searchEmbeddings: Int = 0,
        searchKept: Int = 0,
    ) {
        counts = counts.copy(
            faces = counts.faces + faces,
            people = counts.people + people,
            scanMarkers = counts.scanMarkers + scanMarkers,
            searchEmbeddings = counts.searchEmbeddings + searchEmbeddings,
            searchKept = counts.searchKept + searchKept,
        )
    }

    /**
     * The outcome so far, with [result] folded from the files' statuses unless a run-ending one is given.
     * [failureName] names what ended the run when no file failed before it.
     */
    fun outcome(result: MlSyncResult = foldResult(statuses), failureName: String? = null): MlSyncOutcome =
        MlSyncOutcome(operation, result, faces, search, counts, failure ?: MlSyncWorkData.failureName(failureName))
}

/**
 * One snapshot in the Drive ML data folder, as the restore list shows it: its [kind], the install that wrote
 * it ([deviceId], [isThisDevice]), when the server stored it and its size, and the revision a restore reads.
 * [savedAtMs] is when the saving install wrote it, by its own clock, and the time the list shows: the
 * revision time the Drive SDK reports runs early by the device's offset from UTC, so it only stands in
 * for a file uploaded without a modification time.
 * [deviceLabel] is this device's label on its own snapshots and null on another install's, whose label sits
 * inside the sealed snapshot and is not known before a download.
 */
data class MlRemoteSnapshot(
    val kind: MlDriveFileKind,
    val deviceId: String,
    val isThisDevice: Boolean,
    val deviceLabel: String?,
    val serverModifiedAtMs: Long,
    val sizeBytes: Long,
    val revisionUid: String,
    val savedAtMs: Long = serverModifiedAtMs,
)

/** The restore list's entries for [picks], [MlDriveNaming.selectForImport]'s choice, in its order. */
internal fun remoteSnapshots(
    picks: List<MlDriveImport>,
    ownDeviceId: String,
    ownDeviceLabel: String,
): List<MlRemoteSnapshot> = picks.map { pick ->
    val own = pick.deviceId == ownDeviceId
    MlRemoteSnapshot(
        kind = pick.kind,
        deviceId = pick.deviceId,
        isThisDevice = own,
        deviceLabel = if (own) ownDeviceLabel else null,
        serverModifiedAtMs = pick.file.serverModifiedAtMs,
        sizeBytes = pick.file.sizeBytes,
        revisionUid = pick.file.activeRevisionUid,
        savedAtMs = pick.file.claimedModifiedAtMs ?: pick.file.serverModifiedAtMs,
    )
}
