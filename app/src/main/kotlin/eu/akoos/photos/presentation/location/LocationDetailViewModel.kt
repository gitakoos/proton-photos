/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.presentation.common.GalleryItemSelectionController
import eu.akoos.photos.presentation.common.MoveToFolderController
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/** One-shot outcome of "Save as album", surfaced to the screen's snackbar. */
data class SaveAsAlbumResult(
    val joined: Int,
    val queued: Int,
    val albumName: String,
)

data class LocationDetailUiState(
    /** "City, Country" resolved from the tapped pin - the screen title. */
    val placeName: String = "",
    val isLoading: Boolean = true,
    val items: List<GalleryItem> = emptyList(),
    /** True while the "Save as album" round-trip is in flight. */
    val isSavingAsAlbum: Boolean = false,
    val saveAsAlbumResult: SaveAsAlbumResult? = null,
    val error: String? = null,
)

/**
 * Backs the place page ([LocationPhotosContent] in [PlaceCityScreen]): an album-style view of every
 * geotagged photo taken in one place (city). The coordinates resolve to a "City, Country" label via
 * [OfflineGeocoder]; the screen then shows every located photo whose own coordinates geocode to the
 * SAME label, resolved to its [GalleryItem] from the shared library merge so each cell opens the
 * viewer with the correct synced / cloud state. "Save as album" creates a real Drive album named
 * after the city and adds those photos (uploading any local-only ones first), reusing the same
 * album-create + add-to-album path as the gallery and device-folder surfaces.
 *
 * The multi-select (share / add-to-album / back-up / download / offline / favourite / hide / delete /
 * strip) runs through the shared [GalleryItemSelectionController], the same one the timeline and search
 * use, so this surface offers the full action set from one place. "Save as album" and move-to-folder
 * stay local: the first names itself after the place and takes every photo in it, the second sends the
 * device selection into a DCIM folder through the shared [MoveToFolderController].
 */
