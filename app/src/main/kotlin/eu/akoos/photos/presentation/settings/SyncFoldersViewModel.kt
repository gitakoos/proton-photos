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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

@HiltViewModel
class SyncFoldersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
) : ViewModel() {

    data class SyncFolder(
        val name: String,
        val coverUri: String?,
        val itemCount: Int,
        val isSelected: Boolean,
        val isMirrored: Boolean = false,
        /** True for a folder the user typed in by hand that has no real bucket yet — removable. */
        val isManual: Boolean = false,
    )

    data class UiState(
        val folders: List<SyncFolder> = emptyList(),
        val isLoading: Boolean = true,
    ) {
        val selectedCount: Int  get() = folders.count { it.isSelected }
        val allSelected:   Boolean get() = folders.isNotEmpty() && folders.all { it.isSelected }
    }

    /** null means "not yet configured" (first-run default = backup nothing). */
    private var selectedNames: Set<String>? = null
    private var manualNames: Set<String> = emptySet()
    // Folders opted in to also surface as a Drive album — the second per-folder checkbox. Same
    // ALBUM_OPT_IN_FOLDER_NAMES set the standalone mirror screen and the upload pipeline read.
    private var mirroredNames: Set<String> = emptySet()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Load persisted selection + manual folder names before observing media so the
            // first emission already carries them.
            val initial = context.settingsDataStore.data.first()
            selectedNames = initial[SettingsKeys.SYNC_FOLDER_NAMES]
            manualNames = initial[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES] ?: emptySet()
            mirroredNames = initial[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] ?: emptySet()

            localMediaRepo.observeLocalMedia().collectLatest { items ->
                // Buckets we actually find media in.
                val populated = items
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, groupItems) ->
                        val sorted = groupItems.sortedByDescending { it.dateTaken }
                        SyncFolder(
                            name       = name,
                            coverUri   = sorted.firstOrNull()?.uri,
                            itemCount  = sorted.size,
                            isSelected = selectedNames != null && name in selectedNames!!,
                            isMirrored = name in mirroredNames,
                        )
                    }
                val populatedNames = populated.map { it.name }.toSet()

                // Surface previously-selected folders that have no photos right now (deleted
                // contents, or reserved for future captures) so the tick stays visible.
                val emptySelected = (selectedNames ?: emptySet())
                    .filter { it.isNotBlank() && it !in populatedNames && it !in manualNames }
                    .map { name ->
                        SyncFolder(name, coverUri = null, itemCount = 0, isSelected = true, isMirrored = name in mirroredNames)
                    }

                // Surface user-declared placeholder folders. They show as "empty" until photos
                // arrive in a matching bucket, then they merge with the populated list and we
                // drop them from MANUAL_LOCAL_FOLDER_NAMES (handled below in `cleanupMerged`).
                val manualPlaceholders = manualNames
                    .filter { it.isNotBlank() && it !in populatedNames }
                    .map { name ->
                        SyncFolder(
                            name = name,
                            coverUri = null,
                            itemCount = 0,
                            isSelected = selectedNames != null && name in selectedNames!!,
                            isMirrored = name in mirroredNames,
                            isManual = true,
                        )
                    }

                // Drop manual entries that now have a real bucket — the populated row replaces them.
                if (manualNames.any { it in populatedNames }) {
                    val cleaned = manualNames - populatedNames
                    if (cleaned != manualNames) {
                        manualNames = cleaned
                        context.settingsDataStore.edit { it[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES] = cleaned }
                    }
                }

                // Order: populated by item count desc, then empty/manual placeholders alphabetically.
                val placeholders = (emptySelected + manualPlaceholders)
                    .distinctBy { it.name }
                    .sortedBy { it.name.lowercase() }
                val buckets = populated.sortedByDescending { it.itemCount } + placeholders

                _uiState.value = UiState(folders = buckets, isLoading = false)
            }
        }
    }

    /**
     * Lets the user pre-declare a folder name that doesn't yet exist as a populated MediaStore
     * bucket. The folder shows up immediately with "0 items"; once a matching bucket appears
     * (a photo is saved there), it's automatically merged into the populated list.
     *
     * Returns false when the name is rejected (blank or already added) so the dialog can keep
     * itself open and surface an error; true once the folder is added.
     */
    fun addManualFolder(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) return false
        if (manualNames.contains(name)) return false
        val updated = manualNames + name
        manualNames = updated
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES] = updated }
        }
        // Re-emit so the UI shows the placeholder without waiting for the next media flow tick.
        _uiState.update { s ->
            if (s.folders.any { it.name == name }) s
            else s.copy(folders = s.folders + SyncFolder(name, null, 0, isSelected = false, isManual = true))
        }
        return true
    }

    /** Delete a hand-added placeholder folder from the watch list entirely — drops it from the
     *  manual set and from the backup / album opt-ins so it is no longer tracked. */
    fun removeManualFolder(folderName: String) {
        if (folderName !in manualNames) return
        manualNames = manualNames - folderName
        selectedNames = (selectedNames ?: emptySet()) - folderName
        mirroredNames = mirroredNames - folderName
        _uiState.update { s -> s.copy(folders = s.folders.filter { it.name != folderName }) }
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES] = manualNames
                prefs[SettingsKeys.SYNC_FOLDER_NAMES] = selectedNames ?: emptySet()
                prefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = mirroredNames
            }
            SyncWorker.reconcileBackgroundWork(context)
        }
    }

    fun toggle(folderName: String) {
        val currentEffective  = selectedNames ?: emptySet()
        val newSelected       = if (folderName in currentEffective)
            currentEffective - folderName
        else
            currentEffective + folderName

        selectedNames = newSelected
        _uiState.update { s ->
            s.copy(folders = s.folders.map { f -> f.copy(isSelected = f.name in newSelected) })
        }
        persistSelection(newSelected)
    }

    /** Opt a folder in/out of also surfacing as a Drive album when its photos upload — the second
     *  per-folder checkbox, writing the same set the standalone mirror screen used. */
    fun toggleMirror(folderName: String) {
        val next = if (folderName in mirroredNames) mirroredNames - folderName
                   else mirroredNames + folderName
        mirroredNames = next
        _uiState.update { s ->
            s.copy(folders = s.folders.map { f -> f.copy(isMirrored = f.name in next) })
        }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] = next }
        }
    }

    fun selectAll() {
        val allNames = _uiState.value.folders.map { it.name }.toSet()
        selectedNames = allNames
        _uiState.update { s -> s.copy(folders = s.folders.map { it.copy(isSelected = true) }) }
        persistSelection(allNames)
    }

    fun deselectAll() {
        selectedNames = emptySet()
        _uiState.update { s -> s.copy(folders = s.folders.map { it.copy(isSelected = false) }) }
        persistSelection(emptySet())
    }

    private fun persistSelection(names: Set<String>) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.SYNC_FOLDER_NAMES] = names }
            // Arming the background triggers is gated on a folder actually being selected, so the
            // selection changing here is exactly when that gate flips: picking the first folder must
            // re-arm everything, deselecting the last must tear it all down. reconcile reads the
            // just-written set and does whichever applies.
            SyncWorker.reconcileBackgroundWork(context)
        }
    }
}
