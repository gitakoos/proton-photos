/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
import eu.akoos.photos.domain.repository.LocalMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the "Excluded folders" screen — the carve-out picker that lets users
 * keep specific MediaStore buckets out of [SettingsKeys.BACKUP_EVERYTHING].
 *
 * Mirrors [SyncFoldersViewModel] structurally but writes to
 * [SettingsKeys.EXCLUDED_FOLDER_NAMES] and inverts the semantic: a "selected"
 * (checked) row here means the folder is **excluded** from backup, the opposite
 * of SyncFoldersScreen where selected = included. The two screens never coexist
 * (excluded is only visible while backup-everything is ON; sync-folders only
 * while backup-everything is OFF), so the inverted meaning isn't a UX hazard.
 *
 * No "Add folder by name" affordance — only existing MediaStore buckets can be
 * excluded. You can't pre-emptively exclude a folder that doesn't exist yet.
 */
@HiltViewModel
class ExcludedFoldersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
) : ViewModel() {

    data class ExcludedFolder(
        val name: String,
        val coverUri: String?,
        val itemCount: Int,
        /** True = excluded from backup. False = included (default). */
        val isExcluded: Boolean,
    )

    data class UiState(
        val folders: List<ExcludedFolder> = emptyList(),
        val isLoading: Boolean = true,
    ) {
        val excludedCount: Int get() = folders.count { it.isExcluded }
        val allExcluded: Boolean get() = folders.isNotEmpty() && folders.all { it.isExcluded }
    }

    private var excludedNames: Set<String> = emptySet()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed excluded set BEFORE the first media emission so the initial UI
            // already carries the persisted exclusions.
            excludedNames = context.settingsDataStore.data.first()[SettingsKeys.EXCLUDED_FOLDER_NAMES]
                ?: emptySet()

            localMediaRepo.observeLocalMedia().collectLatest { items ->
                val populated = items
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, groupItems) ->
                        val sorted = groupItems.sortedByDescending { it.dateTaken }
                        ExcludedFolder(
                            name       = name,
                            coverUri   = sorted.firstOrNull()?.uri,
                            itemCount  = sorted.size,
                            isExcluded = name in excludedNames,
                        )
                    }
                val populatedNames = populated.map { it.name }.toSet()

                // Surface previously-excluded folders that have no photos right now (deleted
                // contents, or buckets that vanished) so the user can still un-exclude them.
                val emptyExcluded = excludedNames
                    .filter { it.isNotBlank() && it !in populatedNames }
                    .map { name ->
                        ExcludedFolder(name, coverUri = null, itemCount = 0, isExcluded = true)
                    }
                    .sortedBy { it.name.lowercase() }

                _uiState.value = UiState(
                    folders = populated.sortedByDescending { it.itemCount } + emptyExcluded,
                    isLoading = false,
                )
            }
        }
    }

    fun toggle(folderName: String) {
        val newExcluded = if (folderName in excludedNames)
            excludedNames - folderName
        else
            excludedNames + folderName

        excludedNames = newExcluded
        _uiState.update { s ->
            s.copy(folders = s.folders.map { f -> f.copy(isExcluded = f.name in newExcluded) })
        }
        persist(newExcluded)
    }

    fun excludeAll() {
        val all = _uiState.value.folders.map { it.name }.toSet()
        excludedNames = all
        _uiState.update { s -> s.copy(folders = s.folders.map { it.copy(isExcluded = true) }) }
        persist(all)
    }

    fun includeAll() {
        excludedNames = emptySet()
        _uiState.update { s -> s.copy(folders = s.folders.map { it.copy(isExcluded = false) }) }
        persist(emptySet())
    }

    private fun persist(names: Set<String>) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.EXCLUDED_FOLDER_NAMES] = names }
            // Kick a fresh reconcile so any LOCAL_ONLY rows that just landed in the
            // excluded set are dropped from `pending` immediately — otherwise an
            // in-flight SyncWorker (or one that starts between toggle and the next
            // periodic fire) would still upload them. Reads BACKUP_EVERYTHING +
            // SYNC_WIFI_ONLY itself, so the runNow call only needs the wifi flag.
            val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
            eu.akoos.photos.worker.SyncWorker.runNow(context, wifiOnly)
        }
    }
}
