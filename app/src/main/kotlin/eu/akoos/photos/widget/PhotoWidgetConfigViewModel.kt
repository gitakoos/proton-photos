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

package eu.akoos.photos.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
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
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.domain.entity.LocalAlbum
import eu.akoos.photos.domain.repository.LocalMediaRepository
import javax.inject.Inject

data class WidgetConfigUiState(
    val mode: WidgetMode         = WidgetMode.ALL_PHOTOS,
    val interval: WidgetInterval = WidgetInterval.ONE_HOUR,
    val selectedUris: List<String> = emptyList(),
    val selectedAlbum: String?   = null,
    val albums: List<LocalAlbum> = emptyList(),
    /**
     * Pool of cloud photos available for [WidgetMode.CLOUD_SELECTED]. Each entry
     * is a Row from [PhotoListingDao], including the (possibly null) decrypted
     * thumbnailUrl — null means the gallery cell has not yet been viewed so the
     * thumbnail is not in the app cache. The widget worker will request it lazily.
     */
    val cloudPhotos: List<PhotoListingEntity> = emptyList(),
    val selectedLinkIds: List<String> = emptyList(),
    val isSaving: Boolean        = false,
    val saved: Boolean           = false,
)

@HiltViewModel
class PhotoWidgetConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
    private val photoListingDao: PhotoListingDao,
    private val accountManager: AccountManager,
    private val thumbnailScheduler: ThumbnailDecryptScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetConfigUiState())
    val state: StateFlow<WidgetConfigUiState> = _state.asStateFlow()

    /** Tracks whether [loadFor] has already populated state from Glance, so we don't
     *  overwrite the user's in-progress edits when the screen recomposes. */
    private var hydrated = false

    init {
        observeAlbums()
        observeCloudPhotos()
    }

    /**
     * Pre-fill the form from the widget's existing Glance state. Idempotent — calling
     * twice for the same widget id is a no-op. Lets the user edit a placed widget's
     * config without having to remove + re-add it.
     *
     * Silently no-ops on first-config (no state yet) — the defaults from
     * [WidgetConfigUiState] keep the form in its blank state.
     */
    fun loadFor(appWidgetId: Int) {
        if (hydrated) return
        hydrated = true
        viewModelScope.launch {
            val glanceId = runCatching {
                GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            }.getOrNull() ?: return@launch
            val prefs = runCatching {
                getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            }.getOrNull() ?: return@launch

            val modeName = prefs[PhotoWidgetKeys.MODE]
            val mode = if (modeName != null) {
                runCatching { WidgetMode.valueOf(modeName) }.getOrDefault(WidgetMode.ALL_PHOTOS)
            } else WidgetMode.ALL_PHOTOS

            val urisRaw = prefs[PhotoWidgetKeys.SELECTED_URIS] ?: ""
            val uris = if (urisRaw.isBlank()) emptyList()
                else urisRaw.split(PhotoWidgetKeys.URI_SEPARATOR).filter { it.isNotBlank() }
            val linkIdsRaw = prefs[PhotoWidgetKeys.SELECTED_LINK_IDS] ?: ""
            val linkIds = if (linkIdsRaw.isBlank()) emptyList()
                else linkIdsRaw.split(PhotoWidgetKeys.URI_SEPARATOR).filter { it.isNotBlank() }
            val album = prefs[PhotoWidgetKeys.ALBUM_NAME]?.takeIf { it.isNotBlank() }
            val intervalMin = prefs[PhotoWidgetKeys.INTERVAL_MINUTES]
            val interval = WidgetInterval.entries.firstOrNull { it.minutes == intervalMin }
                ?: WidgetInterval.ONE_HOUR

            _state.update {
                it.copy(
                    mode = mode,
                    interval = interval,
                    selectedUris = uris,
                    selectedLinkIds = linkIds,
                    selectedAlbum = album,
                )
            }
        }
    }

    /**
     * Stream the user's cloud photo listing into [WidgetConfigUiState.cloudPhotos].
     * Sorted newest-first by captureTime so the picker shows recent shots at the
     * top. The list includes photos whose thumbnail has not been decrypted yet
     * (thumbnailUrl == null) — the picker UI can either request a decrypt on
     * scroll or show a placeholder; the widget worker handles the lazy decrypt
     * on its own when the widget cycles to a not-yet-materialised entry.
     */
    private fun observeCloudPhotos() {
        viewModelScope.launch {
            val userId: UserId = accountManager.getPrimaryUserId().first() ?: return@launch
            photoListingDao.observeAll(userId.id).collectLatest { rows ->
                val sorted = rows.sortedByDescending { it.captureTime ?: 0L }
                _state.update { it.copy(cloudPhotos = sorted) }
            }
        }
    }

    /** Push a thumbnail decrypt request through the scheduler so the picker can
     *  render the cell as soon as the bytes land in the on-disk cache. */
    fun requestCloudThumbnailDecrypt(photo: PhotoListingEntity) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            thumbnailScheduler.request(userId, photo)
        }
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

    fun setSelectedLinkIds(linkIds: List<String>) = _state.update { it.copy(selectedLinkIds = linkIds) }

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
                        mp[PhotoWidgetKeys.SELECTED_LINK_IDS] = s.selectedLinkIds
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
