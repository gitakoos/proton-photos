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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.akoos.photos.R
import eu.akoos.photos.data.autosave.AutoSaveKind
import eu.akoos.photos.data.mlsync.FaceSnapshotMode
import eu.akoos.photos.data.mlsync.MlDriveFileKind
import eu.akoos.photos.data.mlsync.MlSyncState
import eu.akoos.photos.presentation.common.ActionSheetRow
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.common.ConfirmSheet
import eu.akoos.photos.presentation.common.PrimaryButton
import eu.akoos.photos.presentation.common.SecondaryButton
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import eu.akoos.photos.presentation.theme.SheetBg
import eu.akoos.photos.presentation.util.LocalIs24Hour
import eu.akoos.photos.presentation.util.appLocale
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.presentation.util.formatDateTimeMinute
import kotlinx.coroutines.launch

/**
 * The "Face and search data on Drive" section of the machine-learning settings, under the feature rows.
 *
 * Save uploads this device's face and search data, sealed to the account, into My files / Photos for
 * Proton / ML data; the first save finds no such folder and only asks before creating it, with no job run
 * and nothing reported as failed until the user agrees. Restore lists what every device of the account
 * saved there, one set per device, and imports the set the user picks, asking Merge or Replace when the set
 * brings face data to a device that already holds some. A running job shows its step and a bar with
 * Cancel above the two actions; a finished one says what it did until closed.
 *
 * Account-only, as the data lives in the account's Drive and is sealed to its key: the section is absent
 * for a guest, while machine learning is off, and on a device the Drive client does not run on.
 */
@Composable
internal fun MlDriveSyncSection(
    mlEnabled: Boolean,
    viewModel: MlDriveSyncViewModel = hiltViewModel(),
    fileImport: FaceFileImportViewModel = hiltViewModel(),
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val jobState by viewModel.syncState.collectAsStateWithLifecycle()
    val restoreSheet by viewModel.restoreSheet.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val preparingSave by viewModel.preparingSave.collectAsStateWithLifecycle()
    val folderPrompt by viewModel.folderPrompt.collectAsStateWithLifecycle()
    val syncState = mlSyncShownState(jobState, preparingSave)
    val mode = mlDriveCardMode(mlEnabled, access.signedIn, access.driveAvailable, syncState)
    if (mode == MlDriveCardMode.HIDDEN) return

    Spacer(Modifier.height(20.dp))
    SectionLabel(stringResource(R.string.settings_ml_drive_section))
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        val running = syncState as? MlSyncState.Running
        if (running != null) {
            MlSyncProgressBlock(running = running, onCancel = viewModel::cancel)
            RowDivider()
        }
        // Held back while a job runs or a tap is on its way, so a second job never queues behind the first.
        val actionsEnabled = mode == MlDriveCardMode.ACTIONS && !starting
        ActionRow(
            label = stringResource(R.string.settings_ml_drive_save),
            description = stringResource(R.string.settings_ml_drive_save_desc),
            icon = Icons.Default.CloudUpload,
            onClick = viewModel::save,
            enabled = actionsEnabled,
        )
        RowDivider()
        ActionRow(
            label = stringResource(R.string.settings_ml_drive_restore),
            description = stringResource(R.string.settings_ml_drive_restore_desc),
            icon = Icons.Default.CloudDownload,
            onClick = viewModel::openRestore,
            enabled = actionsEnabled,
        )
        AutoSaveRows(AutoSaveKind.ML, R.string.settings_autosave_confirm_ml)
    }

    // A face file saved by an earlier version's export, which predates saving to Drive. Kept out of the card
    // as a link under it, so it never reads as a third way to save or restore; the dialog says what file it
    // takes and points at Drive for anything newer.
    val importing by fileImport.inProgress.collectAsStateWithLifecycle()
    var explainFileImport by rememberSaveable { mutableStateOf(false) }
    val pickFaceFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fileImport.import(uri)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(if (importing) R.string.settings_ai_transfer_working else R.string.settings_face_file_link),
        color = if (importing) AppColors.current.fgMute else AppColors.current.accent,
        fontSize = 12.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = mode == MlDriveCardMode.ACTIONS && !starting && !importing) {
                explainFileImport = true
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
    if (explainFileImport && !LocalAppLock.current.isLocked) {
        ConfirmDialog(
            title = stringResource(R.string.settings_face_file_title),
            message = stringResource(R.string.settings_face_file_msg),
            confirmLabel = stringResource(R.string.settings_ai_import_choose),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                explainFileImport = false
                pickFaceFile.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onDismiss = { explainFileImport = false },
        )
    }

    val fileImportMessage by fileImport.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(fileImportMessage) {
        fileImportMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            fileImport.clearMessage()
        }
    }

    // A job's outcome, the folder prompt and the Merge or Replace question all arrive once background work
    // settles, and a sheet raised then is a window of its own that would open above the app lock. Under the
    // lock none is composed; each keeps its state in the view model and shows on the unlock.
    if (LocalAppLock.current.isLocked) return

    when (val sheet = restoreSheet) {
        MlRestoreSheet.Hidden -> Unit
        is MlRestoreSheet.ChooseMode -> MlRestoreModeSheet(
            onChoose = viewModel::restoreWith,
            onClose = viewModel::closeRestore,
        )
        else -> MlRestorePickerSheet(
            sheet = sheet,
            onSelect = viewModel::selectRestoreDevice,
            onToggleKind = viewModel::toggleRestoreKind,
            onRestore = viewModel::restoreSelected,
            onClose = viewModel::closeRestore,
        )
    }

    mlSyncSheet(folderPrompt, syncState)?.let { notice ->
        MlSyncOutcomeSheet(
            notice = notice,
            onCreateFolder = viewModel::confirmCreateFolder,
            onDeclineFolder = viewModel::dismissFolderPrompt,
            onDismiss = viewModel::dismissResult,
        )
    }
}

