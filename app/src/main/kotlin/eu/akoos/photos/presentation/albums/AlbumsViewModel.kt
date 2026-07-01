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

package eu.akoos.photos.presentation.albums

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/** Outcome of an album rename/delete surfaced to the long-press sheets. */
sealed interface AlbumActionResult {
    data object Done : AlbumActionResult
    data class Failed(val message: String) : AlbumActionResult
}

/** A MediaStore bucket surfaced as a card under the "Folders on this device" section. */
data class DeviceFolder(
    val name: String,
    val coverUri: String?,
    val itemCount: Int,
)

data class AlbumsUiState(
    val isLoading: Boolean = true,
    val albums: List<Album> = emptyList(),
    val deviceFolders: List<DeviceFolder> = emptyList(),
    val hideDeviceFolders: Boolean = false,
    val hideCloudAlbums: Boolean = false,
    val error: String? = null,
    val isCreatingAlbum: Boolean = false,
    val createAlbumError: String? = null,
) {
    val visibleCloudAlbums: List<Album> get() = albums
}

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val localMediaRepo: LocalMediaRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    init {
        loadAlbums()
        observeDeviceFolders()
        // Re-fetch on share-state changes so the grid badge updates without a manual pull-to-refresh.
        viewModelScope.launch {
            albumListEvents.changes.collect { loadAlbums() }
        }
        // A cover change patches only that album's thumbnail in place, so the grid card flips without
        // reloading and flashing the whole list.
        viewModelScope.launch {
            albumListEvents.coverChanges.collect { (albumId, coverUrl) ->
                if (coverUrl == null) return@collect
                _uiState.update { st ->
                    st.copy(albums = st.albums.map {
                        if (it.linkId == albumId) it.copy(coverThumbnailUrl = coverUrl) else it
                    })
                }
            }
        }
    }

    /**
     * Group MediaStore items into device-folder cards (bucket → newest cover + count, hidden-vault excluded).
     * Own collector touching only [AlbumsUiState.deviceFolders], so a MediaStore refresh never disturbs cloud state.
     */
    private fun observeDeviceFolders() {
        viewModelScope.launch {
            val hiddenUrisFlow = context.settingsDataStore.data.map {
                it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            }
            combine(localMediaRepo.observeLocalMedia(), hiddenUrisFlow) { items, hiddenUris ->
                items
                    .filter { it.bucketName != null && it.uri !in hiddenUris }
                    .groupBy { it.bucketName!! }
                    .map { (name, groupItems) ->
                        val sorted = groupItems.sortedByDescending { it.dateTaken }
                        DeviceFolder(
                            name = name,
                            coverUri = sorted.firstOrNull()?.uri,
                            itemCount = sorted.size,
                        )
                    }
                    .sortedByDescending { it.itemCount }
            }
                .catch { emit(emptyList()) }
                .collect { folders ->
                    _uiState.update { it.copy(deviceFolders = folders) }
                }
        }
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.HIDE_DEVICE_FOLDERS_IN_ALBUMS] ?: false }
                .catch { emit(false) }
                .collect { hidden -> _uiState.update { it.copy(hideDeviceFolders = hidden) } }
        }
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.HIDE_CLOUD_ALBUMS_IN_ALBUMS] ?: false }
                .catch { emit(false) }
                .collect { hidden -> _uiState.update { it.copy(hideCloudAlbums = hidden) } }
        }
    }

    /** Persisted show/hide toggle for the device-folders section in the Albums grid. */
    fun setHideDeviceFolders(hidden: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.HIDE_DEVICE_FOLDERS_IN_ALBUMS] = hidden }
        }
    }

    /** Persisted show/hide toggle for the cloud-albums section in the Albums grid. */
    fun setHideCloudAlbums(hidden: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.HIDE_CLOUD_ALBUMS_IN_ALBUMS] = hidden }
        }
    }

    fun refresh() = loadAlbums()

    fun loadAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            // Phase 1: instant cache read so the grid paints on cold/airplane-mode launch.
            val cached = runCatching { driveRepo.loadAlbumsCached() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(isLoading = false, albums = cached) }
            }

            // Phase 2: network refresh, online only — else keep the painted cache.
            if (!networkObserver.isOnline.value) {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            // Skeleton only when there's nothing painted yet.
            if (cached.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            runCatching { driveRepo.loadAlbums(userId) }.fold(
                onSuccess = { albums ->
                    _uiState.update { it.copy(isLoading = false, albums = albums) }
                    // Fire-and-forget membership prefetch so a later album-open paints from cache.
                    // Delayed 5 s so foreground gallery decrypts get first crack at the network semaphore.
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(5_000)
                        runCatching { driveRepo.prefetchAlbumsMembership(userId, albums) }
                    }
                },
                onFailure = { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // Swallow the refresh error when a cached snapshot is already on screen.
                            error = if (cached.isNotEmpty()) null
                            else friendly ?: sanitizeErrorMessage(e.message),
                        )
                    }
                },
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun createAlbum(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isCreatingAlbum = true, createAlbumError = null) }
            try {
                val album = driveRepo.createDriveAlbum(userId, trimmed)
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    albums = (listOf(album) + it.albums),
                ) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    createAlbumError = e.message ?: context.getString(R.string.albums_create_failed),
                ) }
            }
        }
    }

    fun clearCreateAlbumError() = _uiState.update { it.copy(createAlbumError = null) }

    /**
     * Rename a cloud album, exposed as a Flow so the long-press sheet can drive an in-flight + snackbar cycle.
     * Also re-keys the bucket→linkId cache (ALBUM_BUCKET_MAP); without it a folder-mirror upload would orphan
     * the original and create a new album under the new name.
     */
    fun renameCloudAlbum(linkId: String, currentName: String, newName: String): Flow<AlbumActionResult> = flow {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            emit(AlbumActionResult.Failed(context.getString(R.string.albums_name_empty)))
            return@flow
        }
        if (trimmed == currentName) {
            emit(AlbumActionResult.Failed(context.getString(R.string.albums_rename_same_name)))
            return@flow
        }
        // Re-throw CancellationException (caught below) — converting it to a Failed result violates Flow transparency.
        val result = runCatching {
            val userId = accountManager.getPrimaryUserId().first()
                ?: throw IllegalStateException(context.getString(R.string.viewer_not_signed_in))
            driveRepo.renameAlbum(userId, linkId, trimmed)
            _uiState.update { state ->
                state.copy(albums = state.albums.map {
                    if (it.linkId == linkId) it.copy(name = trimmed) else it
                })
            }
            // Re-key the bucket→linkId cache. Match BOTH name and linkId so a same-named bucket isn't hijacked.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.ALBUM_BUCKET_MAP] ?: emptySet()
                val rebuilt = current.mapNotNull { entry ->
                    val idx = entry.indexOf('=')
                    if (idx <= 0) return@mapNotNull entry
                    val bucket = entry.substring(0, idx)
                    val storedLinkId = entry.substring(idx + 1)
                    if (bucket.equals(currentName, ignoreCase = true) && storedLinkId == linkId) {
                        "$trimmed=$linkId"
                    } else entry
                }.toSet()
                if (rebuilt != current) {
                    prefs[SettingsKeys.ALBUM_BUCKET_MAP] = rebuilt
                }
            }
        }
        result.fold(
            onSuccess = { emit(AlbumActionResult.Done) },
            onFailure = { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit(AlbumActionResult.Failed(e.message ?: context.getString(R.string.albums_rename_failed)))
            },
        )
    }

    fun deleteAlbum(albumLinkId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            try {
                driveRepo.deleteAlbum(userId, albumLinkId)
                _uiState.update { state ->
                    state.copy(albums = state.albums.filter { it.linkId != albumLinkId })
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(error = context.getString(R.string.albums_delete_failed, e.message ?: "")) }
            }
        }
    }
}
