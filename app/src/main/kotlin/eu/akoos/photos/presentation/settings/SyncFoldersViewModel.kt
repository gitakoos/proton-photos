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

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Load persisted selection + manual folder names before observing media so the
            // first emission already carries them.
            val initial = context.settingsDataStore.data.first()
            selectedNames = initial[SettingsKeys.SYNC_FOLDER_NAMES]
            manualNames = initial[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES] ?: emptySet()

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
                        )
                    }
                val populatedNames = populated.map { it.name }.toSet()

                // Surface previously-selected folders that have no photos right now (deleted
                // contents, or reserved for future captures) so the tick stays visible.
                val emptySelected = (selectedNames ?: emptySet())
                    .filter { it.isNotBlank() && it !in populatedNames && it !in manualNames }
                    .map { name ->
                        SyncFolder(name, coverUri = null, itemCount = 0, isSelected = true)
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
     */
    fun addManualFolder(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        if (manualNames.contains(name)) return
        val updated = manualNames + name
        manualNames = updated
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES] = updated }
        }
        // Re-emit so the UI shows the placeholder without waiting for the next media flow tick.
        _uiState.update { s ->
            if (s.folders.any { it.name == name }) s
            else s.copy(folders = s.folders + SyncFolder(name, null, 0, isSelected = false))
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
        }
    }
}
