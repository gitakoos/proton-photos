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

package eu.akoos.photos.presentation.albums

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.GetUser
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenCloudPhotos
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.hidden.HiddenVaultDecisions
import eu.akoos.photos.data.hidden.HiddenVaultDiagnostics
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.common.FavoriteActionState
import eu.akoos.photos.presentation.common.FavoriteWriter
import eu.akoos.photos.presentation.common.buildDeleteUndoAction
import eu.akoos.photos.presentation.gallery.toPersonUi
import eu.akoos.photos.presentation.common.buildHideUndoAction
import eu.akoos.photos.presentation.common.favoriteTurnsOnForCloudPhotos
import eu.akoos.photos.presentation.common.message
import eu.akoos.photos.presentation.common.shareOutcome
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.presentation.viewer.PublicLinkState
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.retryOnDbTear
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.worker.AlbumDownloadWorker
import javax.inject.Inject

/** How many times the album's photo observe may re-subscribe before the failure reaches the screen.
 *  Enough to ride out a torn cursor window during a chunked upsert, too few to mask a read that
 *  cannot succeed at all: that one would otherwise leave the grid on its skeleton with nothing
 *  said. */
private const val PHOTO_OBSERVE_MAX_RETRIES = 5L

/** Summary of a bulk invite-by-email batch. [failures] is the raw error message per failed email. */
data class InviteBatchResult(
    val successCount: Int,
    val failures: List<Pair<String, String>>,
) {
    val totalAttempted: Int get() = successCount + failures.size
}

sealed class AlbumDownloadState {
    data object Idle : AlbumDownloadState()
    data class Working(val done: Int, val total: Int) : AlbumDownloadState()
    /** Handed off to [AlbumDownloadWorker]; the UI consumes this once to dismiss the overlay + snackbar. */
    data object Enqueued : AlbumDownloadState()
}

/**
 * How a finished album download turned out. [AlbumDownloadState] has no terminal case on purpose,
 * since the ring must vanish the moment the work leaves the queue, and a vanished ring reads the
 * same whether everything saved or nothing did. The counts arrive separately, once per run.
 */
data class AlbumDownloadResult(val saved: Int, val failed: Int)

/** Progress of a share-to-other-apps batch. [Working] advances per resolved photo — cloud-only
 *  album photos decrypt to a temp file first, so the share pill shows a determinate ring. */
sealed class AlbumShareState {
    data object Idle : AlbumShareState()
    data class Working(val done: Int, val total: Int) : AlbumShareState()
}

/** Which foreground bulk action is in flight, so the blocking drawer can label it correctly —
 *  delete, remove-from-album and hide all raise [AlbumDetailUiState.isDeletingPhotos]. */
enum class AlbumBusyOp { None, Deleting, Removing, Hiding }

data class AlbumDetailUiState(
    val albumName: String = "",
    val albumLinkId: String = "",
    val isLoading: Boolean = true,
    val photos: List<CloudPhoto> = emptyList(),
    val error: String? = null,
    val selectedPhotos: Set<String> = emptySet(),
    val isDeletingPhotos: Boolean = false,
    /** The action behind [isDeletingPhotos]; drives the blocking drawer's label. */
    val busyOp: AlbumBusyOp = AlbumBusyOp.None,
    /** System trash/delete consent intent to launch, set when a delete needs MediaStore permission. */
    val pendingDeleteIntent: android.app.PendingIntent? = null,
    /** Set after a hide moves at least one backed-up photo to the vault; the UI snackbars the
     *  "your Drive copies are untouched" notice once, then clears it via [clearHideCloudNotice]. */
    val hideCloudNoticePending: Boolean = false,
    val isSharing: Boolean = false,
    val shareLink: String? = null,
    /** Persistent public-link URL when the album has an active public share. Distinct from
     *  [shareLink] which is a one-shot signal that copies to the clipboard. */
    val publicShareUrl: String? = null,
    /** True while the public-link toggle is creating or revoking the URL. */
    val isTogglingPublicLink: Boolean = false,
    val shareId: String? = null,
    /** Email of the album owner (the currently signed-in user when not shared-with-me).
     *  Rendered in the "Who has access" list as the unremovable owner row. */
    val ownerEmail: String = "",
    /** Non-null for shared-with-me albums — the email of the user who shared it. */
    val sharedByEmail: String? = null,
    /** Volume ID — may differ from the current user's volume for shared-with-me albums. */
    val volumeId: String? = null,
    val invitations: List<ShareInvitation> = emptyList(),
    val members: List<ShareMember> = emptyList(),
    val isLoadingInvitations: Boolean = false,
    val downloadState: AlbumDownloadState = AlbumDownloadState.Idle,
    /** How many photos the current/last album download was asked to fetch (the selection, or all).
     *  Drives the progress pill's denominator before the worker reports its first tick, so a partial
     *  download shows "/<selected>" from the start instead of the whole album's size. */
    val downloadRequestedTotal: Int = 0,
    val shareState: AlbumShareState = AlbumShareState.Idle,
    /** linkId → local MediaStore URI for photos that have been downloaded to this device. */
    val localUriByLinkId: Map<String, String> = emptyMap(),
    /** linkId → the member's device file as the merged library paired it. Holds only this album's
     *  members, and only those the pairing resolved, so a uri in [localUriByLinkId] can still have
     *  no entry here. Carries the name, size, folder and dimensions a uri alone cannot. */
    val localItemByLinkId: Map<String, eu.akoos.photos.domain.entity.LocalMediaItem> = emptyMap(),
    /** Cloud linkIds pinned for offline; the grid draws a download badge on each matching photo. */
    val offlinePinIds: Set<String> = emptySet(),
    /** Progress of a batch favourite from the selection dock. */
    val favoriteState: FavoriteActionState = FavoriteActionState.Idle,
    /** True while the multi-email Share-popup batch is in flight; gates the "Share" button + chip removals. */
    val isInvitingBatch: Boolean = false,
    /** Set after a [AlbumDetailViewModel.inviteUsers] batch completes; consumed once by the UI snackbar. */
    val inviteBatchResult: InviteBatchResult? = null,
    /** Monotonic tick (not a Boolean) bumped on cover-set success so two sets in a row each snackbar. */
    val coverUpdatedTick: Int = 0,
    /** Cover chosen in this session via [AlbumDetailViewModel.runSetCover]; null until a set succeeds.
     *  The hero header prefers this so it flips immediately instead of waiting for a re-open. */
    val coverThumbnailUrl: String? = null,
    /** True while the shared-album "Save to my library" round-trip is in flight. */
    val isSavingToLibrary: Boolean = false,
    /** Per-photo "N of M" save progress; both reset to 0 when the singleton-backed flow returns to Idle. */
    val savingCopied: Int = 0,
    val savingTotal: Int = 0,
    /** One-shot save-to-library outcome the UI snackbars (carries the new album linkId for a "View" jump). */
    val saveToLibraryResult: SaveToLibraryResult? = null,
    /** One-shot copied/total snapshot at cancellation, for the "Save cancelled at N / M" snackbar. */
    val saveCancelledAt: Pair<Int, Int>? = null,
    /** True while the "Leave album" action is in flight on a shared-with-me album. */
    val isLeavingAlbum: Boolean = false,
    /** Set true on leave success so the screen pops back; the ViewModel never navigates on its own. */
    val leaveAlbumDone: Boolean = false,
    /** Set true once the hide write lands, so the screen pops back off an album that has left the
     *  grid. Same contract as [leaveAlbumDone]: the ViewModel never navigates on its own. */
    val hideAlbumDone: Boolean = false,
    /** True while this album's photos are kept out of the main feed. About the photos alone, which
     *  [hideAlbumDone]'s action is not: that one takes the album out of every list. */
    val isHiddenFromTimeline: Boolean = false,
    /** True when this is a shared-with-me album the sharer granted edit rights on. */
    val sharedAlbumIsEditable: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedPhotos.isNotEmpty()
    val selectedCount: Int get() = selectedPhotos.size
    val isSharedWithMe: Boolean get() = sharedByEmail != null

    /**
     * Whether to offer adding photos here. Owning the album is enough; a shared one needs the edit
     * grant, and an album whose grant is not known yet counts as read-only, so the button never
     * appears for something the server would refuse.
     */
    val canAddPhotos: Boolean get() = !isSharedWithMe || sharedAlbumIsEditable
}

