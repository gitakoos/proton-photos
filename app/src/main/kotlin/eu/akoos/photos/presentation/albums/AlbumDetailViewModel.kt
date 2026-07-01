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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.GetUser
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.viewer.PublicLinkState
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.worker.AlbumDownloadWorker
import javax.inject.Inject

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

/** Progress of a share-to-other-apps batch. [Working] advances per resolved photo — cloud-only
 *  album photos decrypt to a temp file first, so the share pill shows a determinate ring. */
sealed class AlbumShareState {
    data object Idle : AlbumShareState()
    data class Working(val done: Int, val total: Int) : AlbumShareState()
}

/** Which foreground bulk action is in flight, so the blocking drawer can label it correctly —
 *  delete, remove-from-album and hide all raise [AlbumDetailUiState.isDeletingPhotos]. */
enum class AlbumBusyOp { None, Deleting, Removing }

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
    val shareState: AlbumShareState = AlbumShareState.Idle,
    /** linkId → local MediaStore URI for photos that have been downloaded to this device. */
    val localUriByLinkId: Map<String, String> = emptyMap(),
    /** Cloud linkIds pinned for offline; the grid draws a download badge on each matching photo. */
    val offlinePinIds: Set<String> = emptySet(),
    /** Live progress of a "make available offline" batch: [offlinePinningDone] of [offlinePinningTotal]
     *  full-res blobs fetched. [offlinePinningTotal] is 0 when no pin batch is running. */
    val offlinePinningDone: Int = 0,
    val offlinePinningTotal: Int = 0,
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
) {
    val isSelectionMode: Boolean get() = selectedPhotos.isNotEmpty()
    val selectedCount: Int get() = selectedPhotos.size
    val isSharedWithMe: Boolean get() = sharedByEmail != null
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
    private val hiddenStorage: HiddenStorageManager,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val offlineStore: eu.akoos.photos.data.offline.OfflineStorageManager,
    private val transferCenter: eu.akoos.photos.data.transfer.TransferCenter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    /** One-shot system-share intents emitted to the screen, which launches the chooser. */
    private val _shareIntent = MutableSharedFlow<Intent>(replay = 0, extraBufferCapacity = 1)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    /** One-shot offline pin/un-pin outcome: a positive count was pinned, a negative count removed. */
    private val _offlineResult = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    val offlineResult: SharedFlow<Int> = _offlineResult.asSharedFlow()

    /** Cached primary userId — same rationale as GalleryViewModel.primaryUserId. */
    @Volatile private var primaryUserId: me.proton.core.domain.entity.UserId? = null

    // captureTime DESC with linkId tie-breaker (matches AlbumService.photoOrder) so equal-captureTime
    // bursts don't reshuffle between the observer's chunked emissions and the final server paint.
    private val photoOrder = compareByDescending<CloudPhoto> { it.captureTimeMs }.thenBy { it.linkId }

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
            syncStateRepo.observeAll(userId).collect { states ->
                // Only SYNCED rows = on-device. CLOUD_ONLY rows can keep a stale localUri (freed-up space).
                val map = states
                    .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
                    .associate { it.cloudFileId!! to it.localUri }
                _uiState.update { it.copy(localUriByLinkId = map) }
            }
        }
        // Offline-pinned linkIds → per-cell offline badge. Same OFFLINE_PIN_IDS pref the timeline reads.
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
                .distinctUntilChanged()
                .collect { ids -> _uiState.update { it.copy(offlinePinIds = ids) } }
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
            // Phase 1: instant cache read so re-opening feels free. Pre-migration rows (parentLinkId == null) miss here.
            val cached = runCatching { driveRepo.loadAlbumPhotosCached(albumLinkId) }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(isLoading = false, photos = cached) }
            }

            if (!networkObserver.isOnline.value) {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Phase 2: full network refresh. onLinkIdsResolved fires after the cheap children-fetch
            // but before the heavy metadata work — drop the skeleton there and observe the DB by
            // linkId so chunked upserts trickle in instead of one shimmer until the whole album lands.
            runCatching {
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
            }.fold(
                    onSuccess = { photos ->
                        // Definitive server list (usually a no-op since the observer already used server order).
                        _uiState.update { state ->
                            val existingById = state.photos.associateBy { it.linkId }
                            state.copy(
                                isLoading = false,
                                photos = photos.map { server ->
                                    val cached = existingById[server.linkId] ?: return@map server
                                    server.copy(
                                        thumbnailUrl = server.thumbnailUrl ?: cached.thumbnailUrl,
                                    )
                                },
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
     * (Re)subscribe the DB observe to [linkIds], collecting chunked upserts into [AlbumDetailUiState.photos].
     * Cancels any prior observe first so the job never leaks, and records [observedLinkIds] so a remove
     * can re-bind to the surviving set instead of letting the old observe re-emit removed photos.
     */
    private fun startPhotoObserve(linkIds: List<String>) {
        observeJob?.cancel()
        observedLinkIds = linkIds
        observeJob = viewModelScope.launch {
            driveRepo.observePhotosByLinkIds(linkIds).collect { dbRows ->
                val byId = dbRows.associateBy { it.linkId }
                val ordered = linkIds.mapNotNull { byId[it] }
                    .sortedWith(photoOrder)
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

    /** Replace the whole selection — used by the drag-select sweep, which sets the swept range each frame. */
    fun setSelectedPhotos(linkIds: Set<String>) = _uiState.update { it.copy(selectedPhotos = linkIds) }

    /**
     * Pin or un-pin the selected album photos for offline viewing, mirroring the timeline's batch
     * toggle. If every selected photo is already offline it removes them (drops the pins + blobs, no
     * network); otherwise it downloads the full-res blob for the ones not yet pinned. The pin set is
     * updated optimistically so the per-cell badge reflects at once, a failed download is reverted,
     * and the outcome (+N pinned / -N removed) is emitted on [offlineResult].
     */
    fun toggleSelectedOffline() {
        val selected = _uiState.value.photos.filter { it.linkId in _uiState.value.selectedPhotos }
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
            _uiState.update {
                it.copy(selectedPhotos = emptySet(), offlinePinningTotal = toPin.size, offlinePinningDone = 0)
            }
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
                    // Advance the progress pill once per attempt so it fills to the total either way.
                    _uiState.update { it.copy(offlinePinningDone = it.offlinePinningDone + 1) }
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
            _uiState.update { it.copy(offlinePinningTotal = 0, offlinePinningDone = 0) }
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
    /** Private-vault URIs collected during a hide, committed once the system delete dialog OK's
     *  (rolled back on cancel so a photo never ends up in both the vault and MediaStore). */
    private var pendingHidePrivateUris: List<String> = emptyList()
    /** linkIds the in-flight hide will drop from the album list once its delete confirms. */
    private var pendingHideLinkIds: List<String> = emptyList()
    /** True when the deferred system-dialog work belongs to a hide, so [onDeletePermissionGranted]
     *  commits the vault URIs instead of running the delete-only finish. */
    private var pendingHideInFlight: Boolean = false

    /** Resolve selected photos to [GalleryItem]s for delete: [GalleryItem.Synced] if a local twin exists, else CloudOnly. */
    private fun selectedGalleryItems(): List<eu.akoos.photos.domain.entity.GalleryItem> {
        val state = _uiState.value
        val byId = state.photos.associateBy { it.linkId }
        return state.selectedPhotos.mapNotNull { linkId ->
            val photo = byId[linkId] ?: return@mapNotNull null
            val uri = state.localUriByLinkId[linkId]
            if (uri != null) eu.akoos.photos.domain.entity.GalleryItem.Synced(
                photo,
                eu.akoos.photos.domain.entity.LocalMediaItem(
                    uri = uri,
                    dateTaken = photo.captureTimeMs,
                    displayName = "",
                    mimeType = photo.mimeType,
                    sizeBytes = 0L,
                    bucketName = null,
                ),
            ) else eu.akoos.photos.domain.entity.GalleryItem.CloudOnly(photo)
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
                is eu.akoos.photos.domain.usecase.DeletePhotoUseCase.Result.Success ->
                    finishDelete(linkIds, deleteFromCloud)
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
        pendingHideLinkIds = emptyList()
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

    /** Persist the staged vault URIs into HIDDEN_PHOTO_URIS and clear the pending list. */
    private fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + uris
            }
        }
    }

    /** Roll back vault copies that were created but never committed (cancel / error path). */
    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        for (u in uris) hiddenStorage.delete(u)
    }

    fun clearHideCloudNotice() = _uiState.update { it.copy(hideCloudNoticePending = false) }

    /** Run the deferred cloud delete (or commit a deferred hide) once the system dialog is confirmed,
     *  then update the view. The same [pendingDeleteIntent] carries both flows; [pending.hide] picks. */
    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult ?: return
        val linkIds = pendingDeleteLinkIds
        val fromCloud = pendingDeleteFromCloud
        val hideLinkIds = pendingHideLinkIds
        val wasHide = pendingHideInFlight
        pendingPermissionResult = null
        pendingHideInFlight = false
        _uiState.update { it.copy(pendingDeleteIntent = null) }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first()
            if (userId != null) runCatching {
                deletePhotoUseCase.completeAfterPermissionGranted(
                    userId = userId,
                    cloudLinkIds = pending.cloudLinkIds,
                    items = pending.itemsBeingDeleted,
                    freeUpSpace = pending.freeUpSpace,
                    hide = pending.hide,
                )
            }
            if (wasHide) finishHide(hideLinkIds) else finishDelete(linkIds, fromCloud)
        }
    }

    /** User cancelled the system trash dialog — drop the deferred cloud work, and for a hide also
     *  roll back the orphaned vault copies so the photo isn't left in both places. */
    fun clearPendingDeleteIntent() {
        pendingPermissionResult = null
        if (pendingHideInFlight) {
            pendingHideInFlight = false
            rollbackPendingHide()
            pendingHideLinkIds = emptyList()
        }
        _uiState.update { it.copy(isDeletingPhotos = false, pendingDeleteIntent = null) }
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

    fun createShareLink() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isSharing = true) }
            runCatching { driveRepo.createAlbumShareLink(userId, albumLinkId) }
                .fold(
                    onSuccess = { url ->
                        // Set both: shareLink (one-shot clipboard trigger) and publicShareUrl (persistent sheet state).
                        _uiState.update {
                            it.copy(isSharing = false, shareLink = url, publicShareUrl = url)
                        }
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
                    onSuccess = { url ->
                        Log.d("AlbumDetailVM", "createPublicLink: SUCCESS url=$url")
                        _uiState.update { it.copy(isTogglingPublicLink = false, publicShareUrl = url) }
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

    fun inviteUser(email: String) {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.inviteToAlbum(userId, albumLinkId, email) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(error = null) } },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "inviteUser failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(error = friendly ?: context.getString(R.string.share_invite_failed))
                        }
                    },
                )
        }
    }

    /**
     * Bulk-invite emails: one [DrivePhotoRepository.inviteToAlbum] per email, then a summary in [inviteBatchResult].
     * [message] and [permissions] have no backend setter yet — accepted for forward compat, dropped at the data layer.
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
            for (email in emails) {
                runCatching { driveRepo.inviteToAlbum(userId, albumLinkId, email) }
                    .fold(
                        onSuccess = { successes++ },
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
                )
            }
            // Refresh so the new pending rows appear in "Who has access" without re-opening the sheet.
            if (successes > 0) loadInvitations()
        }
    }

    fun clearInviteBatchResult() = _uiState.update { it.copy(inviteBatchResult = null) }

    fun clearShareLink() = _uiState.update { it.copy(shareLink = null) }

    fun downloadSelectedPhotos() {
        // Downloads land in Pictures/<AlbumName>/; an empty (undecryptable) name falls back to Pictures/ root.
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
            _uiState.update { it.copy(shareState = AlbumShareState.Idle, selectedPhotos = emptySet()) }
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
            observeDownloadWorkInfo().collect { workInfo ->
                val fallbackTotal = _uiState.value.photos.size
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
