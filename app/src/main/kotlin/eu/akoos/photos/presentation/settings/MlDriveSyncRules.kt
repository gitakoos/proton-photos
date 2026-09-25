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

package eu.akoos.photos.presentation.settings

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import eu.akoos.photos.R
import eu.akoos.photos.data.mlsync.FaceSnapshotMode
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlRemoteSnapshot
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncOutcome
import eu.akoos.photos.data.mlsync.MlSyncPartStatus
import eu.akoos.photos.data.mlsync.MlSyncPhase
import eu.akoos.photos.data.mlsync.MlSyncProgress
import eu.akoos.photos.data.mlsync.MlSyncResult
import eu.akoos.photos.data.mlsync.MlSyncState

/*
 * The decisions behind the "Face and search data on Drive" section of the machine-learning settings, kept
 * pure so each runs in a plain JVM test: when the section shows and what its card holds, the words and the
 * bar for a running job, how the saved sets list per device, whether a restore asks Merge or Replace, and
 * what the section says once a job has ended or a save waits for the folder question.
 */

/** What the Drive section shows. */
internal enum class MlDriveCardMode {
    /** Nothing: machine learning is off, nobody is signed in, or this device cannot use the Drive copy. */
    HIDDEN,

    /** Save and Restore, ready to tap. */
    ACTIONS,

    /** A job's step and progress, with Save and Restore held back until it ends. */
    PROGRESS,
}

/**
 * The section's mode. It needs the machine-learning features on ([mlEnabled]), an account ([signedIn]), and
 * a device the Drive copy runs on ([driveAvailable], the controller's answer for that account). A guest has
 * no Drive to save to and no key to seal the data to, so the section never shows without an account.
 */
internal fun mlDriveCardMode(
    mlEnabled: Boolean,
    signedIn: Boolean,
    driveAvailable: Boolean,
    state: MlSyncState,
): MlDriveCardMode = when {
    !mlEnabled || !signedIn || !driveAvailable -> MlDriveCardMode.HIDDEN
    state is MlSyncState.Running -> MlDriveCardMode.PROGRESS
    else -> MlDriveCardMode.ACTIONS
}

/**
 * The job state the section shows. A Save tap still looking up the ML data folder ([preparingSave]) shows as a
 * save on its first step, so the tap has its answer on screen before any job exists; a job WorkManager already
 * runs is shown as it is.
 */
internal fun mlSyncShownState(state: MlSyncState, preparingSave: Boolean): MlSyncState =
    if (preparingSave && state !is MlSyncState.Running) {
        MlSyncState.Running(MlSyncOperation.SAVE, MlSyncProgress.QUEUED)
    } else {
        state
    }

/**
 * What opens over the section: the folder question while a save waits for the user's agreement
 * ([folderPrompt]) or a job ended without a folder, else a finished job's message, else nothing. A missing
 * folder only ever asks, never reads as a failed save.
 */
internal fun mlSyncSheet(folderPrompt: Boolean, state: MlSyncState): MlSyncNotice? = when {
    folderPrompt -> MlSyncNotice.AskToCreateFolder
    state is MlSyncState.Finished -> mlSyncNotice(state.outcome)
    else -> null
}

/** The line over a running job's bar: what the job does, in the words of its ongoing notification. */
@StringRes
internal fun mlSyncHeadline(operation: MlSyncOperation?): Int = when (operation) {
    MlSyncOperation.SAVE -> R.string.ml_sync_save_title
    MlSyncOperation.RESTORE -> R.string.ml_sync_restore_title
    null -> R.string.ml_sync_channel_name
}

