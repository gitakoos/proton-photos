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

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.R
import eu.akoos.photos.data.mlsync.MlSyncController
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncState
import eu.akoos.photos.data.sharedbackup.SharedBackupScheduler
import eu.akoos.photos.data.sharedbackup.SharedBackupStatus
import eu.akoos.photos.data.sharedbackup.SharedBackupStore
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the shared backup's status line says. */
internal enum class SharedBackupLine { SYNCING, NEVER, LAST, FAILED }

/** The status line for a round [running] now and the record [status] of the last one. Pure, so it is unit-tested. */
internal fun sharedBackupLine(running: Boolean, status: SharedBackupStatus): SharedBackupLine = when {
    running -> SharedBackupLine.SYNCING
    status.lastAtMs == 0L -> SharedBackupLine.NEVER
    status.lastFailed -> SharedBackupLine.FAILED
    else -> SharedBackupLine.LAST
}

/** The Drive backup card's automatic switch, its status, and the Back up now and Load from Drive rows. */
@HiltViewModel
class SharedBackupViewModel @Inject constructor(
    private val store: SharedBackupStore,
    private val scheduler: SharedBackupScheduler,
    controller: MlSyncController,
) : ViewModel() {

    internal data class UiState(val status: SharedBackupStatus, val running: Boolean)

    internal val state: StateFlow<UiState> = combine(store.status, controller.state) { status, job ->
        UiState(status, job is MlSyncState.Running && job.operation == MlSyncOperation.ROUND)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(SharedBackupStatus(false, 0L, false), false))

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { scheduler.setEnabled(enabled) }
    }

    fun saveNow() {
        viewModelScope.launch { scheduler.saveNow() }
    }

    fun loadNow() {
        viewModelScope.launch { scheduler.loadNow() }
    }
}

/**
 * Backup to Proton Drive, on the second settings page: the one place the face and search data, the labels and the
 * hidden items are saved to Drive and loaded back. Automatic backup keeps them the same on every device; Back up now
 * saves at once, whether it is on or not, after taking in what the other devices saved; Load from Drive brings in
 * what the other devices saved. Under the card, the link for a face file an earlier version exported. The caller
 * shows it only to a signed-in account on a device the Drive client runs on.
 */
@Composable
internal fun SharedBackupCard(
    viewModel: SharedBackupViewModel = hiltViewModel(),
    fileImport: FaceFileImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Spacer(Modifier.height(20.dp))
    SectionLabel(stringResource(R.string.drive_backup_section))
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        ToggleRow(
            label = stringResource(R.string.drive_backup_auto),
            description = stringResource(R.string.drive_backup_auto_desc),
            checked = state.status.enabled,
            onCheckedChange = viewModel::setEnabled,
        )
        RowDivider()
        run {
            val line = when (sharedBackupLine(state.running, state.status)) {
                SharedBackupLine.SYNCING -> stringResource(R.string.shared_backup_syncing)
                SharedBackupLine.NEVER -> stringResource(R.string.shared_backup_never)
                SharedBackupLine.FAILED -> stringResource(R.string.shared_backup_failed)
                SharedBackupLine.LAST -> stringResource(
                    R.string.shared_backup_last,
                    DateUtils.formatDateTime(
                        context,
                        state.status.lastAtMs,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    ),
                )
            }
            ActionRow(
                label = stringResource(R.string.drive_backup_save_now),
                description = line,
                icon = Icons.Default.CloudUpload,
                onClick = viewModel::saveNow,
                enabled = !state.running,
            )
        }
        RowDivider()
        ActionRow(
            label = stringResource(R.string.drive_backup_load),
            description = stringResource(R.string.drive_backup_load_desc),
            icon = Icons.Default.CloudDownload,
            onClick = viewModel::loadNow,
            enabled = !state.running,
        )
    }

    // A face file saved by an earlier version's export, which predates saving to Drive. A link under the card, so it
    // never reads as another way to back up; the dialog says what file it takes.
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
            .clickable(enabled = !state.running && !importing) { explainFileImport = true }
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
    LaunchedEffect(fileImportMessage) {
        fileImportMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            fileImport.clearMessage()
        }
    }
}
