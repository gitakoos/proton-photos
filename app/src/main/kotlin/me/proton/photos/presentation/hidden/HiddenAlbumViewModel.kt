package me.proton.photos.presentation.hidden

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.photos.data.hidden.HiddenStorageManager
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.entity.LocalMediaItem
import me.proton.photos.domain.repository.LocalMediaRepository
import javax.inject.Inject

data class HiddenAlbumUiState(
    val isAuthenticated: Boolean = false,
    val items: List<LocalMediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class HiddenAlbumViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val localMediaRepo: LocalMediaRepository,
    private val hiddenStorage: HiddenStorageManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HiddenAlbumUiState())
    val uiState: StateFlow<HiddenAlbumUiState> = _uiState.asStateFlow()

    fun onAuthenticationSuccess() {
        _uiState.value = _uiState.value.copy(isAuthenticated = true)
        loadHiddenPhotos()
    }

    fun onAuthenticationFailed() {
        _uiState.value = _uiState.value.copy(isAuthenticated = false)
    }

    private fun loadHiddenPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val hiddenUris = context.settingsDataStore.data
                    .map { it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet() }
                    .first()
                val items = hiddenUris.mapNotNull { uri ->
                    localMediaRepo.queryByUri(uri)
                }
                _uiState.value = _uiState.value.copy(items = items, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    fun hidePhoto(uri: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + uri
            }
            // Reload
            loadHiddenPhotos()
        }
    }

    fun unhidePhoto(uri: String) {
        viewModelScope.launch {
            // If [uri] is an app-private hidden file, restore it to MediaStore so other gallery
            // apps can see it again, then drop it from the hidden set.
            if (hiddenStorage.isHiddenUri(uri)) {
                val displayName = _uiState.value.items.firstOrNull { it.uri == uri }?.displayName
                withContext(Dispatchers.IO) { hiddenStorage.restore(uri, displayName) }
            }
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - uri
            }
            _uiState.value = _uiState.value.copy(
                items = _uiState.value.items.filter { it.uri != uri }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