/** The step a running job is on, or null for a file step that names no kind of data. */
@StringRes
internal fun mlSyncStepLabel(progress: MlSyncProgress): Int? = when (progress.phase) {
    MlSyncPhase.PREPARING -> R.string.settings_ml_drive_step_preparing
    MlSyncPhase.EXPORTING -> byKind(
        progress.kind,
        faces = R.string.settings_ml_drive_step_encrypting_faces,
        search = R.string.settings_ml_drive_step_encrypting_search,
    )
    MlSyncPhase.UPLOADING -> byKind(
        progress.kind,
        faces = R.string.settings_ml_drive_step_uploading_faces,
        search = R.string.settings_ml_drive_step_uploading_search,
    )
    MlSyncPhase.DOWNLOADING -> byKind(
        progress.kind,
        faces = R.string.settings_ml_drive_step_downloading_faces,
        search = R.string.settings_ml_drive_step_downloading_search,
    )
    MlSyncPhase.IMPORTING -> byKind(
        progress.kind,
        faces = R.string.settings_ml_drive_step_importing_faces,
        search = R.string.settings_ml_drive_step_importing_search,
    )
    MlSyncPhase.FINISHING -> R.string.settings_ml_drive_step_finishing
}

private fun byKind(kind: MlDriveFileKind?, @StringRes faces: Int, @StringRes search: Int): Int? = when (kind) {
    MlDriveFileKind.FACES -> faces
    MlDriveFileKind.SEARCH -> search
    null -> null
}

/**
 * How far a running job has come, from 0 to 1, or null while it prepares or finishes, when nothing
 * measurable moves and the bar runs indeterminate. Each file counts as two equal halves: encrypting then
 * uploading for a save, downloading then importing for a restore.
 */
internal fun mlSyncProgressFraction(progress: MlSyncProgress): Float? {
    if (progress.total <= 0) return null
    val half = when (progress.phase) {
        MlSyncPhase.EXPORTING, MlSyncPhase.DOWNLOADING -> 0f
        MlSyncPhase.UPLOADING, MlSyncPhase.IMPORTING -> 0.5f
        MlSyncPhase.PREPARING, MlSyncPhase.FINISHING -> return null
    }
    return ((progress.done + half) / progress.total).coerceIn(0f, 1f)
}

/** Which install saved a set, as the restore list names it. */
internal sealed interface MlDeviceName {
    data object ThisDevice : MlDeviceName

    /** Another install whose label is known. */
    data class Labeled(val label: String) : MlDeviceName

    /** Another install. Its label sits inside the sealed files, which are not opened before a restore. */
    data object Another : MlDeviceName
}

/**
 * One install's saved set in the restore list: what its files hold, when the newest of them was stored and
 * their total size, and the files a restore of the set reads, newest first.
 */
internal data class MlRemoteDevice(
    val deviceId: String,
    val name: MlDeviceName,
    val kinds: Set<MlDriveFileKind>,
    val savedAtMs: Long,
    val sizeBytes: Long,
    val files: List<MlRemoteSnapshot>,
) {
    /** Only the face data carries names, so only a set holding it can ask Merge or Replace. */
    val hasFaceData: Boolean
        get() = MlDriveFileKind.FACES in kinds
}

/**
 * The controller's listing grouped per install for the restore list, the most recently saved set first.
 * A set is named "This device" when this install wrote it, by its label when the listing knows one, and
 * "Another device" otherwise.
 */
internal fun remoteDevices(snapshots: List<MlRemoteSnapshot>): List<MlRemoteDevice> =
    snapshots.groupBy { it.deviceId }
        .map { (deviceId, files) ->
            val newestFirst = files.sortedByDescending { it.savedAtMs }
            MlRemoteDevice(
                deviceId = deviceId,
                name = deviceName(newestFirst),
                kinds = newestFirst.mapTo(mutableSetOf()) { it.kind },
                savedAtMs = newestFirst.first().savedAtMs,
                sizeBytes = newestFirst.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                files = newestFirst,
            )
        }
        .sortedWith(compareByDescending<MlRemoteDevice> { it.savedAtMs }.thenBy { it.deviceId })

private fun deviceName(files: List<MlRemoteSnapshot>): MlDeviceName {
    if (files.any { it.isThisDevice }) return MlDeviceName.ThisDevice
    val label = files.firstNotNullOfOrNull { file -> file.deviceLabel?.trim()?.takeIf { it.isNotEmpty() } }
    return if (label != null) MlDeviceName.Labeled(label) else MlDeviceName.Another
}

