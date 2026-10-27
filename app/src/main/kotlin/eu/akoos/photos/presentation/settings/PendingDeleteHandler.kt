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
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.SyncStateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drains the upload pipeline's pending delete queue through a foreground OS **trash** consent
 * dialog. Free-up permanent delete uses a separate sink ([FreeUpPermanentDeleteHandler]) and must
 * never write [SettingsKeys.PENDING_DELETE_URIS] or [MediaStoreConsentMode.Trash].
 *
 * Mounted on the Gallery route so the system trash dialog does not appear over the editor, viewer,
 * or other authenticated screens. Growth-based re-prompt lives in
 * [MediaStoreConsentHandler] for [MediaStoreConsentMode.Trash].
 */
@HiltViewModel
class PendingDeleteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateRepo: SyncStateRepository,
) : ViewModel() {

    val pendingUris: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { it[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    fun onConsentGranted(uris: Set<String>) {
        viewModelScope.launch {
            runCatching {
                syncStateRepo.updateStatusAndDeleteLocal(uris.toList(), SyncStatus.CLOUD_ONLY)
            }
            context.settingsDataStore.edit { p ->
                val remaining = (p[SettingsKeys.PENDING_DELETE_URIS] ?: emptySet()) - uris
                p[SettingsKeys.PENDING_DELETE_URIS] = remaining
            }
        }
    }
}

@Composable
fun PendingDeleteHandler(
    viewModel: PendingDeleteViewModel = hiltViewModel(),
) {
    MediaStoreConsentHandler(
        pendingUris = viewModel.pendingUris,
        mode = MediaStoreConsentMode.Trash,
        onGranted = viewModel::onConsentGranted,
    )
}
