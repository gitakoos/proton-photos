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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import eu.akoos.photos.presentation.settings.components.ActionRow
import eu.akoos.photos.presentation.settings.components.RowDivider
import eu.akoos.photos.presentation.settings.components.SettingsCard
import eu.akoos.photos.presentation.settings.components.ToggleRow
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

/** The shared backup's switch, its status and its Sync now row. */
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

    fun syncNow() {
        viewModelScope.launch { scheduler.syncNow(userAsked = true) }
    }
}

/**
 * The shared backup on Proton Drive, on the second settings page: one switch, and while it is on the Sync now row with
 * how the last round went beneath it. The caller shows it only to a signed-in account on a device the Drive client
 * runs on.
 */
@Composable
internal fun SharedBackupCard(viewModel: SharedBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Spacer(Modifier.height(12.dp))
    SettingsCard {
        ToggleRow(
            label = stringResource(R.string.shared_backup_title),
            description = stringResource(R.string.shared_backup_desc),
            checked = state.status.enabled,
            onCheckedChange = viewModel::setEnabled,
        )
        if (state.status.enabled) {
            RowDivider()
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
                label = stringResource(R.string.shared_backup_sync_now),
                description = line,
                icon = Icons.Default.Sync,
                onClick = viewModel::syncNow,
                enabled = !state.running,
            )
        }
    }
}
