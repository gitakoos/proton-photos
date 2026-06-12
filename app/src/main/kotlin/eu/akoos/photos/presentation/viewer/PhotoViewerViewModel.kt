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

package eu.akoos.photos.presentation.viewer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import coil.imageLoader
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.PhotoMetadata
import eu.akoos.photos.util.friendlyNetworkError
import javax.inject.Inject

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val hiddenStorage: HiddenStorageManager,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val forceUploadLocalUris: eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase,
) : ViewModel() {

    override fun onCleared() {
        super.onCleared()
        // TTL prune for the long-lived fullres cache. Files older than the configured
        // window are reclaimed only while the network is up — going offline pauses
        // eviction so cached photos stay viewable until connectivity returns.
        runCatching {
            eu.akoos.photos.data.repository.drive.PhotoDownloadService.pruneStaleFullResCache(
                context = context,
                networkAvailable = networkObserver.isOnline.value,
            )
        }
    }

    sealed class ViewerState {
        /**
         * Identity of the item this state was computed for — used by [PhotoViewerScreen] to
         * detect the brief window after a page-swipe where `settledPage` has already changed
         * to the new page but [loadCloud]/[loadLocal] hasn't replaced state yet. If the
         * renderer used a stale state during that window, the new page would flash the
         * previous photo. The key is the cloud linkId for cloud items, the local URI for
         * local items, or null for Loading/Error which apply regardless of page.
         */
        abstract val itemKey: String?

        data object Loading : ViewerState() { override val itemKey: String? = null }
        data class ShowImage(
            val model: Any,
            override val itemKey: String?,
            val isFullRes: Boolean = false,
        ) : ViewerState()
        /** Used for video content — URI points to a local file or content URI. */
        data class ShowVideo(
            val uri: android.net.Uri,
            override val itemKey: String?,
            val isFullRes: Boolean = false,
        ) : ViewerState()
        data class Error(val message: String?) : ViewerState() { override val itemKey: String? = null }
    }

    sealed class DeleteState {
        data object Idle    : DeleteState()
        data object Working : DeleteState()
        data object Done    : DeleteState()   // caller navigates away
        data class  Failed(val message: String) : DeleteState()
        /** Android 11+ system trash dialog must be shown; pendingIntent launches it. */
        data class  NeedsPermission(val pendingIntent: android.app.PendingIntent) : DeleteState()
    }

    sealed class RenameState {
        data object Idle : RenameState()
        data object Working : RenameState()
        data class Done(val newDisplayName: String) : RenameState()
        data class Failed(val message: String) : RenameState()
    }

    sealed class StripState {
        data object Idle : StripState()
        /** Android 10+ write-permission dialog for a non-app-owned file must be shown;
         *  [pendingIntent] launches it, then the screen calls [retryPendingStrip]. */
        data class NeedsPermission(val pendingIntent: android.app.PendingIntent) : StripState()
    }

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /** Pair of `(doneBytes, totalBytes)` while a cloud download is in flight, or null
     *  otherwise. Reset to null on success/error so the viewer can drop the % overlay. */
    data class DownloadProgress(val doneBytes: Long, val totalBytes: Long)
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _isSavingToDevice = MutableStateFlow(false)
    val isSavingToDevice: StateFlow<Boolean> = _isSavingToDevice.asStateFlow()

    /** True while [shareItem] resolves a shareable URI (decrypting a cloud-only photo first).
     *  Drives the overflow spinner the same way [isSavingToDevice] does. */
    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    /**
     * One-shot carrier for the built share intent. A ViewModel can't call startActivity, so the
     * screen collects this and launches `Intent.createChooser(...)`. Same replay=0 + single-buffer
     * shape as [addToAlbumDone] so a paused screen doesn't block the emit.
     */
    private val _shareIntent = MutableSharedFlow<android.content.Intent>(replay = 0, extraBufferCapacity = 1)
    val shareIntent: SharedFlow<android.content.Intent> = _shareIntent.asSharedFlow()

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    private val _metadata = MutableStateFlow<PhotoMetadata?>(null)
    val metadata: StateFlow<PhotoMetadata?> = _metadata.asStateFlow()

    /** Resolved on-disk size of the currently-loaded cloud full-res blob. The server
     *  batch API often returns `link.size = null` for video uploads, so [CloudPhoto.sizeBytes]
     *  is 0 and the details sheet would otherwise show a blank Size row. Once the download
     *  pipeline finishes, the local `File.length()` is the authoritative byte count; we
     *  expose it here for the metadata UI fallback. Reset to null on every [loadCloud] /
     *  [loadLocal] entry so a stale value doesn't leak across swipes. */
    private val _cloudFullResSize = MutableStateFlow<Long?>(null)
    val cloudFullResSize: StateFlow<Long?> = _cloudFullResSize.asStateFlow()

    /**
     * True when the viewer skipped the auto full-res download because the user has the
     * Wi-Fi-only-for-fullres setting enabled and the device is on a metered network.
     * The screen uses this to surface a small "Connect to Wi-Fi for full quality" hint
     * instead of leaving the user staring at a thumbnail with no explanation.
     */
    private val _fullResBlockedByMetered = MutableStateFlow(false)
    val fullResBlockedByMetered: StateFlow<Boolean> = _fullResBlockedByMetered.asStateFlow()

    private val _isStrippingMetadata = MutableStateFlow(false)
    val isStrippingMetadata: StateFlow<Boolean> = _isStrippingMetadata.asStateFlow()

    private val _stripState = MutableStateFlow<StripState>(StripState.Idle)
    val stripState: StateFlow<StripState> = _stripState.asStateFlow()

    /** Strip args stashed when the OS demanded a write-permission confirmation. Replayed by
     *  [retryPendingStrip] after RESULT_OK; cleared on grant or cancel so it never leaks. */
    private var pendingStrip: Pair<String, MetadataStripConfig>? = null

    private val _isHidden = MutableStateFlow(false)
    val isHidden: StateFlow<Boolean> = _isHidden.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /**
     * Cloud-album linkIds that contain the currently-viewed photo. Drives the checkmarks
     * + remove-on-tap behavior in the "Add to album" picker sheet. Refreshed alongside
     * [loadAlbums] every time the sheet opens, so a stale value (e.g. user removed the
     * photo from an album on Drive web) self-corrects on the next open.
     */
    private val _currentPhotoAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val currentPhotoAlbumIds: StateFlow<Set<String>> = _currentPhotoAlbumIds.asStateFlow()

    private val _isAddingToAlbum = MutableStateFlow(false)
    val isAddingToAlbum: StateFlow<Boolean> = _isAddingToAlbum.asStateFlow()

    /**
     * Emits the destination album name on a successful "Add to album" operation so the screen
     * can show a snackbar. Mirrors how `AddToAlbumState.Done` is surfaced in [GalleryScreen]
     * — the viewer's previous flow silently flipped [isAddingToAlbum] back to false without
     * any user-visible confirmation. Replay=0 + tryEmit so we don't block when the screen is
     * paused. Errors continue to flow through [transientError].
     */
    private val _addToAlbumDone = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val addToAlbumDone: SharedFlow<String> = _addToAlbumDone.asSharedFlow()

    /**
     * One-shot emission when the viewer's overflow "Set as album cover" item succeeds for
     * the currently-viewed photo. The screen uses this to pop a Toast/snackbar. Same shape
     * as [addToAlbumDone] — replay=0, single-buffered so a paused screen doesn't block the
     * VM. Errors route through [transientError] like the rest of the viewer.
     */
    private val _setCoverDone = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val setCoverDone: SharedFlow<Unit> = _setCoverDone.asSharedFlow()

    /** Errors that the UI should toast/snackbar. Set by previously-silent failure paths
     *  (add-to-album, download-to-device, load albums) so the user gets feedback instead of a
     *  silently spinning indicator. Caller clears via [clearTransientError]. */
    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError.asStateFlow()
    fun clearTransientError() { _transientError.value = null }

    private val _renameState = MutableStateFlow<RenameState>(RenameState.Idle)
    val renameState: StateFlow<RenameState> = _renameState.asStateFlow()

    /**
     * Cloud linkId → local MediaStore URI for photos that ALSO live on the device. The
     * viewer is opened with a static `items: List<GalleryItem>` snapshot — when the user
     * downloads a CloudOnly item from the bottom-bar action, that snapshot stays stale
     * and the cloud-only badge keeps showing. This live map lets the screen render the
     * correct "Synced" badge as soon as the sync state lands, without re-creating the
     * navigation entry.
     */
    val localUriByLinkId: StateFlow<Map<String, String>> = flow {
        val userId = accountManager.getPrimaryUserId().first()
        if (userId == null) { emit(emptyMap()); return@flow }
        emitAll(
            syncStateRepo.observeAll(userId).map { states ->
                states.asSequence()
                    .filter { it.status == eu.akoos.photos.domain.entity.SyncStatus.SYNCED }
                    .filter { it.cloudFileId != null && it.localUri.isNotBlank() }
                    .associate { it.cloudFileId!! to it.localUri }
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Holds the cloud-delete work that was deferred until the user confirms the Android 11+
     *  system trash dialog. Cleared on commit OR on cancel — never leaks across user actions. */
    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    fun deleteItem(item: GalleryItem, freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Working
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _deleteState.value = DeleteState.Failed("Not signed in")
                return@launch
            }
            val result = deletePhotoUseCase(
                userId          = userId,
                items           = listOf(item),
                freeUpSpace     = freeUpSpace,
                deleteFromCloud = deleteFromCloud,
            )
            _deleteState.value = when (result) {
                is DeletePhotoUseCase.Result.Success           -> DeleteState.Done
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> DeleteState.Failed("Could not delete from Drive")
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    pendingPermissionResult = result
                    DeleteState.NeedsPermission(result.pendingIntent)
                }
            }
        }
    }

    /** Called after the system trash dialog returns RESULT_OK. */
    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        viewModelScope.launch {
            // First commit the deferred cloud delete, if any. Doing this in a coroutine so a
            // network hiccup surfaces as Failed rather than crashing the UI.
            val cloudResult = if (pending != null) {
                val userId = accountManager.getPrimaryUserId().first()
                if (userId == null) {
                    DeletePhotoUseCase.Result.CloudDeleteFailed
                } else {
                    deletePhotoUseCase.completeAfterPermissionGranted(
                        userId          = userId,
                        cloudLinkIds    = pending.cloudLinkIds,
                        items           = pending.itemsBeingDeleted,
                        freeUpSpace     = pending.freeUpSpace,
                        hide            = pending.hide,
                    )
                }
            } else DeletePhotoUseCase.Result.Success

            if (cloudResult is DeletePhotoUseCase.Result.CloudDeleteFailed) {
                _deleteState.value = DeleteState.Failed("Could not delete from Drive")
                return@launch
            }
            // If the user just confirmed a HIDE-triggered delete, register the private copy now.
            commitPendingHide()
            _deleteState.value = DeleteState.Done
        }
    }

    fun resetDeleteState() {
        // Drop the deferred cloud-delete work — the user backed out of the system trash dialog.
        pendingPermissionResult = null
        // If a hide was in flight and the user canceled the system dialog, drop the orphaned copy.
        cancelPendingHide()
        _deleteState.value = DeleteState.Idle
    }

    fun loadLocal(uri: String, mimeType: String = "") {
        val parsedUri = Uri.parse(uri)
        _state.value = if (mimeType.startsWith("video/"))
            ViewerState.ShowVideo(parsedUri, itemKey = uri)
        else
            ViewerState.ShowImage(parsedUri, itemKey = uri)
        if (mimeType.startsWith("image/")) {
            loadMetadata(uri)
        }
    }

    fun loadMetadata(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _metadata.value = ExifHelper.readMetadata(context, uri)
        }
    }

    fun clearMetadata() {
        _metadata.value = null
    }

    fun checkIfHidden(uri: String) {
        viewModelScope.launch {
            val hiddenUris = context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet() }
                .first()
            _isHidden.value = uri in hiddenUris
        }
    }

    /** Holds the private-storage URI of a file currently being hidden, awaiting the user's
     *  confirmation in the system trash dialog. Only committed to [SettingsKeys.HIDDEN_PHOTO_URIS]
     *  after the delete succeeds; cleared (and the file removed) if the user cancels. */
    private var pendingHidePrivateUri: String? = null

    /**
     * Moves a photo to the Hidden vault. The flow is:
     *   1. Copy the bytes into app-private storage (so other gallery apps can't reach them).
     *   2. Stage the new file:// URI as a "pending hide" — NOT yet in HIDDEN_PHOTO_URIS.
     *   3. Trigger DeletePhotoUseCase which surfaces the Android 11+ system trash dialog via
     *      [DeleteState.NeedsPermission]. The screen launcher reports back via either
     *      [onDeletePermissionGranted] (commit) or [resetDeleteState] (cancel).
     *
     * Only on a successful delete do we register the private URI as hidden. On cancel we delete
     * the orphaned private copy, otherwise the photo would end up in BOTH places.
     */
    fun hideItem(item: GalleryItem) {
        viewModelScope.launch {
            // Pull dateTaken alongside so the hidden file can preserve its capture-time
            // through the round-trip — see HiddenStorageManager.store(captureTimeMs).
            val sourceUri: String; val displayName: String; val mime: String; val dateTakenMs: Long
            val cloudLinkId: String?
            when (item) {
                is GalleryItem.LocalOnly -> {
                    sourceUri = item.local.uri; displayName = item.local.displayName
                    mime = item.local.mimeType; dateTakenMs = item.local.dateTaken; cloudLinkId = null
                }
                is GalleryItem.Synced -> {
                    sourceUri = item.local.uri; displayName = item.local.displayName
                    mime = item.local.mimeType; dateTakenMs = item.local.dateTaken; cloudLinkId = item.cloud.linkId
                }
                is GalleryItem.CloudOnly -> return@launch
            }
            // Defensive: if the URI is already an app-private hidden file, the delete branch
            // below would crash on Android 11+ (`MediaStore.createTrashRequest` only accepts
            // content:// MediaStore URIs, not file://app-private paths). Callers should route
            // to [unhideHiddenItem] instead in that case — bail out loudly here so the bug
            // doesn't reappear silently if a future UI re-introduces it.
            if (hiddenStorage.isHiddenUri(sourceUri)) {
                Log.w("PhotoViewerVM", "hideItem called on already-hidden URI; use unhideHiddenItem instead")
                return@launch
            }
            val privateUri = withContext(Dispatchers.IO) {
                hiddenStorage.store(sourceUri, displayName, mime, captureTimeMs = dateTakenMs)
            }
            if (privateUri == null) {
                _deleteState.value = DeleteState.Failed("Could not move file to Hidden")
                return@launch
            }
            // Persist the (privateUri → cloudLinkId) pair so unhide can transplant the
            // existing SyncState row onto the restored MediaStore URI — keeps reconcile
            // from re-uploading the Synced photo as a duplicate Drive entry.
            if (cloudLinkId != null) {
                context.settingsDataStore.edit { prefs ->
                    val existing = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = existing + "$privateUri|$cloudLinkId"
                }
            }
            pendingHidePrivateUri = privateUri

            val userId = accountManager.getPrimaryUserId().first() ?: run {
                cancelPendingHide()
                return@launch
            }
            val result = deletePhotoUseCase(
                userId          = userId,
                items           = listOf(item),
                freeUpSpace     = true,    // delete the on-device file
                deleteFromCloud = false,   // never delete the Drive copy on a hide
                hide            = true,    // route through createDeleteRequest + HIDDEN status
            )
            when (result) {
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    pendingPermissionResult = result
                    _deleteState.value = DeleteState.NeedsPermission(result.pendingIntent)
                }
                is DeletePhotoUseCase.Result.Success -> {
                    // Pre-Q: delete succeeded synchronously. Commit the hidden URI now.
                    commitPendingHide()
                    _deleteState.value = DeleteState.Done
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                    cancelPendingHide()   // shouldn't happen with deleteFromCloud=false
                    _deleteState.value = DeleteState.Failed("Could not move file to Hidden")
                }
            }
        }
    }

    private fun commitPendingHide() {
        val privateUri = pendingHidePrivateUri ?: return
        pendingHidePrivateUri = null
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + privateUri
            }
            _isHidden.value = true
        }
    }

    private fun cancelPendingHide() {
        val privateUri = pendingHidePrivateUri ?: return
        pendingHidePrivateUri = null
        // Drop the orphaned private copy — the user did not confirm the system delete.
        viewModelScope.launch(Dispatchers.IO) {
            hiddenStorage.delete(privateUri)
        }
    }

    /**
     * Restores a hidden item back to MediaStore (visible to other apps again) and removes the
     * private copy. [hiddenUri] is the file://… URI stored in [SettingsKeys.HIDDEN_PHOTO_URIS].
     *
     * Both HIDDEN_PHOTO_URIS *and* HIDDEN_URI_CLOUD_ID_MAP are mutated in the same edit{}
     * block so the two views can't desync — a separate edit{} could interleave with another
     * writer (e.g. concurrent hide of a different photo) and leave the cloud-id map pointing
     * at a URI that's no longer in the hidden set.
     */
    fun unhideHiddenItem(hiddenUri: String, originalDisplayName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            hiddenStorage.restore(hiddenUri, originalDisplayName)
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - hiddenUri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] =
                    mapping.filterNot { it.startsWith("$hiddenUri|") }.toSet()
            }
            _isHidden.value = false
        }
    }

    /**
     * Legacy in-app-only hide (DataStore filter without moving the file). Kept for callers that
     * cannot perform the full hide flow — exposed mainly so existing tests keep compiling.
     * Prefer [hideItem] for new code.
     */
    fun toggleHide(uri: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = if (uri in current) current - uri else current + uri
            }
            _isHidden.value = !_isHidden.value
        }
    }

    /**
     * Force a not-yet-backed-up (LocalOnly) photo in the viewer to upload to Drive — the same
     * path the gallery selection and device-folder views use. No-op for already-synced or
     * cloud-only items. The viewer's sync badge flips once the upload lands.
     */
    fun backUpItem(item: GalleryItem) {
        val uri = (item as? GalleryItem.LocalOnly)?.local?.uri ?: return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            forceUploadLocalUris.forceUpload(userId, listOf(uri))
        }
    }

    fun checkIfFavorite(item: GalleryItem) {
        viewModelScope.launch {
            val id = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced    -> item.local.uri
                is GalleryItem.CloudOnly -> item.cloud.linkId
            }
            val favIds = context.settingsDataStore.data
                .map { it[SettingsKeys.FAVORITE_IDS] ?: emptySet() }
                .first()
            val cloudFlag = when (item) {
                is GalleryItem.Synced    -> item.cloud.isFavoriteOnCloud
                is GalleryItem.CloudOnly -> item.cloud.isFavoriteOnCloud
                is GalleryItem.LocalOnly -> false
            }
            _isFavorite.value = id in favIds || cloudFlag
        }
    }

    fun toggleFavorite(item: GalleryItem) {
        viewModelScope.launch {
            val id = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced    -> item.local.uri
                is GalleryItem.CloudOnly -> item.cloud.linkId
            }
            val nowFavorite = !_isFavorite.value
            // Optimistic local toggle (so all favorite UI flips immediately).
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.FAVORITE_IDS] ?: emptySet()
                prefs[SettingsKeys.FAVORITE_IDS] = if (nowFavorite) current + id else current - id
            }
            _isFavorite.value = nowFavorite
            // Push cloud favorite tag for backed-up items.
            val cloudPhoto = when (item) {
                is GalleryItem.Synced    -> item.cloud
                is GalleryItem.CloudOnly -> item.cloud
                is GalleryItem.LocalOnly -> null
            }
            if (cloudPhoto != null) {
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                cloudRepo.setCloudFavorite(userId, cloudPhoto, favorite = nowFavorite)
            }
        }
    }

    /**
     * Renames the file behind [item] to [newName].
     *
     * - [replaceOriginal] = true  → "Rename original": the file keeps its place. Local items
     *   are renamed in-place via MediaStore; cloud items are re-uploaded with the new name and
     *   the original linkId is moved to Recently Deleted.
     * - [replaceOriginal] = false → "Save as copy": local items get a new MediaStore entry
     *   under the new name; cloud items get a new linkId (the original stays in Drive).
     */
    fun renameItem(item: GalleryItem, newName: String, replaceOriginal: Boolean, sourceAlbumLinkId: String? = null) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _renameState.value = RenameState.Failed("Name cannot be empty")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _renameState.value = RenameState.Working
            val result = runCatching {
                when (item) {
                    is GalleryItem.LocalOnly -> renameLocal(item.local.uri, trimmed, replaceOriginal)
                    is GalleryItem.Synced    -> renameLocal(item.local.uri, trimmed, replaceOriginal)
                    is GalleryItem.CloudOnly -> renameCloud(item.cloud, trimmed, replaceOriginal, sourceAlbumLinkId)
                }
            }
            _renameState.value = result.fold(
                onSuccess = { RenameState.Done(trimmed) },
                onFailure = { e -> RenameState.Failed(e.message ?: "Rename failed") },
            )
        }
    }

    fun resetRenameState() { _renameState.value = RenameState.Idle }

    private suspend fun renameLocal(uri: String, newName: String, replaceOriginal: Boolean) {
        val parsed = android.net.Uri.parse(uri)
        // Hidden vault rename — the URI is a `file://` path under the app's private
        // storage, NOT a MediaStore content URI. ContentResolver.update would throw
        // "Unknown URI" (the user's reported bug). Route to the dedicated hidden-storage
        // rename which preserves the __<captureMs>__ tag the unhide flow relies on.
        if (hiddenStorage.isHiddenUri(uri)) {
            val newUri = hiddenStorage.rename(uri, newName)
                ?: error("Hidden rename failed (file may already exist with that name)")
            // Update the DataStore sets so the new URI replaces the old in the hidden
            // index AND in the optional cloud-id mapping. Without this the gallery would
            // still know the old URI as "hidden" and the new URI as "not hidden", which
            // would leak the renamed file back into the main listing.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = (current - uri) + newUri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                val updatedMapping = mapping.map { entry ->
                    if (entry.startsWith("$uri|")) "$newUri|${entry.substringAfter('|')}"
                    else entry
                }.toSet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = updatedMapping
            }
            return
        }
        if (replaceOriginal) {
            // Q+ MediaStore allows DISPLAY_NAME update directly; the file is renamed in place.
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            val updated = context.contentResolver.update(parsed, values, null, null)
            if (updated == 0) error("MediaStore declined to rename, the file may be on read-only storage")
            return
        }
        // Save-as-copy: stream the source bytes into a new MediaStore entry.
        val mime = context.contentResolver.getType(parsed) ?: "application/octet-stream"
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo)
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relPath = if (isVideo)
            eu.akoos.photos.util.ProtonPhotosStorage.DEFAULT_MOVIES
        else
            eu.akoos.photos.util.ProtonPhotosStorage.DEFAULT_PICTURES

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val target = context.contentResolver.insert(collection, values)
            ?: error("MediaStore insert failed")
        context.contentResolver.openInputStream(parsed)?.use { input ->
            context.contentResolver.openOutputStream(target)?.use { output ->
                input.copyTo(output)
            } ?: error("openOutputStream returned null")
        } ?: error("openInputStream returned null for $uri")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val finalValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(target, finalValues, null, null)
        }
    }

    private suspend fun renameCloud(photo: CloudPhoto, newName: String, replaceOriginal: Boolean, sourceAlbumLinkId: String?) {
        val userId = accountManager.getPrimaryUserId().first()
            ?: error("Not signed in")
        val newLinkId = cloudRepo.renameOrCopyCloudPhoto(userId, photo, newName, trashOriginal = replaceOriginal)
        // Keep the new linkId in the same album the source was in (best-effort).
        sourceAlbumLinkId?.let { albumId ->
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
        }
    }

    /**
     * Drops any cached image bytes tied to the just-renamed [item] so the stale original
     * stops showing the instant the rename lands. Same Coil memory+disk wipe the editor
     * does after a save (PhotoEditorViewModel.invalidateImageCache); for a cloud
     * replace-original it also deletes the trashed linkId's full-res blob so a re-open of
     * the viewer can't serve the old file from disk. Called from the screen once the rename
     * reports Done.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun invalidateAfterRename(item: GalleryItem) {
        val keys = when (item) {
            is GalleryItem.LocalOnly -> listOf(item.local.uri)
            is GalleryItem.Synced    -> listOf(item.local.uri)
            is GalleryItem.CloudOnly -> {
                // Both the CDN thumbnail URL and the on-disk full-res file are cached under
                // their own Coil keys; wipe whichever the viewer rendered.
                val file = eu.akoos.photos.data.repository.drive.PhotoDownloadService
                    .fullResFile(context, item.cloud)
                file?.let { runCatching { it.delete() } }
                listOfNotNull(item.cloud.thumbnailUrl, file?.let { Uri.fromFile(it).toString() })
            }
        }
        val loader = context.imageLoader
        val mc = loader.memoryCache
        keys.forEach { key ->
            if (mc != null) runCatching {
                mc.keys.filter { it.key == key }.forEach { mc.remove(it) }
            }
            runCatching { loader.diskCache?.remove(key) }
            // Nudge MediaStore observers (the gallery grid) for renamed device files so the
            // new display name surfaces without a manual pull-to-refresh.
            if (item !is GalleryItem.CloudOnly) {
                runCatching { context.contentResolver.notifyChange(Uri.parse(key), null) }
            }
        }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { cloudRepo.loadAlbums(userId) }
                .onSuccess { _albums.value = it }
                .onFailure { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _transientError.value = friendly
                        ?: "Could not load albums: ${eu.akoos.photos.util.sanitizeErrorMessage(e.message)}"
                }
        }
    }

    private fun sanitizeErrorMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown error"
        // Drop everything inside angle brackets (<html>, <body>, <h1>...) and collapse
        // remaining whitespace runs to a single space.
        val stripped = raw.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.isBlank()) return "server error"
        // Cap length so a multi-paragraph 5xx body doesn't push other UI off-screen.
        return if (stripped.length > 200) stripped.substring(0, 200) + "…" else stripped
    }

    /**
     * Resolves the cloud-album linkIds that contain [item] (a Synced or CloudOnly photo) and
     * publishes them via [currentPhotoAlbumIds]. The picker sheet observes this state to
     * draw checkmarks on already-member albums and switch the tap-action to "remove" for
     * those rows. No-op for LocalOnly items — those have no cloud linkId to look up.
     */
    fun loadCurrentPhotoAlbumIds(item: GalleryItem) {
        viewModelScope.launch {
            val cloudLinkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> { _currentPhotoAlbumIds.value = emptySet(); return@launch }
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { cloudRepo.getAlbumIdsByPhoto(userId) }
                .onSuccess { map -> _currentPhotoAlbumIds.value = map[cloudLinkId].orEmpty() }
                .onFailure { e -> Log.w("PhotoViewerVM", "loadCurrentPhotoAlbumIds failed: ${e.message}") }
        }
    }

    /**
     * Removes the viewed cloud item from [albumLinkId]. Counterpart to [addToAlbum]: the
     * picker sheet calls this when the user taps an album row that's already a member.
     * Refreshes [currentPhotoAlbumIds] on success so the checkmark disappears immediately.
     */
    fun removeFromAlbum(albumLinkId: String, item: GalleryItem) {
        viewModelScope.launch {
            val cloudLinkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _isAddingToAlbum.value = true
            runCatching { cloudRepo.removePhotosFromAlbum(userId, albumLinkId, listOf(cloudLinkId)) }
                .onSuccess {
                    _currentPhotoAlbumIds.value = _currentPhotoAlbumIds.value - albumLinkId
                }
                .onFailure { e -> _transientError.value = "Remove from album failed: ${e.message ?: "unknown error"}" }
            _isAddingToAlbum.value = false
        }
    }

    /**
     * Sets the currently-viewed cloud photo as the cover of [albumLinkId]. Called from the
     * viewer's overflow menu when the viewer was opened from an album context — the screen
     * only surfaces the menu item when [sourceAlbumLinkId] is non-null.
     *
     * Emits to [setCoverDone] on success so the screen can pop a snackbar, and routes
     * failures through [transientError]. Local-only items can't be a cloud album cover, so
     * the call is a no-op in that branch (the menu item is already gated on isCloudItem).
     */
    fun setCurrentAsAlbumCover(item: GalleryItem, albumLinkId: String) {
        if (albumLinkId.isBlank()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val cloudLinkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> return@launch
            }
            runCatching { cloudRepo.setAlbumCover(userId, albumLinkId, cloudLinkId) }
                .onSuccess { _setCoverDone.tryEmit(Unit) }
                .onFailure { e ->
                    _transientError.value = "Set cover failed: ${e.message ?: "unknown error"}"
                }
        }
    }

    /**
     * Adds the single viewed item to a cloud album by linkId. Used by the viewer's
     * action-bar bubble when the user picks a Drive album from the picker sheet.
     *
     * Local-only items can't enter a cloud album — there's no [CloudPhoto.linkId] until
     * the photo has been backed up.
     */
    fun addToAlbum(albumLinkId: String, item: GalleryItem) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val linkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> return@launch  // not yet in cloud
            }
            _isAddingToAlbum.value = true
            // Resolve the target album's display name for the success snackbar before the
            // network call — looking it up afterwards risks a stale list reference if the
            // album refreshed between submit and ack.
            val targetName = _albums.value.firstOrNull { it.linkId == albumLinkId }?.name ?: ""
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(linkId)) }
                .onSuccess { result ->
                    if (result.failedLinkIds.isNotEmpty()) {
                        _transientError.value = "Could not add ${result.failedLinkIds.size} photo(s) to the album"
                    } else if (targetName.isNotEmpty()) {
                        _addToAlbumDone.tryEmit(targetName)
                    }
                }
                .onFailure { e -> _transientError.value = "Add to album failed: ${e.message ?: "unknown error"}" }
            _isAddingToAlbum.value = false
        }
    }

    /**
     * Creates a new cloud (Drive) album with [name], then adds [item] to it. Used by the
     * viewer's "+ New album" row when the viewed item is cloud-backed and the user picks
     * a brand-new destination instead of an existing album. The added photo becomes the
     * album cover so the new card isn't blank in the Albums grid.
     */
    fun createCloudAlbumAndAdd(name: String, item: GalleryItem) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val linkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> return@launch
            }
            _isAddingToAlbum.value = true
            runCatching {
                val album = cloudRepo.createDriveAlbum(userId, trimmed)
                cloudRepo.addPhotosToAlbum(userId, album.linkId, listOf(linkId))
                // Cover failure must not fail the add — the photo is already in the album.
                runCatching { cloudRepo.setAlbumCover(userId, album.linkId, linkId) }
                _albums.value = listOf(album) + _albums.value
                albumListEvents.notifyChanged()
            }.onSuccess {
                _addToAlbumDone.tryEmit(trimmed)
            }.onFailure { e ->
                _transientError.value = "Create album failed: ${e.message ?: "unknown error"}"
            }
            _isAddingToAlbum.value = false
        }
    }

    fun stripMetadataFromLocal(uri: String, config: MetadataStripConfig) {
        viewModelScope.launch { runStrip(uri, config) }
    }

    /**
     * Replays the strip that triggered an Android 10+ write-permission dialog, now that the user
     * granted it. Called by the screen on RESULT_OK from the [StripState.NeedsPermission] launcher.
     */
    fun retryPendingStrip() {
        val (uri, config) = pendingStrip ?: return
        pendingStrip = null
        _stripState.value = StripState.Idle
        viewModelScope.launch { runStrip(uri, config) }
    }

    /** User canceled the write-permission dialog — drop the deferred strip, no error toast. */
    fun resetStripState() {
        pendingStrip = null
        _stripState.value = StripState.Idle
    }

    private suspend fun runStrip(uri: String, config: MetadataStripConfig) {
        _isStrippingMetadata.value = true
        when (withContext(Dispatchers.IO) { ExifHelper.stripFieldsInPlace(context, uri, config) }) {
            is StripResult.Stripped -> {
                // Re-read after stripping so the metadata sheet reflects the wipe — the
                // "Strip" button on that section disappears automatically once its source
                // fields are null.
                _metadata.value = withContext(Dispatchers.IO) {
                    ExifHelper.readMetadata(context, uri)
                }
            }
            is StripResult.NeedsPermission -> {
                // Android 11+ scoped storage: the file is not app-owned (e.g. a camera-roll photo).
                // Ask the OS for one-shot write access via createWriteRequest (API 30); older
                // scoped-storage devices fall through to the error path.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pendingStrip = uri to config
                    val pi = MediaStore.createWriteRequest(
                        context.contentResolver, listOf(Uri.parse(uri)),
                    )
                    _stripState.value = StripState.NeedsPermission(pi)
                } else {
                    _transientError.value = context.getString(R.string.strip_metadata_failed)
                }
            }
            is StripResult.Failed ->
                _transientError.value = context.getString(R.string.strip_metadata_failed)
        }
        _isStrippingMetadata.value = false
    }

    /**
     * Save the current item to the device gallery. Routing matches the rest of the app:
     * if the cloud photo lives in an album, the file lands in `Pictures/<AlbumName>/`;
     * otherwise it lands in `Pictures/` root with no app-specific subfolder.
     * Works for cloud-only items (downloads) and local items (copies via MediaStore).
     */
    fun downloadToDevice(item: GalleryItem) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _isSavingToDevice.value = true
            // Look up album membership for the single cloud item so we route into the
            // matching Pictures/<AlbumName>/ folder. Cache hit is the common case (5-min
            // TTL in AlbumService), so this is usually a synchronous map lookup.
            val cloudLinkId = when (item) {
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.Synced -> item.cloud.linkId
                else -> null
            }
            val folder = if (cloudLinkId != null) {
                runCatching { cloudRepo.getAlbumMemberships(userId)[cloudLinkId] }
                    .getOrNull()
                    ?.let { eu.akoos.photos.util.ProtonPhotosStorage.sanitize(it) }
                    .orEmpty()
            } else ""
            runCatching {
                downloadPhotos.downloadGalleryItems(userId, listOf(item), folderName = folder)
            }.onFailure { e ->
                _transientError.value = "Save to device failed: ${e.message ?: "unknown error"}"
            }
            _isSavingToDevice.value = false
        }
    }

    /**
     * Resolve a shareable URI for the item and emit the send intent for the screen to launch.
     * Local items (LocalOnly / Synced) reuse their MediaStore content URI directly — already
     * decrypted and cross-app readable, zero copy. A cloud-only item is decrypted to
     * cacheDir/fullres/ via [DrivePhotoRepository.downloadFullResPhoto] (single-flighted, cheap
     * on a cache hit) and handed out through the share FileProvider. The temp file is left in
     * place — the receiver reads it asynchronously and the fullres TTL sweep reclaims it later.
     */
    fun shareItem(item: GalleryItem) {
        viewModelScope.launch {
            _isSharing.value = true
            runCatching {
                val uri: Uri = when (item) {
                    is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                    is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                    is GalleryItem.CloudOnly -> {
                        val userId = accountManager.getPrimaryUserId().first()
                            ?: error("Not signed in")
                        val file = cloudRepo.downloadFullResPhoto(userId, item.cloud)
                        androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.share.fileprovider", file,
                        ).also {
                            // Report the real filename to the receiver instead of the linkId.
                            eu.akoos.photos.util.ShareFileProvider.putDisplayName(it, item.cloud.displayName)
                        }
                    }
                }
                val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMime(listOf(item))
                eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, listOf(uri), mime)
            }.onSuccess { intent ->
                _shareIntent.tryEmit(intent)
            }.onFailure { e ->
                _transientError.value = "Share failed: ${e.message ?: "unknown error"}"
            }
            _isSharing.value = false
        }
    }

    fun loadCloud(photo: CloudPhoto) {
        val isVideo = photo.mimeType.startsWith("video/")
        val itemKey = photo.linkId

        // Reset the resolved-size fallback so the details sheet doesn't show last item's
        // size while the new page is still downloading.
        _cloudFullResSize.value = null
        _fullResBlockedByMetered.value = false

        if (photo.thumbnailUrl != null) {
            // Show thumbnail as placeholder while downloading full resolution.
            // For videos we still show the thumbnail image while downloading.
            _state.value = ViewerState.ShowImage(photo.thumbnailUrl, itemKey = itemKey)
        } else {
            _state.value = ViewerState.Loading
        }

        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (photo.thumbnailUrl == null) _state.value = ViewerState.Error("Not logged in")
                return@launch
            }
            // Wi-Fi-only-for-fullres gate. When enabled (default) and the device is on a
            // metered network, hold off the auto-download and let the thumbnail stand in.
            // Explicit user actions (Save to device, Edit) bypass this gate elsewhere —
            // those are deliberate and shouldn't be silently blocked.
            //
            // Cache-already-present escape: if the full-res blob is sitting on disk from a
            // previous fetch, opening the viewer doesn't consume any metered bytes — so the
            // wifi-only prompt would be a false alarm. Skip the gate in that case and let
            // the normal download path return the cached file immediately.
            val wifiOnlyFullres = context.settingsDataStore.data
                .map { it[SettingsKeys.FULLRES_WIFI_ONLY] }
                .first() != false
            val alreadyCached = eu.akoos.photos.data.repository.drive.PhotoDownloadService
                .isFullResCached(context, photo)
            if (wifiOnlyFullres && !networkObserver.isUnmetered.value && !alreadyCached) {
                _fullResBlockedByMetered.value = true
                _isDownloading.value = false
                _downloadProgress.value = null
                if (photo.thumbnailUrl == null && _state.value.itemKey == itemKey) {
                    _state.value = ViewerState.Error("Connect to Wi-Fi for full quality")
                }
                return@launch
            }
            _isDownloading.value = true
            _downloadProgress.value = DownloadProgress(0L, photo.sizeBytes)
            val result = runCatching {
                cloudRepo.downloadFullResPhoto(userId, photo) { done, total ->
                    _downloadProgress.value = DownloadProgress(done, total)
                }
            }
            _isDownloading.value = false
            _downloadProgress.value = null
            result.fold(
                onSuccess = { file ->
                    val fileUri = Uri.fromFile(file)
                    // Publish the resolved on-disk size so the details sheet can fall back
                    // to it when CloudPhoto.sizeBytes is 0 (server-side gap for videos).
                    _cloudFullResSize.value = file.length()
                    // Only commit the full-res state if the user is still on this item — they
                    // may have swiped to another page during the download. Without this guard
                    // the late-arriving full-res blob would overwrite whatever the *new* page
                    // loaded, flashing the previous photo onto the current one.
                    // Strict equality, no Elvis fallback — when the state's itemKey is null
                    // (transient Loading / Error during a swap) the `?: itemKey` form would
                    // short-circuit the guard to false and publish the late blob onto whatever
                    // page is now visible. Pure `!=` correctly drops the blob in that case.
                    if (_state.value.itemKey != itemKey) return@fold
                    _state.value = if (isVideo)
                        ViewerState.ShowVideo(fileUri, itemKey = itemKey, isFullRes = true)
                    else
                        ViewerState.ShowImage(fileUri, itemKey = itemKey, isFullRes = true)
                },
                onFailure = { e ->
                    if (photo.thumbnailUrl == null && _state.value.itemKey == itemKey) {
                        _state.value = ViewerState.Error(e.message)
                    }
                    // else keep showing thumbnail silently
                },
            )
        }
    }
}
