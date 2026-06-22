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

package eu.akoos.photos.presentation.location

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/** Determinate progress of a multi-item download / share over the location's photos. */
sealed class LocationOpState {
    data object Idle : LocationOpState()
    data class Working(val done: Int, val total: Int) : LocationOpState()
}

/** One-shot outcome of "Save as album", surfaced to the screen's snackbar. */
data class SaveAsAlbumResult(
    val joined: Int,
    val queued: Int,
    val albumName: String,
)

data class LocationDetailUiState(
    /** "City, Country" resolved from the tapped pin — the screen title. */
    val placeName: String = "",
    val isLoading: Boolean = true,
    val items: List<GalleryItem> = emptyList(),
    /** Selection key set — local uri for local-backed items, cloud linkId for cloud-only. */
    val selectedKeys: Set<String> = emptySet(),
    val downloadState: LocationOpState = LocationOpState.Idle,
    val shareState: LocationOpState = LocationOpState.Idle,
    /** True while the "Save as album" round-trip is in flight. */
    val isSavingAsAlbum: Boolean = false,
    val saveAsAlbumResult: SaveAsAlbumResult? = null,
    val error: String? = null,
) {
    val isSelectionMode: Boolean get() = selectedKeys.isNotEmpty()
    val selectedCount: Int get() = selectedKeys.size
}

/**
 * Backs [LocationDetailSheet]: an album-style drawer of every geotagged photo taken in one place
 * (city). The tapped pin's coordinates resolve to a "City, Country" label via [OfflineGeocoder];
 * the screen then shows every located photo whose own coordinates geocode to the SAME label,
 * resolved to its [GalleryItem] from the shared library merge so each cell opens the viewer with
 * the correct synced / cloud state. "Save as album" creates a real Drive album named after the
 * city and adds those photos (uploading any local-only ones first), reusing the same album-create +
 * add-to-album path as the gallery and device-folder surfaces.
 */
