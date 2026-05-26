package me.proton.photos.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
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
import me.proton.photos.domain.entity.LocalAlbum
import me.proton.photos.domain.repository.LocalMediaRepository
import javax.inject.Inject

data class WidgetConfigUiState(
    val mode: WidgetMode         = WidgetMode.ALL_PHOTOS,
    val interval: WidgetInterval = WidgetInterval.ONE_HOUR,
    val selectedUris: List<String> = emptyList(),
    val selectedAlbum: String?   = null,
    val albums: List<LocalAlbum> = emptyList(),
    val isSaving: Boolean        = false,
    val saved: Boolean           = false,
)

@HiltViewModel
class PhotoWidgetConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetConfigUiState())
    val state: StateFlow<WidgetConfigUiState> = _state.asStateFlow()

    init {
        observeAlbums()
    }

    private fun observeAlbums() {
        viewModelScope.launch {
            localMediaRepo.observeLocalMedia().collectLatest { items ->
                val albums = items
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, grouped) ->
                        val sorted = grouped.sortedByDescending { it.dateTaken }
                        LocalAlbum(
                            name      = name,
                            coverUri  = sorted.firstOrNull()?.uri,
                            itemCount = sorted.size,
                            items     = sorted,
                        )
                    }
                    .sortedByDescending { it.items.firstOrNull()?.dateTaken ?: 0L }
                _state.update { it.copy(albums = albums) }
            }
        }
    }

    fun setMode(mode: WidgetMode) = _state.update { it.copy(mode = mode) }

    fun setInterval(interval: WidgetInterval) = _state.update { it.copy(interval = interval) }

    fun setSelectedUris(uris: List<String>) = _state.update { it.copy(selectedUris = uris) }

    fun setAlbum(albumName: String) = _state.update { it.copy(selectedAlbum = albumName) }

    /**
     * Persist widget state to Glance DataStore, schedule workers, and signal "done".
     */
    fun save(appWidgetId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                val s       = _state.value
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

                // Write all config into Glance PreferencesGlanceStateDefinition
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                    p.toMutablePreferences().also { mp ->
                        mp[PhotoWidgetKeys.MODE]             = s.mode.name
                        mp[PhotoWidgetKeys.SELECTED_URIS]    = s.selectedUris
                            .joinToString(PhotoWidgetKeys.URI_SEPARATOR)
                        mp[PhotoWidgetKeys.ALBUM_NAME]       = s.selectedAlbum ?: ""
                        mp[PhotoWidgetKeys.INTERVAL_MINUTES] = s.interval.minutes
                        mp[PhotoWidgetKeys.CURRENT_INDEX]    = 0
                    }
                }

                // Fire an immediate first update so the widget shows a photo right away
                PhotoWidgetUpdateWorker.enqueueImmediate(context, appWidgetId)
                // Schedule the recurring interval update
                PhotoWidgetUpdateWorker.enqueueOrReplace(context, appWidgetId, s.interval.minutes)

                _state.update { it.copy(isSaving = false, saved = true) }
            } catch (_: Exception) {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}
