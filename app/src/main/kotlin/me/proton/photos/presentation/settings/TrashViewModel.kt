package me.proton.photos.presentation.settings

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.domain.repository.LocalMediaRepository
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
) : ViewModel() {

    data class UiState(
        val deviceItems: List<LocalMediaItem> = emptyList(),
        val deviceLoading: Boolean = true,
        val apiUnsupported: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        val selectedDeviceUris: Set<String> = emptySet(),
    ) {
        val deviceSelectedCount: Int get() = selectedDeviceUris.size
        val isDeviceSelectionMode: Boolean get() = selectedDeviceUris.isNotEmpty()
        val deviceAllSelected: Boolean get() =
            deviceItems.isNotEmpty() && deviceItems.all { it.uri in selectedDeviceUris }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadDeviceTrash()
    }

    // ── Device trash ────────────────────────────────────────────────────────────

    private fun loadDeviceTrash() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModelScope.launch {
                localMediaRepo.observeTrashedMedia().collectLatest { items ->
                    _uiState.update { it.copy(deviceItems = items, deviceLoading = false) }
                }
            }
        } else {
            _uiState.update { it.copy(deviceLoading = false) }
        }
    }

    fun toggleDeviceSelection(uri: String) {
        _uiState.update { state ->
            val new = if (uri in state.selectedDeviceUris) state.selectedDeviceUris - uri
                      else state.selectedDeviceUris + uri
            state.copy(selectedDeviceUris = new)
        }
    }

    fun selectAllDevice() {
        _uiState.update { state ->
            state.copy(selectedDeviceUris = state.deviceItems.map { it.uri }.toSet())
        }
    }

    fun clearDeviceSelection() {
        _uiState.update { it.copy(selectedDeviceUris = emptySet()) }
    }

    fun buildRestoreDeviceIntent(): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = _uiState.value.selectedDeviceUris
            .ifEmpty { _uiState.value.deviceItems.map { it.uri }.toSet() }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return null
        return MediaStore.createTrashRequest(context.contentResolver, uris, false)
    }

    fun buildDeleteDeviceForeverIntent(): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = _uiState.value.selectedDeviceUris
            .ifEmpty { _uiState.value.deviceItems.map { it.uri }.toSet() }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    fun onDeviceActionCompleted() {
        _uiState.update { it.copy(selectedDeviceUris = emptySet()) }
    }
}
