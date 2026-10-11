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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.autosave.AutoSaveKind
import eu.akoos.photos.data.label.LabelBackupOperation
import eu.akoos.photos.data.label.LabelBackupProgress
import eu.akoos.photos.data.label.LabelBackupState
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.theme.AppColors

/**
 * The "Labels on Drive" section of the Labels settings, set apart from the label list like the Drive section of
 * the machine-learning settings is from its feature rows.
 *
 * Save uploads this device's labels, sealed to the account, into My files / Photos for Proton / Labels; the
 * first save finds no such folder and only asks before creating it. Restore merges what every device of the
 * account saved there: Restore opens a drawer that lists the saved copies, and one picked copy comes back. Both
 * run in the background with a notification. While one runs, its own row turns into the running job, with the step it is on and a Cancel, and the other row is
 * held back until it ends; a finished job says what it did until closed.
 *
 * Account-only, as the file lives in the account's Drive and is sealed to its key: the section is absent for a
 * guest and on a device the Drive client does not run on, while the labels themselves work without either.
 */
@Composable
internal fun LabelBackupSection(viewModel: LabelsSettingsViewModel) {
    val access by viewModel.backupAccess.collectAsStateWithLifecycle()
    val jobState by viewModel.backupState.collectAsStateWithLifecycle()
    val preparingSave by viewModel.preparingSave.collectAsStateWithLifecycle()
    val folderPrompt by viewModel.folderPrompt.collectAsStateWithLifecycle()
    var confirmSave by rememberSaveable { mutableStateOf(false) }
    val state = labelBackupShownState(jobState, preparingSave)
    val mode = labelBackupMode(access.signedIn, access.driveAvailable, state)
    if (mode == LabelBackupMode.HIDDEN) return

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionLabel(stringResource(R.string.label_backup_section))
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            val running = state as? LabelBackupState.Running
            val actionsEnabled = mode == LabelBackupMode.ACTIONS
            if (running?.operation == LabelBackupOperation.SAVE) {
                LabelBackupRunningRow(running.progress, LabelBackupOperation.SAVE, viewModel::cancelBackup)
            } else {
                ActionRow(
                    label = stringResource(R.string.settings_ml_drive_save),
                    description = stringResource(R.string.label_backup_save_desc),
                    icon = Icons.Default.CloudUpload,
                    onClick = { confirmSave = true },
                    enabled = actionsEnabled,
                )
            }
            RowDivider()
            if (running?.operation == LabelBackupOperation.RESTORE) {
                LabelBackupRunningRow(running.progress, LabelBackupOperation.RESTORE, viewModel::cancelBackup)
            } else {
                ActionRow(
                    label = stringResource(R.string.settings_ml_drive_restore),
                    description = stringResource(R.string.label_backup_restore_desc),
                    icon = Icons.Default.CloudDownload,
                    onClick = viewModel::openRestore,
                    enabled = actionsEnabled,
                )
            }
            AutoSaveRows(AutoSaveKind.LABELS, R.string.settings_autosave_confirm_labels)
        }
    }

    // A run's outcome and the folder prompt arrive once background work settles, and a sheet raised then is a
    // window of its own that would open above the app lock. Under the lock none is composed; each keeps its
    // state in the view model and shows on the unlock.
    if (LocalAppLock.current.isLocked) return

    if (confirmSave) {
        ConfirmSheet(
            title = stringResource(R.string.label_backup_save_confirm_title),
            message = stringResource(R.string.label_backup_save_confirm_message),
            confirmLabel = stringResource(R.string.settings_ml_drive_save),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmSave = false
                viewModel.saveToDrive()
            },
            onDismiss = { confirmSave = false },
        )
    }

    val restoreSheet by viewModel.restoreSheet.collectAsStateWithLifecycle()
    if (restoreSheet != LabelRestoreSheet.Hidden) {
        LabelRestoreDrawer(
            sheet = restoreSheet,
            onSelect = viewModel::selectCopy,
            onRestore = viewModel::restoreSelected,
            onClose = viewModel::closeRestore,
        )
    }

    when (val notice = labelBackupSheet(folderPrompt, state)) {
        null -> Unit
        LabelBackupNotice.AskToCreateFolder -> ConfirmSheet(
            title = stringResource(R.string.settings_ml_drive_folder_title),
            message = stringResource(R.string.label_backup_folder_message),
            confirmLabel = stringResource(R.string.settings_ml_drive_folder_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmCreateFolder,
            onDismiss = viewModel::dismissFolderPrompt,
        )
        is LabelBackupNotice.Message -> ConfirmDialog(
            title = stringResource(notice.title),
            message = noticeText(notice),
            confirmLabel = stringResource(R.string.err_dismiss_ok),
            dismissLabel = null,
            onConfirm = viewModel::dismissBackupResult,
            onDismiss = viewModel::dismissBackupResult,
        )
    }
}

/**
 * The row of the running job, in the place of the action that started it: what it does, the step it is on, and a
 * Cancel at its end.
 */
@Composable
private fun LabelBackupRunningRow(progress: LabelBackupProgress, operation: LabelBackupOperation, onCancel: () -> Unit) {
    val colors = AppColors.current
    val step = labelBackupStepText(progress)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = colors.accent,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(labelBackupHeadline(operation)),
                color = colors.fgPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(step.res, *step.args.toTypedArray()),
                color = colors.fgMute,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel), color = colors.accent)
        }
    }
}

/** The body of a finished run's message: the count, then the sentence that explains the rest. Null when neither. */
@Composable
private fun noticeText(notice: LabelBackupNotice.Message): String? {
    val count = notice.plural?.let { pluralStringResource(it, notice.count, notice.count) }
    val line = notice.line?.let { stringResource(it) }
    return listOfNotNull(count, line).joinToString("\n\n").ifBlank { null }
}
