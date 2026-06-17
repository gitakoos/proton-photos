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
import eu.akoos.photos.R
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
 * ViewModel for the "Mirror folders as cloud albums" screen — the carve-out picker that
 * lets users decide which device folders should also exist as Drive albums in addition
 * to being backed up. Writes to [SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES]; the upload
 * pipeline reads this set to gate album creation per bucket.
 *
 * Two visual groups feed the UI:
 *  - **Device folders** — populated MediaStore buckets, surfaced live from
 *    [LocalMediaRepository.observeLocalMedia].
 *  - **Custom folders** — opt-in names the user typed in manually that don't (yet) match
 *    a populated bucket. We don't persist a separate "custom names" key; anything left in
 *    [SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] that doesn't appear as a real bucket counts
 *    as custom. Toggling a custom entry off removes it from the opt-in set entirely
 *    (otherwise the row would vanish but the persisted name would linger).
 */
@HiltViewModel
class AlbumMirrorFoldersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
) : ViewModel() {

    private var optInNames: Set<String> = emptySet()

    private val _uiState = MutableStateFlow(AlbumMirrorFoldersUiState())
    val uiState: StateFlow<AlbumMirrorFoldersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed opt-in set BEFORE the first media emission so the first UI tick already
            // reflects the persisted selection.
            optInNames = context.settingsDataStore.data.first()[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES]
                ?: emptySet()

            localMediaRepo.observeLocalMedia().collectLatest { items ->
                val deviceBuckets = items
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, groupItems) ->
                        AlbumMirrorFolder(
                            name = name,
                            isOptedIn = name in optInNames,
                            itemCount = groupItems.size,
                        )
                    }
                    .sortedByDescending { it.itemCount ?: 0 }
                val deviceNames = deviceBuckets.map { it.name }.toSet()

                // Anything left in the opt-in set that isn't a real bucket counts as "custom".
                // Empty / blank entries are filtered out (defensive — they should never be
                // persisted, but stale installs may carry them).
                val customEntries = optInNames
                    .filter { it.isNotBlank() && it !in deviceNames }
                    .map { name -> AlbumMirrorFolder(name = name, isOptedIn = true, itemCount = null) }
                    .sortedBy { it.name.lowercase() }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        deviceFolders = deviceBuckets,
                        customFolders = customEntries,
                    )
                }
            }
        }
    }

    fun toggle(folderName: String) {
        val newSet = if (folderName in optInNames) optInNames - folderName else optInNames + folderName
        optInNames = newSet
        _uiState.update { s ->
            val updatedDevice = s.deviceFolders.map { f -> f.copy(isOptedIn = f.name in newSet) }
            // Custom rows that just got toggled OFF need to disappear from the UI — a custom
            // entry only exists by virtue of being in the opt-in set, so an unticked custom
            // row has no reason to stick around. Real device buckets stay even when off.
            val updatedCustom = s.customFolders
                .filter { it.name in newSet }
                .map { f -> f.copy(isOptedIn = true) }
            s.copy(deviceFolders = updatedDevice, customFolders = updatedCustom)
        }
        persist(newSet)
    }

    fun removeCustomFolder(folderName: String) {
        if (folderName !in optInNames) return
        val newSet = optInNames - folderName
        optInNames = newSet
        _uiState.update { s ->
            s.copy(customFolders = s.customFolders.filter { it.name != folderName })
        }
        persist(newSet)
    }

    fun openAddDialog() {
        _uiState.update { it.copy(isAddDialogOpen = true, addDialogText = "", addDialogError = null) }
    }

    fun closeAddDialog() {
        _uiState.update { it.copy(isAddDialogOpen = false, addDialogText = "", addDialogError = null) }
    }

    fun updateAddDialogText(value: String) {
        // Clear any prior validation error the moment the user edits the field — same UX
        // convention as other settings dialogs in the app.
        _uiState.update { it.copy(addDialogText = value, addDialogError = null) }
    }

    fun submitCustomFolder() {
        val raw = _uiState.value.addDialogText.trim()
        when {
            raw.isEmpty() -> {
                _uiState.update { it.copy(addDialogError = context.getString(R.string.mirror_name_empty)) }
                return
            }
            raw.contains('/') || raw.contains('=') -> {
                // '/' would collide with path separators in any future on-disk lookup, '=' is
                // reserved by DataStore-encoded mappings elsewhere in the app (see
                // ALBUM_BUCKET_MAP entries which use `bucketName=albumLinkId`).
                _uiState.update { it.copy(addDialogError = context.getString(R.string.mirror_name_invalid_chars)) }
                return
            }
            raw in optInNames -> {
                _uiState.update { it.copy(addDialogError = context.getString(R.string.mirror_name_already_added)) }
                return
            }
        }
        val newSet = optInNames + raw
        optInNames = newSet
        _uiState.update { s ->
            val alreadyDevice = s.deviceFolders.any { it.name == raw }
            val updatedDevice = if (alreadyDevice) {
                // Edge case: user typed the exact name of a device bucket. Toggle it on
                // there instead of duplicating in the custom list.
                s.deviceFolders.map { f -> if (f.name == raw) f.copy(isOptedIn = true) else f }
            } else {
                s.deviceFolders
            }
            val updatedCustom = if (alreadyDevice) {
                s.customFolders
            } else {
                (s.customFolders + AlbumMirrorFolder(name = raw, isOptedIn = true, itemCount = null))
                    .sortedBy { it.name.lowercase() }
            }
            s.copy(
                deviceFolders = updatedDevice,
                customFolders = updatedCustom,
                isAddDialogOpen = false,
                addDialogText = "",
                addDialogError = null,
            )
        }
        persist(newSet)
    }

    private fun persist(names: Set<String>) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = names }
        }
    }
}

data class AlbumMirrorFoldersUiState(
    val isLoading: Boolean = true,
    val deviceFolders: List<AlbumMirrorFolder> = emptyList(),
    val customFolders: List<AlbumMirrorFolder> = emptyList(),
    val isAddDialogOpen: Boolean = false,
    val addDialogText: String = "",
    val addDialogError: String? = null,
)

data class AlbumMirrorFolder(
    val name: String,
    val isOptedIn: Boolean,
    val itemCount: Int? = null,
)