@HiltViewModel
class LocationDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val driveRepo: DrivePhotoRepository,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val moveController: MoveToFolderController,
    private val selectionFactory: GalleryItemSelectionController.Factory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationDetailUiState())
    val uiState: StateFlow<LocationDetailUiState> = _uiState.asStateFlow()

    /** Whether a Proton account is signed in. A null-userId local-only session leaves the cloud
     *  actions (add-to-album) without a destination, so the screen hides them. Defaults to signed-in
     *  so nothing flickers before the first emit. */
    val isSignedIn: StateFlow<Boolean> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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
                val userId = accountManager.getPrimaryUserId().first()
                // The tapped pin's place is the title. A null geocode (dataset missing) leaves the
                // screen empty rather than guessing.
                val target = OfflineGeocoder.reverseGeocode(context, latitude, longitude) ?: run {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                _uiState.update { it.copy(placeName = target) }

                // The merged library is the single source the gallery / search / calendar open the
                // viewer with, so a resolved item carries the right synced / cloud state.
                val libraryItems = (if (userId == null) getGalleryItems.invokeLocalOnly()
                    else getGalleryItems.invoke(userId)).first()
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
                // Read the local partition when signed out, so a guest's place resolves its photos too.
                val located: List<PhotoLocationEntity> =
                    photoLocationDao.observeForUser(userId?.id ?: PhotoLocationEntity.LOCAL_USER).first()
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

    fun clearError() = _uiState.update { it.copy(error = null) }

    // ── Multi-select ──────────────────────────────────────────────────────────────────────────────
    //
    // Delegates to the shared [GalleryItemSelectionController] (the timeline and search use the same
    // one), so every selection action, the delete + strip permission handshakes and the Undo offer
    // live in one place. Only Select-all needs local context - the current place list.
    val sel = selectionFactory.create(viewModelScope)

    val selectedItems: StateFlow<Set<GalleryItem>> get() = sel.selectedItems
    val albums: StateFlow<List<Album>> get() = sel.albums
    val shareIntent get() = sel.shareIntent
    val offlineBatchResult get() = sel.offlineBatchResult
    val actionFailure get() = sel.actionFailure
    val downloadStarted get() = sel.downloadStarted
    val isDeleting: StateFlow<Boolean> get() = sel.isDeleting
    val pendingDeleteIntent get() = sel.pendingDeleteIntent
    val pendingStripIntent get() = sel.pendingStripIntent
    val multiStripState get() = sel.multiStripState
    val favoriteIds get() = sel.favoriteIds
    val offlinePinIds get() = sel.offlinePinIds
    val favoriteState get() = sel.favoriteState

    fun toggleSelection(item: GalleryItem) = sel.toggleSelection(item)
    fun setSelection(items: Set<GalleryItem>) = sel.setSelection(items)
    fun clearSelection() = sel.clearSelection()
    fun selectAll() = sel.selectAll(_uiState.value.items)
    fun shareSelected() = sel.shareSelected()
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) =
        sel.addSelectedToAlbum(albumLinkId, onResult)
    fun createAlbumThenAddSelected(
        name: String,
        onResult: (joined: Int, queued: Int, error: String?) -> Unit,
    ) = sel.createAlbumThenAddSelected(name, onResult)
    fun backUpSelected(onResult: (queued: Int) -> Unit) = sel.backUpSelected(onResult)
    fun downloadSelected(onResult: (succeeded: Int, failed: Int) -> Unit) = sel.downloadSelected(onResult)
    fun toggleSelectedOffline() = sel.toggleSelectedOffline()
    fun toggleSelectedFavorite() = sel.toggleSelectedFavorite()
    fun hideSelected() = sel.hideSelected()
    /** The two halves the selection's hide would act on, for the confirmation that fronts it. */
    fun hideSplitForSelection() = sel.hideSplitForSelection()
    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) =
        sel.deleteSelected(freeUpSpace, deleteFromCloud)
    fun onDeletePermissionGranted() = sel.onDeletePermissionGranted()
    fun clearPendingDeleteIntent() = sel.clearPendingDeleteIntent()
    fun stripMetadataSelected(config: MetadataStripConfig) = sel.stripMetadataSelected(config)
    fun onStripPermissionGranted() = sel.onStripPermissionGranted()
    fun clearPendingStripIntent() = sel.clearPendingStripIntent()
    fun resetMultiStripState() = sel.resetMultiStripState()

    // ── Move to a device folder (logged-out, device data only) ──────────────────────────────────
    // Delegated to the shared [MoveToFolderController], the same relocation the timeline offers, so a
    // located selection can send its device photos into a DCIM folder.

    /** Existing device folders offered as move targets, kept warm for the picker. */
    val moveTargetFolders = moveController.targetFolders(viewModelScope)

    /** One-shot system write-consent request a foreign-file move needs; the screen's host drives it. */
    val pendingMoveIntent = moveController.pendingMoveIntent

    /** Destination folder of a completed move, for the host's snackbar. */
    val moveConfirmation = moveController.moveConfirmation

    /** The selected photos that carry a device file, mapped to their uris; a cloud-only one has none. */
    private fun selectedDeviceUris(): List<String> = selectedItems.value.mapNotNull { item ->
        when (item) {
            is GalleryItem.LocalOnly -> item.local.uri
            is GalleryItem.Synced -> item.local.uri
            is GalleryItem.CloudOnly -> null
        }
    }

    /** Move every selected device photo into [folderName] under DCIM/, then drop the selection. */
    fun moveSelectedToFolder(folderName: String) {
        val uris = selectedDeviceUris()
        moveController.move(viewModelScope, uris, folderName)
        clearSelection()
    }

    /** Move the selection into a freshly named device folder, born with the photos the move lands there. */
    fun createFolderWithPhotos(name: String) {
        val uris = selectedDeviceUris()
        moveController.createFolder(viewModelScope, name, uris)
        clearSelection()
    }

    fun onMovePermissionGranted() = moveController.onPermissionGranted(viewModelScope)

    fun clearPendingMove() = moveController.clearPending()

    // ── Save as album ──────────────────────────────────────────────────────────────

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
}