/** A running job: what it does, the bar, the step it is on, and Cancel. */
@Composable
private fun MlSyncProgressBlock(running: MlSyncState.Running, onCancel: () -> Unit) {
    val colors = AppColors.current
    val fraction = mlSyncProgressFraction(running.progress)
    val step = mlSyncStepLabel(running.progress)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(mlSyncHeadline(running.operation)),
            color = colors.fgPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))
        if (fraction != null) {
            val shown by animateFloatAsState(fraction, label = "ml_drive_progress")
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.trackBg,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.trackBg,
            )
        }
        if (step != null) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(step), color = colors.fgMute, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton(
            label = stringResource(R.string.cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The restore sheet while it lists the saved sets and while the user picks one. One sheet holds every stage,
 * so the list replaces the spinner in place instead of a second sheet sliding up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MlRestorePickerSheet(
    sheet: MlRestoreSheet,
    onSelect: (String) -> Unit,
    onToggleKind: (MlDriveFileKind) -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Slide the sheet away before the action lands, as the app's other action sheets do.
    fun close(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        // Height-capped and scrolling, so a long list on a short screen never pushes the buttons off it.
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_ml_drive_restore),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when (sheet) {
                is MlRestoreSheet.Pick -> {
                    Text(
                        stringResource(R.string.settings_ml_drive_restore_pick),
                        color = colors.fgDim,
                        fontSize = 14.sp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        sheet.devices.forEach { device ->
                            MlRestoreSetCard(
                                device = device,
                                picked = device.deviceId == sheet.selectedId,
                                ticked = sheet.kinds,
                                onPick = { onSelect(device.deviceId) },
                                onToggle = onToggleKind,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SecondaryButton(
                            label = stringResource(R.string.cancel),
                            onClick = { close(onClose) },
                            modifier = Modifier.weight(1f),
                        )
                        PrimaryButton(
                            label = stringResource(R.string.settings_ml_drive_restore_confirm),
                            onClick = { close(onRestore) },
                            modifier = Modifier.weight(1f),
                            enabled = sheet.selectedId != null && sheet.kinds.isNotEmpty(),
                        )
                    }
                }
                MlRestoreSheet.Empty, MlRestoreSheet.Failed -> {
                    Text(
                        stringResource(
                            if (sheet == MlRestoreSheet.Empty) {
                                R.string.settings_ml_drive_restore_empty
                            } else {
                                R.string.settings_ml_drive_restore_list_failed
                            },
                        ),
                        color = colors.fgDim,
                        fontSize = 14.sp,
                    )
                    SecondaryButton(
                        label = stringResource(R.string.close),
                        onClick = { close(onClose) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Text(
                        stringResource(R.string.settings_ml_drive_restore_loading),
                        color = colors.fgDim,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/**
 * One saved set in the restore list, drawn as the sheet's action cards are. Picking a set that holds both
 * kinds of data opens its card downwards onto a switch per kind, both on, so one of the two can stay behind.
 */
@Composable
private fun MlRestoreSetCard(
    device: MlRemoteDevice,
    picked: Boolean,
    ticked: Set<MlDriveFileKind>,
    onPick: () -> Unit,
    onToggle: (MlDriveFileKind) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val kinds = restorableKinds(device)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PillBg, shape)
            .border(0.5.dp, PillBorder, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick)
                .semantics { selected = picked }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                if (device.name == MlDeviceName.ThisDevice) Icons.Default.PhoneAndroid else Icons.Default.Devices,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(deviceName(device.name), color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(deviceSummary(device), color = FgMute, fontSize = 12.sp)
            }
            if (picked) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(
            visible = picked && kinds.size > 1,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = PillBorder,
                )
                kinds.forEach { kind ->
                    ToggleRow(
                        label = stringResource(
                            if (kind == MlDriveFileKind.FACES) {
                                R.string.settings_ml_drive_holds_faces
                            } else {
                                R.string.settings_ml_drive_holds_search
                            },
                        ),
                        checked = kind in ticked,
                        onCheckedChange = { onToggle(kind) },
                    )
                }
            }
        }
    }
}

/** Merge or Replace, asked when a restore brings face data to a device that already holds some. Merge first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MlRestoreModeSheet(
    onChoose: (FaceSnapshotMode) -> Unit,
    onClose: () -> Unit,
) {
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_ml_drive_mode_title),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_ml_drive_mode_message),
                color = colors.fgDim,
                fontSize = 14.sp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionSheetRow(
                    icon = Icons.Default.Merge,
                    title = stringResource(R.string.settings_ml_drive_mode_merge),
                    subtitle = stringResource(R.string.settings_ml_drive_mode_merge_desc),
                    onClick = { close { onChoose(FaceSnapshotMode.Merge) } },
                )
                // Red, as it overwrites the names this device holds for its Drive photos.
                ActionSheetRow(
                    icon = Icons.Default.SwapHoriz,
                    title = stringResource(R.string.settings_ml_drive_mode_replace),
                    subtitle = stringResource(R.string.settings_ml_drive_mode_replace_desc),
                    onClick = { close { onChoose(FaceSnapshotMode.Replace) } },
                    destructive = true,
                )
            }
        }
    }
}

/**
 * The folder consent a first save stops at, or what a finished job did, until the user closes it. Closing
 * either way hands the result back to the controller, so it is not shown twice.
 */
@Composable
private fun MlSyncOutcomeSheet(
    notice: MlSyncNotice,
    onCreateFolder: () -> Unit,
    onDeclineFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (notice) {
        MlSyncNotice.AskToCreateFolder -> ConfirmSheet(
            title = stringResource(R.string.settings_ml_drive_folder_title),
            message = stringResource(R.string.settings_ml_drive_folder_message),
            confirmLabel = stringResource(R.string.settings_ml_drive_folder_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = onCreateFolder,
            onDismiss = onDeclineFolder,
        )
        is MlSyncNotice.Message -> ConfirmDialog(
            title = stringResource(notice.title),
            message = noticeText(notice),
            confirmLabel = stringResource(R.string.err_dismiss_ok),
            dismissLabel = null,
            onConfirm = onDismiss,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The body of a finished job's message: the counts as one list ("12 faces, 3 people and search data for
 * 40 photos"), then the sentences that explain the rest. Null when there is neither.
 */
@Composable
private fun noticeText(notice: MlSyncNotice.Message): String? {
    val locale = appLocale()
    val parts = notice.counts.map { pluralStringResource(it.plural, it.count, it.count) }
    val counts = when (parts.size) {
        0 -> null
        1 -> parts[0]
        2 -> stringResource(R.string.settings_ml_drive_list_two, parts[0], parts[1])
        else -> stringResource(R.string.settings_ml_drive_list_three, parts[0], parts[1], parts[2])
    }?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    val sentences = notice.lines.map { stringResource(it) }.joinToString(" ").ifBlank { null }
    return listOfNotNull(counts, sentences).joinToString("\n\n").ifBlank { null }
}

@Composable
private fun deviceName(name: MlDeviceName): String = when (name) {
    MlDeviceName.ThisDevice -> stringResource(R.string.settings_ml_drive_this_device)
    MlDeviceName.Another -> stringResource(R.string.settings_ml_drive_another_device)
    is MlDeviceName.Labeled -> name.label
}

/** What a set holds and its size, then when it was saved, in the app language. */
@Composable
private fun deviceSummary(device: MlRemoteDevice): String {
    val locale = appLocale()
    val is24Hour = LocalIs24Hour.current
    val savedAt = remember(device.savedAtMs, locale, is24Hour) { formatDateTimeMinute(device.savedAtMs, locale, is24Hour) }
    val contents = stringResource(mlRemoteDeviceContents(device))
    return "$contents · ${formatBytes(device.sizeBytes)}\n" +
        stringResource(R.string.settings_ml_drive_saved_at, savedAt)
}

