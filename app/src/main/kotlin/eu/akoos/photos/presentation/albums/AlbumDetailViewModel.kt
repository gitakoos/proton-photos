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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.GetUser
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.ShareInvitation
import eu.akoos.photos.domain.entity.ShareMember
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.worker.AlbumDownloadWorker
import javax.inject.Inject

/**
 * Result of a bulk invite-by-email batch — used to feed a single snackbar summary
 * back to the UI after [AlbumDetailViewModel.inviteUsers] finishes. [failures] is the
 * raw error message per failed email so the snackbar can show e.g. "foo@bar.com:
 * outdated app". A clean run has [successCount] > 0 and an empty [failures].
 */
data class InviteBatchResult(
    val successCount: Int,
    val failures: List<Pair<String, String>>,
) {
    val totalAttempted: Int get() = successCount + failures.size
}

sealed class AlbumDownloadState {
    data object Idle : AlbumDownloadState()
    data class Working(val done: Int, val total: Int) : AlbumDownloadState()
    /**
     * The download has been handed off to [AlbumDownloadWorker]; the actual per-file progress
     * is now reflected by the system notification, not the in-app sheet. The UI uses this
     * state to dismiss the "Downloading" overlay and show a one-shot confirmation snackbar.
     */
    data object Enqueued : AlbumDownloadState()
}