data class SaveToLibraryResult(
    val newAlbumLinkId: String,
    val copiedCount: Int,
    val failedCount: Int,
    val totalRequested: Int,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val getUser: GetUser,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val deletePhotoUseCase: eu.akoos.photos.domain.usecase.DeletePhotoUseCase,
    private val getGalleryItems: eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val offlineStore: eu.akoos.photos.data.offline.OfflineStorageManager,
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
    private val undoController: eu.akoos.photos.presentation.common.UndoController,
    private val hiddenStorage: HiddenStorageManager,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val favoriteWriter: FavoriteWriter,
    private val observePeopleUseCase: eu.akoos.photos.domain.usecase.ObservePeopleUseCase,
    private val addPhotosToPersonUseCase: eu.akoos.photos.domain.usecase.AddPhotosToPersonUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    /** One-shot system-share intents emitted to the screen, which launches the chooser. */
    private val _shareIntent = MutableSharedFlow<Intent>(replay = 0, extraBufferCapacity = 1)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    /** One-shot offline pin/un-pin outcome: a positive count was pinned, a negative count removed. */
    private val _offlineResult = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    val offlineResult: SharedFlow<Int> = _offlineResult.asSharedFlow()

    /**
     * Fires when saving for offline actually begins downloading. Separate from [offlineResult] so
     * the screen can say the work started: the running count lives on the Activity screen, and the
     * un-pin branch is instant and local, so only the pinning branch has anything to announce.
     */
    private val _offlineStarted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val offlineStarted: SharedFlow<Unit> = _offlineStarted.asSharedFlow()

    /** Outcome of a finished album download, emitted once per run. See [AlbumDownloadResult]. */
    private val _downloadResult = MutableSharedFlow<AlbumDownloadResult>(replay = 0, extraBufferCapacity = 1)
    val downloadResult: SharedFlow<AlbumDownloadResult> = _downloadResult.asSharedFlow()

    /**
     * A download has been handed to the worker. An event rather than a UI-state value: the state is
     * a conflated flow, and the work observer reports the queued entry in the same breath, so a
     * transient "enqueued" value is routinely collapsed into the progress value that follows it and
     * never reaches a collector.
     */
    private val _downloadStarted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val downloadStarted: SharedFlow<Unit> = _downloadStarted.asSharedFlow()

    /** Cached primary userId — same rationale as GalleryViewModel.primaryUserId. */
    @Volatile private var primaryUserId: me.proton.core.domain.entity.UserId? = null

    /** Direction this album lists its members in, from the one global preference every album shares
     *  (#85). Every place that builds the member list reads it: the list is rebuilt from a cache
     *  read, a server list and a DB observe, and a direction applied to only some of them would be
     *  undone by the next paint. */
    private val photoSortMode = MutableStateFlow(AlbumPhotoSortMode.Default)

    /** One run of the device-twin lookup: the member list it ran over, the twins it paired, and the
     *  order those twins imply. [source] is kept so the result can be discarded if the member list
     *  moved on while the pass was in flight. */
    private data class TwinPass(
        val source: List<CloudPhoto>,
        val twins: Map<String, eu.akoos.photos.domain.entity.LocalMediaItem>,
        val ordered: List<CloudPhoto>,
    )

    /** Enqueue an on-demand thumbnail decrypt; deduped by linkId, no-op until primaryUserId lands. */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        driveRepo.requestThumbnailDecrypt(userId, linkId)
    }

    /** Cancel any in-flight decrypt for [linkId] when the cell scrolls off-screen. */
    fun cancelThumbnailDecrypt(linkId: String) {
        driveRepo.cancelThumbnailDecrypt(linkId)
    }

    init {
        viewModelScope.launch { accountManager.getPrimaryUserId().collect { primaryUserId = it } }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            syncStateRepo.observeAll(userId)
                .retryOnDbTear("AlbumDetailSync")
                .collect { states ->
                // Only SYNCED rows = on-device. CLOUD_ONLY rows can keep a stale localUri (freed-up space).
                val map = states
                    .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
                    .associate { it.cloudFileId!! to it.localUri }
                _uiState.update { it.copy(localUriByLinkId = map) }
            }
        }
        // Device files for this album's members, taken from the same merged library every other
        // surface reads. The merge is the @Singleton GetGalleryItemsUseCase, so this adds no second
        // full-library pass, and the map it yields is bounded by the album rather than the library.
        // The order is recomputed here too: the twins settle after the photos do, and a member whose
        // Drive captureTime is sub-floor only finds its real place once its twin is in hand.
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            combine(
                getGalleryItems.invoke(userId),
                // Re-reads only when the member list itself changes: a selection toggle or a progress
                // tick copies the same list reference through, which this drops.
                _uiState.map { it.photos }.distinctUntilChanged(),
                // In the pass rather than read from the field, so a direction change mid-flight can
                // never be overwritten by an order this pass computed before it.
                photoSortMode,
            ) { library, photos, mode ->
                val twins = AlbumPhotoItems.twinsFor(photos, library)
                TwinPass(photos, twins, AlbumPhotoItems.ordered(photos, twins, mode))
            }
                // A cold listing re-emits the merged library several times a second; an unchanged
                // pass must not repaint the grid, so it never reaches the state at all.
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("AlbumDetailVM", "album device twins unavailable: ${e.message}")
                }
                .collect { pass ->
                    _uiState.update { state ->
                        // A remove or delete landing while the pass ran replaced the member list, and
                        // its result wins: taking the stale order back would re-paint what it dropped.
                        // The twins still apply, and the next pass orders the survivors.
                        if (state.photos !== pass.source) state.copy(localItemByLinkId = pass.twins)
                        else state.copy(localItemByLinkId = pass.twins, photos = pass.ordered)
                    }
                }
        }
        // Newest-first or oldest-first, from the one global preference. Re-orders what is already on
        // screen so the choice lands immediately instead of waiting for a reload, and holds the value
        // the cache read, the server list and the DB observe each sort by.
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { AlbumPhotoSortMode.fromOrdinal(it[SettingsKeys.ALBUM_PHOTO_SORT_MODE]) }
                .distinctUntilChanged()
                .collect { mode ->
                    photoSortMode.value = mode
                    _uiState.update {
                        it.copy(photos = AlbumPhotoItems.ordered(it.photos, it.localItemByLinkId, mode))
                    }
                }
        }
        // Offline-pinned linkIds → per-cell offline badge. Same OFFLINE_PIN_IDS pref the timeline reads.
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
                .distinctUntilChanged()
                .collect { ids -> _uiState.update { it.copy(offlinePinIds = ids) } }
        }
        // This album's own timeline exclusion, which the drawer ticks. Keyed on the album so the
        // state follows a load, and resolved to the one Boolean here: the stored ids are a small set
        // the timeline resolves to members itself, so nothing per-photo is built on this side.
        viewModelScope.launch {
            combine(
                _uiState.map { it.albumLinkId }.distinctUntilChanged(),
                context.settingsDataStore.data
                    .map { it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] ?: emptySet() }
                    .distinctUntilChanged(),
            ) { linkId, excluded -> AlbumTimelineHide.isExcluded(excluded, linkId) }
                .distinctUntilChanged()
                .catch { emit(false) }
                .collect { excluded -> _uiState.update { it.copy(isHiddenFromTimeline = excluded) } }
        }
        // Resolve the owner email once for the share-sheet "owner" row; failures are silent.
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val email = runCatching { getUser(userId, refresh = false).email }.getOrNull().orEmpty()
            _uiState.update { it.copy(ownerEmail = email) }
        }
        // Re-pull when photos change elsewhere (e.g. gallery "Add to album"), else the grid stays stale.
        // Skip while a local remove is in flight: that path re-binds the observe to the surviving ids
        // itself, so a concurrent full refresh would only re-capture the pre-remove linkIds and re-paint
        // the removed photos, flickering the grid. A legitimate external change after the remove settles
        // still refreshes normally.
        viewModelScope.launch {
            albumListEvents.changes.collect {
                if (suppressSelfRefresh) return@collect
                if (_uiState.value.albumLinkId.isNotBlank()) refresh()
            }
        }
        // A removal undone through the shared bar re-adds the photos server-side; refresh so they
        // reappear in this album's grid.
        viewModelScope.launch {
            undoController.restored.collect { action ->
                when (action) {
                    is eu.akoos.photos.presentation.common.UndoAction.AlbumRemove ->
                        if (action.albumLinkId == _uiState.value.albumLinkId) refresh()
                    // A delete undone elsewhere restores the cloud copy server-side; refresh so a photo
                    // that belongs to this album reappears in the grid. The observe is bound to a fixed
                    // linkId set, so it would not otherwise repaint.
                    is eu.akoos.photos.presentation.common.UndoAction.Delete ->
                        if (action.cloudLinkIds.any { it in observedLinkIds }) refresh()
                    else -> Unit
                }
            }
        }
    }

    // Cancelled on each new load() call so stale DB observers don't linger.
    private var albumJob: Job? = null

    // The DB-observe job from load() and the linkIds it is collecting. Promoted to fields so a
    // remove can re-bind the observe to the surviving ids — observePhotosByLinkIds keeps returning a
    // removed photo (only its album membership row is deleted; the photo_listing row remains), which
    // would otherwise fight the optimistic filter and make the removed tiles flicker.
    private var observeJob: Job? = null
    private var observedLinkIds: List<String> = emptyList()

    // True only for the duration of a local remove so the albumListEvents collector skips a racing
    // full reload while we re-bind the observe to the surviving ids. Cleared in a finally so a later
    // external change (gallery "Add to album", a cover-set) still refreshes this album.
    private var suppressSelfRefresh = false

    fun load(albumLinkId: String, albumName: String, shareId: String?, sharedByEmail: String? = null, volumeId: String? = null) {
        albumJob?.cancel()
        observeJob?.cancel()
        observedLinkIds = emptyList()
        _uiState.update { it.copy(
            isLoading = true, albumName = albumName, albumLinkId = albumLinkId,
            shareId = shareId, sharedByEmail = sharedByEmail, volumeId = volumeId, error = null,
        ) }
        albumJob = viewModelScope.launch {
            // The edit grant comes from the cached shared-with-me set rather than a navigation
            // argument: it is a property of the share that can change without the user reopening
            // the screen, and the cached list is already filtered to what this user may add to.
            if (sharedByEmail != null) {
                val editable = runCatching { driveRepo.loadSharedAddableAlbumsCached() }
                    .getOrNull().orEmpty().any { it.linkId == albumLinkId }
                _uiState.update { it.copy(sharedAlbumIsEditable = editable) }
            }
            // Phase 1: instant cache read so re-opening feels free. Pre-migration rows (parentLinkId == null) miss here.
            val cached = runCatching { driveRepo.loadAlbumPhotosCached(albumLinkId) }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                // Drop individually-hidden members from the instant cache read too: without this a
                // hidden cloud photo flashes back into the album until the reactive observe re-filters.
                val hidden = hiddenMemberFilterFor(albumLinkId)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        photos = AlbumPhotoItems.ordered(
                            cached.filterNot { p -> p.linkId in hidden },
                            state.localItemByLinkId,
                            photoSortMode.value,
                        ),
                    )
                }
            }

            if (!networkObserver.isOnline.value) {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Phase 2: full network refresh. onLinkIdsResolved fires after the cheap children-fetch
            // but before the heavy metadata work — drop the skeleton there and observe the DB by
            // linkId so chunked upserts trickle in instead of one shimmer until the whole album lands.
            val refresh = runCatching {
                driveRepo.loadAlbumPhotos(
                    userId = userId,
                    albumLinkId = albumLinkId,
                    volumeId = volumeId,
                    // Pass shareId ONLY for shared-with-me albums (recipient lacks the owner's root key).
                    // For owned albums it would pin photos to the album and drop them from timeline/search/count.
                    sharingShareId = if (_uiState.value.isSharedWithMe) _uiState.value.shareId else null,
                    onLinkIdsResolved = { linkIds ->
                        // Keep isLoading until the first DB row lands so skeletons don't flash the empty-album copy.
                        if (linkIds.isEmpty()) {
                            _uiState.update { it.copy(isLoading = false) }
                            return@loadAlbumPhotos
                        }
                        startPhotoObserve(linkIds)
                    },
                )
            }
            // Fresh hidden snapshot so the definitive server list below can't re-add a member the user
            // hid: the server list is unfiltered and lands AFTER the reactive observe, so without this it
            // would overwrite the filtered grid and a hidden cloud photo would reappear in the album.
            val hiddenNow = hiddenMemberFilterFor(albumLinkId)
            refresh.fold(
                    onSuccess = { photos ->
                        // Definitive server list (usually a no-op since the observer already used server order).
                        _uiState.update { state ->
                            val existingById = state.photos.associateBy { it.linkId }
                            state.copy(
                                isLoading = false,
                                // Re-ordered here too: the server list is raw-captureTime order, so a
                                // sub-floor member would jump back to the tail on the final paint.
                                photos = AlbumPhotoItems.ordered(
                                    photos.filterNot { it.linkId in hiddenNow }.map { server ->
                                        val cached = existingById[server.linkId] ?: return@map server
                                        server.copy(
                                            thumbnailUrl = server.thumbnailUrl ?: cached.thumbnailUrl,
                                        )
                                    },
                                    state.localItemByLinkId,
                                    photoSortMode.value,
                                ),
                            )
                        }
                    },
                    onFailure = { e ->
                        // Cancellation isn't an error — it fires on back-press before the refresh finishes.
                        if (e is kotlinx.coroutines.CancellationException) return@fold
                        // Keep the cached snapshot on network failure; sanitize so server HTML can't leak into the banner.
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = if (friendly != null) null else sanitizeErrorMessage(e.message),
                            )
                        }
                    },
                )
        }
        // Fetch members/invitations in parallel so the avatar row is ready when the header renders.
        // loadInvitations() self-guards on shareId/shared-with-me, so this never blocks the photo path.
        if (sharedByEmail == null) {
            loadInvitations()
        }
    }

    /**
     * Hidden-member filter for THIS album. Empty when the album itself is hidden (opened from the
     * Hidden view), so all of its own members stay visible; otherwise the global hidden-member set,
     * so an individually hidden photo (or a member of a different hidden album) still drops out here.
     */
    private suspend fun hiddenMemberFilterFor(albumLinkId: String): Set<String> {
        val hiddenAlbumIds = runCatching {
            context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_ALBUM_IDS]
        }.getOrNull().orEmpty()
        if (albumLinkId in hiddenAlbumIds) return emptySet()
        return runCatching { driveRepo.observeHiddenAlbumMemberLinkIds().first() }.getOrNull().orEmpty()
    }

    /**
     * (Re)subscribe the DB observe to [linkIds], collecting chunked upserts into [AlbumDetailUiState.photos].
     * Cancels any prior observe first so the job never leaks, and records [observedLinkIds] so a remove
     * can re-bind to the surviving set instead of letting the old observe re-emit removed photos.
     */
    private fun startPhotoObserve(linkIds: List<String>) {
        observeJob?.cancel()
        observedLinkIds = linkIds
        observeJob = viewModelScope.launch {
            // A large album's full-row read can land mid-chunk-upsert (the album load upserts photos
            // in batches while this observe is live) and throw a transient CursorWindow error, so
            // re-subscribe rather than let it reach the collector as a force-close. Capped, because a
            // read that keeps failing is not a torn window and no number of retries will fix it.
            val photosFlow = driveRepo.observePhotosByLinkIds(linkIds)
                .retryOnDbTear("AlbumDetailVM", maxAttempts = PHOTO_OBSERVE_MAX_RETRIES)
            // Drop members hidden individually (a cloud photo hidden here or from another surface) or
            // that belong to a hidden album. The album observes its members directly, bypassing the
            // global timeline filter, so an already-hidden member would otherwise still show here. A
            // HashSet membership test per row, no extra query.
            combine(
                photosFlow,
                driveRepo.observeHiddenAlbumMemberLinkIds().distinctUntilChanged(),
                context.settingsDataStore.data
                    .map { it[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet() }
                    .distinctUntilChanged(),
            ) { dbRows, hiddenMembers, hiddenAlbumIds ->
                // When this album is itself hidden (opened from the Hidden view), its own members are
                // all in hiddenMembers, so filtering by it would empty the grid; keep them all then.
                val hidden = if (_uiState.value.albumLinkId in hiddenAlbumIds) emptySet() else hiddenMembers
                dbRows to hidden
            }
                // Terminal failure of the observe, past its retry cap. Surfacing it drops the skeleton
                // and says what went wrong, where a swallowed one leaves the album loading forever.
                .catch { e ->
                    Log.e("AlbumDetailVM", "album photo observe gave up", e)
                    _uiState.update { it.copy(isLoading = false, error = sanitizeErrorMessage(e.message)) }
                }
                .collect { (dbRows, hidden) ->
                val byId = dbRows.associateBy { it.linkId }
                val ordered = AlbumPhotoItems.ordered(
                    linkIds.mapNotNull { byId[it] }.filterNot { it.linkId in hidden },
                    _uiState.value.localItemByLinkId,
                    photoSortMode.value,
                )
                _uiState.update { state ->
                    val existingById = state.photos.associateBy { it.linkId }
                    // An empty pass doesn't mean empty album — could be between chunked upserts or a
                    // shared-album rewrite mid-flight. Keep the cached snapshot rather than wiping.
                    if (ordered.isEmpty() && state.photos.isNotEmpty()) {
                        return@update state
                    }
                    state.copy(
                        isLoading = state.isLoading && ordered.isEmpty(),
                        photos = ordered.map { dbPhoto ->
                            // Fall back to existing state for fields that would blank out (e.g. pre-decrypt name).
                            val existing = existingById[dbPhoto.linkId]
                            if (existing == null) dbPhoto
                            else dbPhoto.copy(
                                thumbnailUrl = dbPhoto.thumbnailUrl ?: existing.thumbnailUrl,
                                displayName  = dbPhoto.displayName.ifBlank { existing.displayName },
                            )
                        },
                    )
                }
            }
        }
    }

    fun refresh() {
        val s = _uiState.value
        if (s.albumLinkId.isBlank()) return
        load(s.albumLinkId, s.albumName, s.shareId, s.sharedByEmail, s.volumeId)
    }

    fun loadInvitations() {
        val shareId = _uiState.value.shareId ?: return
        if (_uiState.value.isSharedWithMe) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isLoadingInvitations = true) }
            // Surface failures via state.error so a network drop snackbars instead of showing an empty list.
            val invitationsResult = runCatching { driveRepo.loadShareInvitations(userId, shareId) }
            val membersResult = runCatching { driveRepo.loadShareMembers(userId, shareId) }
            val firstError = invitationsResult.exceptionOrNull() ?: membersResult.exceptionOrNull()
            val friendly = firstError?.let {
                friendlyNetworkError(it, networkObserver.isOnline.value, context)
            }
            _uiState.update {
                it.copy(
                    isLoadingInvitations = false,
                    invitations = invitationsResult.getOrDefault(emptyList()),
                    members = membersResult.getOrDefault(emptyList()),
                    error = friendly ?: firstError?.let { e -> sanitizeErrorMessage(e.message) } ?: it.error,
                )
            }
        }
    }

    fun revokeInvitation(invitationId: String) {
        val shareId = _uiState.value.shareId ?: return
        // Optimistic placeholder rows carry a blank id (nothing to revoke on the backend yet).
        if (invitationId.isBlank()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.revokeShareInvitation(userId, shareId, invitationId) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(invitations = it.invitations.filter { inv -> inv.invitationId != invitationId }) } },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "revokeInvitation failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(error = friendly ?: context.getString(R.string.share_revoke_failed))
                        }
                    },
                )
        }
    }

    /** Revokes an already-accepted member from the album share (kicks them off). */
    fun removeMember(memberId: String) {
        val shareId = _uiState.value.shareId ?: return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.removeShareMember(userId, shareId, memberId) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(members = it.members.filter { m -> m.memberId != memberId }) } },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "removeMember failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(error = friendly ?: context.getString(R.string.share_remove_member_failed))
                        }
                    },
                )
        }
    }

    fun togglePhotoSelection(linkId: String) {
        _uiState.update { state ->
            val newSet = if (linkId in state.selectedPhotos) state.selectedPhotos - linkId
                         else state.selectedPhotos + linkId
            state.copy(selectedPhotos = newSet)
        }
    }

    /** Toggle a whole date group from a month header's tri-state circle: if every photo in the
     *  group is already selected, drop them all; otherwise add the missing ones. No-op when empty. */
    fun toggleGroupSelection(linkIds: Collection<String>) {
        if (linkIds.isEmpty()) return
        _uiState.update { state ->
            val all = linkIds.all { it in state.selectedPhotos }
            val next = if (all) state.selectedPhotos - linkIds.toSet()
                       else state.selectedPhotos + linkIds
            state.copy(selectedPhotos = next)
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedPhotos = emptySet()) }

    /** People for the "add to person" sheet, resolved to UI tiles from the merged library so a cover
     *  renders. Only collected while the sheet observes it. */
    val people: StateFlow<List<eu.akoos.photos.presentation.gallery.PersonUi>> =
        accountManager.getPrimaryUserId()
            .flatMapLatest { userId ->
                if (userId == null) flowOf(emptyList())
                else observePeopleUseCase(userId, getGalleryItems.invoke(userId))
                    .map { list -> list.mapNotNull { it.toPersonUi() } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Attach the selected album photos to a person, mapping each album linkId to the item's stableId
     *  (a synced photo keys on its local uri, not the linkId) so the membership matches the person's
     *  keyspace. Survives a rescan; an unnamed person is a no-op inside the use case. */
    fun addSelectedToPerson(personId: Long) {
        val st = _uiState.value
        val keys = st.selectedPhotos.map { linkId -> st.localUriByLinkId[linkId] ?: linkId }
        if (keys.isEmpty()) return
        viewModelScope.launch {
            addPhotosToPersonUseCase(personId, keys)
            clearSelection()
        }
    }

    /** Replace the whole selection — used by the drag-select sweep, which sets the swept range each frame. */
    fun setSelectedPhotos(linkIds: Set<String>) = _uiState.update { it.copy(selectedPhotos = linkIds) }

    /**
     * Puts every selected album photo into the state [favoriteTurnsOnForCloudPhotos] picks for it: on
     * if any of them is not a favourite yet, off once they all are.
     *
     * Every member is a Drive photo, so the heart is PhotoTag 0 and the write is the same one the
     * timeline and the viewer make. The album's rows come from a one-shot listing rather than a live
     * flow, so each photo that settles has its tag patched here and its cell's heart follows at once.
     * The selection is kept, so a second press takes the first one back.
     *
     * Shared-with-me albums never get here: their photos are the owner's, on the owner's volume, and
     * the same line already keeps hide, delete and cover off this surface.
     */
    fun toggleSelectedFavorite() {
        val state = _uiState.value
        if (state.isSharedWithMe) return
        val selected = state.photos.filter { it.linkId in state.selectedPhotos }
        if (selected.isEmpty() || state.favoriteState !is FavoriteActionState.Idle) return
        val turnOn = favoriteTurnsOnForCloudPhotos(selected)
        viewModelScope.launch {
            _uiState.update { it.copy(favoriteState = FavoriteActionState.Working(0, selected.size)) }
            val outcome = favoriteWriter.write(
                items = selected.map { GalleryItem.CloudOnly(it) },
                favorite = turnOn,
                onProgress = { done ->
                    _uiState.update {
                        it.copy(favoriteState = FavoriteActionState.Working(done, selected.size))
                    }
                },
                onSettled = { item ->
                    val linkId = (item as? GalleryItem.CloudOnly)?.cloud?.linkId
                    if (linkId != null) {
                        _uiState.update { s ->
                            s.copy(
                                photos = s.photos.map { photo ->
                                    if (photo.linkId != linkId) photo
                                    else photo.copy(
                                        tags = if (turnOn) photo.tags + 0 else photo.tags - 0,
                                    )
                                },
                            )
                        }
                    }
                },
            )
            _uiState.update {
                it.copy(
                    favoriteState = FavoriteActionState.Idle,
                    error = outcome.message()?.resolve(context) ?: it.error,
                )
            }
        }
    }

    /**
     * Pin or un-pin the selected album photos for offline viewing, mirroring the timeline's batch
     * toggle. If every selected photo is already offline it removes them (drops the pins + blobs, no
     * network); otherwise it downloads the full-res blob for the ones not yet pinned. The pin set is
     * updated optimistically so the per-cell badge reflects at once, a failed download is reverted,
     * and the outcome (+N pinned / -N removed) is emitted on [offlineResult].
     */
    fun toggleSelectedOffline() {
        // Only cloud-only photos need pinning; Synced ones already have a device copy that serves
        // offline viewing, so exclude anything present in localUriByLinkId (matches the gallery).
        val selected = _uiState.value.photos.filter {
            it.linkId in _uiState.value.selectedPhotos && it.linkId !in _uiState.value.localUriByLinkId
        }
        if (selected.isEmpty()) return
        val pinned = _uiState.value.offlinePinIds
        val allOffline = selected.all { it.linkId in pinned }

        if (allOffline) {
            // Remove from offline — instant, no network: drop the pins and their blobs.
            val linkIds = selected.map { it.linkId }
            viewModelScope.launch {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - linkIds.toSet()
                }
                linkIds.forEach { offlineStore.delete(it) }
                _uiState.update { it.copy(selectedPhotos = emptySet()) }
                _offlineResult.emit(-linkIds.size)
            }
            return
        }

        // Pin only the ones not already offline.
        val toPin = selected.filter { it.linkId !in pinned }
        val linkIds = toPin.map { it.linkId }
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                prefs[SettingsKeys.OFFLINE_PIN_IDS] = current + linkIds
            }
            _uiState.update { it.copy(selectedPhotos = emptySet()) }
            _offlineStarted.emit(Unit)
            val userId = primaryUserId ?: accountManager.getPrimaryUserId().first()
            var succeeded = 0
            val failedLinkIds = mutableListOf<String>()
            val savedPaths = mutableListOf<String>()
            val transferId = transferCenter.start(
                eu.akoos.photos.data.transfer.TransferCenter.Kind.OFFLINE, toPin.size,
            )
            try {
                for (photo in toPin) {
                    try {
                        val uid = userId ?: error("Not signed in")
                        val file = driveRepo.downloadFullResPhoto(uid, photo)
                        val stored = offlineStore.store(photo.linkId, file)
                        savedPaths += "file://${stored.absolutePath}"
                        succeeded++
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w("AlbumDetailVM", "offline pin failed: ${e.message}")
                        failedLinkIds += photo.linkId
                    }
                    // Advance once per attempt so the Activity screen's row fills to the total
                    // either way.
                    transferCenter.progress(transferId, succeeded + failedLinkIds.size)
                }
            } finally {
                transferCenter.finish(transferId)
            }
            // Revert the optimistic pin for anything that didn't download.
            if (failedLinkIds.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - failedLinkIds.toSet()
                }
                failedLinkIds.forEach { offlineStore.delete(it) }
            }
            transferCenter.log(
                eu.akoos.photos.data.transfer.TransferCenter.Kind.OFFLINE, succeeded, uris = savedPaths,
            )
            _offlineResult.emit(succeeded)
        }
    }

    /** Deferred cloud-delete work + context, held while the system trash dialog is up. */
    private var pendingPermissionResult: eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null
    private var pendingDeleteLinkIds: List<String> = emptyList()
    private var pendingDeleteFromCloud: Boolean = false

    /** Resolve selected photos to [GalleryItem]s for delete: [GalleryItem.Synced] if a local twin exists, else CloudOnly. */
    private fun selectedGalleryItems(): List<GalleryItem> {
        val state = _uiState.value
        val byId = state.photos.associateBy { it.linkId }
        return state.selectedPhotos.mapNotNull { linkId ->
            val photo = byId[linkId] ?: return@mapNotNull null
            AlbumPhotoItems.galleryItem(
                photo, state.localItemByLinkId[linkId], state.localUriByLinkId[linkId],
            )
        }
    }

    /**
     * Delete selected photos (matches the gallery): [freeUpSpace] removes on-device, [deleteFromCloud] trashes
     * the Drive copy (recoverable). A device-only delete keeps the photo in the album; a cloud delete drops it.
     */
    fun deleteSelectedPhotos(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val linkIds = _uiState.value.selectedPhotos.toList()
        val items = selectedGalleryItems()
        if (linkIds.isEmpty() || items.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isDeletingPhotos = true, busyOp = AlbumBusyOp.Deleting) }
            val result = runCatching { deletePhotoUseCase(userId, items, freeUpSpace, deleteFromCloud) }
                .getOrElse { e ->
                    Log.e("AlbumDetailVM", "deleteSelectedPhotos failed", e)
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update { it.copy(isDeletingPhotos = false, error = friendly ?: context.getString(R.string.album_delete_photos_failed)) }
                    return@launch
                }
            when (result) {
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.Success -> {
                    // No system-trash dialog was needed, so device copies were untouched or removed
                    // permanently (pre-R): only a cloud trash is reversible, localRecoverable = false.
                    buildDeleteUndoAction(items, freeUpSpace, deleteFromCloud, hide = false, localRecoverable = false)
                        ?.let { undoController.offer(it) }
                    finishDelete(linkIds, deleteFromCloud)
                }
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    pendingPermissionResult = result
                    pendingDeleteLinkIds = linkIds
                    pendingDeleteFromCloud = deleteFromCloud
                    _uiState.update { it.copy(isDeletingPhotos = false, pendingDeleteIntent = result.pendingIntent) }
                }
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.CloudDeleteFailed ->
                    _uiState.update { it.copy(isDeletingPhotos = false, error = context.getString(R.string.album_delete_photos_failed)) }
            }
        }
    }

    /** Drop the deleted photos from the album view — but only when the cloud copy was trashed; a
     *  device-only delete leaves the cloud photo in the album (it just loses its green cloud). */
    private fun finishDelete(linkIds: List<String>, deleteFromCloud: Boolean) {
        _uiState.update { state ->
            state.copy(
                isDeletingPhotos = false,
                selectedPhotos = emptySet(),
                photos = if (deleteFromCloud) state.photos.filter { it.linkId !in linkIds } else state.photos,
            )
        }
    }

    /** Drop the hidden photos from the album list and surface the "Drive copies untouched" notice. */
    private fun finishHide(linkIds: List<String>) {
        _uiState.update { state ->
            state.copy(
                isDeletingPhotos = false,
                busyOp = AlbumBusyOp.None,
                selectedPhotos = emptySet(),
                photos = state.photos.filter { it.linkId !in linkIds },
                hideCloudNoticePending = true,
            )
        }
    }

    fun clearHideCloudNotice() = _uiState.update { it.copy(hideCloudNoticePending = false) }

    /**
     * The two halves the current selection's hide would act on, for the confirmation that fronts it.
     *
     * The same routing [hideSelected] follows, read from the same selection, so the sheet describes
     * exactly what the Hide button is about to do rather than what hiding does in general.
     */
    fun hideSplitForSelection(): HiddenFolderRecords.HideSplit =
        HiddenFolderRecords.hideSplit(selectedGalleryItems())

    /** Private vault URIs of a hide whose intent is journalled and whose system delete has not
     *  confirmed yet. Published into HIDDEN_PHOTO_URIS once it does, discarded if it is cancelled. */
    private var pendingHidePrivateUris: List<String> = emptyList()

    /** The client-side half of the same in-flight hide, held so the Undo offered once the delete
     *  confirms reverses the whole hide rather than only the photos that were vaulted. */
    private var pendingHideCloudLinkIds: List<String> = emptyList()

    /** How many device files that hide could not copy into the vault. Carried to whichever commit
     *  path lands so the count is reported once the hide is actually done. */
    private var pendingHideFailures = 0

    /**
     * Hide the selected album members, running the very body the timeline, search and device-folder
     * hides run.
     *
     * A member that is also on this device is a photo like any other: its device file moves into the
     * app-private vault and its MediaStore original is removed, so the file leaves every other
     * gallery app on the phone rather than merely leaving this app's listings. Its cloud linkId
     * travels into the vault with it, which is what lets the reveal re-pair it to the Drive copy
     * instead of uploading a second one. A member that lives only on Drive has no file to move and
     * hides by its linkId alone.
     *
     * The intent is journalled BEFORE the delete and confirmed after it, so an interruption between
     * the two leaves a repairable record instead of bytes nothing refers to — see [HiddenVaultJournal].
     * Hidden members drop from the grid via [finishHide], and the reactive hidden filter keeps them
     * out across refreshes. Nothing on Drive changes.
     */
    fun hideSelected() {
        if (_uiState.value.isSharedWithMe) return
        val items = selectedGalleryItems()
        if (items.isEmpty()) return
        val hiddenLinkIds = _uiState.value.selectedPhotos.toList()
        val split = HiddenFolderRecords.hideSplit(items)
        val vaultable = split.vaultable
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingPhotos = true, busyOp = AlbumBusyOp.Hiding) }
            HiddenVaultDiagnostics.hideStarted(split)
            HiddenCloudPhotos.hide(context, split.cloudLinkIds)
            pendingHideCloudLinkIds = split.cloudLinkIds
            pendingHideFailures = 0
            if (vaultable.isEmpty()) {
                // Nothing on this device to move, so the client-side hide above is the whole
                // operation and it is already done. The Undo bar is raised for it exactly as it is
                // for a vaulting hide, so the same button stays reversible either way.
                pendingHideCloudLinkIds = emptyList()
                buildHideUndoAction(emptyList(), split.cloudLinkIds)?.let { undoController.offer(it) }
                finishHide(hiddenLinkIds)
                return@launch
            }
            // Refuse up front when the copies cannot fit, measured over the whole batch. A hide holds
            // both the originals and the vault copies at once, so a volume that runs out mid-batch
            // fails per file with nothing the user can act on.
            val shortfall = hiddenVaultJournal.spaceShortfallBytes(vaultable.sumOf { it.sizeBytes })
            if (shortfall > 0L) {
                failHide(context.getString(R.string.gallery_hide_needs_free_space, formatBytes(shortfall)))
                return@launch
            }
            // Copy each device file into app-private hidden storage, a backed-up photo stashing its
            // cloud linkId so the reveal re-pairs by id. A file that could not be copied is counted
            // rather than dropped: its photo stays visible, so a hide that reported plain success
            // would be describing a state the user can see is not true.
            val collected = mutableListOf<HiddenVaultJournal.Entry>()
            var hideFailures = 0
            for (target in vaultable) {
                val local = target.local
                val sourceFolder = withContext(Dispatchers.IO) {
                    hiddenStorage.sourceFolderFor(local.uri, local.bucketName)
                }
                val privateUri = hiddenStorage.store(
                    local.uri, local.displayName, local.mimeType, captureTimeMs = target.captureTimeMs,
                )
                if (privateUri != null) {
                    collected += HiddenVaultJournal.Entry(
                        privateUri = privateUri,
                        sourceUri = local.uri,
                        sourceFolder = sourceFolder,
                        originalName = local.displayName,
                        cloudLinkId = target.cloudLinkId,
                    )
                } else {
                    hideFailures++
                }
            }
            HiddenVaultDiagnostics.copied(collected.size, hideFailures)
            if (collected.isEmpty()) {
                failHide(context.getString(R.string.gallery_copy_to_hidden_failed))
                return@launch
            }
            // Record the intent BEFORE anything is deleted, so an interruption during the delete
            // leaves a recoverable state rather than orphaned bytes.
            if (!hiddenVaultJournal.journal(collected)) {
                hiddenVaultJournal.discard(collected.map { it.privateUri })
                failHide(context.getString(R.string.gallery_move_to_hidden_failed))
                return@launch
            }
            pendingHidePrivateUris = collected.map { it.privateUri }
            pendingHideFailures = hideFailures
            // Delete the MediaStore originals of exactly what was copied. Narrowing to the copied
            // files is what leaves a photo whose copy failed where the user can still see it: this
            // delete is permanent, so passing the whole selection would take it nowhere.
            val deleting = HiddenVaultDecisions.deletableOriginals(vaultable, collected)
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                failHide(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            val result = runCatching {
                deletePhotoUseCase(userId, deleting, freeUpSpace = true, deleteFromCloud = false, hide = true)
            }.getOrElse { e ->
                Log.e("AlbumDetailVM", "hideSelected failed", e)
                failHide(context.getString(R.string.gallery_move_to_hidden_failed))
                return@launch
            }
            when (result) {
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.Success -> {
                    HiddenVaultDiagnostics.originalsRemoved(deleting.size, neededConsent = false)
                    // Snapshot both halves before commitPendingHide() clears the pending list, so
                    // Undo reverses exactly the hide that just landed.
                    val hideUris = pendingHidePrivateUris
                    val hideCloudIds = pendingHideCloudLinkIds
                    pendingHideCloudLinkIds = emptyList()
                    commitPendingHide()
                    buildHideUndoAction(hideUris, hideCloudIds)?.let { undoController.offer(it) }
                    reportHideFailures()
                    finishHide(hiddenLinkIds)
                }
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    HiddenVaultDiagnostics.originalsAwaitingConsent(deleting.size)
                    pendingPermissionResult = result
                    pendingDeleteLinkIds = hiddenLinkIds
                    pendingDeleteFromCloud = false
                    _uiState.update { it.copy(isDeletingPhotos = false, pendingDeleteIntent = result.pendingIntent) }
                }
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                    failHide(context.getString(R.string.gallery_move_to_hidden_failed))
                }
            }
        }
    }

    /** Say a hide could not finish and hand the selection back, so the bar never sits busy over a
     *  photo that is still exactly where the user left it. Undoing whatever the attempt already
     *  wrote is part of the same promise: the cloud-only half is hidden before the device half is
     *  attempted, so a message alone would leave those photos gone from every listing. */
    private fun failHide(message: String) {
        rollbackPendingHide()
        _uiState.update { it.copy(isDeletingPhotos = false, busyOp = AlbumBusyOp.None, error = message) }
    }

    /** Say how many photos a landed hide left behind, and only then: a photo whose copy failed is
     *  still on the device, so a hide that reported plain success would contradict the grid. */
    private fun reportHideFailures() {
        val failures = pendingHideFailures
        pendingHideFailures = 0
        if (failures <= 0) return
        _uiState.update {
            it.copy(error = context.resources.getQuantityString(R.plurals.gallery_hide_partial_failed, failures, failures))
        }
    }

    /** Publish the journalled hide now that the delete has confirmed. */
    private suspend fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        hiddenVaultJournal.confirm(uris)
    }

    /** Undo a hide that did not land: drop the copies and everything journalled for them, and put the
     *  cloud-only half back in every listing. The originals are untouched, so the photos stay where
     *  the user already sees them. */
    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        val cloudIds = pendingHideCloudLinkIds
        pendingHidePrivateUris = emptyList()
        pendingHideCloudLinkIds = emptyList()
        pendingHideFailures = 0
        if (uris.isEmpty() && cloudIds.isEmpty()) return
        viewModelScope.launch {
            if (uris.isNotEmpty()) hiddenVaultJournal.discard(uris)
            HiddenCloudPhotos.reveal(context, cloudIds)
        }
    }

    /** Run the deferred cloud delete once the system dialog is confirmed, then update the view. */
    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult ?: return
        val linkIds = pendingDeleteLinkIds
        val fromCloud = pendingDeleteFromCloud
        pendingPermissionResult = null
        _uiState.update { it.copy(pendingDeleteIntent = null) }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            // The refusal comes back as an answer rather than an exception, so runCatching alone
            // never saw it: the device file had gone, the Drive copy had not, and the screen said
            // nothing at all. The timeline surface already reports this.
            val cloudResult = if (userId != null) runCatching {
                deletePhotoUseCase.completeAfterPermissionGranted(
                    userId = userId,
                    cloudLinkIds = pending.cloudLinkIds,
                    items = pending.itemsBeingDeleted,
                    freeUpSpace = pending.freeUpSpace,
                    hide = pending.hide,
                )
            }.getOrNull() else null
            if (cloudResult is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.CloudDeleteFailed) {
                _uiState.update { it.copy(error = context.getString(R.string.viewer_delete_drive_failed)) }
            }
            if (pending.hide) {
                // Snapshot both halves before commitPendingHide() clears them, then offer Undo for
                // the whole hide rather than only the photos that were vaulted.
                val hideUris = pendingHidePrivateUris
                val hideCloudIds = pendingHideCloudLinkIds
                pendingHideCloudLinkIds = emptyList()
                commitPendingHide()
                buildHideUndoAction(hideUris, hideCloudIds)?.let { undoController.offer(it) }
                reportHideFailures()
                finishHide(linkIds)
                return@launch
            }
            // The system trash keeps the local files for ~30 days, so a confirmed delete is
            // reversible: localRecoverable = true.
            buildDeleteUndoAction(pending.itemsBeingDeleted, pending.freeUpSpace, fromCloud, hide = false, localRecoverable = true)
                ?.let { undoController.offer(it) }
            finishDelete(linkIds, fromCloud)
        }
    }

    /** User cancelled the system trash dialog, so drop the deferred cloud work — and, for a hide,
     *  the vault copies it had already written: the originals are untouched, so the photos stay
     *  exactly where the user can still see them. */
    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        rollbackPendingHide()
        _uiState.update { it.copy(isDeletingPhotos = false, busyOp = AlbumBusyOp.None, pendingDeleteIntent = null) }
    }

    /** Drop the selected photos' album reference (they stay in Photos). Reuses [isDeletingPhotos] for the working state. */
    fun removeSelectedPhotosFromAlbum() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        val linkIds = _uiState.value.selectedPhotos.toList()
        if (linkIds.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isDeletingPhotos = true, busyOp = AlbumBusyOp.Removing) }
            suppressSelfRefresh = true
            try {
                runCatching { driveRepo.removePhotosFromAlbum(userId, albumLinkId, linkIds) }
                    .fold(
                        onSuccess = { removed ->
                            val removedSet = removed.toSet()
                            // Removal is reversible on your own album: Undo re-adds exactly the
                            // confirmed ones.
                            //
                            // Not offered on a shared album, because the undo cannot honour it. Its
                            // photos live on the sharer's volume, so re-adding them is a membership
                            // change there, while the add path this undo calls copies a photo from
                            // this device's own volume and would not find them. An Undo that
                            // silently does nothing is worse than no Undo, so the button stays away
                            // until the membership re-add exists.
                            if (removed.isNotEmpty() && !_uiState.value.isSharedWithMe) {
                                undoController.offer(
                                    eu.akoos.photos.presentation.common.UndoAction.AlbumRemove(albumLinkId, removed),
                                )
                            }
                            _uiState.update { state ->
                                state.copy(
                                    isDeletingPhotos = false,
                                    selectedPhotos = emptySet(),
                                    // Drop only server-confirmed linkIds; leave chunk-failed photos visible.
                                    photos = state.photos.filter { it.linkId !in removedSet },
                                    error = if (removed.size != linkIds.size)
                                        context.getString(R.string.album_remove_partial, removed.size, linkIds.size)
                                    else null,
                                )
                            }
                            // Re-bind the DB observe to the surviving ids. Remove only deletes the album
                            // membership row, not the photo_listing row, so the old observe would keep
                            // re-emitting the removed photos and fight the optimistic filter above —
                            // the source of the post-remove grid flicker.
                            if (removedSet.isNotEmpty()) {
                                val surviving = observedLinkIds.filter { it !in removedSet }
                                startPhotoObserve(surviving)
                            }
                        },
                        onFailure = { e ->
                            Log.e("AlbumDetailVM", "removeSelectedPhotosFromAlbum failed", e)
                            val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                            _uiState.update {
                                it.copy(
                                    isDeletingPhotos = false,
                                    error = friendly ?: context.getString(R.string.album_remove_photos_failed),
                                )
                            }
                        },
                    )
            } finally {
                suppressSelfRefresh = false
            }
        }
    }

    fun renameAlbum(newName: String) {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == _uiState.value.albumName) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.renameAlbum(userId, albumLinkId, trimmed) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(albumName = trimmed, error = null) } },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "renameAlbum failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(error = friendly ?: context.getString(R.string.album_rename_failed))
                        }
                    },
                )
        }
    }

    /** Set the single selected photo as the album cover. No-op unless exactly one is selected. */
    fun setSelectedPhotoAsCover() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        val selected = _uiState.value.selectedPhotos
        if (selected.size != 1) return
        val coverLinkId = selected.first()
        runSetCover(albumLinkId, coverLinkId, clearSelection = true)
    }

    /** Set a specific photo as the cover, bypassing multi-select (per-cell menu + viewer overflow). */
    fun setPhotoAsCover(coverLinkId: String) {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        if (coverLinkId.isBlank()) return
        runSetCover(albumLinkId, coverLinkId, clearSelection = false)
    }

    private fun runSetCover(albumLinkId: String, coverLinkId: String, clearSelection: Boolean) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.setAlbumCover(userId, albumLinkId, coverLinkId) }
                .fold(
                    onSuccess = {
                        // Resolve the chosen cover's thumbnail the same way the album grid does:
                        // the in-memory photo's URL first, then the on-disk thumbnail cache file.
                        // The path is keyed by linkId, so a different cover yields a different model
                        // and Coil paints the new image without a cache bust.
                        val resolvedCover = _uiState.value.photos
                            .firstOrNull { it.linkId == coverLinkId }?.thumbnailUrl
                            ?: java.io.File(java.io.File(context.cacheDir, "thumbnails"), "thumb_$coverLinkId.jpg")
                                .takeIf { it.exists() && it.length() > 0 }
                                ?.let { "file://${it.absolutePath}" }
                        _uiState.update {
                            it.copy(
                                selectedPhotos = if (clearSelection) emptySet() else it.selectedPhotos,
                                error = null,
                                coverUpdatedTick = it.coverUpdatedTick + 1,
                                coverThumbnailUrl = resolvedCover ?: it.coverThumbnailUrl,
                            )
                        }
                        // Patch only this album's grid card (targeted) instead of a generic change.
                        // A generic change would fire this screen's own [changes] collector and
                        // reload + flash the whole album; the header already flipped via
                        // coverThumbnailUrl above, so no reload is needed here.
                        albumListEvents.notifyCoverChanged(albumLinkId, resolvedCover)
                    },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "setAlbumCover failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(error = friendly ?: context.getString(R.string.album_set_cover_failed))
                        }
                    },
                )
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /**
     * What a just-created share needs beyond its id, so managing it works without leaving the album.
     *
     * The member list is server truth keyed by the share, and there is none to read until the share
     * exists — the fetch here is what fills "Who has access" and what [disablePublicLink] reads to
     * decide between dropping the URL and dropping the whole share. The album grid then re-pulls so
     * its shared marker appears. Call only after the new id is in state: both reads take it from there.
     */
    private fun onShareCreated() {
        loadInvitations()
        albumListEvents.notifyChanged()
    }

    fun createShareLink() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isSharing = true) }
            runCatching { driveRepo.createAlbumShareLink(userId, albumLinkId) }
                .fold(
                    onSuccess = { created ->
                        // Set both: shareLink (one-shot clipboard trigger) and publicShareUrl (persistent sheet state).
                        _uiState.update {
                            it.copy(
                                isSharing = false,
                                shareLink = created.url,
                                publicShareUrl = created.url,
                                shareId = AlbumShareIds.resolve(it.shareId, created.shareId),
                            )
                        }
                        onShareCreated()
                    },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "createShareLink failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                isSharing = false,
                                error = friendly ?: context.getString(R.string.share_create_link_failed),
                            )
                        }
                    },
                )
        }
    }

    /** Toggle the public link ON. Like [createShareLink] but yields a persistent URL, not a one-shot clipboard event. */
    fun createPublicLink() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank {
            Log.w("AlbumDetailVM", "createPublicLink: albumLinkId is blank, ignoring tap")
            return
        }
        Log.d("AlbumDetailVM", "createPublicLink: ENTER albumLinkId=$albumLinkId")
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                Log.w("AlbumDetailVM", "createPublicLink: no primary userId, aborting")
                return@launch
            }
            _uiState.update { it.copy(isTogglingPublicLink = true) }
            runCatching { driveRepo.createAlbumShareLink(userId, albumLinkId) }
                .fold(
                    onSuccess = { created ->
                        Log.d("AlbumDetailVM", "createPublicLink: SUCCESS url=${created.url}")
                        _uiState.update {
                            it.copy(
                                isTogglingPublicLink = false,
                                publicShareUrl = created.url,
                                shareId = AlbumShareIds.resolve(it.shareId, created.shareId),
                            )
                        }
                        onShareCreated()
                    },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "createPublicLink: FAILURE msg=${e.message}", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                isTogglingPublicLink = false,
                                error = friendly ?: context.getString(R.string.share_create_link_failed),
                            )
                        }
                    },
                )
        }
    }

    /** Toggle the public link OFF. Keep the share (delete only the URL) when members/invites exist; else drop it all. */
    fun disablePublicLink() {
        val shareId = _uiState.value.shareId ?: return
        val keepShare = _uiState.value.members.isNotEmpty() || _uiState.value.invitations.isNotEmpty()
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isTogglingPublicLink = true) }
            val action = runCatching {
                if (keepShare) driveRepo.revokeShareUrlOnly(userId, shareId)
                else driveRepo.deleteShare(userId, shareId)
            }
            action.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isTogglingPublicLink = false,
                            publicShareUrl = null,
                            // Keep shareId while members remain so the member list keeps loading.
                            shareId = if (keepShare) it.shareId else null,
                        )
                    }
                    // Refresh the gallery so the shared-badge tracks the new state.
                    albumListEvents.notifyChanged()
                },
                onFailure = { e ->
                    Log.e("AlbumDetailVM", "disablePublicLink failed", e)
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update {
                        it.copy(
                            isTogglingPublicLink = false,
                            error = friendly ?: context.getString(R.string.share_disable_link_failed),
                        )
                    }
                },
            )
        }
    }

    /** Change an accepted member's permission bitmap (4 = viewer, 6 = editor). Optimistic, reverts on failure. */
    fun changeMemberPermission(memberId: String, permissions: Int) {
        val shareId = _uiState.value.shareId ?: return
        val originalMembers = _uiState.value.members
        val updatedMembers = originalMembers.map { m ->
            if (m.memberId == memberId) m.copy(permissions = permissions) else m
        }
        _uiState.update { it.copy(members = updatedMembers) }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.changeMemberPermission(userId, shareId, memberId, permissions) }
                .fold(
                    onSuccess = {
                        _uiState.update { it.copy(error = null) }
                    },
                    onFailure = { e ->
                        // Revert so the row reflects reality.
                        Log.e("AlbumDetailVM", "changeMemberPermission failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                members = originalMembers,
                                error = friendly ?: context.getString(R.string.share_permission_change_failed),
                            )
                        }
                    },
                )
        }
    }

    /** [changeMemberPermission] for a pending (not-yet-accepted) invitation. Optimistic, reverts on failure. */
    fun changeInvitationPermission(invitationId: String, permissions: Int) {
        val shareId = _uiState.value.shareId ?: return
        // Optimistic placeholder rows carry a blank id (no invitation to update on the backend yet).
        if (invitationId.isBlank()) return
        val originalInvitations = _uiState.value.invitations
        val updatedInvitations = originalInvitations.map { inv ->
            if (inv.invitationId == invitationId) inv.copy(permissions = permissions) else inv
        }
        _uiState.update { it.copy(invitations = updatedInvitations) }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.changeInvitationPermission(userId, shareId, invitationId, permissions) }
                .fold(
                    onSuccess = {
                        _uiState.update { it.copy(error = null) }
                    },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "changeInvitationPermission failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                invitations = originalInvitations,
                                error = friendly ?: context.getString(R.string.share_permission_change_failed),
                            )
                        }
                    },
                )
        }
    }

    /**
     * Bulk-invite emails: one [DrivePhotoRepository.inviteToAlbum] per email, then a summary in [inviteBatchResult].
     * [permissions] is the role picked in the sheet (4 = viewer, 6 = editor) and rides on each invitation,
     * so the pending rows below show what was actually granted. [message] has no backend setter yet.
     */
    fun inviteUsers(emails: List<String>, message: String, permissions: Int) {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        if (emails.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Optimistic pending rows for new invitees (blank invitationId; replaced by server truth on refresh).
            _uiState.update { state ->
                val known = (state.invitations.map { it.email } + state.members.map { it.email })
                    .map { it.lowercase() }
                    .toSet()
                val placeholders = emails
                    .filter { it.lowercase() !in known }
                    .map { ShareInvitation(invitationId = "", email = it, permissions = permissions) }
                state.copy(
                    isInvitingBatch = true,
                    invitations = state.invitations + placeholders,
                )
            }
            val failures = mutableListOf<Pair<String, String>>() // email → error message
            var successes = 0
            // The share each invite lands on. The first invite to an unshared album mints it, so
            // collecting it here is what lets this screen address the share it just made.
            val reportedShareIds = mutableListOf<String?>()
            for (email in emails) {
                runCatching { driveRepo.inviteToAlbum(userId, albumLinkId, email, permissions) }
                    .fold(
                        onSuccess = { shareId ->
                            successes++
                            reportedShareIds.add(shareId)
                        },
                        onFailure = { e ->
                            val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                            // Trust our IllegalArgumentException messages verbatim (they include the email);
                            // sanitise would mask it as <email>. Only sanitise opaque server messages.
                            val raw = if (e is IllegalArgumentException) e.message.orEmpty()
                                else sanitizeErrorMessage(e.message)
                            failures.add(email to (friendly ?: raw))
                        },
                    )
            }
            _uiState.update {
                it.copy(
                    isInvitingBatch = false,
                    // No success = no refetch, so strip placeholders to avoid ghost rows; on success loadInvitations() repaints.
                    invitations = if (successes > 0) it.invitations
                        else it.invitations.filter { inv -> inv.invitationId.isNotBlank() },
                    inviteBatchResult = InviteBatchResult(
                        successCount = successes,
                        failures = failures.toList(),
                    ),
                    shareId = AlbumShareIds.resolveBatch(it.shareId, reportedShareIds),
                )
            }
            // Refresh so the new pending rows appear in "Who has access" without re-opening the sheet.
            if (successes > 0) onShareCreated()
        }
    }

    fun clearInviteBatchResult() = _uiState.update { it.copy(inviteBatchResult = null) }

    fun clearShareLink() = _uiState.update { it.copy(shareLink = null) }

    fun downloadSelectedPhotos() {
        // Downloads land in DCIM/<AlbumName>/; an empty (undecryptable) name falls back to DCIM/Camera.
        val folderName = eu.akoos.photos.util.ProtonPhotosStorage.sanitize(_uiState.value.albumName)
        val selectedIds = _uiState.value.selectedPhotos
        val photos = _uiState.value.photos.filter { it.linkId in selectedIds }
        if (photos.isEmpty()) return
        enqueueAlbumDownload(folderName, photos.map { it.linkId }, clearSelectionOnEnqueue = true)
    }

    /**
     * Share selected photos to other apps: a local twin shares its URI directly; a cloud-only one
     * decrypts to cacheDir via [DrivePhotoRepository.downloadFullResPhoto] and goes through the share FileProvider.
     */
    fun shareSelected() {
        val state = _uiState.value
        val selected = state.photos.filter { it.linkId in state.selectedPhotos }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(shareState = AlbumShareState.Working(0, selected.size)) }
            val userId = accountManager.getPrimaryUserId().first()
            val uris = ArrayList<Uri>(selected.size)
            var done = 0
            for (photo in selected) {
                runCatching {
                    val local = state.localUriByLinkId[photo.linkId]
                    if (local != null) {
                        Uri.parse(local)
                    } else {
                        val uid = userId ?: error("Not signed in")
                        val file = driveRepo.downloadFullResPhoto(uid, photo)
                        androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.share.fileprovider", file,
                        ).also {
                            // Report the real filename to the receiver, not the linkId.
                            eu.akoos.photos.util.ShareFileProvider.putDisplayName(it, photo.displayName)
                        }
                    }
                }.onSuccess { uris.add(it) }
                    .onFailure { Log.w("AlbumDetailVM", "share resolve failed: ${it.message}") }
                done++
                _uiState.update { it.copy(shareState = AlbumShareState.Working(done, selected.size)) }
            }
            if (uris.isNotEmpty()) {
                val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMimeOf(selected.map { it.mimeType })
                _shareIntent.tryEmit(
                    eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, uris, mime),
                )
            }
            // A photo that could not be resolved never reaches the chooser, so say how many did.
            val shareMessage = shareOutcome(uris.size, selected.size - uris.size).message()
            _uiState.update {
                it.copy(
                    shareState = AlbumShareState.Idle,
                    selectedPhotos = emptySet(),
                    error = shareMessage?.resolve(context),
                )
            }
        }
    }

    // ── Public link for the single selected photo — delegated to the shared [PublicLinkController]
    // so the album selection behaves identically to the timeline and viewer. Only share metadata is
    // touched, never photo content. ────────────────────────────────────────────────────────────────
    val publicLinkState: StateFlow<PublicLinkState> = publicLink.state

    /** The single selected album photo's linkId, or null when 0 or >1 are selected. */
    fun singleSelectedLinkId(): String? =
        _uiState.value.selectedPhotos.takeIf { it.size == 1 }?.first()

    fun loadPublicLink() = publicLink.load(viewModelScope, singleSelectedLinkId(), setLoading = true)

    fun createSelectedPhotoLink() = publicLink.create(viewModelScope)

    fun revokePublicLink() = publicLink.revoke(viewModelScope)

    fun setLinkPassword(password: String?) = publicLink.setPassword(viewModelScope, password)

    fun currentPublicLinkUrl(): String? = publicLink.currentUrl()

    fun downloadAllPhotos() {
        // See downloadSelectedPhotos above — same album-aware routing.
        val folderName = eu.akoos.photos.util.ProtonPhotosStorage.sanitize(_uiState.value.albumName)
        val linkIds = _uiState.value.photos.map { it.linkId }
        if (linkIds.isEmpty()) return
        enqueueAlbumDownload(folderName, linkIds, clearSelectionOnEnqueue = false)
    }

    /**
     * Shared-with-me "download": copies the shared album into a new owned album of the same name.
     * The backend duplicates the encrypted blobs server-side, so this is metadata-only on the client.
     */
    fun saveSharedAlbumToOwnLibrary() {
        val state = _uiState.value
        val albumLinkId = state.albumLinkId
        val sharingShareId = state.shareId
        val volumeId = state.volumeId
        if (albumLinkId.isBlank() || sharingShareId == null || volumeId == null) return
        if (!state.isSharedWithMe) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            driveRepo.startSaveSharedAlbumToOwnLibrary(
                userId = userId,
                sharingShareId = sharingShareId,
                sourceAlbumLinkId = albumLinkId,
                sourceAlbumDecryptedName = state.albumName,
                sourceVolumeId = volumeId,
            )
        }
    }

    fun clearSaveToLibraryResult() {
        driveRepo.acknowledgeSaveSharedAlbumResult()
        _uiState.update { it.copy(saveToLibraryResult = null) }
    }

    /** Abort an in-flight save-to-library copy. Safe to call when none is running. */
    fun cancelSaveToLibrary() {
        driveRepo.cancelSaveSharedAlbumToOwnLibrary()
    }

    /**
     * Hide this album client-side by adding its linkId to [SettingsKeys.HIDDEN_ALBUM_IDS], the same
     * write the Albums grid's own hide makes. Nothing on Drive changes, so the album stays intact and
     * returns on unhide.
     *
     * The pop-back signal goes up only once the write has landed. Popping first would clear this
     * ViewModel, and with it the scope the edit is running in.
     */
    fun hideAlbum() {
        val albumLinkId = _uiState.value.albumLinkId
        if (albumLinkId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_ALBUM_IDS] = current + albumLinkId
                }
            }.onSuccess {
                _uiState.update { it.copy(hideAlbumDone = true) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AlbumDetailVM", "hideAlbum failed", e)
            }
        }
    }

    /**
     * Show or hide this album's photos in the main feed, the same key the Albums grid's drawer and
     * the Settings picker write.
     *
     * Display only, so nothing to reconcile: the gallery observes the key and re-filters on the next
     * emission. A separate set from [hideAlbum]'s, so this leaves the card on the grid, leaves the
     * photos in search, on the map, in the calendar and in every picker, and leaves this screen open
     * rather than popping back. Reading and writing inside the same edit keeps the flip atomic.
     */
    fun toggleHiddenFromTimeline() {
        val albumLinkId = _uiState.value.albumLinkId
        if (albumLinkId.isBlank()) return
        viewModelScope.launch {
            runCatching {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] ?: emptySet()
                    prefs[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] =
                        AlbumTimelineHide.toggled(current, albumLinkId)
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AlbumDetailVM", "toggleHiddenFromTimeline failed", e)
            }
        }
    }

    /** Recipient-side "Leave album": resolve the user's membership + POST the delete, then signal a pop-back. */
    fun leaveSharedAlbum() {
        val st = _uiState.value
        val shareId = st.shareId
        val albumLinkId = st.albumLinkId
        val userId = primaryUserId
        if (shareId.isNullOrBlank() || albumLinkId.isBlank() || userId == null) {
            _uiState.update { it.copy(error = context.getString(R.string.album_leave_missing_details)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLeavingAlbum = true, error = null) }
            runCatching {
                driveRepo.leaveSharedAlbum(userId, shareId, albumLinkId)
            }.onSuccess {
                _uiState.update { it.copy(isLeavingAlbum = false, leaveAlbumDone = true) }
                // Refresh the shared-with-me grid so the album disappears on pop-back.
                albumListEvents.notifyChanged()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AlbumDetailVM", "leaveSharedAlbum failed", e)
                val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                _uiState.update {
                    it.copy(isLeavingAlbum = false, error = friendly ?: context.getString(R.string.album_leave_failed))
                }
            }
        }
    }

    fun clearSaveCancelledAt() {
        driveRepo.acknowledgeSaveSharedAlbumResult()
        _uiState.update { it.copy(saveCancelledAt = null) }
    }

    // Singleton-backed so save progress survives VM destruction — the next VM re-subscribes and inherits it.
    init {
        viewModelScope.launch {
            driveRepo.saveSharedAlbumState.collect { progress ->
                when (progress) {
                    is DrivePhotoRepository.SaveSharedAlbumProgress.Idle -> {
                        _uiState.update { it.copy(isSavingToLibrary = false, savingCopied = 0, savingTotal = 0) }
                    }
                    is DrivePhotoRepository.SaveSharedAlbumProgress.Running -> {
                        if (progress.sourceAlbumLinkId == _uiState.value.albumLinkId) {
                            _uiState.update {
                                it.copy(
                                    isSavingToLibrary = true,
                                    savingCopied = progress.copied,
                                    savingTotal = progress.total,
                                )
                            }
                        }
                    }
                    is DrivePhotoRepository.SaveSharedAlbumProgress.Done -> {
                        if (progress.sourceAlbumLinkId == _uiState.value.albumLinkId) {
                            _uiState.update {
                                it.copy(
                                    isSavingToLibrary = false,
                                    savingCopied = 0,
                                    savingTotal = 0,
                                    saveToLibraryResult = SaveToLibraryResult(
                                        newAlbumLinkId = progress.newAlbumLinkId,
                                        copiedCount = progress.copiedCount,
                                        failedCount = progress.failedCount,
                                        totalRequested = progress.totalRequested,
                                    ),
                                )
                            }
                            albumListEvents.notifyChanged()
                        }
                    }
                    is DrivePhotoRepository.SaveSharedAlbumProgress.Failed -> {
                        if (progress.sourceAlbumLinkId == _uiState.value.albumLinkId) {
                            _uiState.update {
                                it.copy(
                                    isSavingToLibrary = false,
                                    savingCopied = 0,
                                    savingTotal = 0,
                                    error = context.getString(R.string.shared_save_failed),
                                )
                            }
                            driveRepo.acknowledgeSaveSharedAlbumResult()
                        }
                    }
                    is DrivePhotoRepository.SaveSharedAlbumProgress.Cancelled -> {
                        if (progress.sourceAlbumLinkId == _uiState.value.albumLinkId) {
                            _uiState.update {
                                it.copy(
                                    isSavingToLibrary = false,
                                    savingCopied = 0,
                                    savingTotal = 0,
                                    saveCancelledAt = progress.copied to progress.total,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Mirror the background album download into downloadState; re-attaches to the unique-work entry on each VM open.
    init {
        viewModelScope.launch {
            // Only a run this collector actually watched leaves a result. WorkManager keeps a
            // finished entry, so re-opening the album re-delivers its terminal state, and reporting
            // on that alone would announce a download the user already saw finish.
            var sawRunning = false
            observeDownloadWorkInfo().collect { workInfo ->
                // The requested count is the true denominator for a partial download; fall back to
                // the album size only when no download has been requested this session.
                val fallbackTotal = _uiState.value.downloadRequestedTotal
                    .takeIf { it > 0 } ?: _uiState.value.photos.size
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> sawRunning = true
                    WorkInfo.State.SUCCEEDED -> if (sawRunning) {
                        sawRunning = false
                        val out = workInfo.outputData
                        _downloadResult.emit(
                            AlbumDownloadResult(
                                saved = out.getInt(AlbumDownloadWorker.KEY_RESULT_SAVED, 0),
                                failed = out.getInt(AlbumDownloadWorker.KEY_RESULT_FAILED, 0),
                            ),
                        )
                    }
                    // A failed run carries no counts, so everything it was asked for is reported
                    // as failed. Cancellation is the user's own action and needs no report.
                    WorkInfo.State.FAILED -> if (sawRunning) {
                        sawRunning = false
                        _downloadResult.emit(AlbumDownloadResult(saved = 0, failed = fallbackTotal))
                    }
                    WorkInfo.State.CANCELLED -> sawRunning = false
                    null -> Unit
                }
                val next = when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> AlbumDownloadState.Working(
                        workInfo.progress.getInt(AlbumDownloadWorker.KEY_PROGRESS_DONE, 0),
                        workInfo.progress.getInt(AlbumDownloadWorker.KEY_PROGRESS_TOTAL, fallbackTotal),
                    )
                    // Queued — show the ring at zero so the button morphs the instant work is accepted.
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> AlbumDownloadState.Working(0, fallbackTotal)
                    // Terminal states (or no entry) collapse the in-app ring; the notification self-dismisses.
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                    null -> AlbumDownloadState.Idle
                }
                _uiState.update { state ->
                    // Hold Enqueued through the window before WorkManager registers the entry (observer reports Idle then),
                    // so the one-shot snackbar isn't pre-empted; a real Working tick still takes over.
                    if (state.downloadState is AlbumDownloadState.Enqueued &&
                        next is AlbumDownloadState.Idle
                    ) {
                        state
                    } else {
                        state.copy(downloadState = next)
                    }
                }
            }
        }
    }

    /** Tracks download work for the loaded album; albumLinkId arrives via [load], so flatMapLatest switches the subscription. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeDownloadWorkInfo(): Flow<WorkInfo?> =
        _uiState
            .map { it.albumLinkId }
            .distinctUntilChanged()
            .flatMapLatest { albumLinkId ->
                if (albumLinkId.isBlank()) emptyFlowOfWorkInfo()
                else observeUniqueWork(AlbumDownloadWorker.uniqueName(albumLinkId))
            }

    /** Empty stand-in so [flatMapLatest] has a flow to switch to before an album is loaded. */
    private fun emptyFlowOfWorkInfo(): Flow<WorkInfo?> = callbackFlow {
        trySend(null)
        awaitClose { }
    }

    /** Bridge WorkManager's LiveData to a Flow (2.9 has no Flow accessor); emits null with no entry so the collector idles. */
    private fun observeUniqueWork(uniqueWorkName: String): Flow<WorkInfo?> = callbackFlow {
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(uniqueWorkName)
        val observer = Observer<List<WorkInfo>> { infos ->
            // REPLACE leaves the prior run as a terminal entry, so take the last (freshest).
            trySend(infos.lastOrNull())
        }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }.flowOn(Dispatchers.Main)

    /** Hand the download off to [AlbumDownloadWorker] and set [AlbumDownloadState.Enqueued] once for the snackbar. */
    private fun enqueueAlbumDownload(
        folderName: String,
        photoLinkIds: List<String>,
        clearSelectionOnEnqueue: Boolean,
    ) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val albumLinkId = _uiState.value.albumLinkId
            // Record the requested count before enqueuing so the progress observer reads it the
            // instant WorkManager reports the work enqueued, never flashing the whole-album size.
            _uiState.update { it.copy(downloadRequestedTotal = photoLinkIds.size) }
            // enqueue() spills the id list to a cache file, so run it off the main thread.
            withContext(Dispatchers.IO) {
                AlbumDownloadWorker.enqueue(
                    context = context,
                    albumLinkId = albumLinkId,
                    albumName = folderName,
                    photoLinkIds = photoLinkIds,
                    userIdString = userId.id,
                )
            }
            _uiState.update {
                it.copy(
                    downloadState = AlbumDownloadState.Enqueued,
                    selectedPhotos = if (clearSelectionOnEnqueue) emptySet() else it.selectedPhotos,
                )
            }
            _downloadStarted.emit(Unit)
        }
    }

    /** Cancel the in-flight album download; the observer flips the button back to Idle. */
    fun cancelDownload() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        WorkManager.getInstance(context)
            .cancelUniqueWork(AlbumDownloadWorker.uniqueName(albumLinkId))
    }

    fun resetDownloadState() = _uiState.update { it.copy(downloadState = AlbumDownloadState.Idle) }

    fun deleteShare() {
        val shareId = _uiState.value.shareId ?: return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isSharing = true) }
            runCatching { driveRepo.deleteShare(userId, shareId) }
                .fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(
                                isSharing = false,
                                shareId = null,
                                publicShareUrl = null,
                                members = emptyList(),
                                invitations = emptyList(),
                                error = null,
                            )
                        }
                        // Re-fetch the gallery album list, else the shared-badge sticks until cold start.
                        albumListEvents.notifyChanged()
                    },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "deleteShare failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                isSharing = false,
                                error = friendly ?: context.getString(R.string.share_stop_failed),
                            )
                        }
                    },
                )
        }
    }
}