/** What a set holds, for its line in the restore list. */
@StringRes
internal fun mlRemoteDeviceContents(device: MlRemoteDevice): Int = when {
    device.hasFaceData && MlDriveFileKind.SEARCH in device.kinds -> R.string.settings_ml_drive_holds_both
    device.hasFaceData -> R.string.settings_ml_drive_holds_faces
    else -> R.string.settings_ml_drive_holds_search
}

/** The kinds of data a picked set offers to restore, faces first. A choice is offered only when it holds both. */
internal fun restorableKinds(device: MlRemoteDevice): List<MlDriveFileKind> =
    listOf(MlDriveFileKind.FACES, MlDriveFileKind.SEARCH).filter { it in device.kinds }

/** [pick]'s ticked kinds with [kind] flipped, limited to what the picked set holds. */
internal fun toggledRestoreKinds(pick: MlRestoreSheet.Pick, kind: MlDriveFileKind): Set<MlDriveFileKind> {
    val held = pick.devices.firstOrNull { it.deviceId == pick.selectedId }?.kinds ?: return pick.kinds
    if (kind !in held) return pick.kinds
    return if (kind in pick.kinds) pick.kinds - kind else pick.kinds + kind
}

/** The files a restore of [device] reads with [kinds] ticked, newest first as the set lists them. */
internal fun filesToRestore(device: MlRemoteDevice, kinds: Set<MlDriveFileKind>): List<MlRemoteSnapshot> =
    device.files.filter { it.kind in kinds }

/** The set the restore list opens with picked: the only one there is, else none, so the user chooses. */
internal fun initialRestorePick(devices: List<MlRemoteDevice>): String? = devices.singleOrNull()?.deviceId

/**
 * The mode a restore starts under without asking, or null when the user has to choose Merge or Replace.
 * The question comes up for a set that holds face data on any device but a fresh one ([needsChoice], the
 * controller's answer: faces of cloud photos here, or names still waiting to be put back). Any other
 * restore runs as a Replace: a fresh device has nothing of the user's to lose, and search data can always
 * be computed again.
 */
internal fun restoreModeWithoutAsking(hasFaceData: Boolean, needsChoice: Boolean): FaceSnapshotMode? =
    if (hasFaceData && needsChoice) null else FaceSnapshotMode.Replace

/** A count a finished job's message lists: a plural resource and its number. */
internal data class MlCountPart(@PluralsRes val plural: Int, val count: Int)

/** What the section says once a job has ended. */
internal sealed interface MlSyncNotice {
    /** A save found no ML data folder in Drive, so the user is asked before one is created. */
    data object AskToCreateFolder : MlSyncNotice

    /**
     * A [title], the [counts] the job moved (listed first), and the sentences that explain the rest, as
     * string resources in reading order.
     */
    data class Message(
        @StringRes val title: Int,
        val counts: List<MlCountPart> = emptyList(),
        val lines: List<Int> = emptyList(),
    ) : MlSyncNotice
}