data class AlbumDetailUiState(
    val albumName: String = "",
    val albumLinkId: String = "",
    val isLoading: Boolean = true,
    val photos: List<CloudPhoto> = emptyList(),
    val error: String? = null,
    val selectedPhotos: Set<String> = emptySet(),
    val isDeletingPhotos: Boolean = false,
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
    /** linkId → local MediaStore URI for photos that have been downloaded to this device. */
    val localUriByLinkId: Map<String, String> = emptyMap(),
    /** True while the multi-email Share-popup batch is in flight; gates the "Share" button + chip removals. */
    val isInvitingBatch: Boolean = false,
    /** Set after a [AlbumDetailViewModel.inviteUsers] batch completes; consumed once by the UI snackbar. */
    val inviteBatchResult: InviteBatchResult? = null,
    /** Bumped when [AlbumDetailViewModel.setPhotoAsCover] or [setSelectedPhotoAsCover] succeed; the
     *  UI consumes this via a LaunchedEffect to show a one-shot "Cover updated" snackbar. Using a
     *  monotonically increasing tick (not a Boolean flag) means two consecutive sets in a row still
     *  trigger two snackbars without a manual "clear" round-trip. */
    val coverUpdatedTick: Int = 0,
    /** True while the shared-album "Save to my library" round-trip is in flight. */
    val isSavingToLibrary: Boolean = false,
    /** Photos already copied + total to copy. Surfaces the per-photo progress as
     *  a real "N of M" indicator on the action button. Both reset to 0 once
     *  the singleton-backed flow returns to Idle. */
    val savingCopied: Int = 0,
    val savingTotal: Int = 0,
    /** One-shot summary the UI consumes via LaunchedEffect to snackbar the outcome
     *  of [saveSharedAlbumToOwnLibrary] — copied count, total requested, and the
     *  new owned album's linkId so the toast can offer a "View library album" jump. */
    val saveToLibraryResult: SaveToLibraryResult? = null,
    /** One-shot snapshot of a user-cancelled save-to-library job. Holds the
     *  copied/total pair at the moment of cancellation so the snackbar can
     *  surface "Save cancelled at N / M". Cleared by the UI once consumed. */
    val saveCancelledAt: Pair<Int, Int>? = null,
    /** True while the "Leave album" action is in flight on a shared-with-me album. */
    val isLeavingAlbum: Boolean = false,
    /** Bumped to `true` once the leave round-trip completes successfully so the screen
     *  can pop back to the Shared tab. The UI consumes this via a LaunchedEffect; the
     *  ViewModel does not navigate on its own. */
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    /** Cached primary userId — same rationale as GalleryViewModel.primaryUserId. */
    @Volatile private var primaryUserId: me.proton.core.domain.entity.UserId? = null

    // Canonical album photo order, kept in step with AlbumService.photoOrder: effective
    // captureTime DESC (via TimestampSanity, matching the timeline key) with linkId as a
    // stable tie-breaker so equal-captureTime bursts don't reshuffle between the observer's
    // chunked emissions and the final server paint.
    private val photoOrder = compareByDescending<CloudPhoto> { it.captureTimeMs }.thenBy { it.linkId }

    /**
     * Enqueue an on-demand thumbnail decrypt for the album cell with [linkId]. The
     * scheduler dedup's by linkId so it's safe to call repeatedly across recompositions.
     * No-op until the first primaryUserId emission has landed (init flow below).
     */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        driveRepo.requestThumbnailDecrypt(userId, linkId)
    }

    /**
     * Cancel any in-flight decrypt for [linkId] — called from PhotoCell's DisposableEffect
     * when the cell scrolls off-screen.
     */
    fun cancelThumbnailDecrypt(linkId: String) {
        driveRepo.cancelThumbnailDecrypt(linkId)
    }

    init {
        viewModelScope.launch { accountManager.getPrimaryUserId().collect { primaryUserId = it } }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            syncStateRepo.observeAll(userId).collect { states ->
                // Only SYNCED rows mean "the file is on this device AND on Drive". CLOUD_ONLY
                // rows can still carry a non-null localUri (a leftover reference from before
                // the user freed up space) — using those would falsely mark cloud-only photos
                // as "downloaded" in the album grid.
                val map = states
                    .filter { it.status == SyncStatus.SYNCED && it.cloudFileId != null }
                    .associate { it.cloudFileId!! to it.localUri }
                _uiState.update { it.copy(localUriByLinkId = map) }
            }
        }
        // Resolve the owner email once per VM — the share-sheet renders it as the "owner"
        // row, so it must be ready when the user opens the sheet. Failures are silent
        // (the sheet falls back to "You" in copy if the email is empty).
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val email = runCatching { getUser(userId, refresh = false).email }.getOrNull().orEmpty()
            _uiState.update { it.copy(ownerEmail = email) }
        }
        // Re-pull this album when photos are added/removed elsewhere (e.g. the gallery's
        // "Add to album" sheet emits on the album-list bus). Without this the detail grid
        // keeps the stale snapshot until the user re-opens the album.
        viewModelScope.launch {
            albumListEvents.changes.collect {
                if (_uiState.value.albumLinkId.isNotBlank()) refresh()
            }
        }
    }

    // Cancelled on each new load() call so stale DB observers don't linger.
    private var albumJob: Job? = null

    fun load(albumLinkId: String, albumName: String, shareId: String?, sharedByEmail: String? = null, volumeId: String? = null) {
        albumJob?.cancel()
        _uiState.update { it.copy(
            isLoading = true, albumName = albumName, albumLinkId = albumLinkId,
            shareId = shareId, sharedByEmail = sharedByEmail, volumeId = volumeId, error = null,
        ) }
        albumJob = viewModelScope.launch {
            // Phase 1: instant cache read. Renders the grid without any network round trip,
            // so re-opening an album feels free. Pre-migration rows (parentLinkId == null)
            // return empty here and fall back to the network phase.
            val cached = runCatching { driveRepo.loadAlbumPhotosCached(albumLinkId) }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(isLoading = false, photos = cached) }
            }

            if (!networkObserver.isOnline.value) {
                // Offline: stick with the cached snapshot (already emitted above) and clear
                // the skeleton spinner.
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Phase 2: full network refresh. Picks up new additions, refreshed thumbnail
            // URLs, etc.
            //
            // The onLinkIdsResolved callback runs after the cheap children-fetch step (a
            // paginated call that only returns linkIds + capture times) but BEFORE the
            // heavier chunked metadata/thumbnail-info work. We drop the skeleton at that
            // point and start observing the DB by linkId — chunked upserts inside
            // loadAlbumPhotos then trickle into the UI as they land, instead of the user
            // staring at a shimmer until the WHOLE album finished processing.
            var observeJob: kotlinx.coroutines.Job? = null
            runCatching {
                driveRepo.loadAlbumPhotos(
                    userId = userId,
                    albumLinkId = albumLinkId,
                    volumeId = volumeId,
                    // For shared-with-me albums (any time we have a shareId attached) the
                    // album metadata + photo keys can't be opened via this user's root
                    // link key — pass the sharing share id so the lower layer bootstraps
                    // and unlocks the share key to use as the parent in its place.
                    sharingShareId = _uiState.value.shareId,
                    onLinkIdsResolved = { linkIds ->
                        // If the album is genuinely empty (rare) drop the skeleton
                        // straight to the "No photos" empty state. Otherwise keep
                        // `isLoading = true` until the first DB row lands so the
                        // skeleton tiles transition straight to real cells instead
                        // of flashing through the empty-album copy mid-load.
                        if (linkIds.isEmpty()) {
                            _uiState.update { it.copy(isLoading = false) }
                            return@loadAlbumPhotos
                        }
                        observeJob = viewModelScope.launch {
                            driveRepo.observePhotosByLinkIds(linkIds).collect { dbRows ->
                                val byId = dbRows.associateBy { it.linkId }
                                // Render captureTime DESC — matches Drive web UI and the
                                // final loadAlbumPhotos result, so the observer's chunked
                                // updates settle into the same order as the final paint.
                                val ordered = linkIds.mapNotNull { byId[it] }
                                    .sortedWith(photoOrder)
                                _uiState.update { state ->
                                    val existingById = state.photos.associateBy { it.linkId }
                                    // An empty observation pass is NOT a green light to wipe
                                    // the grid. It can mean the network refresh just walked
                                    // the album membership table and the entities haven't
                                    // landed in `photo_listing` under the new linkIds yet
                                    // (shared-album rewrites do that), or that we're between
                                    // chunked upserts. In both cases the cached snapshot
                                    // we painted from `loadAlbumPhotosCached` is still the
                                    // truthful view of what's in the album, so keep it.
                                    if (ordered.isEmpty() && state.photos.isNotEmpty()) {
                                        return@update state
                                    }
                                    state.copy(
                                        // First non-empty DB emission is what swaps the
                                        // skeleton for the real grid.
                                        isLoading = state.isLoading && ordered.isEmpty(),
                                        photos = ordered.map { dbPhoto ->
                                            // Adopt updates from DB; fall back to the existing
                                            // state if a field would otherwise blank out (e.g.
                                            // pre-decrypt display name).
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
                    },
                )
            }.fold(
                    onSuccess = { photos ->
                        // Definitive list — make sure ordering matches the server's final
                        // response (the observer above used the eager linkId list which is
                        // already in server order, so this is usually a no-op).
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
                        // Coroutine cancellation is not an error: it fires whenever the
                        // user navigates back before the network refresh finishes (the
                        // viewModelScope cancels its children on dispose). Surfacing
                        // "StandaloneCoroutine was cancelled" as a snackbar makes every
                        // back-press look like a failure even though everything worked.
                        if (e is kotlinx.coroutines.CancellationException) return@fold
                        // Network failure: keep the cached snapshot (if any), drop the
                        // skeleton, and let the offline banner / avatar dot tell the user
                        // why the list didn't refresh. For non-network exceptions we still
                        // route the message through sanitizeErrorMessage so a server-side
                        // HTML page can't leak into the error banner.
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
        // Kick the members/invitations fetch off in parallel with the photo load for any
        // owned album (not just ones whose share sheet is already open), so the avatar row
        // is populated by the time the header renders instead of popping in late.
        // loadInvitations() self-guards on shareId/shared-with-me and launches its own
        // coroutine, so this never blocks the photo path.
        if (sharedByEmail == null) {
            loadInvitations()
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
            // Surface load failures via state.error so a network drop renders an
            // error snackbar instead of an empty members list.
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
        // Optimistic placeholder rows carry a blank id until loadInvitations() replaces
        // them with server truth — there's nothing to revoke on the backend yet.
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

    fun clearSelection() = _uiState.update { it.copy(selectedPhotos = emptySet()) }

    fun deleteSelectedPhotos() {
        val linkIds = _uiState.value.selectedPhotos.toList()
        if (linkIds.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isDeletingPhotos = true) }
            runCatching { driveRepo.deleteFiles(userId, linkIds) }
                .fold(
                    onSuccess = {
                        _uiState.update { state ->
                            state.copy(
                                isDeletingPhotos = false,
                                selectedPhotos   = emptySet(),
                                photos           = state.photos.filter { it.linkId !in linkIds },
                            )
                        }
                    },
                    onFailure = { e ->
                        Log.e("AlbumDetailVM", "deleteSelectedPhotos failed", e)
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                isDeletingPhotos = false,
                                error = friendly ?: context.getString(R.string.album_delete_photos_failed),
                            )
                        }
                    },
                )
        }
    }

    /**
     * Removes the currently selected photos from this album (Drive `remove-multiple`).
     * The photos themselves stay in Photos; only the album reference is dropped. Reuses
     * the [isDeletingPhotos] flag so the UI shows a single "working" state regardless of
     * whether the user picked Remove-from-album or full Delete.
     */
    fun removeSelectedPhotosFromAlbum() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        val linkIds = _uiState.value.selectedPhotos.toList()
        if (linkIds.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isDeletingPhotos = true) }
            runCatching { driveRepo.removePhotosFromAlbum(userId, albumLinkId, linkIds) }
                .fold(
                    onSuccess = { removed ->
                        val removedSet = removed.toSet()
                        _uiState.update { state ->
                            state.copy(
                                isDeletingPhotos = false,
                                selectedPhotos = emptySet(),
                                // Drop only the linkIds the server confirmed — leave any
                                // chunk-failed photos in the grid so the user sees they
                                // weren't actually removed.
                                photos = state.photos.filter { it.linkId !in removedSet },
                                error = if (removed.size != linkIds.size)
                                    context.getString(R.string.album_remove_partial, removed.size, linkIds.size)
                                else null,
                            )
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
        }
    }

    /**
     * Renames the current album. Empties [error] on success; surfaces a user-visible
     * message on failure so the rename dialog can stay open if needed.
     */
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

    /**
     * Sets the single selected photo as the album cover. No-op if more or fewer than one
     * photo is selected — the UI only exposes this action in that exact state.
     *
     * On success: bumps [AlbumDetailUiState.coverUpdatedTick] so the screen pops a snackbar,
     * and notifies [albumListEvents] so the AlbumsViewModel re-fetches and the gallery
     * album-card thumbnail picks up the new cover without waiting for pull-to-refresh.
     */
    fun setSelectedPhotoAsCover() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        val selected = _uiState.value.selectedPhotos
        if (selected.size != 1) return
        val coverLinkId = selected.first()
        runSetCover(albumLinkId, coverLinkId, clearSelection = true)
    }

    /**
     * One-shot "set this specific photo as the cover" — bypasses the multi-select flow.
     * Used by the per-cell long-press context menu in [AlbumDetailScreen] and (proxied
     * through a separate VM method) the viewer's overflow "Set as album cover" action.
     */
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
                        _uiState.update {
                            it.copy(
                                selectedPhotos = if (clearSelection) emptySet() else it.selectedPhotos,
                                error = null,
                                coverUpdatedTick = it.coverUpdatedTick + 1,
                            )
                        }
                        // Albums grid card needs to re-fetch so its thumbnail flips to the
                        // newly chosen cover the moment the user pops back to the list.
                        albumListEvents.notifyChanged()
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
                        // Set BOTH `shareLink` (one-shot clipboard trigger) and `publicShareUrl`
                        // (persistent state for the redesigned sheet). Existing callers that
                        // observe `shareLink` keep working; the new sheet reads `publicShareUrl`
                        // to decide whether the public-link toggle is on.
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

    /**
     * Toggles the public link ON — creates a public-link URL backed by the album share.
     * Distinct from [createShareLink] in that the redesigned sheet wants a persistent
     * URL displayed (and a separate "Copy" button), not a one-shot clipboard event.
     */
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

    /**
     * Toggles the public link OFF. When the share has accepted members or pending
     * invitations we now keep the share itself and just delete the public URL — so
     * invited Proton accounts retain access. Otherwise we drop the whole share so the
     * album reverts to "not shared" everywhere.
     */
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
                            // When members stayed we keep `shareId` populated so the
                            // member list keeps loading; only drop it when the share
                            // itself is gone.
                            shareId = if (keepShare) it.shareId else null,
                        )
                    }
                    // Refresh the gallery album list so the shared-badge stays in sync
                    // with the new state — present when members remain, gone otherwise.
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

    /**
     * Upgrades or downgrades an accepted member's permission bitmap on the album share.
     * `4` = viewer (read), `6` = editor (read + write). The local member list is updated
     * optimistically and the server call follows; on failure we revert + surface a
     * friendly error.
     */
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
                        // Revert to the prior member list so the row reflects reality.
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

    /**
     * Same permission swap for a PENDING invitation — lets the owner downgrade a
     * not-yet-accepted invite from the editor default to viewer (or back). Optimistic
     * local update with revert-on-failure, mirroring [changeMemberPermission].
     */
    fun changeInvitationPermission(invitationId: String, permissions: Int) {
        val shareId = _uiState.value.shareId ?: return
        // Optimistic placeholder rows carry a blank id until loadInvitations() replaces
        // them with server truth — there's no invitation to update on the backend yet.
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
     * Bulk-invite the given emails to the current album. Mirrors the Drive web "Share"
     * dialog flow: collect chips → tap Share → fire one [DrivePhotoRepository.inviteToAlbum]
     * per email and summarize the result. The repo signature doesn't yet carry a message
     * field — [message] is accepted for forward compatibility and is currently dropped at
     * the data-layer boundary. [permissions] also has no backend setter yet (the value is
     * baked into [AlbumSharingService.inviteToAlbum]) so it's a no-op today; we accept it
     * so the UI semantics are correct the moment the backend lands.
     *
     * After the batch completes, [inviteBatchResult] is set to a non-null summary the UI
     * can read once and clear via [clearInviteBatchResult]. We also refresh the invitation
     * list so freshly-added pending rows show up under "Who has access".
     */
    fun inviteUsers(emails: List<String>, message: String, permissions: Int) {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        if (emails.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // Optimistically drop a pending row in "Who has access" for every brand-new
            // invitee so the sheet reflects the action instantly instead of looking frozen
            // until loadInvitations() lands. These carry a blank invitationId (revoke /
            // permission-change are no-ops on them) and are replaced by server truth once
            // the refresh below returns.
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
                            // IllegalArgumentException carries our hand-crafted "not a
                            // Proton account / couldn't reach directory" strings that
                            // already include the email — sanitising them would replace
                            // the email with `<email>` and confuse the user. Trust those
                            // verbatim; sanitise only opaque server-side messages.
                            val raw = if (e is IllegalArgumentException) e.message.orEmpty()
                                else sanitizeErrorMessage(e.message)
                            failures.add(email to (friendly ?: raw))
                        },
                    )
            }
            _uiState.update {
                it.copy(
                    isInvitingBatch = false,
                    // When nothing landed there's no refetch below to repaint the list, so
                    // strip the optimistic placeholders here to avoid ghost pending rows.
                    // On a (partial) success loadInvitations() replaces the whole list with
                    // server truth, so the placeholders are left in place to avoid a flicker.
                    invitations = if (successes > 0) it.invitations
                        else it.invitations.filter { inv -> inv.invitationId.isNotBlank() },
                    inviteBatchResult = InviteBatchResult(
                        successCount = successes,
                        failures = failures.toList(),
                    ),
                )
            }
            // The new invitations should appear in the "Who has access" list — refresh so
            // the user sees them as pending without having to re-open the sheet.
            if (successes > 0) loadInvitations()
        }
    }

    fun clearInviteBatchResult() = _uiState.update { it.copy(inviteBatchResult = null) }

    fun clearShareLink() = _uiState.update { it.copy(shareLink = null) }

    fun downloadSelectedPhotos() {
        // Album-bound downloads land in Pictures/<AlbumName>/ so the device gallery shows
        // them under a folder matching the cloud album. Empty album name (shared-with-me
        // edge case where the name failed to decrypt) falls back to Pictures/ root.
        val folderName = eu.akoos.photos.util.ProtonPhotosStorage.sanitize(_uiState.value.albumName)
        val selectedIds = _uiState.value.selectedPhotos
        val photos = _uiState.value.photos.filter { it.linkId in selectedIds }
        if (photos.isEmpty()) return
        enqueueAlbumDownload(folderName, photos.map { it.linkId }, clearSelectionOnEnqueue = true)
    }

    fun downloadAllPhotos() {
        // See downloadSelectedPhotos above — same album-aware routing.
        val folderName = eu.akoos.photos.util.ProtonPhotosStorage.sanitize(_uiState.value.albumName)
        val linkIds = _uiState.value.photos.map { it.linkId }
        if (linkIds.isEmpty()) return
        enqueueAlbumDownload(folderName, linkIds, clearSelectionOnEnqueue = false)
    }

    /**
     * Shared-with-me equivalent of "download": copies every photo from the shared
     * album into a new owned album with the same name in the caller's own photos
     * library. The backend duplicates the encrypted blobs server-side, so this is
     * a metadata-only round-trip on the recipient client. Once it returns, the
     * caller's regular Photos sync brings the new copies down + the green-cloud
     * badge appears on each one naturally.
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

    /** Aborts an in-flight save-to-library copy. The repository tears the job
     *  down + emits a Cancelled progress state which we surface as a neutral
     *  snackbar. Safe to call when no copy is running. */
    fun cancelSaveToLibrary() {
        driveRepo.cancelSaveSharedAlbumToOwnLibrary()
    }

    /** Recipient-side "Leave album". Resolves the user's membership for the share
     *  and POSTs the delete. On success the album row is wiped from the local
     *  cache and `leaveAlbumDone = true` signals the screen to pop back. */
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
                // Refresh the shared-with-me grid so the album disappears the moment the
                // screen pops back, instead of lingering until the next manual reload.
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

    // Observe the singleton-backed save-to-library state so progress and outcome
    // survive VM destruction. The viewModelScope subscription dies when the VM
    // goes — the next AlbumDetailViewModel that opens the same album simply
    // re-subscribes and inherits whatever the singleton currently holds.
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

    /**
     * Hands the download off to [AlbumDownloadWorker]. The worker survives view-exit and
     * app-close, showing per-file progress + a Cancel action in a system notification —
     * see `AlbumDownloadWorker.doWork()` for the worker side.
     *
     * The VM only emits the [AlbumDownloadState.Enqueued] state so the UI can dismiss its
     * "Downloading" overlay; no in-app progress tracking is attempted, because the worker
     * may outlive the screen.
     */
    private fun enqueueAlbumDownload(
        folderName: String,
        photoLinkIds: List<String>,
        clearSelectionOnEnqueue: Boolean,
    ) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val albumLinkId = _uiState.value.albumLinkId
            AlbumDownloadWorker.enqueue(
                context = context,
                albumLinkId = albumLinkId,
                albumName = folderName,
                photoLinkIds = photoLinkIds,
                userIdString = userId.id,
            )
            _uiState.update {
                it.copy(
                    downloadState = AlbumDownloadState.Enqueued,
                    selectedPhotos = if (clearSelectionOnEnqueue) emptySet() else it.selectedPhotos,
                )
            }
        }
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
                        // Tell the gallery's AlbumsViewModel to re-fetch — otherwise its cached
                        // album list still carries sharingShareId for this album and the badge
                        // sticks until the next cold-start refresh.
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
