package me.proton.photos.presentation.viewer

import android.content.Context
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.domain.entity.Album
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.GalleryItem
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.LocalMediaRepository
import me.proton.photos.domain.usecase.DeletePhotoUseCase
import me.proton.photos.data.hidden.HiddenStorageManager
import me.proton.photos.domain.usecase.DownloadPhotosUseCase
import me.proton.photos.util.ExifHelper
import me.proton.photos.util.MetadataStripConfig
import me.proton.photos.util.PhotoMetadata
import javax.inject.Inject

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val hiddenStorage: HiddenStorageManager,
    private val localMediaRepo: LocalMediaRepository,
) : ViewModel() {

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

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    private val _metadata = MutableStateFlow<PhotoMetadata?>(null)
    val metadata: StateFlow<PhotoMetadata?> = _metadata.asStateFlow()

    private val _isStrippingMetadata = MutableStateFlow(false)
    val isStrippingMetadata: StateFlow<Boolean> = _isStrippingMetadata.asStateFlow()

    private val _isHidden = MutableStateFlow(false)
    val isHidden: StateFlow<Boolean> = _isHidden.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /**
     * Names of all on-device albums: auto-discovered MediaStore buckets + user-created
     * manual markers + every album referenced by a virtual-membership entry. Deduplicated
     * and sorted alphabetically for predictable picker order.
     *
     * Surfaced so the viewer's "Add to album" sheet can offer local destinations alongside
     * cloud albums, mirroring [GalleryAddToAlbumPickerSheet] in the main gallery.
     */
    val localAlbumNames: StateFlow<List<String>> = combine(
        localMediaRepo.observeLocalMedia(),
        context.settingsDataStore.data.map { it[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet() },
        context.settingsDataStore.data.map { prefs ->
            val raw = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
            raw.mapNotNull { entry ->
                val sep = entry.indexOf("||")
                if (sep <= 0) null else entry.substring(0, sep)
            }.toSet()
        },
    ) { items, manualMarkers, virtualNames ->
        val bucketNames = items.mapNotNull { it.bucketName }.toSet()
        (bucketNames + manualMarkers + virtualNames)
            .filter { it.isNotBlank() }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Errors that the UI should toast/snackbar. Set by previously-silent failure paths
     *  (add-to-album, download-to-device, load albums) so the user gets feedback instead of a
     *  silently spinning indicator. Caller clears via [clearTransientError]. */
    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError.asStateFlow()
    fun clearTransientError() { _transientError.value = null }

    private val _renameState = MutableStateFlow<RenameState>(RenameState.Idle)
    val renameState: StateFlow<RenameState> = _renameState.asStateFlow()

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

    /** Called after the system trash dialog is confirmed by the user. */
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
            val (sourceUri, displayName, mime) = when (item) {
                is GalleryItem.LocalOnly -> Triple(item.local.uri, item.local.displayName, item.local.mimeType)
                is GalleryItem.Synced    -> Triple(item.local.uri, item.local.displayName, item.local.mimeType)
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
                hiddenStorage.store(sourceUri, displayName, mime)
            }
            if (privateUri == null) {
                _deleteState.value = DeleteState.Failed("Could not move file to Hidden")
                return@launch
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
            )
            when (result) {
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission ->
                    _deleteState.value = DeleteState.NeedsPermission(result.pendingIntent)
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
     */
    fun unhideHiddenItem(hiddenUri: String, originalDisplayName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            hiddenStorage.restore(hiddenUri, originalDisplayName)
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - hiddenUri
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

    private fun renameLocal(uri: String, newName: String, replaceOriginal: Boolean) {
        val parsed = android.net.Uri.parse(uri)
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
            me.proton.photos.util.ProtonPhotosStorage.DEFAULT_MOVIES
        else
            me.proton.photos.util.ProtonPhotosStorage.DEFAULT_PICTURES

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

    fun loadAlbums() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { cloudRepo.loadAlbums(userId) }
                .onSuccess { _albums.value = it }
                .onFailure { e -> _transientError.value = "Could not load albums: ${e.message ?: "unknown error"}" }
        }
    }

    /**
     * Adds the single viewed item to a cloud album by linkId. Used by the viewer's
     * action-bar bubble when the user picks a Drive album from the picker sheet.
     *
     * Local-only items can't enter a cloud album — there's no [CloudPhoto.linkId] yet.
     * For "add a local file to a local album" use [addToLocalAlbum] which writes to the
     * virtual-membership DataStore map.
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
     * a brand-new destination instead of an existing album.
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
                _albums.value = listOf(album) + _albums.value
            }.onSuccess {
                _addToAlbumDone.tryEmit(trimmed)
            }.onFailure { e ->
                _transientError.value = "Create album failed: ${e.message ?: "unknown error"}"
            }
            _isAddingToAlbum.value = false
        }
    }

    /**
     * Adds the viewed local file to a local album by writing a virtual-membership entry
     * to [SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP]. The file is NOT moved on disk —
     * its MediaStore row keeps its original DATE_TAKEN, which Android Q+ would otherwise
     * silently nuke on a RELATIVE_PATH update we don't own.
     *
     * For Synced items the cloud copy ALSO gets added to the cloud album (if a cloud
     * counterpart album linkId is supplied), matching the multi-select "Add to album"
     * picker's routing.
     */
    fun addToLocalAlbum(albumName: String, item: GalleryItem) {
        viewModelScope.launch {
            val localUri = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced    -> item.local.uri
                is GalleryItem.CloudOnly -> return@launch // nothing to write virtually
            }
            _isAddingToAlbum.value = true
            runCatching {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
                    prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] = current + "$albumName||$localUri"
                    // Also persist the album marker so empty-yet-named local albums still
                    // appear in the Albums tab when the user only added local files to them.
                    val markers = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
                    prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = markers + albumName
                }
            }.onSuccess {
                _addToAlbumDone.tryEmit(albumName)
            }.onFailure { e ->
                _transientError.value = "Add to album failed: ${e.message ?: "unknown error"}"
            }
            _isAddingToAlbum.value = false
        }
    }

    fun stripMetadataFromLocal(uri: String, config: MetadataStripConfig) {
        viewModelScope.launch {
            _isStrippingMetadata.value = true
            val success = withContext(Dispatchers.IO) {
                ExifHelper.stripFieldsInPlace(context, uri, config)
            }
            if (success) {
                // Re-read after stripping so the metadata sheet reflects the wipe — the
                // "Strip" button on that section disappears automatically once its source
                // fields are null.
                _metadata.value = withContext(Dispatchers.IO) {
                    ExifHelper.readMetadata(context, uri)
                }
            } else {
                // Most common cause on Android 10+: the file isn't owned by this app
                // (e.g. user-taken camera roll photo) and scoped storage blocks the
                // in-place rw open without an IntentSender flow. Tell the user instead of
                // silently no-op'ing.
                _transientError.value = "Couldn't strip metadata, this file may need a permission " +
                    "confirmation that's not yet wired up. Try editing a copy instead (Edit, Save as copy)."
            }
            _isStrippingMetadata.value = false
        }
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
                    ?.let { me.proton.photos.util.ProtonPhotosStorage.sanitize(it) }
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

    fun loadCloud(photo: CloudPhoto) {
        val isVideo = photo.mimeType.startsWith("video/")
        val itemKey = photo.linkId

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
                    // Only commit the full-res state if the user is still on this item — they
                    // may have swiped to another page during the download. Without this guard
                    // the late-arriving full-res blob would overwrite whatever the *new* page
                    // loaded, flashing the previous photo onto the current one.
                    if ((_state.value.itemKey ?: itemKey) != itemKey) return@fold
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
