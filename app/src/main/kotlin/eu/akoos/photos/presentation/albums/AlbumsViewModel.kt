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
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/**
 * Outcome of an album rename or delete action surfaced to the long-press sheets. The
 * action runs entirely on Drive, so the only branches are success and a sanitised failure
 * message the UI snackbars.
 */
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
        // Detail-screen actions (unshare, public-link-disable) emit on this bus when the
        // album's share state actually flipped — we re-fetch so the badge in the grid
        // drops without waiting for a manual pull-to-refresh.
        viewModelScope.launch {
            albumListEvents.changes.collect { loadAlbums() }
        }
    }

    /**
     * Groups MediaStore items into device-folder cards shown under the cloud albums. Runs in its
     * own collector that only touches [AlbumsUiState.deviceFolders], so a MediaStore refresh never
     * disturbs the cloud-album load/create/delete state. Mirrors the grouping the dedicated
     * folder browser used: bucket name → newest cover + count, hidden-vault items excluded with
     * the same DataStore key the gallery uses. Resilient to MediaStore errors via an empty fallback.
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
    }

    /** Persisted show/hide toggle for the device-folders section in the Albums grid. */
    fun setHideDeviceFolders(hidden: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.HIDE_DEVICE_FOLDERS_IN_ALBUMS] = hidden }
        }
    }

    fun refresh() = loadAlbums()

    fun loadAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            // Phase 1: instant cache read. Paints the grid on cold launch — including
            // airplane-mode starts — without waiting for any network round trip. Mirrors the
            // AlbumDetailViewModel.load() pattern for album-detail photos.
            val cached = runCatching { driveRepo.loadAlbumsCached() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(isLoading = false, albums = cached) }
            }

            // Phase 2: network refresh — only when online. When offline, keep whatever cache
            // already painted (or stop the spinner if cache was empty too).
            if (!networkObserver.isOnline.value) {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            // Only show the skeleton when we have nothing to paint yet — otherwise the user
            // already sees content and a spinner on top is just visual churn.
            if (cached.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            runCatching { driveRepo.loadAlbums(userId) }.fold(
                onSuccess = { albums ->
                    _uiState.update { it.copy(isLoading = false, albums = albums) }
                    // Fire-and-forget prefetch — walks each album's children pagination
                    // on Drive and writes membership rows so a subsequent open paints
                    // instantly from cache (online OR offline), without forcing the user
                    // to manually tap into every album to "warm" it first. Skips albums
                    // whose cached row count already matches Drive's photoCount.
                    //
                    // Delayed 5 s so the foreground Gallery's thumbnail-decrypt round
                    // gets first crack at the shared network semaphore + bandwidth.
                    // Without this delay the prefetch starts hammering Drive immediately
                    // and the gallery scroll feels stuttery for the first 30 s of a
                    // fresh launch as foreground decrypts queue behind background album
                    // children fetches.
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
                            // When the cache already painted, swallow the refresh error — the
                            // user is looking at the previous good snapshot and doesn't need a
                            // banner. Only surface the friendly / sanitised message when there
                            // was no cache to fall back to.
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
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    createAlbumError = e.message ?: "Failed to create album",
                ) }
            }
        }
    }

    fun clearCreateAlbumError() = _uiState.update { it.copy(createAlbumError = null) }

    /**
     * Rename a cloud album by [linkId]. Same API the AlbumDetail header pencil uses
     * internally — exposed here as a Flow<AlbumActionResult> so the AlbumsScreen long-press
     * sheet can drive an in-flight + snackbar cycle. Validation rules: trim, refuse empty,
     * refuse identical name.
     *
     * Also re-keys the bucket→linkId cache in [SettingsKeys.ALBUM_BUCKET_MAP] so the next
     * SyncWorker run finds the renamed album by its new name. Without this, the cache would
     * still point at oldName=linkId and a subsequent upload from a folder-mirror bucket would
     * silently create a brand-new cloud album with the new name — leaving the original
     * orphaned.
     */
    fun renameCloudAlbum(linkId: String, currentName: String, newName: String): Flow<AlbumActionResult> = flow {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            emit(AlbumActionResult.Failed("Album name cannot be empty"))
            return@flow
        }
        if (trimmed == currentName) {
            emit(AlbumActionResult.Failed("New name is the same as the current name"))
            return@flow
        }
        // Coroutine cancellation is signalled via CancellationException; swallowing it and
        // re-emitting would re-enter a Flow that the collector has already abandoned and
        // throws "Flow exception transparency is violated". Re-throw it so the upstream
        // teardown path stays intact, and only convert genuine failures into a Failed result.
        val result = runCatching {
            val userId = accountManager.getPrimaryUserId().first()
                ?: throw IllegalStateException("Not signed in")
            driveRepo.renameAlbum(userId, linkId, trimmed)
            _uiState.update { state ->
                state.copy(albums = state.albums.map {
                    if (it.linkId == linkId) it.copy(name = trimmed) else it
                })
            }
            // Re-key the bucket→linkId cache so the SyncWorker doesn't drift. Parse each
            // "bucket=linkId" entry, swap the bucket name when (and only when) it matches
            // currentName AND the stored linkId matches the renamed album. Doing both checks
            // avoids accidentally hijacking a separate bucket that happens to share the name.
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
                emit(AlbumActionResult.Failed(e.message ?: "Rename failed"))
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
                _uiState.update { it.copy(error = "Failed to delete album: ${e.message}") }
            }
        }
    }
}