@HiltViewModel
class LocationDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationDetailUiState())
    val uiState: StateFlow<LocationDetailUiState> = _uiState.asStateFlow()

    /** One-shot system-share intents emitted to the screen, which launches the chooser. */
    private val _shareIntent = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val shareIntent: SharedFlow<android.content.Intent> = _shareIntent.asSharedFlow()

    private var loadJob: Job? = null

    /**
     * Resolve the tapped pin to its place label, then collect every located photo that geocodes to
     * the same place and resolve each to a [GalleryItem]. Keyed on the coordinates so re-entering a
     * different pin reloads cleanly.
     */
    fun load(latitude: Double, longitude: Double) {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val userId = accountManager.getPrimaryUserId().first() ?: run {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                // The tapped pin's place is the title. A null geocode (dataset missing) leaves the
                // screen empty rather than guessing.
                val target = OfflineGeocoder.reverseGeocode(context, latitude, longitude) ?: run {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(placeName = target) }

                // The merged library is the single source the gallery / search / calendar open the
                // viewer with, so a resolved item carries the right synced / cloud state.
                val libraryItems = getGalleryItems.invoke(userId).first()
                val itemByKey = HashMap<String, GalleryItem>(libraryItems.size * 2)
                for (item in libraryItems) {
                    when (item) {
                        is GalleryItem.LocalOnly -> itemByKey[item.local.uri] = item
                        is GalleryItem.Synced -> {
                            itemByKey[item.local.uri] = item
                            itemByKey[item.cloud.linkId] = item
                        }
                        is GalleryItem.CloudOnly -> itemByKey[item.cloud.linkId] = item
                    }
                }

                // Geocode every located row off the main thread; OfflineGeocoder caches its dataset so
                // this is a sub-millisecond scan per row. Keep those matching the tapped place and map
                // each to its library item by the entity id (local content uri or cloud linkId).
                val located: List<PhotoLocationEntity> = photoLocationDao.observeForUser(userId.id).first()
                val matched = withContext(Dispatchers.Default) {
                    val seen = LinkedHashSet<String>()
                    val out = ArrayList<GalleryItem>()
                    for (loc in located) {
                        val place = OfflineGeocoder.reverseGeocode(context, loc.latitude, loc.longitude)
                        if (place != target) continue
                        val item = itemByKey[loc.id] ?: continue
                        // De-dup: a Synced item is reachable by both its uri and its linkId.
                        if (!seen.add(item.stableId)) continue
                        out += item
                    }
                    out.sortedByDescending { it.captureTimeMs }
                }

                _uiState.update { it.copy(isLoading = false, items = matched) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A transient failure (DB / geocode read) must end the spinner rather than leave the
                // drawer shimmering forever; show an empty place instead.
                _uiState.update { it.copy(isLoading = false, items = emptyList()) }
            }
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────
    //
    // Cells are keyed by local uri where a device copy exists, else the cloud linkId — the same
    // keying the device-folder grid uses, so the drag-select sweep maps swept keys back to items.

    private fun keyOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly -> item.cloud.linkId
    }

    fun toggleSelection(key: String) {
        _uiState.update {
            val next = if (key in it.selectedKeys) it.selectedKeys - key else it.selectedKeys + key
            it.copy(selectedKeys = next)
        }
    }

    /** Replace the whole selection — used by the drag-select sweep, which sets the swept range each frame. */
    fun setSelectedKeys(keys: Set<String>) = _uiState.update { it.copy(selectedKeys = keys) }

    fun clearSelection() = _uiState.update { it.copy(selectedKeys = emptySet()) }

    private fun selectedGalleryItems(): List<GalleryItem> {
        val sel = _uiState.value.selectedKeys
        return _uiState.value.items.filter { keyOf(it) in sel }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    // ── Download selected ──────────────────────────────────────────────────────

    /** Download the selected photos to the device, mirroring the gallery's multi-download. */
    fun downloadSelected() {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(downloadState = LocationOpState.Working(0, items.size)) }
            val memberships: Map<String, String> = runCatching { driveRepo.getAlbumMemberships(userId) }
                .getOrDefault(emptyMap())
                .mapValues { (_, name) -> eu.akoos.photos.util.ProtonPhotosStorage.sanitize(name) }
            runCatching {
                downloadPhotos.downloadGalleryItems(
                    userId, items,
                    folderName = "",
                    folderByLinkId = memberships,
                ) { progress ->
                    _uiState.update {
                        it.copy(downloadState = LocationOpState.Working(progress.done, progress.total))
                    }
                }
            }
            _uiState.update { it.copy(downloadState = LocationOpState.Idle, selectedKeys = emptySet()) }
        }
    }

    // ── Share selected ──────────────────────────────────────────────────────────

    /**
     * Share the selection to other apps: local items reuse their content uri directly; cloud-only
     * items decrypt to cacheDir first and go through the share FileProvider. Mirrors the gallery.
     */
    fun shareSelected() {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(shareState = LocationOpState.Working(0, items.size)) }
            val userId = accountManager.getPrimaryUserId().first()
            val uris = ArrayList<android.net.Uri>(items.size)
            var done = 0
            for (item in items) {
                runCatching {
                    when (item) {
                        is GalleryItem.LocalOnly -> android.net.Uri.parse(item.local.uri)
                        is GalleryItem.Synced -> android.net.Uri.parse(item.local.uri)
                        is GalleryItem.CloudOnly -> {
                            val uid = userId ?: error("Not signed in")
                            val file = driveRepo.downloadFullResPhoto(uid, item.cloud)
                            androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.share.fileprovider", file,
                            ).also {
                                eu.akoos.photos.util.ShareFileProvider.putDisplayName(it, item.cloud.displayName)
                            }
                        }
                    }
                }.onSuccess { uris.add(it) }
                    .onFailure { android.util.Log.w("LocationDetailVM", "share resolve failed: ${it.message}") }
                done++
                _uiState.update { it.copy(shareState = LocationOpState.Working(done, items.size)) }
            }
            if (uris.isNotEmpty()) {
                val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMime(items)
                _shareIntent.tryEmit(
                    eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, uris, mime),
                )
            }
            _uiState.update { it.copy(shareState = LocationOpState.Idle, selectedKeys = emptySet()) }
        }
    }

    // ── Add selected to an existing cloud album ────────────────────────────────

    /**
     * Add the selection to album [albumLinkId]: cloud-backed items join now; local-only ones are
     * queued to upload and join after. Reuses the same path as the gallery / device-folder add.
     * Reports (joined now, queued for after) for the snackbar.
     */
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) {
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val (joined, queued) = addItemsToAlbum(userId, albumLinkId, items)
            _uiState.update { it.copy(selectedKeys = emptySet()) }
            onResult(joined, queued)
        }
    }

    // ── Save as album ──────────────────────────────────────────────────────────

    /**
     * Create a Drive album named after the city and add every photo in this place to it. Local-only
     * photos upload first and join afterwards; the first cloud-backed photo becomes the cover. This
     * is the same create + add-to-album path the gallery's "New album from selection" uses.
     */
    fun saveAsAlbum() {
        val items = _uiState.value.items
        val placeName = _uiState.value.placeName
        if (items.isEmpty() || placeName.isBlank() || _uiState.value.isSavingAsAlbum) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.update { it.copy(error = context.getString(R.string.viewer_not_signed_in)) }
                return@launch
            }
            _uiState.update { it.copy(isSavingAsAlbum = true, error = null) }
            val album: Album = runCatching { driveRepo.createDriveAlbum(userId, placeName) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val friendly = friendlyNetworkError(e, true, context)
                    _uiState.update {
                        it.copy(
                            isSavingAsAlbum = false,
                            error = friendly ?: sanitizeErrorMessage(e.message),
                        )
                    }
                    return@launch
                }
            val (joined, queued) = addItemsToAlbum(userId, album.linkId, items)
            // Set the first cloud-backed photo as the cover so the album card isn't blank.
            val firstCloud = items.firstNotNullOfOrNull {
                when (it) {
                    is GalleryItem.Synced -> it.cloud.linkId
                    is GalleryItem.CloudOnly -> it.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
            if (firstCloud != null) {
                runCatching { driveRepo.setAlbumCover(userId, album.linkId, firstCloud) }
            }
            albumListEvents.notifyChanged()
            _uiState.update {
                it.copy(
                    isSavingAsAlbum = false,
                    saveAsAlbumResult = SaveAsAlbumResult(joined, queued, placeName),
                )
            }
        }
    }

    fun clearSaveAsAlbumResult() = _uiState.update { it.copy(saveAsAlbumResult = null) }

    /** Partition [items] into cloud (join now) + local (upload then join) and run both halves. */
    private suspend fun addItemsToAlbum(
        userId: UserId,
        albumLinkId: String,
        items: List<GalleryItem>,
    ): Pair<Int, Int> {
        val cloudLinkIds = items.mapNotNull { item ->
            when (item) {
                is GalleryItem.Synced -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
        }
        val localUris = items.mapNotNull { (it as? GalleryItem.LocalOnly)?.local?.uri }
        val joined = if (cloudLinkIds.isNotEmpty()) {
            runCatching { driveRepo.addPhotosToAlbum(userId, albumLinkId, cloudLinkIds) }
                .getOrNull()?.succeededLinkIds?.size ?: 0
        } else 0
        val queued = if (localUris.isNotEmpty()) {
            forceUploadLocalUris.queueForAlbum(userId, albumLinkId, localUris)
        } else 0
        return joined to queued
    }

    /** Seed the album picker for "Add to album" from the local album cache (no network round-trip). */
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { driveRepo.loadAlbumsCached() }.onSuccess { _albums.value = it }
        }
    }
}
