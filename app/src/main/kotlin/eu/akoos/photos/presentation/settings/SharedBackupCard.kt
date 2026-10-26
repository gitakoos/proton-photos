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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.akoos.photos.R
import eu.akoos.photos.data.mlsync.MlDriveStore
import eu.akoos.photos.data.mlsync.MlSyncController
import eu.akoos.photos.data.mlsync.MlSyncOperation
import eu.akoos.photos.data.mlsync.MlSyncState
import eu.akoos.photos.data.sharedbackup.DriveDeviceCopies
import eu.akoos.photos.data.sharedbackup.SharedBackupRunner
import eu.akoos.photos.data.sharedbackup.SharedBackupScheduler
import eu.akoos.photos.data.sharedbackup.SharedBackupStatus
import eu.akoos.photos.data.sharedbackup.SharedBackupStore
import eu.akoos.photos.presentation.common.AppModalSheet
import eu.akoos.photos.presentation.common.ConfirmDialog
import eu.akoos.photos.presentation.lock.LocalAppLock
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SectionLabel
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.SheetBg
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import me.proton.core.accountmanager.domain.AccountManager

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
    private val runner: SharedBackupRunner,
    private val driveStore: MlDriveStore,
    private val accountManager: AccountManager,
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

    /** The list of the backups on Drive while its sheet is open: null closed. */
    internal sealed interface Copies {
        data object Loading : Copies
        data object Failed : Copies
        data class Loaded(val ownDeviceId: String?, val devices: List<DriveDeviceCopies>) : Copies
    }

    private val _copies = MutableStateFlow<Copies?>(null)
    internal val copies: StateFlow<Copies?> = _copies.asStateFlow()

    private val _trashFailed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    internal val trashFailed: SharedFlow<Unit> = _trashFailed.asSharedFlow()

    fun openCopies() {
        _copies.value = Copies.Loading
        viewModelScope.launch { _copies.value = readCopies() }
    }

    fun closeCopies() {
        _copies.value = null
    }

    /**
     * Moves [device]'s copies to the Drive trash. A trashed device leaves the list at once: the Drive client lists a
     * folder from its cache for a while, so reading the list again right away could still show it. A trash that
     * failed reads the list again, to show what is still there.
     */
    fun trash(device: DriveDeviceCopies) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId != null && runner.trash(userId, device)) {
                (_copies.value as? Copies.Loaded)?.let { loaded ->
                    _copies.value = loaded.copy(devices = loaded.devices.filterNot { it.deviceId == device.deviceId })
                }
            } else {
                _trashFailed.tryEmit(Unit)
                if (_copies.value != null) _copies.value = readCopies()
            }
        }
    }

    private suspend fun readCopies(): Copies {
        val userId = accountManager.getPrimaryUserId().first() ?: return Copies.Failed
        val own = runCatching { driveStore.deviceId() }.getOrNull()
        return runner.devices(userId)?.let { Copies.Loaded(own, it) } ?: Copies.Failed
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
        RowDivider()
        ActionRow(
            label = stringResource(R.string.drive_copies_row),
            description = stringResource(R.string.drive_copies_row_desc),
            icon = Icons.Default.Devices,
            onClick = viewModel::openCopies,
        )
    }

    val copies by viewModel.copies.collectAsStateWithLifecycle()
    copies?.let { DriveCopiesSheet(it, onDismiss = viewModel::closeCopies, onTrash = viewModel::trash) }
    val trashFailedText = stringResource(R.string.drive_copies_trash_failed)
    LaunchedEffect(Unit) {
        viewModel.trashFailed.collect {
            android.widget.Toast.makeText(context, trashFailedText, android.widget.Toast.LENGTH_LONG).show()
        }
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

/** A copy not updated for this long reads as left behind in the list. */
private const val STALE_COPY_MS = 30L * 24 * 60 * 60 * 1000

/**
 * The backups on Drive, one row per device: its name (or "This phone"), when it saved last, a note when that was
 * over 30 days ago, and for any other device a button that moves its copies to the Drive trash after a confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveCopiesSheet(
    copies: SharedBackupViewModel.Copies,
    onDismiss: () -> Unit,
    onTrash: (DriveDeviceCopies) -> Unit,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<DriveDeviceCopies?>(null) }
    AppModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.drive_copies_row),
                color = colors.fgPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            when (copies) {
                SharedBackupViewModel.Copies.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.drive_copies_loading), color = colors.fgMute, fontSize = 14.sp)
                }
                SharedBackupViewModel.Copies.Failed ->
                    Text(stringResource(R.string.drive_copies_failed), color = colors.fgMute, fontSize = 14.sp)
                is SharedBackupViewModel.Copies.Loaded -> if (copies.devices.isEmpty()) {
                    Text(stringResource(R.string.drive_copies_empty), color = colors.fgMute, fontSize = 14.sp)
                } else {
                    SettingsCard {
                        copies.devices.forEachIndexed { index, device ->
                            if (index > 0) RowDivider()
                            val own = device.deviceId == copies.ownDeviceId
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        when {
                                            own -> stringResource(R.string.drive_copies_this_device)
                                            device.name != null -> device.name
                                            else -> stringResource(R.string.drive_copies_other_device)
                                        },
                                        color = colors.fgPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.drive_copies_saved,
                                            DateUtils.formatDateTime(
                                                context,
                                                device.lastSavedMs,
                                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                                            ),
                                        ),
                                        color = colors.fgMute,
                                        fontSize = 12.5.sp,
                                    )
                                    if (System.currentTimeMillis() - device.lastSavedMs > STALE_COPY_MS) {
                                        Text(stringResource(R.string.drive_copies_stale), color = colors.errorColor, fontSize = 12.5.sp)
                                    }
                                }
                                if (!own) {
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { confirm = device }) {
                                        Text(
                                            stringResource(R.string.drive_copies_trash),
                                            color = colors.errorColor,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    confirm?.let { device ->
        val name = device.name ?: stringResource(R.string.drive_copies_other_device)
        ConfirmDialog(
            title = stringResource(R.string.drive_copies_trash_title),
            message = stringResource(R.string.drive_copies_trash_msg, name),
            confirmLabel = stringResource(R.string.drive_copies_trash),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirm = null
                onTrash(device)
            },
            onDismiss = { confirm = null },
            destructive = true,
        )
    }
}
