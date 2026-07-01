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
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import eu.akoos.photos.util.PhotoMetadata
import eu.akoos.photos.util.friendlyNetworkError
import java.io.File
import javax.inject.Inject

/** Raw stream dimensions + length of a cloud video, read off its decrypted full-res for the
 *  details sheet (a cloud-only video carries no on-device media row and no EXIF). */
data class CloudVideoMeta(val width: Int, val height: Int, val durationMs: Long)

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val hiddenStorage: HiddenStorageManager,
    private val offlineStore: OfflineStorageManager,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val forceUploadLocalUris: eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val photoLocationDao: PhotoLocationDao,
    private val getGalleryItems: GetGalleryItemsUseCase,
) : ViewModel() {

    private companion object {
        /** Drive PhotoTag id for the Panoramas category (see CategorizeItem mapping). */
        const val PANORAMA_TAG_ID = 8
    }

    override fun onCleared() {
        super.onCleared()
        // TTL prune of the fullres cache, but only while online so going offline keeps cached
        // photos viewable until connectivity returns.
        runCatching {
            eu.akoos.photos.data.repository.drive.PhotoDownloadService.pruneStaleFullResCache(
                context = context,
                networkAvailable = networkObserver.isOnline.value,
            )
        }
    }

    sealed class ViewerState {
        /**
         * Identity of the item this state was computed for (cloud linkId / local URI, or null for
         * Loading/Error). [PhotoViewerScreen] checks it so the brief post-swipe window where
         * settledPage has changed but state hasn't doesn't flash the previous photo.
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
        /** A non-app-owned file needs one-shot MediaStore write consent before the in-place rename. */
        data class NeedsPermission(val pendingIntent: android.app.PendingIntent) : RenameState()
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

    /** One-shot share intent — the VM can't startActivity, so the screen collects + launches the
     *  chooser. replay=0 + single-buffer so a paused screen doesn't block the emit. */
    private val _shareIntent = MutableSharedFlow<android.content.Intent>(replay = 0, extraBufferCapacity = 1)
    val shareIntent: SharedFlow<android.content.Intent> = _shareIntent.asSharedFlow()

    /** Single-photo public-link state shown in the manage-link sheet, owned by [publicLink].
     *  Reset to [PublicLinkState.None] on every page load so a link from the previously viewed
     *  photo never lingers. */
    val publicLinkState: StateFlow<PublicLinkState> = publicLink.state

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    private val _metadata = MutableStateFlow<PhotoMetadata?>(null)
    val metadata: StateFlow<PhotoMetadata?> = _metadata.asStateFlow()

    /** Geocoded place name for the details overview's Location row, or null while it resolves / when
     *  the photo carries no GPS. Loaded per item by [loadDetailsPlace] so the row reserves its slot
     *  and fills in like the Size row instead of only appearing once a location is found. */
    private val _detailsPlace = MutableStateFlow<String?>(null)
    val detailsPlace: StateFlow<String?> = _detailsPlace.asStateFlow()

    /** Resolved on-disk size of the loaded cloud full-res, used as the Size-row fallback when
     *  [CloudPhoto.sizeBytes] is 0 (server returns null size for some videos). Reset per page. */
    private val _cloudFullResSize = MutableStateFlow<Long?>(null)
    val cloudFullResSize: StateFlow<Long?> = _cloudFullResSize.asStateFlow()

    /** Resolution + length of a cloud VIDEO, read off its decrypted full-res once it downloads, so a
     *  cloud-only video's details fill in like a local one's. Null for images / until the blob lands. */
    private val _cloudVideoMeta = MutableStateFlow<CloudVideoMeta?>(null)
    val cloudVideoMeta: StateFlow<CloudVideoMeta?> = _cloudVideoMeta.asStateFlow()

    /** True when auto full-res was skipped (Wi-Fi-only setting + metered network); the screen then
     *  shows a "Connect to Wi-Fi for full quality" hint. */
    private val _fullResBlockedByMetered = MutableStateFlow(false)
    val fullResBlockedByMetered: StateFlow<Boolean> = _fullResBlockedByMetered.asStateFlow()

    /** True when the shown still is a Motion Photo (JPEG/HEIC + embedded MP4), driving the play
     *  affordance. Reset per page; flipped on by [detectMotionPhoto] (off-thread, non-blocking). */
    private val _isMotionPhoto = MutableStateFlow(false)
    val isMotionPhoto: StateFlow<Boolean> = _isMotionPhoto.asStateFlow()

    /** The extracted embedded clip currently playing (null otherwise); the temp is deleted on
     *  playback end / dismiss / page change. */
    private val _motionVideoFile = MutableStateFlow<File?>(null)
    val motionVideoFile: StateFlow<File?> = _motionVideoFile.asStateFlow()

    /** True while the embedded clip is being extracted to a cache temp, so the affordance can
     *  show a spinner instead of feeling dead on the tap. */
    private val _isExtractingMotion = MutableStateFlow(false)
    val isExtractingMotion: StateFlow<Boolean> = _isExtractingMotion.asStateFlow()

    /** Identity of the item the current motion-photo detection / playback applies to. Guards
     *  against a late detect result landing on a page the user already swiped away from. */
    private var motionItemKey: String? = null
    /** The on-disk source the embedded clip is extracted from once detection succeeds. */
    private var motionSourceFile: File? = null

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

    /** True when the settled cloud-only photo is pinned for offline (a blob exists in the
     *  offline store). Recomputed per item alongside [_isFavorite]; always false off a
     *  CloudOnly item. */
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    /** One-shot offline pin/un-pin status for the screen to snackbar; same replay=0 +
     *  single-buffer shape as [addToAlbumDone] so a paused screen never blocks the toggle. */
    private val _offlineMessage = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val offlineMessage: SharedFlow<String> = _offlineMessage.asSharedFlow()

    /** The category PhotoTag ids on the currently-shown photo, so the details sheet's tag chips
     *  reflect adds/removes immediately (optimistic, before the next sync). */
    private val _currentPhotoTags = MutableStateFlow<Set<Int>>(emptySet())
    val currentPhotoTags: StateFlow<Set<Int>> = _currentPhotoTags.asStateFlow()

    /** True when the settled item is a panorama still, set by [detectPanorama] (GPano marker or
     *  cloud Panoramas tag). Reset per page so a swipe to a normal photo drops the badge. */
    private val _isPanorama = MutableStateFlow(false)
    val isPanorama: StateFlow<Boolean> = _isPanorama.asStateFlow()

    /** True while the immersive horizontal-pan panorama mode is active. Reset per page. */
    private val _isPanoramaMode = MutableStateFlow(false)
    val isPanoramaMode: StateFlow<Boolean> = _isPanoramaMode.asStateFlow()

    fun enterPanorama() { _isPanoramaMode.value = true }
    fun exitPanorama() { _isPanoramaMode.value = false }

    /** Clears panorama detection + mode. Called from [loadLocal]/[loadCloud] so a freshly
     *  loaded page starts clean; [detectPanorama] re-arms the flag if the new item qualifies. */
    private fun resetPanoramaState() {
        _isPanorama.value = false
        _isPanoramaMode.value = false
    }

    /**
     * Off-thread panorama probe: the cloud Panoramas tag (id 8, no I/O) or a GPano XMP marker
     * scanned via [PanoramaDetector]. A hit commits only while [itemKey] still matches the live
     * page, so a slow read for photo A can't flip the badge after a swipe to B.
     */
    fun detectPanorama(item: GalleryItem?, uri: Uri?, itemKey: String?) {
        if (item == null) return
        // Cloud-tag fast path — no byte read needed when the server already classified it.
        val cloudTags = when (item) {
            is GalleryItem.Synced    -> item.cloud.tags
            is GalleryItem.CloudOnly -> item.cloud.tags
            is GalleryItem.LocalOnly -> emptySet()
        }
        if (PANORAMA_TAG_ID in cloudTags) {
            if (_state.value.itemKey == itemKey) _isPanorama.value = true
            return
        }
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val hit = eu.akoos.photos.util.PanoramaDetector.isPanorama(context, uri)
            if (hit) withContext(Dispatchers.Main) {
                // Re-check identity on the main thread before publishing — the user may have
                // paged away while the head scan was running.
                if (_state.value.itemKey == itemKey) _isPanorama.value = true
            }
        }
    }

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /** Cloud-album linkIds containing the viewed photo, driving the picker's checkmarks +
     *  remove-on-tap. Refreshed each time the sheet opens so a Drive-web change self-corrects. */
    private val _currentPhotoAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val currentPhotoAlbumIds: StateFlow<Set<String>> = _currentPhotoAlbumIds.asStateFlow()

    private val _isAddingToAlbum = MutableStateFlow(false)
    val isAddingToAlbum: StateFlow<Boolean> = _isAddingToAlbum.asStateFlow()

    /** Emits the destination album name on a successful add so the screen can snackbar. replay=0 +
     *  single-buffer so a paused screen doesn't block; errors flow through [transientError]. */
    private val _addToAlbumDone = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val addToAlbumDone: SharedFlow<String> = _addToAlbumDone.asSharedFlow()

    /** One-shot on "Set as album cover" success; same replay=0 + single-buffer shape as
     *  [addToAlbumDone]. */
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
    private var pendingRename: PendingRenameRequest? = null
    private data class PendingRenameRequest(
        val item: GalleryItem,
        val newName: String,
        val replaceOriginal: Boolean,
        val sourceAlbumLinkId: String?,
    )

    /** Live gallery list backing the viewer's reconciliation. The screen re-resolves each item in
     *  its passed-in static `items` snapshot against this by identity so a photo finishing upload
     *  (LocalOnly → Synced) or any metadata refresh reflects in the open viewer instead of staying
     *  frozen at click time. Empty until the merge first emits. */
    val liveItems: StateFlow<List<GalleryItem>> = flow {
        val userId = accountManager.getPrimaryUserId().first()
        if (userId == null) { emit(emptyList()); return@flow }
        emitAll(getGalleryItems.invoke(userId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cloud linkId → local URI for photos also on device. Lets the screen upgrade a CloudOnly
     *  badge to "Synced" after a download, since the static `items` snapshot can't reflect it. */
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
                _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_not_signed_in))
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
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> DeleteState.Failed(context.getString(R.string.viewer_delete_drive_failed))
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
                _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_delete_drive_failed))
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
        resetMotionState()
        resetPanoramaState()
        resetPublicLinkState()
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
        _detailsPlace.value = null
    }

    /**
     * Resolve the current item's place name for the details overview's Location row — from local EXIF
     * GPS, or for a cloud photo from the stored fix the map backfill records ([PhotoLocationEntity]).
     * Reset to null first so the row shows its placeholder immediately, then filled once resolved. A
     * photo with no GPS simply leaves it null (the row keeps the dash).
     */
    fun loadDetailsPlace(item: GalleryItem) {
        _detailsPlace.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val latLng = when (item) {
                is GalleryItem.LocalOnly -> localGps(item.local.uri)
                is GalleryItem.Synced -> localGps(item.local.uri) ?: cloudGps(item.cloud.linkId)
                is GalleryItem.CloudOnly -> cloudGps(item.cloud.linkId)
            }
            if (latLng != null) {
                _detailsPlace.value = OfflineGeocoder.reverseGeocode(context, latLng.first, latLng.second)
            }
        }
    }

    private fun localGps(uri: String): Pair<Double, Double>? {
        // Images keep GPS in EXIF.
        val meta = runCatching { ExifHelper.readMetadata(context, uri) }.getOrNull()
        val lat = meta?.gpsLatitude
        val lng = meta?.gpsLongitude
        if (lat != null && lng != null) return lat to lng
        // Videos keep it in the container (ISO 6709), which ExifInterface doesn't read.
        return videoGps(uri)
    }

    private fun videoGps(uri: String): Pair<Double, Double>? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            val loc = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            // ISO 6709, e.g. "+37.7749-122.4194/" — take the first two signed numbers (lat, lng).
            val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(loc) ?: return null
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lng = m.groupValues[2].toDoubleOrNull() ?: return null
            lat to lng
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun cloudGps(linkId: String): Pair<Double, Double>? {
        val userId = accountManager.getPrimaryUserId().first() ?: return null
        val row = runCatching { photoLocationDao.getById(userId.id, linkId) }.getOrNull() ?: return null
        return row.latitude to row.longitude
    }

    private fun readVideoMeta(file: File): CloudVideoMeta? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val d = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (w > 0 && h > 0) CloudVideoMeta(w, h, d) else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Off-thread probe for an embedded Motion Photo clip; on a hit flips [isMotionPhoto]. A
     * `content://` URI is first screened for the XMP flag (bounded prefix read) before staging the
     * full file, since vendor naming heuristics miss many real motion photos. [itemKey] drops a
     * late result after a swipe.
     */
    fun detectMotionPhoto(uri: String, itemKey: String?) {
        motionItemKey = itemKey
        motionSourceFile = null
        _isMotionPhoto.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            val source: File = when (parsed.scheme) {
                "file" -> parsed.path?.let { File(it) }?.takeIf { it.isFile } ?: return@launch
                else -> {
                    // Screen the content URI for the motion XMP flag from a small prefix before
                    // copying the whole file — an ordinary image reads a few hundred KB and stops.
                    val flagged = runCatching {
                        context.contentResolver.openInputStream(parsed)?.use {
                            MotionPhotoUtil.hasMotionXmp(it)
                        }
                    }.getOrNull() ?: false
                    if (!flagged) return@launch
                    stageContentToTemp(parsed) ?: return@launch
                }
            }
            val info = runCatching { MotionPhotoUtil.detect(source) }.getOrNull()
            // Drop the result if the user swiped to another page while we probed.
            if (info != null && motionItemKey == itemKey) {
                motionSourceFile = source
                _isMotionPhoto.value = true
            } else {
                // Not a motion photo (or stale) — reclaim any staged temp.
                runCatching { if (source.parentFile == motionTempDir()) source.delete() }
            }
        }
    }

    /** Extracts the embedded clip to a cache temp and publishes it via [motionVideoFile] for inline
     *  playback. No-op until detection confirms; [linkIdOrName] keeps concurrent temps distinct. */
    fun playMotionPhoto(linkIdOrName: String) {
        val source = motionSourceFile ?: return
        if (_isExtractingMotion.value || _motionVideoFile.value != null) return
        viewModelScope.launch {
            _isExtractingMotion.value = true
            val dest = withContext(Dispatchers.IO) {
                val safe = linkIdOrName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val out = File(motionTempDir(), "motion_play_$safe.mp4")
                val ok = runCatching { MotionPhotoUtil.extractVideo(source, out) }.getOrDefault(false)
                if (ok && out.isFile) out else { runCatching { out.delete() }; null }
            }
            _isExtractingMotion.value = false
            if (dest != null) _motionVideoFile.value = dest
            else _transientError.value = context.getString(R.string.motion_photo_play_failed)
        }
    }

    /** Ends inline motion playback: clears [motionVideoFile] and deletes the extracted temp. */
    fun stopMotionPhoto() {
        val playing = _motionVideoFile.value
        _motionVideoFile.value = null
        _isExtractingMotion.value = false
        if (playing != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { playing.delete() } }
        }
    }

    /** Resets motion-photo state on page change. A staged content-probe temp is reclaimed; a
     *  cloud full-res probe source is left alone (owned by the download cache). */
    private fun resetMotionState() {
        val staged = motionSourceFile?.takeIf { it.parentFile == motionTempDir() }
        motionItemKey = null
        motionSourceFile = null
        _isMotionPhoto.value = false
        stopMotionPhoto()
        if (staged != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { staged.delete() } }
        }
    }

    private fun motionTempDir(): File = File(context.cacheDir, "motion").also { it.mkdirs() }

    /** Copies a content URI's bytes into a cache temp so file-based detection can scan it.
     *  Returns null on any read failure. */
    private fun stageContentToTemp(uri: Uri): File? = runCatching {
        val tmp = File.createTempFile("motion_probe_", ".jpg", motionTempDir())
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: run { tmp.delete(); return@runCatching null }
        tmp
    }.getOrNull()

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

    /** Source folder of [pendingHidePrivateUri], captured at hide time so the eventual unhide
     *  can return the file to its original location. Persisted alongside the private URI in
     *  [SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] once the hide commits. */
    private var pendingHideSourceFolder: String? = null

    /** Original display name of [pendingHidePrivateUri], captured at hide time and persisted in
     *  [SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] once the hide commits so unhide can restore
     *  the original filename. */
    private var pendingHideOriginalName: String? = null

    /**
     * Moves a photo to the Hidden vault: copy bytes to app-private storage, stage the URI as
     * pending (not yet in HIDDEN_PHOTO_URIS), then delete the MediaStore original (Android 11+
     * system trash dialog). Only a successful delete commits the hidden URI; cancel drops the
     * orphaned copy so the photo can't end up in both places.
     */
    fun hideItem(item: GalleryItem) {
        viewModelScope.launch {
            // Pull dateTaken alongside so the hidden file can preserve its capture-time
            // through the round-trip — see HiddenStorageManager.store(captureTimeMs).
            val sourceUri: String; val displayName: String; val mime: String; val dateTakenMs: Long
            val cloudLinkId: String?; val bucketName: String?
            when (item) {
                is GalleryItem.LocalOnly -> {
                    sourceUri = item.local.uri; displayName = item.local.displayName
                    mime = item.local.mimeType; dateTakenMs = item.local.dateTaken; cloudLinkId = null
                    bucketName = item.local.bucketName
                }
                is GalleryItem.Synced -> {
                    sourceUri = item.local.uri; displayName = item.local.displayName
                    mime = item.local.mimeType; dateTakenMs = item.local.dateTaken; cloudLinkId = item.cloud.linkId
                    bucketName = item.local.bucketName
                }
                is GalleryItem.CloudOnly -> return@launch
            }
            // An already-hidden file:// URI would crash createTrashRequest (content:// only) — bail.
            if (hiddenStorage.isHiddenUri(sourceUri)) {
                Log.w("PhotoViewerVM", "hideItem called on already-hidden URI; use unhideHiddenItem instead")
                return@launch
            }
            val sourceFolder = withContext(Dispatchers.IO) {
                hiddenStorage.sourceFolderFor(sourceUri, bucketName)
            }
            val privateUri = withContext(Dispatchers.IO) {
                hiddenStorage.store(sourceUri, displayName, mime, captureTimeMs = dateTakenMs)
            }
            if (privateUri == null) {
                _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_hide_failed))
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
            pendingHideSourceFolder = sourceFolder
            pendingHideOriginalName = displayName.takeIf { it.isNotBlank() }

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
                    _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_hide_failed))
                }
            }
        }
    }

    private fun commitPendingHide() {
        val privateUri = pendingHidePrivateUri ?: return
        val sourceFolder = pendingHideSourceFolder
        val originalName = pendingHideOriginalName
        pendingHidePrivateUri = null
        pendingHideSourceFolder = null
        pendingHideOriginalName = null
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + privateUri
                if (!sourceFolder.isNullOrBlank()) {
                    val folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] = folders + "$privateUri|$sourceFolder"
                }
                if (!originalName.isNullOrBlank()) {
                    val names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] = names + "$privateUri|$originalName"
                }
            }
            _isHidden.value = true
        }
    }

    private fun cancelPendingHide() {
        val privateUri = pendingHidePrivateUri ?: return
        pendingHidePrivateUri = null
        pendingHideSourceFolder = null
        pendingHideOriginalName = null
        // Drop the orphaned private copy — the user did not confirm the system delete.
        viewModelScope.launch(Dispatchers.IO) {
            hiddenStorage.delete(privateUri)
        }
    }

    /**
     * Restores a hidden item back to MediaStore and removes the private copy. HIDDEN_PHOTO_URIS
     * and HIDDEN_URI_CLOUD_ID_MAP are mutated in one edit{} block so a concurrent hide can't
     * desync them.
     */
    fun unhideHiddenItem(hiddenUri: String, originalDisplayName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            // Restore to the folder the file was hidden from (recorded at hide time); falls
            // back to the Pictures/Movies root when no source folder was captured.
            val prefsSnapshot = context.settingsDataStore.data.first()
            val sourceFolder = prefsSnapshot[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP]
                ?.firstOrNull { it.startsWith("$hiddenUri|") }
                ?.substringAfter('|')
            // Prefer the name persisted at hide time over any passed-in name, which may be
            // derived from the private UUID file and would restore as a code.
            val resolvedName = prefsSnapshot[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP]
                ?.firstOrNull { it.startsWith("$hiddenUri|") }
                ?.substringAfter('|')
                ?: originalDisplayName
            hiddenStorage.restore(hiddenUri, resolvedName, albumFolderName = sourceFolder)
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - hiddenUri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] =
                    mapping.filterNot { it.startsWith("$hiddenUri|") }.toSet()
                val folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] =
                    folders.filterNot { it.startsWith("$hiddenUri|") }.toSet()
                val names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] =
                    names.filterNot { it.startsWith("$hiddenUri|") }.toSet()
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
            // Offline pin reflects only a cloud-only item — a Synced/LocalOnly photo already
            // has its bytes on the device, so it has no separate offline blob. The check is a
            // directory walk, so keep it off the main thread (this runs on every page-settle).
            _isOffline.value = (item as? GalleryItem.CloudOnly)?.let {
                withContext(Dispatchers.IO) { offlineStore.isOffline(it.cloud.linkId) }
            } ?: false
            // Seed the category tags for the details-sheet chips (cloud-backed photos only).
            _currentPhotoTags.value = when (item) {
                is GalleryItem.Synced    -> item.cloud.tags
                is GalleryItem.CloudOnly -> item.cloud.tags
                is GalleryItem.LocalOnly -> emptySet()
            }
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
     * Pins (downloads a full-res blob into the offline store) or un-pins the current cloud-only
     * photo for offline viewing. CloudOnly only — a Synced/LocalOnly item already has its bytes
     * on the device. The pin set in [SettingsKeys.OFFLINE_PIN_IDS] is flipped optimistically
     * (mirroring [toggleFavorite]); a failed download reverts both the pin and the blob.
     */
    fun toggleOfflinePin(item: GalleryItem) {
        val cloud = (item as? GalleryItem.CloudOnly)?.cloud ?: return
        val linkId = cloud.linkId
        // Branch on the displayed state (kept current by checkIfFavorite) instead of a disk walk
        // on the main thread; the button always reflects the settled item being toggled.
        if (_isOffline.value) {
            // Un-pin: drop the blob and the pin immediately; no network needed.
            viewModelScope.launch {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - linkId
                }
                offlineStore.delete(linkId)
                _isOffline.value = false
                _offlineMessage.emit(context.getString(R.string.offline_removed))
            }
            return
        }
        // Pin: optimistically mark on, then download the full-res blob into offline storage.
        _isOffline.value = true
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                prefs[SettingsKeys.OFFLINE_PIN_IDS] = current + linkId
            }
            try {
                val userId = accountManager.getPrimaryUserId().first()
                    ?: error("not logged in")
                val file = cloudRepo.downloadFullResPhoto(userId, cloud)
                offlineStore.store(linkId, file)
                // If the photo was un-pinned (or the user signed out) while this was downloading,
                // the blob would outlive its pin — drop it so the disk never drifts from the set.
                val stillPinned = context.settingsDataStore.data.first()[SettingsKeys.OFFLINE_PIN_IDS]
                    ?.contains(linkId) == true
                if (!stillPinned) {
                    offlineStore.delete(linkId)
                    return@launch
                }
                _offlineMessage.emit(context.getString(R.string.offline_available))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Revert the optimistic pin so the button doesn't claim an offline copy exists.
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - linkId
                }
                offlineStore.delete(linkId)
                _isOffline.value = false
                _offlineMessage.emit(context.getString(R.string.offline_failed))
            }
        }
    }

    /**
     * Adds or removes a single category [tagId] on the current photo. Cloud-backed photos only —
     * a local-only item has no Drive link to tag. Optimistically flips the chip, then writes the
     * tag through the metadata-only [DrivePhotoRepository.setCloudTag] (no content/revision touch);
     * reverts the chip if the write is rejected.
     */
    fun setPhotoTag(item: GalleryItem, tagId: Int, add: Boolean) {
        val cloudPhoto = when (item) {
            is GalleryItem.Synced    -> item.cloud
            is GalleryItem.CloudOnly -> item.cloud
            is GalleryItem.LocalOnly -> return
        }
        viewModelScope.launch {
            val previous = _currentPhotoTags.value
            _currentPhotoTags.value = if (add) previous + tagId else previous - tagId
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _currentPhotoTags.value = previous
                return@launch
            }
            val ok = cloudRepo.setCloudTag(userId, cloudPhoto, tagId, add)
            if (!ok) _currentPhotoTags.value = previous
        }
    }

    /**
     * Renames [item] to [newName].
     * - [replaceOriginal] true ("Rename original"): local renamed in-place; cloud re-uploaded with
     *   the new name and the old linkId trashed.
     * - [replaceOriginal] false ("Save as copy"): a new MediaStore entry / new cloud linkId; the
     *   original stays.
     */
    fun renameItem(item: GalleryItem, newName: String, replaceOriginal: Boolean, sourceAlbumLinkId: String? = null) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            _renameState.value = RenameState.Failed(context.getString(R.string.viewer_name_empty))
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
                onFailure = { e ->
                    val uri = (item as? GalleryItem.Synced)?.local?.uri
                        ?: (item as? GalleryItem.LocalOnly)?.local?.uri
                    // A camera-roll file the app doesn't own throws a SecurityException on the
                    // in-place DISPLAY_NAME update (Android 11+ scoped storage). Ask the OS for
                    // one-shot write access — same flow as metadata strip — and retry on consent.
                    if (replaceOriginal && uri != null && e is SecurityException
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ) {
                        pendingRename = PendingRenameRequest(item, trimmed, replaceOriginal, sourceAlbumLinkId)
                        RenameState.NeedsPermission(
                            MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(uri))),
                        )
                    } else {
                        RenameState.Failed(e.message ?: context.getString(R.string.viewer_rename_failed))
                    }
                },
            )
        }
    }

    /** Re-runs the rename the screen deferred for write consent, now that the user granted it. */
    fun retryPendingRename() {
        val p = pendingRename ?: return
        pendingRename = null
        renameItem(p.item, p.newName, p.replaceOriginal, p.sourceAlbumLinkId)
    }

    fun resetRenameState() {
        pendingRename = null
        _renameState.value = RenameState.Idle
    }

    private suspend fun renameLocal(uri: String, newName: String, replaceOriginal: Boolean) {
        val parsed = android.net.Uri.parse(uri)
        // Hidden-vault file:// URIs aren't MediaStore content URIs (ContentResolver.update throws
        // "Unknown URI"); route to the dedicated rename that preserves the __<captureMs>__ tag.
        if (hiddenStorage.isHiddenUri(uri)) {
            val newUri = hiddenStorage.rename(uri, newName)
                ?: error("Hidden rename failed (file may already exist with that name)")
            // Swap the URI in both DataStore sets, or the renamed file leaks back into the listing.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = (current - uri) + newUri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                val updatedMapping = mapping.map { entry ->
                    if (entry.startsWith("$uri|")) "$newUri|${entry.substringAfter('|')}"
                    else entry
                }.toSet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = updatedMapping
                val folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] = folders.map { entry ->
                    if (entry.startsWith("$uri|")) "$newUri|${entry.substringAfter('|')}"
                    else entry
                }.toSet()
                val names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] = names.map { entry ->
                    if (entry.startsWith("$uri|")) "$newUri|${entry.substringAfter('|')}"
                    else entry
                }.toSet()
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

        // Carry the source's capture date onto the copy. Without it the new row gets
        // DATE_TAKEN = insert time (today), so a renamed copy would jump to the top of the
        // gallery. The raw byte copy keeps the original EXIF for the scanner, and the column is
        // re-asserted after the IS_PENDING flip because the publish scan can clobber a timestamp
        // set during insert (the same Android 13+ behaviour the download path handles).
        val srcDateTakenMs = runCatching {
            context.contentResolver.query(
                parsed, arrayOf(android.provider.MediaStore.MediaColumns.DATE_TAKEN), null, null, null,
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            if (srcDateTakenMs > 0L) {
                put(android.provider.MediaStore.MediaColumns.DATE_TAKEN, srcDateTakenMs)
                put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, srcDateTakenMs / 1000L)
            }
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
            if (srcDateTakenMs > 0L) {
                val dateValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DATE_TAKEN, srcDateTakenMs)
                    put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, srcDateTakenMs / 1000L)
                }
                context.contentResolver.update(target, dateValues, null, null)
            }
        }
    }

    private suspend fun renameCloud(photo: CloudPhoto, newName: String, replaceOriginal: Boolean, sourceAlbumLinkId: String?) {
        val userId = accountManager.getPrimaryUserId().first()
            ?: error(context.getString(R.string.viewer_not_signed_in))
        val newLinkId = cloudRepo.renameOrCopyCloudPhoto(userId, photo, newName, trashOriginal = replaceOriginal)
        // Keep the new linkId in the same album the source was in (best-effort).
        sourceAlbumLinkId?.let { albumId ->
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumId, listOf(newLinkId)) }
        }
    }

    /** Drops the renamed item's cached Coil bytes (and, for a cloud replace-original, the trashed
     *  linkId's full-res blob) so the stale original stops showing once the rename lands. */
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
            // Seed from the cached set first — the same full list the Albums tab and the gallery
            // picker show (including albums created this session). A fresh network fetch alone can
            // surface fewer entries while it's slow or a detail chunk fails, which left the viewer's
            // picker missing albums. Then refresh from the network.
            runCatching { cloudRepo.loadAlbumsCached() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { _albums.value = it }
            runCatching { cloudRepo.loadAlbums(userId) }
                .onSuccess { _albums.value = it }
                .onFailure { e ->
                    // Passive prefetch on viewer open: a no-network failure is expected (e.g.
                    // opening a pinned photo offline) and must not pop a "no connection" snackbar.
                    // Surface only a genuine, non-connectivity error; the add-to-album sheet copes
                    // with an empty list.
                    if (friendlyNetworkError(e, networkObserver.isOnline.value, context) == null) {
                        _transientError.value = context.getString(
                            R.string.viewer_load_albums_failed,
                            eu.akoos.photos.util.sanitizeErrorMessage(e.message),
                        )
                    }
                }
        }
    }

    /** Resolves the cloud-album linkIds containing [item] into [currentPhotoAlbumIds] so the picker
     *  can mark member rows. No-op for LocalOnly. */
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

    /** Removes the viewed cloud item from [albumLinkId] (counterpart to [addToAlbum]), refreshing
     *  [currentPhotoAlbumIds] so the checkmark clears. */
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
                .onFailure { e ->
                    _transientError.value = context.getString(
                        R.string.viewer_remove_from_album_failed,
                        e.message ?: context.getString(R.string.viewer_unknown_error),
                    )
                }
            _isAddingToAlbum.value = false
        }
    }

    /** Sets the viewed cloud photo as the cover of [albumLinkId]; emits [setCoverDone] on success.
     *  No-op for LocalOnly (the menu item is gated on a cloud item). */
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
                    _transientError.value = context.getString(
                        R.string.viewer_set_cover_failed,
                        e.message ?: context.getString(R.string.viewer_unknown_error),
                    )
                }
        }
    }

    /** Upload the viewed local photo, wait for its cloud id, then mint a public link — the manage
     *  sheet shows the shared Loading spinner throughout, matching the gallery's flow. */
    fun uploadAndCreateViewedLink(item: GalleryItem) {
        val uri = (item as? GalleryItem.LocalOnly)?.local?.uri ?: return
        publicLink.uploadAndCreate(viewModelScope, uri)
    }

    /** Adds the viewed item to a cloud album. A LocalOnly photo is queued to upload first and
     *  auto-joins the album once backed up — same as the gallery's multi-select. */
    fun addToAlbum(albumLinkId: String, item: GalleryItem) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Resolve the target album's display name for the success snackbar before the
            // network call — looking it up afterwards risks a stale list reference if the
            // album refreshed between submit and ack.
            val targetName = _albums.value.firstOrNull { it.linkId == albumLinkId }?.name ?: ""
            val linkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> {
                    _isAddingToAlbum.value = true
                    runCatching { forceUploadLocalUris.queueForAlbum(userId, albumLinkId, listOf(item.local.uri)) }
                    _isAddingToAlbum.value = false
                    if (targetName.isNotEmpty()) _addToAlbumDone.tryEmit(targetName)
                    return@launch
                }
            }
            _isAddingToAlbum.value = true
            runCatching { cloudRepo.addPhotosToAlbum(userId, albumLinkId, listOf(linkId)) }
                .onSuccess { result ->
                    if (result.failedLinkIds.isNotEmpty()) {
                        val n = result.failedLinkIds.size
                        _transientError.value = context.resources.getQuantityString(
                            R.plurals.viewer_add_photos_failed, n, n,
                        )
                    } else if (targetName.isNotEmpty()) {
                        _addToAlbumDone.tryEmit(targetName)
                    }
                }
                .onFailure { e ->
                    _transientError.value = context.getString(
                        R.string.viewer_add_to_album_failed,
                        e.message ?: context.getString(R.string.viewer_unknown_error),
                    )
                }
            _isAddingToAlbum.value = false
        }
    }

    /** Creates a Drive album with [name], adds [item], and sets it as cover so the new card
     *  isn't blank. */
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
                _transientError.value = context.getString(
                    R.string.viewer_create_album_failed,
                    e.message ?: context.getString(R.string.viewer_unknown_error),
                )
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

    /** Saves the item to the device gallery. A cloud photo in an album lands in
     *  `Pictures/<AlbumName>/`, otherwise `Pictures/` root. */
    fun downloadToDevice(item: GalleryItem) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _isSavingToDevice.value = true
            // Album membership → Pictures/<AlbumName>/ routing. Usually a cache hit (5-min TTL).
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
                _transientError.value = context.getString(
                    R.string.viewer_save_to_device_failed,
                    e.message ?: context.getString(R.string.viewer_unknown_error),
                )
            }
            _isSavingToDevice.value = false
        }
    }

    /** Resolves a shareable URI and emits the send intent. Local items reuse their content URI;
     *  a cloud-only item is decrypted to the fullres cache (single-flighted) and shared via the
     *  FileProvider, left for the TTL sweep to reclaim. */
    fun shareItem(item: GalleryItem) {
        viewModelScope.launch {
            _isSharing.value = true
            runCatching {
                val uri: Uri = when (item) {
                    is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                    is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                    is GalleryItem.CloudOnly -> {
                        // Offline pin short-circuit: a pinned blob already holds the full-res
                        // bytes in app-private storage, so share it with no network download. The
                        // blob dir isn't a configured FileProvider root, so copy it into the
                        // canonical fullres path the provider already exposes (a local, instant copy).
                        val file = withContext(Dispatchers.IO) {
                            offlineStore.findBlob(item.cloud.linkId)
                                ?.takeIf { it.length() > 0 }
                                ?.let { blob ->
                                    eu.akoos.photos.data.repository.drive.PhotoDownloadService
                                        .fullResFile(context, item.cloud)?.also { dest ->
                                        if (!dest.exists() || dest.length() == 0L) {
                                            dest.parentFile?.mkdirs()
                                            blob.copyTo(dest, overwrite = true)
                                        }
                                    }
                                }
                        } ?: run {
                            val userId = accountManager.getPrimaryUserId().first()
                                ?: error(context.getString(R.string.viewer_not_signed_in))
                            cloudRepo.downloadFullResPhoto(userId, item.cloud)
                        }
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
                _transientError.value = context.getString(
                    R.string.viewer_share_failed,
                    e.message ?: context.getString(R.string.viewer_unknown_error),
                )
            }
            _isSharing.value = false
        }
    }

    /** Seeds [publicLinkState] from any existing link when the share sheet opens. LocalOnly has no
     *  linkId, so it stays None and the sheet shows the "back up first" note. */
    fun loadPublicLink(item: GalleryItem) {
        publicLink.load(viewModelScope, item.cloudLinkIdOrNull(), setLoading = false)
    }

    /** Mint a public link for the photo the sheet is acting on. None/Error → Loading → Active/Error. */
    fun createPublicLink() = publicLink.create(viewModelScope)

    /** Revoke the photo's public link. On success the sheet returns to None; on failure it
     *  surfaces a localized error inline so the user can retry. */
    fun revokePublicLink() = publicLink.revoke(viewModelScope)

    /** The live public-link URL if one is currently active, for the screen's copy-to-clipboard. */
    fun currentPublicLinkUrl(): String? = publicLink.currentUrl()

    /** Sets ([password] non-blank) or clears (null/blank → random anyone-with-the-link) the
     *  custom password. No-op if no link exists yet. */
    fun setLinkPassword(password: String?) = publicLink.setPassword(viewModelScope, password)

    /** Cloud Drive linkId for a Synced/CloudOnly item, or null for LocalOnly. */
    private fun GalleryItem.cloudLinkIdOrNull(): String? = when (this) {
        is GalleryItem.Synced    -> cloud.linkId
        is GalleryItem.CloudOnly -> cloud.linkId
        is GalleryItem.LocalOnly -> null
    }

    /** Clear any per-photo public-link state so a stale link can't show on the next photo. */
    private fun resetPublicLinkState() = publicLink.reset()

    fun loadCloud(photo: CloudPhoto) {
        resetMotionState()
        resetPanoramaState()
        resetPublicLinkState()
        val isVideo = photo.mimeType.startsWith("video/")
        val itemKey = photo.linkId

        // Reset the resolved-size fallback so the details sheet doesn't show last item's
        // size while the new page is still downloading.
        _cloudFullResSize.value = null
        _cloudVideoMeta.value = null
        _fullResBlockedByMetered.value = false
        // Clear the previous photo's EXIF; the cloud photo's own metadata is read from its
        // decrypted full-res once the download lands (see onSuccess below).
        _metadata.value = null

        if (photo.thumbnailUrl != null) {
            // Thumbnail placeholder while downloading full-res (videos too).
            _state.value = ViewerState.ShowImage(photo.thumbnailUrl, itemKey = itemKey)
        } else {
            _state.value = ViewerState.Loading
        }

        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (photo.thumbnailUrl == null) _state.value = ViewerState.Error(context.getString(R.string.viewer_not_logged_in))
                return@launch
            }
            // Offline pin short-circuit: a pinned photo's full-res blob lives in app-private
            // storage, so serve it straight from disk (no network, works fully offline) before
            // the metered gate or any download. Mirrors the cached-blob success branch below.
            val pinned = offlineStore.findBlob(photo.linkId)
            if (pinned != null && pinned.length() > 0) {
                val pinnedUri = Uri.fromFile(pinned)
                _cloudFullResSize.value = pinned.length()
                if (_state.value.itemKey != itemKey) return@launch
                _state.value = if (isVideo)
                    ViewerState.ShowVideo(pinnedUri, itemKey = itemKey, isFullRes = true)
                else
                    ViewerState.ShowImage(pinnedUri, itemKey = itemKey, isFullRes = true)
                if (!isVideo) loadMetadata(pinnedUri.toString())
                else _cloudVideoMeta.value = readVideoMeta(pinned)
                return@launch
            }
            // Wi-Fi-only-for-fullres gate: on a metered network hold the auto-download and let the
            // thumbnail stand in. Skip the gate if the blob is already cached (no metered bytes
            // consumed); explicit actions (Save, Edit) bypass it elsewhere.
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
                    _state.value = ViewerState.Error(context.getString(R.string.viewer_wifi_for_full_quality))
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
                    // Drop a late blob if the user swiped away. Strict `!=` (no `?: itemKey`): a
                    // null itemKey during a swap must NOT pass the guard and flash the old photo.
                    if (_state.value.itemKey != itemKey) return@fold
                    _state.value = if (isVideo)
                        ViewerState.ShowVideo(fileUri, itemKey = itemKey, isFullRes = true)
                    else
                        ViewerState.ShowImage(fileUri, itemKey = itemKey, isFullRes = true)
                    // Read EXIF from the decrypted full-res so the details sheet matches a local
                    // photo's. Videos have none; stripped-on-upload simply shows nothing.
                    if (!isVideo) loadMetadata(fileUri.toString())
                    // A video's resolution + length live in the container, not EXIF — read them off
                    // the decrypted blob so a cloud-only video's details fill in like a local one's.
                    else _cloudVideoMeta.value = readVideoMeta(file)
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
