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

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.service.BackgroundSyncService
import eu.akoos.photos.worker.SyncWorker
import eu.akoos.photos.worker.UpdateCheckWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the "Notifications" screen — three switches backed by the NOTIFY_* keys in
 * [SettingsKeys]. Album-download and delete-reminder are opt-OUTS: absent reads as `true`
 * (shown), so a fresh install keeps the notifications it would post anyway. Backup-status is
 * the opposite, an opt-IN: absent reads as `false`, so that switch starts OFF.
 *
 * The backup-status switch carries extra weight: [SettingsKeys.NOTIFY_BACKUP_STATUS]
 * also decides whether the persistent [BackgroundSyncService] runs. Android refuses to
 * keep a foreground service alive without showing its notification, so opting out has to
 * stop the service rather than merely hide a banner. The handler defers to
 * [SyncWorker.reconcileBackgroundWork], the single arming/teardown point every site shares,
 * so the service only re-starts on enable when backup is effectively on.
 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        /** Persistent keep-alive "Photo backup" foreground-service notification. Opt-in, off by
         *  default — backups run on the content trigger + periodic worker without it. */
        val backupStatus: Boolean = false,
        /** Album download progress + completion notifications. */
        val albumDownload: Boolean = true,
        /** "Photos ready to remove" reminder after a delete-after-backup run. */
        val deleteReminder: Boolean = true,
        /** Periodic background release check plus the notification it posts. Opt-out, on by
         *  default: an app that is rarely opened is the one that needs telling. */
        val updateAvailable: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = context.settingsDataStore.data.first()
            _uiState.value = UiState(
                backupStatus = prefs[SettingsKeys.NOTIFY_BACKUP_STATUS] == true,
                albumDownload = prefs[SettingsKeys.NOTIFY_ALBUM_DOWNLOAD] != false,
                deleteReminder = prefs[SettingsKeys.NOTIFY_DELETE_REMINDER] != false,
                updateAvailable = prefs[SettingsKeys.UPDATE_BACKGROUND_CHECK] != false,
            )
        }
    }

    /**
     * Persist the backup-status opt-out AND bring the persistent service in line with it.
     * On enable we only restart the service when continuous backup is on (matching
     * [SettingsViewModel.setAutoSync]); on disable we always stop it so the ongoing
     * notification disappears immediately.
     */
    fun setBackupStatus(enabled: Boolean) {
        _uiState.update { it.copy(backupStatus = enabled) }
        viewModelScope.launch {
            try {
                context.settingsDataStore.edit { it[SettingsKeys.NOTIFY_BACKUP_STATUS] = enabled }
                // reconcile reads the just-written flag: with backup effectively on it starts the
                // keep-alive service on enable and stops it on disable, keeping the service in lock
                // step with the same opt-in gate every other arming site uses.
                SyncWorker.reconcileBackgroundWork(context)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun setAlbumDownload(enabled: Boolean) {
        _uiState.update { it.copy(albumDownload = enabled) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.NOTIFY_ALBUM_DOWNLOAD] = enabled }
        }
    }

    fun setDeleteReminder(enabled: Boolean) {
        _uiState.update { it.copy(deleteReminder = enabled) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.NOTIFY_DELETE_REMINDER] = enabled }
        }
    }

    /**
     * Persist the update-check opt-out AND bring the periodic work in line with it. Off cancels the
     * request outright rather than leaving it enqueued to wake up and no-op, so opting out stops
     * the wake-ups as well as the notification; on re-enqueues it. [UpdateCheckWorker.reconcile]
     * reads the just-written flag, so it is the same single arming point [App] uses at startup.
     */
    fun setUpdateAvailable(enabled: Boolean) {
        _uiState.update { it.copy(updateAvailable = enabled) }
        viewModelScope.launch {
            try {
                context.settingsDataStore.edit { it[SettingsKeys.UPDATE_BACKGROUND_CHECK] = enabled }
                UpdateCheckWorker.reconcile(context)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
