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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import javax.inject.Inject

/** One-shot result of an "add to album" run, consumed once by the screen. */
sealed class PickerAddState {
    data object Idle : PickerAddState()
    data object Working : PickerAddState()
    /** [added] joined the album now; [queued] are local-only photos uploading first then joining. */
    data class Done(val added: Int, val queued: Int) : PickerAddState()
    data class Failed(val message: String) : PickerAddState()
}

/**
 * Backs [AlbumPhotoPickerScreen]. Reuses the shared [GetGalleryItemsUseCase] (the same merged
 * library the timeline shows) as the photo source and keeps its OWN selection set so it never
 * touches the main gallery's selection. Adding routes through [DrivePhotoRepository.addPhotosToAlbum]
 * for cloud-backed items and [ForceUploadLocalUrisUseCase] for local-only ones, identical to the
 * gallery's add-to-album path.
 */
@HiltViewModel
class AlbumPhotoPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val syncStateRepo: SyncStateRepository,
    private val driveRepo: DrivePhotoRepository,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) : ViewModel() {

    @Volatile private var primaryUserId: me.proton.core.domain.entity.UserId? = null

    init {
        viewModelScope.launch { accountManager.getPrimaryUserId().collect { primaryUserId = it } }
    }

    /** The full merged library, shared with the timeline so the picker adds no extra DB work. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList()) else getGalleryItems.invoke(userId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cloud linkIds of photos hidden on this device — dropped from the picker so a hidden photo
     *  can't leak back into an album (which would un-hide it). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val hiddenCloudLinkIds: StateFlow<Set<String>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptySet())
            else syncStateRepo.observeAll(userId).map { states ->
                states.filter { it.status == SyncStatus.HIDDEN && it.cloudFileId != null }
                    .mapNotNull { it.cloudFileId }
                    .toSet()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** Stable per-item keys of the picked photos (cloud linkId, else local uri). */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _addState = MutableStateFlow<PickerAddState>(PickerAddState.Idle)
    val addState: StateFlow<PickerAddState> = _addState.asStateFlow()

    /** Stable key for an item — matches [stableKeyOf] so taps toggle the right cell. */
    fun toggle(key: String) {
        _selected.update { if (key in it) it - key else it + key }
    }

    /** Replace the whole selection — drives the drag-sweep range select. */
    fun setSelection(keys: Set<String>) {
        _selected.value = keys
    }

    fun resetAddState() = _addState.update { PickerAddState.Idle }

    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        driveRepo.requestThumbnailDecrypt(userId, linkId)
    }

    fun cancelThumbnailDecrypt(linkId: String) = driveRepo.cancelThumbnailDecrypt(linkId)

    /**
     * Add the currently selected photos to [albumLinkId]. Cloud-backed items join immediately;
     * local-only ones are queued to back up then join. The album grid is poked so the cover/count
     * refresh on pop-back.
     */
    fun addSelectedToAlbum(albumLinkId: String) {
        val keys = _selected.value
        if (keys.isEmpty() || albumLinkId.isBlank()) return
        val picked = items.value.filter { stableKeyOf(it) in keys }
        if (picked.isEmpty()) return
        viewModelScope.launch {
            _addState.update { PickerAddState.Working }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _addState.update { PickerAddState.Failed(context.getString(R.string.viewer_not_signed_in)) }
                return@launch
            }
            val cloudLinkIds = picked.mapNotNull {
                when (it) {
                    is GalleryItem.Synced    -> it.cloud.linkId
                    is GalleryItem.CloudOnly -> it.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
            val localUris = picked.mapNotNull { (it as? GalleryItem.LocalOnly)?.local?.uri }

            var added = 0
            if (cloudLinkIds.isNotEmpty()) {
                try {
                    val r = driveRepo.addPhotosToAlbum(userId, albumLinkId, cloudLinkIds)
                    added = r.succeededLinkIds.size
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("AlbumPickerVM", "addPhotosToAlbum failed", e)
                    _addState.update {
                        PickerAddState.Failed(e.message ?: context.getString(R.string.gallery_add_to_album_failed))
                    }
                    return@launch
                }
            }
            val hadLocal = localUris.isNotEmpty()
            if (hadLocal) {
                try {
                    forceUploadLocalUris.queueForAlbum(userId, albumLinkId, localUris)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("AlbumPickerVM", "queueForAlbum failed", e)
                    _addState.update {
                        PickerAddState.Failed(e.message ?: context.getString(R.string.gallery_add_to_album_failed))
                    }
                    return@launch
                }
            }

            // A local pick is queued to upload and joins the album once it finishes; queueForAlbum
            // reporting 0 newly-scheduled items (already pending) is not a failure. Only a genuinely
            // empty result — nothing added to the cloud and nothing local to queue — is a real failure.
            if (added == 0 && !hadLocal) {
                _addState.update { PickerAddState.Failed(context.getString(R.string.gallery_add_to_album_failed)) }
                return@launch
            }
            albumListEvents.notifyChanged()
            _selected.update { emptySet() }
            // Report the local count as "queued" so the picker can tell the user those photos will
            // appear after upload, even when queueForAlbum reported nothing newly scheduled.
            _addState.update { PickerAddState.Done(added, if (hadLocal) localUris.size else 0) }
        }
    }

    companion object {
        /** Item identity used by both the selection set and the cell's memory-cache key. */
        fun stableKeyOf(item: GalleryItem): String = when (item) {
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.Synced    -> item.cloud.linkId
            is GalleryItem.LocalOnly -> item.local.uri
        }
    }
}