/** The section's words for a job that ended in [outcome]. */
internal fun mlSyncNotice(outcome: MlSyncOutcome): MlSyncNotice {
    val restore = outcome.operation == MlSyncOperation.RESTORE
    return when (outcome.result) {
        MlSyncResult.SUCCESS -> MlSyncNotice.Message(
            title = if (restore) R.string.settings_ml_drive_restored else R.string.settings_ml_drive_saved,
            counts = mlSyncCountParts(outcome),
            lines = restoreNotes(outcome, restore),
        )
        MlSyncResult.PARTIAL -> MlSyncNotice.Message(
            title = if (restore) R.string.settings_ml_drive_restored_partly else R.string.settings_ml_drive_saved_partly,
            counts = mlSyncCountParts(outcome),
            lines = failedParts(outcome, restore) + failureReason(outcome) + restoreNotes(outcome, restore),
        )
        MlSyncResult.NOTHING_TO_SAVE -> MlSyncNotice.Message(
            title = R.string.settings_ml_drive_nothing_title,
            lines = listOf(R.string.settings_ml_drive_nothing_message),
        )
        MlSyncResult.NOT_CAPABLE -> MlSyncNotice.Message(title = R.string.settings_ml_drive_not_capable)
        // Only a restore ends this way, at the file that did not fit; the files before it stay imported.
        MlSyncResult.NO_SPACE -> MlSyncNotice.Message(
            title = if (outcome.counts.filesDone > 0) {
                R.string.settings_ml_drive_restored_partly
            } else {
                R.string.settings_ml_drive_restore_failed
            },
            counts = mlSyncCountParts(outcome),
            lines = failedParts(outcome, restore = true) + R.string.settings_ml_restore_no_space +
                restoreNotes(outcome, restore = true),
        )
        // Only a save ends this way: it found no folder and had no agreement to create one.
        MlSyncResult.FOLDER_MISSING -> if (restore) failed(outcome, restore) else MlSyncNotice.AskToCreateFolder
        MlSyncResult.NOT_SIGNED_IN,
        MlSyncResult.INVALID_REQUEST,
        MlSyncResult.FAILED -> failed(outcome, restore)
    }
}

/** The counts a job moved that are above zero, in the order faces, people, search. */
internal fun mlSyncCountParts(outcome: MlSyncOutcome): List<MlCountPart> = buildList {
    val counts = outcome.counts
    if (counts.faces > 0) add(MlCountPart(R.plurals.settings_ml_drive_count_faces, counts.faces))
    if (counts.people > 0) add(MlCountPart(R.plurals.settings_ml_drive_count_people, counts.people))
    if (counts.searchEmbeddings > 0) {
        add(MlCountPart(R.plurals.settings_ml_drive_count_search, counts.searchEmbeddings))
    }
}

private fun failed(outcome: MlSyncOutcome, restore: Boolean) = MlSyncNotice.Message(
    title = if (restore) R.string.settings_ml_drive_restore_failed else R.string.settings_ml_drive_save_failed,
    lines = listOf(failureReason(outcome)),
)

/** Which kind of data did not make it, for a job that finished only in part. */
private fun failedParts(outcome: MlSyncOutcome, restore: Boolean): List<Int> = buildList {
    if (outcome.faces?.failed == true) {
        add(if (restore) R.string.settings_ml_drive_faces_not_restored else R.string.settings_ml_drive_faces_not_saved)
    }
    if (outcome.search?.failed == true) {
        add(if (restore) R.string.settings_ml_drive_search_not_restored else R.string.settings_ml_drive_search_not_saved)
    }
}

/**
 * Why a job failed, where a file's status says something the user can act on or should know: storage too
 * full, data from a newer app, data this account cannot open, or a damaged copy. Anything else, the network
 * included, reads as a plain "try again later".
 */
@StringRes
private fun failureReason(outcome: MlSyncOutcome): Int {
    val failed = listOfNotNull(outcome.faces, outcome.search).filter { it.failed }
    return when {
        MlSyncPartStatus.NO_SPACE in failed -> R.string.settings_ml_restore_no_space
        MlSyncPartStatus.TOO_NEW in failed -> R.string.settings_ml_drive_too_new
        MlSyncPartStatus.WRONG_ACCOUNT in failed -> R.string.settings_ml_drive_wrong_account
        MlSyncPartStatus.DAMAGED in failed -> R.string.settings_ml_drive_damaged
        else -> R.string.settings_ml_drive_try_later
    }
}

/** What a restore took differently from a full import: only the names of the faces, or no search data. */
private fun restoreNotes(outcome: MlSyncOutcome, restore: Boolean): List<Int> = buildList {
    if (!restore) return@buildList
    if (outcome.faces == MlSyncPartStatus.NAMES_ONLY) add(R.string.settings_ml_drive_names_only)
    if (outcome.search == MlSyncPartStatus.MODEL_MISMATCH) add(R.string.settings_ml_drive_search_other_model)
}
