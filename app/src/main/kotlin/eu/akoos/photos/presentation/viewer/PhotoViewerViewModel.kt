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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.UndoController
import eu.akoos.photos.presentation.common.buildHideUndoAction
import eu.akoos.photos.presentation.gallery.isItemFavorite
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.data.db.dao.LocalTagDao
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.hidden.HiddenVaultDiagnostics
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.data.hidden.HiddenVaultRecords
import eu.akoos.photos.data.hidden.HiddenVaultRestorer
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.data.ocr.OcrModelManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.InvalidateStrippedLocationsUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.MotionPhotoUtil
import eu.akoos.photos.util.PhotoGpsResolver
import eu.akoos.photos.util.PhotoMetadata
import eu.akoos.photos.util.UserPhotoTags
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.originalUriForExif
import eu.akoos.photos.util.retryOnDbTear
import java.io.File
import javax.inject.Inject

/** Raw stream dimensions + length of a cloud video, read off its decrypted full-res for the
 *  details sheet (a cloud-only video carries no on-device media row and no EXIF). */
data class CloudVideoMeta(val width: Int, val height: Int, val durationMs: Long)

/** The fix the details sheet states, in degrees. Resolved by [PhotoGpsResolver] exactly as the place
 *  name is, so a photo whose only fix is the backfilled cloud one still names its coordinates. */
data class DetailsGps(val latitude: Double, val longitude: Double)

/** Album context for the details sheet: the device MediaStore bucket the photo lives in (null when
 *  there is no on-device copy) and the names of every cloud album that owns it (a photo can belong
 *  to many). */
data class DetailsAlbums(val localFolder: String? = null, val cloudAlbums: List<String> = emptyList())

/** A completed album removal: the album the photo was taken out of, plus the photo's Drive linkId.
 *  Both ids travel together so a viewer can tell a removal from the album it was opened from apart
 *  from one aimed at any other album the photo also belongs to. */
data class AlbumRemoval(val albumLinkId: String, val photoLinkId: String)

/**
 * The device-side favourite set as it stands after toggling [item] to [nowFavorite].
 *
 * The set speaks for a photo that lives only on the device and for nothing else, so only such a
 * photo's uri goes into it or comes out of it. A backed-up photo keeps its heart on Drive as tag 0,
 * which is what every reader asks (see [isItemFavorite]), so it never gains an entry here, and any
 * entry it still carries under either key it can be stored under comes out: the local uri from while
 * it was device-only, the linkId from while it was cloud-only. Neither is read for it, and once the
 * photo is keyed the other way round no toggle reaches it either. That is the whole cleanup, spread
 * over the photos the user touches rather than run as a pass of its own.
 */
internal fun favoriteIdsAfterToggle(
    item: GalleryItem,
    favoriteIds: Set<String>,
    nowFavorite: Boolean,
): Set<String> = when (item) {
    is GalleryItem.LocalOnly ->
        if (nowFavorite) favoriteIds + item.local.uri else favoriteIds - item.local.uri
    is GalleryItem.Synced    -> favoriteIds - item.local.uri - item.cloud.linkId
    is GalleryItem.CloudOnly -> favoriteIds - item.cloud.linkId
}

/**
 * Where the heart settles after a backed-up photo's cloud write is answered: on [attempted] when the
 * write landed, back on [previous] when it did not.
 *
 * A heart that flipped on the tap but whose write was rejected (offline, signed out, a server that
 * refused) would otherwise show a favourite Drive never recorded, and the next listing would take it
 * away with no explanation. Being unable to name the user counts as a rejected write, since no tag
 * can be written without one.
 */
internal fun favoriteAfterCloudWrite(previous: Boolean, attempted: Boolean, ok: Boolean): Boolean =
    if (ok) attempted else previous

/**
 * Whether the metadata state describes a photo other than the settled one, so it has to go before the
 * incoming read lands.
 *
 * [describes] is the photo the state was read for and [settled] the one now on screen. Not every
 * settle produces a read (a video carries no EXIF), so without this the rows would keep stating the
 * photo the user swiped away from. A re-read of the same photo answers false and its rows stay put,
 * which keeps an editor save from blinking them away and back.
 */
