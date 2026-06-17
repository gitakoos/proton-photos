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
import eu.akoos.photos.domain.repository.DrivePhotoRepository
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
 * ViewModel for the "Folders in timeline" screen — the per-folder show/hide picker that
 * controls which MediaStore buckets appear in the main Photos timeline.
 *
 * Structurally mirrors [ExcludedFoldersViewModel] (existing buckets only, no add-by-name)
 * but writes to [SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES] with a purely display-only
 * meaning: a "selected" (checked) row hides that folder from every timeline tab, while the
 * folder's photos stay on the device, remain browsable, and keep backing up. Because it
 * never touches the backup model there is no reconcile to trigger on change — the gallery's
 * own DataStore observer re-filters the grid on the next emission.
 *
 * No "Add folder by name" affordance — only existing MediaStore buckets can be hidden.
 */
@HiltViewModel
class TimelineFilterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
    private val driveRepo: DrivePhotoRepository,
) : ViewModel() {

    data class TimelineFolder(
        val name: String,
        val coverUri: String?,
        val itemCount: Int,
        /** True = hidden from the timeline. False = shown (default). */
        val isExcluded: Boolean,
    )

    /** A cloud album the user can individually hide from the timeline. */
    data class TimelineAlbum(
        val linkId: String,
        val name: String,
        val coverUrl: String?,
        val itemCount: Int,
        /** True = this album's photos are hidden from the timeline. */
        val isExcluded: Boolean,
    )

    data class UiState(
        val folders: List<TimelineFolder> = emptyList(),
        val albums: List<TimelineAlbum> = emptyList(),
        val isLoading: Boolean = true,
        /** Master toggle — hide every photo that belongs to any album from the timeline. */
        val hideAlbumPhotos: Boolean = false,
    ) {
        val excludedCount: Int get() = folders.count { it.isExcluded }
        val allExcluded: Boolean get() = folders.isNotEmpty() && folders.all { it.isExcluded }
    }

    private var excludedNames: Set<String> = emptySet()
    private var excludedAlbumIds: Set<String> = emptySet()
    private var hideAlbumPhotos: Boolean = false

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed persisted state BEFORE the first media emission so the initial UI already
            // carries it.
            val seedPrefs = context.settingsDataStore.data.first()
            excludedNames = seedPrefs[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES] ?: emptySet()
            hideAlbumPhotos = seedPrefs[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] ?: false
            excludedAlbumIds = seedPrefs[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] ?: emptySet()

            // Cloud album list for the per-album filter — DB-only cached read, offline-safe.
            val albumRows = runCatching { driveRepo.loadAlbumsCached() }.getOrDefault(emptyList())
                .map { a ->
                    TimelineAlbum(
                        linkId = a.linkId,
                        name = a.name,
                        coverUrl = a.coverThumbnailUrl,
                        itemCount = a.photoCount,
                        isExcluded = a.linkId in excludedAlbumIds,
                    )
                }
                .sortedBy { it.name.lowercase() }

            localMediaRepo.observeLocalMedia().collectLatest { items ->
                val populated = items
                    .filter { it.bucketName != null }
                    .groupBy { it.bucketName!! }
                    .map { (name, groupItems) ->
                        val sorted = groupItems.sortedByDescending { it.dateTaken }
                        TimelineFolder(
                            name       = name,
                            coverUri   = sorted.firstOrNull()?.uri,
                            itemCount  = sorted.size,
                            isExcluded = name in excludedNames,
                        )
                    }
                val populatedNames = populated.map { it.name }.toSet()

                // Surface previously-hidden folders that have no photos right now (deleted
                // contents, or buckets that vanished) so the user can still un-hide them.
                val emptyExcluded = excludedNames
                    .filter { it.isNotBlank() && it !in populatedNames }
                    .map { name ->
                        TimelineFolder(name, coverUri = null, itemCount = 0, isExcluded = true)
                    }
                    .sortedBy { it.name.lowercase() }

                _uiState.value = UiState(
                    folders = populated.sortedByDescending { it.itemCount } + emptyExcluded,
                    // Recompute isExcluded from the live set so a per-album toggle survives the
                    // next media re-emission.
                    albums = albumRows.map { it.copy(isExcluded = it.linkId in excludedAlbumIds) },
                    isLoading = false,
                    hideAlbumPhotos = hideAlbumPhotos,
                )
            }
        }
    }

    /** Master toggle — persist HIDE_PHOTOS_IN_ALBUMS. The gallery observes the key directly
     *  and re-filters on its next emission. */
    fun setHideAlbumPhotos(enabled: Boolean) {
        hideAlbumPhotos = enabled
        _uiState.update { it.copy(hideAlbumPhotos = enabled) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] = enabled }
        }
    }

    /** Per-album toggle — flip whether this album's photos are hidden from the timeline. */
    fun toggleAlbum(albumLinkId: String) {
        val newSet = if (albumLinkId in excludedAlbumIds)
            excludedAlbumIds - albumLinkId
        else
            excludedAlbumIds + albumLinkId
        excludedAlbumIds = newSet
        _uiState.update { s ->
            s.copy(albums = s.albums.map { a -> a.copy(isExcluded = a.linkId in newSet) })
        }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] = newSet }
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
        // Display-only setting — just write the key. The gallery observes it directly and
        // re-filters on the next emission; no reconcile/upload pass is needed.
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES] = names }
        }
    }
}
