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
import eu.akoos.photos.data.label.LabelBackupOperation
import eu.akoos.photos.data.label.LabelBackupOutcome
import eu.akoos.photos.data.label.LabelBackupProgress
import eu.akoos.photos.data.label.LabelBackupResult
import eu.akoos.photos.data.label.LabelBackupState
import eu.akoos.photos.data.label.LabelCount
import eu.akoos.photos.data.label.LabelBackupStep

/*
 * The decisions behind the "Labels on Drive" section of the Labels settings, kept pure so each runs in a
 * plain JVM test: when the section shows, which of its two rows turns into a running row, the words for
 * that row, and what the page says once a run has ended or a save waits for the folder question.
 */

/** What the Drive section shows. */
internal enum class LabelBackupMode {
    /** Nothing: nobody is signed in, or this device cannot use the Drive copy. */
    HIDDEN,

    /** Save and Restore, ready to tap. */
    ACTIONS,

    /** One of the two rows is the running job, and the other is held back until it ends. */
    PROGRESS,
}

/**
 * The section's mode. It needs an account ([signedIn]) and a device the Drive copy runs on ([driveAvailable]):
 * a guest has no Drive to save to and no key to seal the labels to, so the section never shows without one.
 */
internal fun labelBackupMode(signedIn: Boolean, driveAvailable: Boolean, state: LabelBackupState): LabelBackupMode =
    when {
        !signedIn || !driveAvailable -> LabelBackupMode.HIDDEN
        state is LabelBackupState.Running -> LabelBackupMode.PROGRESS
        else -> LabelBackupMode.ACTIONS
    }

/**
 * The run state the section shows. A Save tap still looking up the Labels folder ([preparingSave]) shows as a
 * save, so the tap has its answer on screen before any job exists.
 */
internal fun labelBackupShownState(state: LabelBackupState, preparingSave: Boolean): LabelBackupState =
    if (preparingSave && state !is LabelBackupState.Running) {
        LabelBackupState.Running(LabelBackupOperation.SAVE)
    } else {
        state
    }

/** The title of the row that is the running job. */
@StringRes
internal fun labelBackupHeadline(operation: LabelBackupOperation): Int = when (operation) {
    LabelBackupOperation.SAVE -> R.string.label_backup_working_save
    LabelBackupOperation.RESTORE -> R.string.label_backup_working_restore
}

/** The line under the running row's title: a string and what fills it. */
internal data class LabelBackupStepText(@StringRes val res: Int, val args: List<Any> = emptyList())

/** What the running job says it is doing. */
internal fun labelBackupStepText(progress: LabelBackupProgress): LabelBackupStepText = when (progress.step) {
    LabelBackupStep.PREPARING -> LabelBackupStepText(R.string.settings_ml_drive_step_preparing)
    LabelBackupStep.ENCRYPTING -> LabelBackupStepText(R.string.label_backup_step_encrypting)
    LabelBackupStep.UPLOADING -> LabelBackupStepText(R.string.label_backup_step_uploading)
    LabelBackupStep.DOWNLOADING -> LabelBackupStepText(R.string.label_backup_step_downloading)
    LabelBackupStep.MERGING -> LabelBackupStepText(R.string.label_backup_step_merging)
}

/** How many labels a picked saved copy lists in the restore drawer before the rest go behind "Show all". */
internal const val RESTORE_PREVIEW_LABELS = 5

/** The first [shown] labels of a saved copy that has [total] of them. */
internal data class LabelCopyPreview(val shown: List<LabelCount>, val total: Int) {
    /** True when labels are left out of [shown]. */
    val hasMore: Boolean get() = total > shown.size
}

/** A saved copy's labels cut to the first [limit], most used first as the copy lists them. */
internal fun labelCopyPreview(labels: List<LabelCount>, limit: Int = RESTORE_PREVIEW_LABELS): LabelCopyPreview =
    LabelCopyPreview(labels.take(limit), labels.size)

/** What opens over the section. */
internal sealed interface LabelBackupNotice {
    /** The first save finds no Labels folder: the user is asked before one is created. */
    data object AskToCreateFolder : LabelBackupNotice

    /** A finished run's message: [title], then an optional [line] and an optional [count] of [plural]. */
    data class Message(
        @StringRes val title: Int,
        @StringRes val line: Int? = null,
        @PluralsRes val plural: Int? = null,
        val count: Int = 0,
    ) : LabelBackupNotice
}

/**
 * What opens over the section: the folder question while a save waits for the user's agreement
 * ([folderPrompt]) or a job ended without a folder, else a finished run's message, else nothing. A missing
 * folder only ever asks, never reads as a failed save.
 */
internal fun labelBackupSheet(folderPrompt: Boolean, state: LabelBackupState): LabelBackupNotice? = when {
    folderPrompt -> LabelBackupNotice.AskToCreateFolder
    state is LabelBackupState.Finished && state.outcome.result == LabelBackupResult.FOLDER_MISSING ->
        LabelBackupNotice.AskToCreateFolder
    state is LabelBackupState.Finished -> labelBackupNotice(state.outcome)
    else -> null
}

/** The message for a run that ended in [outcome]. */
internal fun labelBackupNotice(outcome: LabelBackupOutcome): LabelBackupNotice.Message {
    val save = outcome.operation == LabelBackupOperation.SAVE
    val failedTitle = if (save) R.string.settings_ml_drive_save_failed else R.string.settings_ml_drive_restore_failed
    return when (outcome.result) {
        LabelBackupResult.SAVED ->
            if (outcome.photos > 0) {
                LabelBackupNotice.Message(
                    R.string.settings_ml_drive_saved,
                    plural = R.plurals.label_backup_saved_count,
                    count = outcome.photos,
                )
            } else {
                LabelBackupNotice.Message(R.string.settings_ml_drive_saved)
            }
        LabelBackupResult.NOTHING_TO_SAVE ->
            LabelBackupNotice.Message(R.string.settings_ml_drive_nothing_title, line = R.string.label_backup_nothing_message)
        LabelBackupResult.RESTORED ->
            when {
                outcome.photos > 0 -> LabelBackupNotice.Message(
                    R.string.settings_ml_drive_restored,
                    plural = R.plurals.label_backup_restored_count,
                    count = outcome.photos,
                )
                outcome.newLabels > 0 -> LabelBackupNotice.Message(R.string.settings_ml_drive_restored)
                else -> LabelBackupNotice.Message(R.string.settings_ml_drive_restored, line = R.string.label_backup_up_to_date)
            }
        LabelBackupResult.NOT_CAPABLE -> LabelBackupNotice.Message(R.string.settings_ml_drive_not_capable)
        LabelBackupResult.WRONG_ACCOUNT ->
            LabelBackupNotice.Message(failedTitle, line = R.string.settings_ml_drive_wrong_account)
        LabelBackupResult.TOO_NEW -> LabelBackupNotice.Message(failedTitle, line = R.string.settings_ml_drive_too_new)
        LabelBackupResult.DAMAGED -> LabelBackupNotice.Message(failedTitle, line = R.string.settings_ml_drive_damaged)
        LabelBackupResult.NOT_SIGNED_IN, LabelBackupResult.CONFLICT, LabelBackupResult.FOLDER_MISSING,
        LabelBackupResult.FAILED ->
            LabelBackupNotice.Message(failedTitle, line = R.string.settings_ml_drive_try_later)
    }
}