internal fun metadataOutlivesPhoto(describes: String?, settled: String): Boolean =
    describes != null && describes != settled

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: DrivePhotoRepository,
    private val accountManager: AccountManager,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
    private val hiddenStorage: HiddenStorageManager,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val hiddenVaultRestorer: HiddenVaultRestorer,
    private val hiddenVaultEditor: eu.akoos.photos.data.hidden.HiddenVaultEditor,
    private val offlineStore: OfflineStorageManager,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val forceUploadLocalUris: eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val photoLocationDao: PhotoLocationDao,
    private val localTagDao: LocalTagDao,
    private val photoListingDao: PhotoListingDao,
    private val faceDao: eu.akoos.photos.data.db.dao.FaceDao,
    private val personDao: eu.akoos.photos.data.db.dao.PersonDao,
    private val faceIndexingScheduler: eu.akoos.photos.data.face.FaceIndexingScheduler,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val invalidateStrippedLocations: InvalidateStrippedLocationsUseCase,
    private val undoController: UndoController,
    private val thumbnailUrlStore: eu.akoos.photos.data.repository.drive.ThumbnailUrlStore,
    private val cloudTrashService: eu.akoos.photos.data.repository.drive.CloudTrashService,
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

    /** Fires when an undo puts a deleted photo back. The viewer drops photos it deleted from its own
     *  pager, and only this tells it that one of them is worth showing again. */
    val undoRestored: SharedFlow<UndoAction> = undoController.restored

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

    /** The fix [detailsPlace] is the coarse reading of, so the sheet's coordinate rows state the very
     *  location its place row names. Filled by [loadDetailsPlace] alongside the place name; null while
     *  it resolves / when the photo carries no fix. */
    private val _detailsGps = MutableStateFlow<DetailsGps?>(null)
    val detailsGps: StateFlow<DetailsGps?> = _detailsGps.asStateFlow()

    /** Identity of the photo the metadata state describes: a device photo's uri, a cloud photo's
     *  linkId. [retargetMetadata] compares the settled photo against it, so the rows never outlive
     *  the photo they were read for. */
    private var metadataItemKey: String? = null

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

    /** Local folder + cloud album names for the viewed photo, shown on the details sheet. Reset to
     *  empty per photo and filled lazily by [loadDetailsAlbums] so the sheet never blocks on it. */
    private val _detailsAlbums = MutableStateFlow(DetailsAlbums())
    val detailsAlbums: StateFlow<DetailsAlbums> = _detailsAlbums.asStateFlow()

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

    /** One-shot on a successful remove-from-album, so a viewer showing that album's membership can
     *  drop the photo from its pager. Same replay=0 + single-buffer shape as [addToAlbumDone];
     *  failures flow through [transientError] and emit nothing, leaving the photo on screen. */
    private val _removeFromAlbumDone = MutableSharedFlow<AlbumRemoval>(replay = 0, extraBufferCapacity = 1)
    val removeFromAlbumDone: SharedFlow<AlbumRemoval> = _removeFromAlbumDone.asSharedFlow()

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
    }
        .retryOnDbTear("ViewerLocalUris")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
                is DeletePhotoUseCase.Result.Success           -> {
                    // A cloud-trash delete is reversible; offer Undo through the shared bar so it
                    // shows even after the viewer closes. A local-only free-up carries no undo.
                    if (deleteFromCloud) {
                        val linkId = when (item) {
                            is GalleryItem.Synced    -> item.cloud.linkId
                            is GalleryItem.CloudOnly -> item.cloud.linkId
                            is GalleryItem.LocalOnly -> null
                        }
                        if (linkId != null) undoController.offer(UndoAction.Delete(cloudLinkIds = listOf(linkId)))
                    }
                    DeleteState.Done
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> DeleteState.Failed(context.getString(R.string.viewer_delete_drive_failed))
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    pendingPermissionResult = result
                    DeleteState.NeedsPermission(result.pendingIntent)
                }
            }
        }
    }

    /** Whether [uri] names a file in the app-private vault. Exposed so the screen can ask it about the
     *  photo it is drawing, rather than being told by whichever surface opened the viewer. */
    fun isVaultUri(uri: String): Boolean = hiddenStorage.isHiddenUri(uri)

    /**
     * The vaulted photos that still have a Drive copy, so the viewer's status badge answers for one
     * the same way the vault's grid does.
     *
     * A vaulted photo reaches every screen as a device-only item — vaulting removes the MediaStore row
     * that made it a [GalleryItem.Synced] — so nothing about the item itself can say a Drive copy is
     * there, and the vault's own cloud-id records are the only thing that still can. Keyed by vault
     * uri, so membership already means the photo is one the vault holds and nothing further has to be
     * asked. A rename re-keys the record in the same edit that moves the file, so the badge follows the
     * photo instead of being left on a path nothing is at.
     */
    val pairedVaultUris: StateFlow<Set<String>> = context.settingsDataStore.data
        .map { HiddenVaultRecords.pairedUris(it[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Master AI-features gate (Settings, AI and machine learning). Off suppresses the Copy-text long
     *  press so no text-detection model is ever fetched from the viewer. Observed, so a change made in
     *  Settings is reflected the next time the viewer is opened. */
    val aiFeaturesEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.AI_FEATURES_ENABLED] ?: false }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Resolves the Copy-text opt-in fallback for a device that never touched the switch: whether the
    // reader's models are already on disk. Non-suspend and cheap enough to answer inline.
    private val ocrModelManager by lazy { OcrModelManager(context) }

    /** Copy-text gate (Settings, AI and machine learning): the master switch AND the per-feature Copy
     *  text opt-in, whose absent state falls back to the reader's models already being on disk so a user
     *  who used Copy text before keeps it. Off keeps the long press to read inert, so no OCR model is
     *  fetched from the viewer. Observed, so a Settings change is reflected the next time it is opened. */
    val copyTextEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map {
            it[SettingsKeys.AI_FEATURES_ENABLED] == true &&
                (it[SettingsKeys.OCR_ENABLED] ?: ocrModelManager.filesPresentQuick())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Per-feature face gate (Settings, AI and machine learning). AND-ed with [aiFeaturesEnabled] before
     *  the viewer offers the people-in-this-photo action, so turning faces off suppresses it while the
     *  master AI switch stays on. Observed, so a Settings change is reflected the next time the viewer
     *  is opened. */
    val faceEnabled: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.FACE_ENABLED] ?: false }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** One person the face index found on the photo now on screen, for the "people in this photo" bar.
     *  [faceBox] is the 0..1 crop of this photo, so the chip shows the person's face from this frame. */
    data class ViewerPerson(
        val personId: Long,
        val name: String?,
        val faceBox: eu.akoos.photos.presentation.gallery.FaceBox,
    )

    private val _peopleInPhoto = MutableStateFlow<List<ViewerPerson>>(emptyList())
    /** The people found on the settled photo, named first; empty when AI is off or none are grouped. */
    val peopleInPhoto: StateFlow<List<ViewerPerson>> = _peopleInPhoto.asStateFlow()

    /**
     * Resolve the grouped faces on [photoKey] to the people they belong to, so the viewer can offer a
     * jump to each. One chip per person (the clearest face), named people before unnamed clusters.
     * Clears when AI is off, so nothing is read or shown for a user who never opted in.
     */
    fun loadPeopleInPhoto(item: GalleryItem) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            val prefs = context.settingsDataStore.data.first()
            val aiOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
            _peopleInPhoto.value =
                if (!aiOn || userId == null) emptyList() else resolvePeople(item, userId, detectIfEmpty = false)
        }
    }

    /**
     * Detect the faces on [item] now if it has none grouped yet, and return the people found, updating
     * the bar. Called from a long-press on a spot with no already-known face, so tagging works on a
     * photo the background walk has not reached (the way the text read runs on demand). Empty when AI is
     * off. The detection itself runs off the main thread.
     */
    suspend fun detectFacesNow(item: GalleryItem): List<ViewerPerson> {
        val userId = accountManager.getPrimaryUserId().first() ?: return emptyList()
        val prefs = context.settingsDataStore.data.first()
        val aiOn = prefs[SettingsKeys.AI_FEATURES_ENABLED] == true && prefs[SettingsKeys.FACE_ENABLED] == true
        if (!aiOn) return emptyList()
        val list = resolvePeople(item, userId, detectIfEmpty = true)
        _peopleInPhoto.value = list
        return list
    }

    /** The grouped faces on [item] as people; when [detectIfEmpty] and none are grouped yet, scan the
     *  photo on demand first (a no-op on one already scanned) so a face can still be found. */
    private suspend fun resolvePeople(
        item: GalleryItem,
        userId: me.proton.core.domain.entity.UserId,
        detectIfEmpty: Boolean,
    ): List<ViewerPerson> {
        val account = userId.id
        val photoKey = item.stableId
        var faces = runCatching { faceDao.groupedFacesForPhoto(account, photoKey) }.getOrDefault(emptyList())
        if (faces.isEmpty() && detectIfEmpty) {
            val scanned = runCatching {
                withContext(Dispatchers.Default) { faceIndexingScheduler.indexPhotoOnDemand(item, userId, force = true) }
            }.getOrDefault(false)
            if (scanned) faces = runCatching { faceDao.groupedFacesForPhoto(account, photoKey) }.getOrDefault(emptyList())
        }
        val byPerson = LinkedHashMap<Long, ViewerPerson>()
        for (f in faces) {
            val pid = f.personId ?: continue
            if (byPerson.containsKey(pid)) continue
            val name = personDao.personById(pid)?.displayName?.takeIf { it.isNotBlank() }
            byPerson[pid] = ViewerPerson(
                pid, name,
                eu.akoos.photos.presentation.gallery.FaceBox(f.left, f.top, f.right, f.bottom),
            )
        }
        return byPerson.values.sortedByDescending { it.name != null }
    }

    /**
     * Where each vaulted photo moved since the pager took its snapshot, so the page keeps showing the
     * same photo after a rename or a date edit moved its file.
     *
     * The pager works from a list captured when the grid was tapped, and [liveItems] leaves vaulted
     * photos out on purpose, so nothing else would ever tell it the file is somewhere new — the page
     * would go on pointing at a path nothing is at, and every action taken from it with it. Published by
     * the vault itself, so an edit made on the metadata screen in front of the viewer arrives the same
     * way one made in the viewer does.
     */
    val vaultMoves: StateFlow<Map<String, eu.akoos.photos.data.hidden.VaultMove>>
        get() = hiddenVaultEditor.moves

    /**
     * Delete a vaulted photo outright.
     *
     * Its bytes are an app-private file with no MediaStore row, so the system trash the ordinary
     * delete goes through cannot take it and there is no cloud copy to answer for: the whole delete
     * is the vault's own file and the records that point at it. Reports through the same
     * [DeleteState] the ordinary delete does, so the screen drops the photo from the pager the one
     * way it already knows.
     */
    fun deleteVaultedItem(item: GalleryItem) {
        val uri = PhotoViewerVaultGate.vaultUriOf(item, ::isVaultUri) ?: return
        viewModelScope.launch {
            _deleteState.value = DeleteState.Working
            _deleteState.value = try {
                if (hiddenVaultRestorer.deletePhoto(uri)) DeleteState.Done
                else DeleteState.Failed(context.getString(R.string.viewer_delete_vault_failed))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("PhotoViewerVM", "vault delete failed: ${e.message}")
                DeleteState.Failed(context.getString(R.string.viewer_delete_vault_failed))
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
            // Snapshot the vault URI first: commitPendingHide() clears it.
            val hiddenUri = pendingHidePrivateUri
            commitPendingHide()
            if (pending != null && pending.hide) {
                // The bytes were copied into the vault before the MediaStore original was removed, so the
                // hide restores from the vault regardless of the system trash; offer it on the shared bar.
                buildHideUndoAction(listOfNotNull(hiddenUri))?.let { undoController.offer(it) }
            } else if (pending != null && !pending.hide) {
                // The device trash keeps the local file, so offer to restore the cloud copy and
                // un-trash the local one together (or just the local one for a device-only delete).
                val localTrashed = pending.itemsBeingDeleted.mapNotNull { di ->
                    when (di) {
                        is GalleryItem.LocalOnly -> di.local.uri
                        is GalleryItem.Synced    -> di.local.uri
                        is GalleryItem.CloudOnly -> null
                    }
                }
                val syncedRelinks = pending.itemsBeingDeleted.mapNotNull { di ->
                    (di as? GalleryItem.Synced)?.let {
                        UndoAction.Delete.Relink(it.local.uri, it.cloud.linkId, it.cloud.sizeBytes)
                    }
                }
                if (pending.cloudLinkIds.isNotEmpty() || localTrashed.isNotEmpty()) {
                    undoController.offer(UndoAction.Delete(pending.cloudLinkIds, localTrashed, syncedRelinks))
                }
            }
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
        retargetMetadata(uri)
        val parsedUri = Uri.parse(uri)
        _state.value = if (mimeType.startsWith("video/"))
            ViewerState.ShowVideo(parsedUri, itemKey = uri)
        else
            ViewerState.ShowImage(parsedUri, itemKey = uri)
        if (mimeType.startsWith("image/")) {
            loadMetadata(uri)
        }
    }

    /** Reads through [originalUriForExif] so a device photo's GPS survives the Android 10+ redaction
     *  and the details sheet can show the coordinates the file actually carries. */
    fun loadMetadata(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _metadata.value = ExifHelper.readMetadata(context, originalUriForExif(context, uri))
        }
    }

    private fun clearMetadata() {
        _metadata.value = null
        _detailsPlace.value = null
        _detailsGps.value = null
    }

    /** Points the metadata state at [itemKey], dropping what it holds when that names another photo.
     *  Called from [loadLocal] / [loadCloud], which is where a page settle arrives. */
    private fun retargetMetadata(itemKey: String) {
        if (metadataOutlivesPhoto(metadataItemKey, itemKey)) clearMetadata()
        metadataItemKey = itemKey
    }

    /**
     * Resolve the current item's place name and coordinates for the details overview's Location rows,
     * from local EXIF GPS or, for a cloud photo, from the stored fix the map backfill records
     * ([PhotoLocationEntity]). Reset to null first so the rows show their placeholder immediately, then
     * filled once resolved. A photo with no GPS simply leaves both null (the rows keep the dash).
     */
    fun loadDetailsPlace(item: GalleryItem) {
        _detailsPlace.value = null
        _detailsGps.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val userId = accountManager.getPrimaryUserId().first()?.id
            val latLng = when (item) {
                is GalleryItem.LocalOnly ->
                    PhotoGpsResolver.localGps(context, item.local.uri, item.local.mimeType)
                is GalleryItem.Synced ->
                    PhotoGpsResolver.localGps(context, item.local.uri, item.local.mimeType)
                        ?: userId?.let { PhotoGpsResolver.cloudGps(photoLocationDao, it, item.cloud.linkId) }
                is GalleryItem.CloudOnly ->
                    userId?.let { PhotoGpsResolver.cloudGps(photoLocationDao, it, item.cloud.linkId) }
            } ?: metadataGps()
            _detailsGps.value = latLng?.let { DetailsGps(it.first, it.second) }
            if (latLng != null) {
                _detailsPlace.value = OfflineGeocoder.reverseGeocode(context, latLng.first, latLng.second)
            }
        }
    }

    /** Fallback coordinates for the place name: the GPS already read from the viewed photo's own
     *  metadata (for a cloud photo, its decrypted blob EXIF). Used when no backfilled cloud fix
     *  exists yet, so the place name resolves whenever the coordinates are already on screen. */
    private fun metadataGps(): Pair<Double, Double>? {
        val meta = _metadata.value ?: return null
        val lat = meta.gpsLatitude ?: return null
        val lng = meta.gpsLongitude ?: return null
        return lat to lng
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

    /** Item-aware hidden check: a device-backed photo is hidden when its uri sits in the device vault
     *  set; a synced/cloud photo is hidden when its cloud linkId sits in the client-side cloud-hidden
     *  set. Drives the viewer's Hide/Unhide label for every item kind, not just device-backed ones. */
    fun checkIfHidden(item: GalleryItem) {
        viewModelScope.launch {
            val prefs = context.settingsDataStore.data.first()
            val hiddenUris = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            val hiddenCloudIds = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
            val deviceHidden = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri in hiddenUris
                is GalleryItem.Synced    -> item.local.uri in hiddenUris
                is GalleryItem.CloudOnly -> false
            }
            val cloudHidden = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId in hiddenCloudIds
                is GalleryItem.CloudOnly -> item.cloud.linkId in hiddenCloudIds
                is GalleryItem.LocalOnly -> false
            }
            _isHidden.value = deviceHidden || cloudHidden
        }
    }

    /** Reveal the item currently shown in the viewer, picking the right mechanism: a client-side cloud
     *  hide drops the linkId from [SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS]; a device-vault hide restores
     *  the file from app-private storage. Lets the viewer unhide a cloud photo too, not just device ones. */
    fun unhideItem(item: GalleryItem) {
        viewModelScope.launch {
            val hiddenCloudIds = context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet() }
                .first()
            val linkId = when (item) {
                is GalleryItem.Synced    -> item.cloud.linkId
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
            if (linkId != null && linkId in hiddenCloudIds) {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = current - linkId
                }
                _isHidden.value = false
            } else {
                val uri = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced    -> item.local.uri
                    is GalleryItem.CloudOnly -> null
                }
                val name = when (item) {
                    is GalleryItem.LocalOnly -> item.local.displayName
                    is GalleryItem.Synced    -> item.local.displayName
                    is GalleryItem.CloudOnly -> null
                }
                if (uri != null) unhideHiddenItem(uri, name)
            }
        }
    }

    /** Holds the private-storage URI of a file currently being hidden, awaiting the user's
     *  confirmation in the system trash dialog. Its intent is journalled; only a confirmed delete
     *  publishes it into [SettingsKeys.HIDDEN_PHOTO_URIS], and a cancel drops both copy and record. */
    private var pendingHidePrivateUri: String? = null

    /**
     * Hide a single photo. A photo with a device file, backed up or not, moves to the Hidden vault:
     * copy bytes to app-private storage, record the intent, then delete the MediaStore original
     * (Android 11+ system trash dialog); only a successful delete publishes the hidden URI, and
     * cancel drops the orphaned copy so the photo can't end up in both places. Recording BEFORE the
     * delete is what makes an interruption between the two repairable — see [HiddenVaultJournal]. A
     * cloud-only photo has no device file to move and hides by its cloud linkId instead. The Drive
     * copy is untouched either way.
     */
    fun hideItem(item: GalleryItem) {
        viewModelScope.launch {
            // Pull the effective capture time alongside so the hidden file can preserve it through
            // the round-trip — see HiddenStorageManager.store(captureTimeMs).
            val sourceUri: String; val displayName: String; val mime: String; val dateTakenMs: Long
            val cloudLinkId: String?; val bucketName: String?; val sizeBytes: Long
            when (item) {
                is GalleryItem.LocalOnly -> {
                    sourceUri = item.local.uri; displayName = item.local.displayName
                    mime = item.local.mimeType; dateTakenMs = item.captureTimeMs; cloudLinkId = null
                    bucketName = item.local.bucketName; sizeBytes = item.local.sizeBytes
                }
                is GalleryItem.Synced -> {
                    // The device file moves into the vault exactly as a device-only photo's does, and
                    // the cloud linkId travels with it: that is what the reveal re-pairs by, so the
                    // photo comes back as the backed-up photo it already is instead of uploading a
                    // second copy of itself. The Drive copy stays where it is throughout.
                    sourceUri = item.local.uri; displayName = item.local.displayName
                    mime = item.local.mimeType; dateTakenMs = item.captureTimeMs
                    cloudLinkId = item.cloud.linkId
                    bucketName = item.local.bucketName
                    sizeBytes = item.local.sizeBytes.takeIf { it > 0L } ?: item.cloud.sizeBytes
                }
                is GalleryItem.CloudOnly -> {
                    HiddenVaultDiagnostics.hideStarted(
                        vaultedCount = 0, pairedCount = 0, filteredCount = 1, totalBytes = 0L,
                    )
                    // No device file to vault: hide the cloud photo client-side by linkId. It drops
                    // from every listing via the HIDDEN_CLOUD_PHOTO_IDS filter, nothing on Drive changes.
                    context.settingsDataStore.edit { prefs ->
                        val existing = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
                        prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = existing + item.cloud.linkId
                    }
                    buildHideUndoAction(emptyList(), listOf(item.cloud.linkId))
                        ?.let { undoController.offer(it) }
                    _isHidden.value = true
                    _deleteState.value = DeleteState.Done
                    return@launch
                }
            }
            // An already-hidden file:// URI would crash createTrashRequest (content:// only) — bail.
            if (hiddenStorage.isHiddenUri(sourceUri)) {
                Log.w("PhotoViewerVM", "hideItem called on already-hidden URI; use unhideHiddenItem instead")
                return@launch
            }
            HiddenVaultDiagnostics.hideStarted(
                vaultedCount = 1,
                pairedCount = if (cloudLinkId != null) 1 else 0,
                filteredCount = 0,
                totalBytes = sizeBytes,
            )
            // Refuse up front when the copy cannot fit. The original stays until the copy is written,
            // so the volume has to hold both at once.
            val shortfall = hiddenVaultJournal.spaceShortfallBytes(sizeBytes)
            if (shortfall > 0L) {
                _deleteState.value = DeleteState.Failed(
                    context.getString(R.string.gallery_hide_needs_free_space, formatBytes(shortfall)),
                )
                return@launch
            }
            val sourceFolder = withContext(Dispatchers.IO) {
                hiddenStorage.sourceFolderFor(sourceUri, bucketName)
            }
            val privateUri = withContext(Dispatchers.IO) {
                hiddenStorage.store(sourceUri, displayName, mime, captureTimeMs = dateTakenMs)
            }
            HiddenVaultDiagnostics.copied(
                copiedCount = if (privateUri != null) 1 else 0,
                failedCount = if (privateUri == null) 1 else 0,
            )
            if (privateUri == null) {
                _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_hide_failed))
                return@launch
            }
            // Record the intent BEFORE the delete, together with everything the eventual unhide needs
            // — the original folder and name, and the cloud linkId that lets unhide transplant the
            // existing SyncState row instead of re-uploading the photo as a duplicate Drive entry.
            val journalled = hiddenVaultJournal.journal(
                listOf(
                    HiddenVaultJournal.Entry(
                        privateUri = privateUri,
                        sourceUri = sourceUri,
                        sourceFolder = sourceFolder,
                        originalName = displayName,
                        cloudLinkId = cloudLinkId,
                    ),
                ),
            )
            if (!journalled) {
                hiddenVaultJournal.discard(listOf(privateUri))
                _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_hide_failed))
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
                hide            = true,    // route through createDeleteRequest + HIDDEN status
            )
            when (result) {
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    HiddenVaultDiagnostics.originalsAwaitingConsent(1)
                    pendingPermissionResult = result
                    _deleteState.value = DeleteState.NeedsPermission(result.pendingIntent)
                }
                is DeletePhotoUseCase.Result.Success -> {
                    HiddenVaultDiagnostics.originalsRemoved(1, neededConsent = false)
                    // Pre-Q: delete succeeded synchronously. Commit the hidden URI now.
                    // Snapshot the vault URI first: commitPendingHide() clears it.
                    val hiddenUri = pendingHidePrivateUri
                    commitPendingHide()
                    buildHideUndoAction(listOfNotNull(hiddenUri))?.let { undoController.offer(it) }
                    _deleteState.value = DeleteState.Done
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                    cancelPendingHide()   // shouldn't happen with deleteFromCloud=false
                    _deleteState.value = DeleteState.Failed(context.getString(R.string.viewer_hide_failed))
                }
            }
        }
    }

    /** Publish the journalled hide now that the delete has confirmed. */
    private fun commitPendingHide() {
        val privateUri = pendingHidePrivateUri ?: return
        pendingHidePrivateUri = null
        viewModelScope.launch {
            hiddenVaultJournal.confirm(listOf(privateUri))
            _isHidden.value = true
        }
    }

    /** Drop the copy and its record — the user did not confirm the system delete, so the original
     *  is still where they can see it. */
    private fun cancelPendingHide() {
        val privateUri = pendingHidePrivateUri ?: return
        pendingHidePrivateUri = null
        viewModelScope.launch { hiddenVaultJournal.discard(listOf(privateUri)) }
    }

    /**
     * Return the vaulted photo on screen to the device.
     *
     * [HiddenVaultRestorer] owns the whole round trip — the name and folder recorded at hide time, the
     * cloud pairing, the journal entry, and the folder name a reveal empties — so a photo revealed
     * here comes back exactly as it does from the vault screen, and the last photo out of a hidden
     * folder takes that folder's own record with it.
     *
     * [originalDisplayName] serves as the fallback for a vault entry old enough to have recorded no
     * name; the private code the file is stored under would otherwise land on the device.
     */
    fun unhideHiddenItem(hiddenUri: String, originalDisplayName: String? = null) {
        viewModelScope.launch {
            if (hiddenVaultRestorer.restorePhoto(hiddenUri, fallbackDisplayName = originalDisplayName)) {
                _isHidden.value = false
            } else {
                _deleteState.value = DeleteState.Failed(context.getString(R.string.hidden_restore_failed))
            }
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
            val favIds = context.settingsDataStore.data
                .map { it[SettingsKeys.FAVORITE_IDS] ?: emptySet() }
                .first()
            _isFavorite.value = isItemFavorite(item, favIds, liveCloudTags(item))
            // Offline pin reflects only a cloud-only item — a Synced/LocalOnly photo already
            // has its bytes on the device, so it has no separate offline blob. The check is a
            // directory walk, so keep it off the main thread (this runs on every page-settle).
            _isOffline.value = (item as? GalleryItem.CloudOnly)?.let {
                withContext(Dispatchers.IO) { offlineStore.isOffline(it.cloud.linkId) }
            } ?: false
            // Seed the category tags for the details-sheet chips. A cloud-backed photo reports the
            // server's set, which the chips write to directly. A device-only one has no such set,
            // so it reports the categories it effectively shows, and a chip therefore always
            // matches the badge on the same photo's grid cell.
            _currentPhotoTags.value = when (item) {
                is GalleryItem.Synced    -> item.cloud.tags
                is GalleryItem.CloudOnly -> item.cloud.tags
                is GalleryItem.LocalOnly -> effectiveLocalTags(item, readUserTags(item.local.uri))
            }
        }
    }

    /**
     * The Drive tag set the library currently holds for a cloud-backed photo, read from its own
     * listing row rather than taken off the pager's item.
     *
     * The pager works from a snapshot taken when the grid was tapped, so the tags riding on the item
     * are as old as that tap: a tag written since then, in this viewer or on another client, is not
     * on them. The listing row is mirrored by every tag write and costs no network, so it answers
     * for the photo as it stands, offline included.
     *
     * Null for a device-only photo (it has no Drive link), and null when the library holds no row
     * for the link yet, which hands the answer back to the snapshot as the best one available.
     */
    private suspend fun liveCloudTags(item: GalleryItem): Set<Int>? {
        val linkId = when (item) {
            is GalleryItem.Synced    -> item.cloud.linkId
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.LocalOnly -> return null
        }
        val csv = runCatching { photoListingDao.getTagsCsv(linkId) }.getOrNull() ?: return null
        return if (csv.isEmpty()) emptySet()
               else csv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    }

    /**
     * Flips the heart on [item], on whichever side records it: a photo that lives only on the device
     * writes the device-side set, a backed-up one writes Drive tag 0 and nothing local, since that
     * tag is the only answer read back for it (see [isItemFavorite]).
     *
     * The heart flips before the network so the button responds to the tap, and goes back where it
     * was if the write is rejected ([favoriteAfterCloudWrite]), the same as [setPhotoTag] does for a
     * category chip. A write that lands also settles the device-side set through
     * [favoriteIdsAfterToggle].
     */
    fun toggleFavorite(item: GalleryItem) {
        val cloudPhoto = when (item) {
            is GalleryItem.Synced    -> item.cloud
            is GalleryItem.CloudOnly -> item.cloud
            is GalleryItem.LocalOnly -> null
        }
        viewModelScope.launch {
            val previous = _isFavorite.value
            val nowFavorite = !previous
            // No Drive link to tag, so the device-side set is where the heart lives, and reaching it
            // takes no network: flip it and settle.
            if (cloudPhoto == null) {
                writeFavoriteIds(item, nowFavorite)
                _isFavorite.value = nowFavorite
                return@launch
            }
            _isFavorite.value = nowFavorite
            val userId = accountManager.getPrimaryUserId().first()
            val ok = userId != null &&
                cloudRepo.setCloudFavorite(userId, cloudPhoto, favorite = nowFavorite)
            _isFavorite.value = favoriteAfterCloudWrite(previous, nowFavorite, ok)
            if (ok) writeFavoriteIds(item, nowFavorite)
        }
    }

    /** Stores whatever [favoriteIdsAfterToggle] makes of the device-side set for [item]. The read
     *  and the write are one transaction, so two quick taps compose instead of the second one
     *  storing a set the first has already replaced. */
    private suspend fun writeFavoriteIds(item: GalleryItem, nowFavorite: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[SettingsKeys.FAVORITE_IDS] ?: emptySet()
            val next = favoriteIdsAfterToggle(item, current, nowFavorite)
            if (next != current) prefs[SettingsKeys.FAVORITE_IDS] = next
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
     * Adds or removes a single category [tagId] on the current photo, on whichever side holds its
     * categories. A cloud-backed photo writes the tag through the metadata-only
     * [DrivePhotoRepository.setCloudTag] (no content/revision touch); a device-only one records the
     * choice on the device, see [setLocalPhotoTag]. Both flip the chip optimistically and put it
     * back if the write is rejected.
     */
    fun setPhotoTag(item: GalleryItem, tagId: Int, add: Boolean) {
        val cloudPhoto = when (item) {
            is GalleryItem.Synced    -> item.cloud
            is GalleryItem.CloudOnly -> item.cloud
            // No Drive link to tag, so the choice is kept in the file's own local-tag row.
            is GalleryItem.LocalOnly -> { setLocalPhotoTag(item, tagId, add); return }
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

    /** Serialises the local-tag writes below. Each one re-reads the row it is about to replace, so
     *  two quick chip taps have to queue or the second would compose its toggle onto the set the
     *  first has not stored yet and drop it. */
    private val userTagWriteLock = Mutex()

    /**
     * Records a category choice for a photo that lives only on the device.
     *
     * A stored choice is complete: once the row holds anything, that is the photo's whole category
     * set. So what is written is always the set the photo effectively shows with this one toggle
     * applied, which on the first toggle seeds the choice from the automatic categories the photo
     * already carries. Storing the single toggled id alone would drop all of those instead. Turning
     * the last one back off stores nothing, which hands the photo back to automatic categorisation.
     *
     * The chip flips before any I/O (the same optimism as [toggleFavorite]) and then settles on
     * what the photo shows once the row holds [next]; a failed write puts it back where it was.
     */
    private fun setLocalPhotoTag(item: GalleryItem.LocalOnly, tagId: Int, add: Boolean) {
        val uri = item.local.uri
        val previous = _currentPhotoTags.value
        _currentPhotoTags.value = if (add) previous + tagId else previous - tagId
        viewModelScope.launch {
            userTagWriteLock.withLock {
                val base = effectiveLocalTags(item, readUserTags(uri))
                val next = if (add) base + tagId else base - tagId
                val ok = runCatching { localTagDao.setUserTagsCsv(uri, UserPhotoTags.encode(next)) }
                    .onFailure { Log.w("PhotoViewerVM", "user category write failed: ${it.message}") }
                    .isSuccess
                // Through [effectiveLocalTags] rather than [next] itself, so clearing the last
                // category shows the automatic ones it hands the photo back to instead of leaving
                // the chips empty against a grid badge that already says otherwise.
                _currentPhotoTags.value = if (ok) effectiveLocalTags(item, next) else previous
            }
        }
    }

    /** The categories the user picked for one device file, read from its local-tag row rather than
     *  taken off the pager's item, whose copy a write made in this viewer session already outdates
     *  (the list refreshes on the next media scan). Empty when none were picked. */
    private suspend fun readUserTags(uri: String): Set<Int> =
        UserPhotoTags.decode(runCatching { localTagDao.getUserTagsCsv(uri) }.getOrNull())

    /** The categories a device-only photo effectively shows given [stored] as its choice: that
     *  choice when it holds anything, and the automatic sources otherwise. Answered by
     *  [CategorizeItem] so the chips and the grid badges never disagree. */
    private fun effectiveLocalTags(item: GalleryItem.LocalOnly, stored: Set<Int>): Set<Int> =
        CategorizeItem.classify(GalleryItem.LocalOnly(item.local.copy(userTags = stored)))

    /**
     * Renames [item] to [newName].
     * - [replaceOriginal] true ("Rename original"): the file is renamed where it already is, on the
     *   device or on Drive, keeping its identity.
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
        // A vaulted photo is an app-private file with no MediaStore row, so both halves of the rename —
        // moving the file and re-keying what the vault records for it — belong to the vault itself.
        // "Rename original" moves the file; "Save as copy" adds a second hidden photo, which is where a
        // copy of a hidden photo has to go.
        if (hiddenStorage.isHiddenUri(uri)) {
            val renamed =
                if (replaceOriginal) hiddenVaultEditor.rename(uri, newName)
                else hiddenVaultEditor.copy(uri, newName)
            if (renamed == null) {
                // A rename that cannot move the file has one cause the user can act on, and the name
                // being taken is it. A copy that could not be written has none to name, so it says only
                // that it did not happen.
                error(
                    context.getString(
                        if (replaceOriginal) R.string.viewer_rename_name_taken
                        else R.string.viewer_rename_failed,
                    ),
                )
            }
            // The path the photo now lives at is what the pager loads it from, and a path a rename has
            // freed can still hold that photo's pixels in the cache.
            dropCachedBytes(renamed)
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
        if (replaceOriginal) {
            // In-place rename: the linkId, the album membership and the bytes all stay put, so
            // there is no new link to file anywhere. A failure propagates to renameItem, which turns
            // it into RenameState.Failed and shows it in the rename sheet. Falling back to the copy
            // path on failure would hand the user the duplicate this call exists to avoid.
            cloudTrashService.renameCloudPhoto(userId, photo, newName)
            return
        }
        val newLinkId = cloudRepo.copyCloudPhotoAs(userId, photo, newName)
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
                val thumbUrl = item.cloud.thumbnailUrl ?: thumbnailUrlStore.urls.value[item.cloud.linkId]
                listOfNotNull(thumbUrl, file?.let { Uri.fromFile(it).toString() })
            }
        }
        keys.forEach { key ->
            dropCachedBytes(key)
            // Nudge MediaStore observers (the gallery grid) for renamed device files so the
            // new display name surfaces without a manual pull-to-refresh.
            if (item !is GalleryItem.CloudOnly) {
                runCatching { context.contentResolver.notifyChange(Uri.parse(key), null) }
            }
        }
    }

    /**
     * Drops whatever the image cache holds under [key], in memory and on disk.
     *
     * A path is the key, so a path that has just been given to a different photo answers with the
     * previous one's pixels until this runs — which is exactly what a vault rename onto a name another
     * photo has left behind produces.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun dropCachedBytes(key: String) {
        val loader = context.imageLoader
        loader.memoryCache?.let { mc ->
            runCatching { mc.keys.filter { it.key == key }.forEach { mc.remove(it) } }
        }
        runCatching { loader.diskCache?.remove(key) }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Seed from the cached set first — the same full list the Albums tab and the gallery
            // picker show (including albums created this session). A fresh network fetch alone can
            // surface fewer entries while it's slow or a detail chunk fails, which left the viewer's
            // picker missing albums. Then refresh from the network.
            // Shared albums this user may contribute to belong in the picker too, and the network
            // refresh below returns only owned albums, so they are appended to both results rather
            // than fetched once and overwritten.
            val sharedAddable = runCatching { cloudRepo.loadSharedAddableAlbumsCached() }.getOrNull().orEmpty()
            runCatching { cloudRepo.loadAlbumsCached() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { _albums.value = it + sharedAddable }
            runCatching { cloudRepo.loadAlbums(userId) }
                .onSuccess { _albums.value = it + sharedAddable }
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

    /** Fills [detailsAlbums] for [item]: the device bucket name from the on-device copy (LocalOnly /
     *  Synced) and the names of the cloud albums that own it (CloudOnly / Synced), mapped from the
     *  [albums] list. The cloud walk reuses the same [getAlbumIdsByPhoto] cache as the picker and
     *  degrades to no albums if it fails, so the sheet still shows the folder. */
    fun loadDetailsAlbums(item: GalleryItem) {
        val localFolder = when (item) {
            is GalleryItem.LocalOnly -> item.local.bucketName
            is GalleryItem.Synced    -> item.local.bucketName
            is GalleryItem.CloudOnly -> null
        }
        val cloudLinkId = when (item) {
            is GalleryItem.Synced    -> item.cloud.linkId
            is GalleryItem.CloudOnly -> item.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
        // Show the folder immediately; a LocalOnly photo has no cloud walk to wait on.
        _detailsAlbums.value = DetailsAlbums(localFolder = localFolder)
        if (cloudLinkId == null) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val names = runCatching { cloudRepo.getAlbumIdsByPhoto(userId) }
                .getOrNull()
                ?.get(cloudLinkId)
                ?.let { ids ->
                    _albums.value
                        .filter { it.linkId in ids }
                        .map { it.name }
                        .distinct()
                        .sorted()
                }
                .orEmpty()
            _detailsAlbums.value = DetailsAlbums(localFolder = localFolder, cloudAlbums = names)
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
                    // Undo re-adds by copying from this device's own volume, which cannot reach a
                    // photo that lives on the sharer's volume, so a shared album gets no Undo
                    // rather than one that silently does nothing. Same rule the album grid follows.
                    // An album missing from the loaded list is not confirmed as this user's own, so
                    // it takes the same quiet path.
                    val isOwnAlbum =
                        _albums.value.firstOrNull { it.linkId == albumLinkId }?.isSharedWithMe == false
                    if (isOwnAlbum) {
                        undoController.offer(UndoAction.AlbumRemove(albumLinkId, listOf(cloudLinkId)))
                    }
                    _removeFromAlbumDone.tryEmit(AlbumRemoval(albumLinkId, cloudLinkId))
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
                    ExifHelper.readMetadata(context, originalUriForExif(context, uri))
                }
                // The file is live in MediaStore, so a GPS strip also has to drop the fix stored for
                // it; otherwise the map and the place groupings keep plotting the coordinates the
                // photo no longer carries.
                invalidateStrippedLocations(config, listOf(uri))
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
     *  `DCIM/<AlbumName>/`, otherwise `DCIM/Camera`. */
    fun downloadToDevice(item: GalleryItem) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _isSavingToDevice.value = true
            // Album membership → DCIM/<AlbumName>/ routing. Usually a cache hit (20-min TTL).
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
            val transferId = transferCenter.start(
                eu.akoos.photos.data.transfer.TransferCenter.Kind.DOWNLOAD, 1,
            )
            try {
                // A per-photo failure is COUNTED, not thrown: the use case catches it and returns a
                // normal snapshot, so a catch alone never fired and a save that wrote nothing finished
                // in silence. The count is the only thing that says whether the photo reached the
                // device, which is why it is read here rather than only the exception.
                val outcome = runCatching {
                    downloadPhotos.downloadGalleryItems(userId, listOf(item), folderName = folder)
                }
                val reason = outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                if (outcome.isFailure || (outcome.getOrNull()?.failed ?: 0) > 0) {
                    _transientError.value = context.getString(
                        R.string.viewer_save_to_device_failed,
                        reason ?: context.getString(R.string.viewer_unknown_error),
                    )
                }
            } finally {
                transferCenter.finish(transferId)
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
                    is GalleryItem.LocalOnly -> localShareUri(item.local)
                    is GalleryItem.Synced    -> localShareUri(item.local)
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

    /**
     * The uri another app may read a device-backed photo through.
     *
     * A MediaStore photo already has one. A vaulted photo does not — it is an app-private `file://`
     * that the platform refuses to put in an intent at all — so it goes out through the share
     * provider instead, which grants the receiver that one file, serves the bytes in place, and puts
     * no part of the vault's location in the uri it hands over.
     */
    private fun localShareUri(local: eu.akoos.photos.domain.entity.LocalMediaItem): Uri {
        val parsed = Uri.parse(local.uri)
        if (!hiddenStorage.isHiddenUri(local.uri)) return parsed
        val file = parsed.path?.let { File(it) }?.takeIf { it.exists() }
            ?: error(context.getString(R.string.viewer_unknown_error))
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.share.fileprovider", file,
        ).also {
            // The vault names its files by a private code, so report the name the photo was hidden
            // under instead — the same substitution the cloud branch above makes.
            eu.akoos.photos.util.ShareFileProvider.putDisplayName(it, local.displayName)
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
        // The timeline projection no longer carries a cloud row's thumbnail URL, so fall back to the
        // shared store. This URL is the placeholder while full-res downloads, the metered-gate
        // stand-in, and what a download failure keeps showing instead of erroring.
        val thumbUrl = photo.thumbnailUrl ?: thumbnailUrlStore.urls.value[photo.linkId]

        // Reset the resolved-size fallback so the details sheet doesn't show last item's
        // size while the new page is still downloading.
        _cloudFullResSize.value = null
        _cloudVideoMeta.value = null
        _fullResBlockedByMetered.value = false
        // Drop another photo's EXIF; this one's is read from its decrypted full-res once the download
        // lands (see onSuccess below), and a video never produces a read at all.
        retargetMetadata(itemKey)

        if (thumbUrl != null) {
            // Thumbnail placeholder while downloading full-res (videos too).
            _state.value = ViewerState.ShowImage(thumbUrl, itemKey = itemKey)
        } else {
            _state.value = ViewerState.Loading
        }

        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (thumbUrl == null) _state.value = ViewerState.Error(context.getString(R.string.viewer_not_logged_in))
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
                if (thumbUrl == null && _state.value.itemKey == itemKey) {
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
                    if (thumbUrl == null && _state.value.itemKey == itemKey) {
                        _state.value = ViewerState.Error(e.message)
                    }
                    // else keep showing thumbnail silently
                },
            )
        }
    }
}
