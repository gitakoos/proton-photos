package me.proton.photos.presentation.albums

import android.content.Context
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
import me.proton.photos.domain.entity.CloudPhoto
import me.proton.photos.domain.entity.ShareInvitation
import me.proton.photos.domain.entity.ShareMember
import me.proton.photos.domain.entity.SyncStatus
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.SyncStateRepository
import me.proton.photos.worker.AlbumDownloadWorker
import javax.inject.Inject
// Note: DownloadPhotosUseCase is no longer injected — album downloads are now driven by
// AlbumDownloadWorker, which calls the use case from its own worker scope so the work
// survives view-exit and app-close.

sealed class AlbumDownloadState {
    data object Idle : AlbumDownloadState()
    data class Working(val done: Int, val total: Int) : AlbumDownloadState()
    /**
     * The download has been handed off to [AlbumDownloadWorker]; the actual per-file progress
     * is now reflected by the system notification, not the in-app sheet. The UI uses this
     * state to dismiss the "Downloading" overlay and show a one-shot confirmation snackbar.
     */
    data object Enqueued : AlbumDownloadState()
    data class Done(val downloaded: Int, val skipped: Int, val failed: Int) : AlbumDownloadState()
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
) {
    val isSelectionMode: Boolean get() = selectedPhotos.isNotEmpty()
    val selectedCount: Int get() = selectedPhotos.size
    val isSharedWithMe: Boolean get() = sharedByEmail != null
}

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val syncStateRepo: SyncStateRepository,
    private val getUser: GetUser,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
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
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // loadAlbumPhotos checks DB cache first (fast path), then falls back to API.
            // It also upserts all fetched entities into the DB so observePhotosByLinkIds
            // can serve them reactively for future background-sync thumbnail updates.
            runCatching { driveRepo.loadAlbumPhotos(userId, albumLinkId, volumeId) }
                .fold(
                    onSuccess = { photos ->
                        _uiState.update { it.copy(isLoading = false, photos = photos) }
                        // Keep observing the DB so background sync thumbnail updates flow
                        // into the album detail without requiring a manual refresh.
                        //
                        // We MERGE the DB snapshot into the existing album list (by linkId)
                        // instead of replacing it. Reason: observePhotosByLinkIds only returns
                        // rows that exist in photo_listing — an album photo that hasn't been
                        // touched by the main stream refresh yet won't be there, and a naive
                        // overwrite would blank the entire album grid.
                        val linkIds = photos.map { it.linkId }
                        if (linkIds.isNotEmpty()) {
                            driveRepo.observePhotosByLinkIds(linkIds)
                                .drop(1) // first emission is identical to what we just set
                                .collect { dbRows ->
                                    val byId = dbRows.associateBy { it.linkId }
                                    _uiState.update { state ->
                                        state.copy(photos = state.photos.map { p ->
                                            val updated = byId[p.linkId] ?: return@map p
                                            // Adopt any field that newly became available
                                            // (thumbnailUrl is the common case); never blank
                                            // values that the album build already produced.
                                            p.copy(
                                                thumbnailUrl = updated.thumbnailUrl ?: p.thumbnailUrl,
                                                displayName  = updated.displayName.ifBlank { p.displayName },
                                            )
                                        })
                                    }
                                }
                        }
                    },
                    onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } },
                )
        }
        if (shareId != null && sharedByEmail == null) {
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
            val invitations = runCatching { driveRepo.loadShareInvitations(userId, shareId) }.getOrElse { emptyList() }
            val members = runCatching { driveRepo.loadShareMembers(userId, shareId) }.getOrElse { emptyList() }
            _uiState.update { it.copy(isLoadingInvitations = false, invitations = invitations, members = members) }
        }
    }

    fun revokeInvitation(invitationId: String) {
        val shareId = _uiState.value.shareId ?: return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.revokeShareInvitation(userId, shareId, invitationId) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(invitations = it.invitations.filter { inv -> inv.invitationId != invitationId }) } },
                    onFailure = { e -> _uiState.update { it.copy(error = "Revoke failed: ${e.message}") } },
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
                    onFailure = { e -> _uiState.update { it.copy(error = "Remove failed: ${e.message}") } },
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
                        _uiState.update { it.copy(isDeletingPhotos = false, error = "Delete failed: ${e.message}") }
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
                                    "Removed ${removed.size}/${linkIds.size} — some chunks failed"
                                else null,
                            )
                        }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isDeletingPhotos = false, error = "Remove failed: ${e.message}") }
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
                    onFailure = { e -> _uiState.update { it.copy(error = "Rename failed: ${e.message}") } },
                )
        }
    }

    /**
     * Sets the single selected photo as the album cover. No-op if more or fewer than one
     * photo is selected — the UI only exposes this action in that exact state.
     */
    fun setSelectedPhotoAsCover() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        val selected = _uiState.value.selectedPhotos
        if (selected.size != 1) return
        val coverLinkId = selected.first()
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.setAlbumCover(userId, albumLinkId, coverLinkId) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(selectedPhotos = emptySet(), error = null) } },
                    onFailure = { e -> _uiState.update { it.copy(error = "Set cover failed: ${e.message}") } },
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
                    onFailure = { e -> _uiState.update { it.copy(isSharing = false, error = "Share failed: ${e.message}") } },
                )
        }
    }

    /**
     * Toggles the public link ON — creates a public-link URL backed by the album share.
     * Distinct from [createShareLink] in that the redesigned sheet wants a persistent
     * URL displayed (and a separate "Copy" button), not a one-shot clipboard event.
     */
    fun createPublicLink() {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isTogglingPublicLink = true) }
            runCatching { driveRepo.createAlbumShareLink(userId, albumLinkId) }
                .fold(
                    onSuccess = { url ->
                        _uiState.update { it.copy(isTogglingPublicLink = false, publicShareUrl = url) }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isTogglingPublicLink = false, error = "Share failed: ${e.message}") }
                    },
                )
        }
    }

    /**
     * Toggles the public link OFF. There is no dedicated `deleteShareUrl` endpoint plumbed
     * through the repository, so we fall back to [deleteShare] — BUT only when there are
     * no accepted members or pending invitations (otherwise [deleteShare] would also wipe
     * member access, which is not the user's intent when they only meant to disable the
     * public URL). If members exist, we surface an error so the user knows the public
     * link can't be selectively revoked yet.
     *
     * TODO: implement `revokeShareUrlOnly` in [DrivePhotoRepository] backed by
     *  `DELETE drive/shares/{shareId}/urls/{shareUrlId}` so we can disable the public link
     *  without nuking member shares.
     */
    fun disablePublicLink() {
        val shareId = _uiState.value.shareId ?: return
        val hasMembers = _uiState.value.members.isNotEmpty() || _uiState.value.invitations.isNotEmpty()
        if (hasMembers) {
            _uiState.update {
                it.copy(error = "Remove members first to disable the public link separately.")
            }
            return
        }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isTogglingPublicLink = true) }
            runCatching { driveRepo.deleteShare(userId, shareId) }
                .fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(
                                isTogglingPublicLink = false,
                                publicShareUrl = null,
                                shareId = null,
                            )
                        }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isTogglingPublicLink = false, error = "Failed: ${e.message}") }
                    },
                )
        }
    }

    /**
     * Permission upgrade/downgrade stub.
     *
     * The Drive backend supports `POST drive/v2/shares/{shareId}/members/{memberId}` with a
     * `Permissions` field for editor/viewer toggles, but this app's [DrivePhotoRepository]
     * does NOT yet expose that endpoint. Until it does, we surface a Toast-like error so the
     * UI can react without claiming the change succeeded.
     *
     * TODO: implement `updateMemberPermissions(userId, shareId, memberId, permissions)` in
     *  [DrivePhotoRepository] + [AlbumSharingService] and replace this stub.
     */
    fun changeMemberPermission(memberId: String, permissions: Int) {
        _uiState.update { it.copy(error = "Permission changes coming soon") }
    }

    fun inviteUser(email: String) {
        val albumLinkId = _uiState.value.albumLinkId.ifBlank { return }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.inviteToAlbum(userId, albumLinkId, email) }
                .fold(
                    onSuccess = { _uiState.update { it.copy(error = null) } },
                    onFailure = { e -> _uiState.update { it.copy(error = "Invite failed: ${e.message}") } },
                )
        }
    }

    fun clearShareLink() = _uiState.update { it.copy(shareLink = null) }

    fun downloadSelectedPhotos() {
        // Album-bound downloads land in Pictures/<AlbumName>/ so the device gallery shows
        // them under a folder matching the cloud album. Empty album name (shared-with-me
        // edge case where the name failed to decrypt) falls back to Pictures/ root.
        val folderName = me.proton.photos.util.ProtonPhotosStorage.sanitize(_uiState.value.albumName)
        val selectedIds = _uiState.value.selectedPhotos
        val photos = _uiState.value.photos.filter { it.linkId in selectedIds }
        if (photos.isEmpty()) return
        enqueueAlbumDownload(folderName, photos.map { it.linkId }, clearSelectionOnEnqueue = true)
    }

    fun downloadAllPhotos() {
        // See downloadSelectedPhotos above — same album-aware routing.
        val folderName = me.proton.photos.util.ProtonPhotosStorage.sanitize(_uiState.value.albumName)
        val linkIds = _uiState.value.photos.map { it.linkId }
        if (linkIds.isEmpty()) return
        enqueueAlbumDownload(folderName, linkIds, clearSelectionOnEnqueue = false)
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
                    },
                    onFailure = { e -> _uiState.update { it.copy(isSharing = false, error = "Failed to stop sharing: ${e.message}") } },
                )
        }
    }
}
