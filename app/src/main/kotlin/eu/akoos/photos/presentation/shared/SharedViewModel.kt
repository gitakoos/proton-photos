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

package eu.akoos.photos.presentation.shared

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.SharedPhoto
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.presentation.gallery.SharedFilter
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

data class SharedUiState(
    val isLoading: Boolean = true,
    /** All albums from the user's volume (we filter by isShared for "shared by me"). */
    val allAlbums: List<Album> = emptyList(),
    val sharedWithMeAlbums: List<Album> = emptyList(),
    /** Individual library photos the current user has shared via a public link. */
    val sharedByMePhotos: List<SharedPhoto> = emptyList(),
    val pendingInvitations: List<PendingInvitation> = emptyList(),
    val filter: SharedFilter = SharedFilter.SharedWithMe,
    /** linkIds of shared-by-me photos the user has multi-selected for a bulk action. */
    val selectedPhotoIds: Set<String> = emptySet(),
    /** True while a bulk stop-sharing pass is running, to gate the action button. */
    val isRevoking: Boolean = false,
    val error: String? = null,
) {
    /** Long-press on a shared-by-me photo turns the grid into a selection grid. */
    val isSelectionMode: Boolean get() = selectedPhotoIds.isNotEmpty()
    val selectedCount: Int get() = selectedPhotoIds.size

    /** Albums the current user has shared with others. */
    val sharedByMe: List<Album> get() = allAlbums.filter { it.isShared }

    /** Albums other users have shared with the current user. */
    val sharedWithMe: List<Album> get() = sharedWithMeAlbums

    /** Unique emails of people who shared albums with the current user (for filter picker). */
    val availableEmails: List<String> get() = sharedWithMeAlbums.mapNotNull { it.sharedByEmail }.distinct()

    val displayedAlbums: List<Album> get() = when (filter) {
        SharedFilter.SharedByMe -> sharedByMe
        SharedFilter.SharedWithMe -> sharedWithMe
    }
}

@HiltViewModel
class SharedViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    init {
        loadSharedAlbums()
        // Album-detail actions emit on this bus when a share flips — e.g. leaving a
        // shared-with-me album. Re-pull so the grid drops the album immediately instead
        // of waiting for the next screen resume.
        viewModelScope.launch {
            albumListEvents.changes.collect { loadSharedAlbums() }
        }
    }

    fun refresh() = loadSharedAlbums()

    fun setFilter(filter: SharedFilter) {
        // Switching tabs leaves selection mode — the shared-by-me photos that drive it
        // only exist on the "Shared by me" tab.
        _uiState.update {
            if (it.filter == filter) it
            else it.copy(filter = filter, selectedPhotoIds = emptySet())
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    // ── Bulk selection of shared-by-me photos ───────────────────────────────────

    /** Long-press / tap toggles a shared-by-me photo in the selection set. */
    fun toggleSelection(photoId: String) {
        _uiState.update { state ->
            val next = state.selectedPhotoIds.toMutableSet()
            if (!next.add(photoId)) next.remove(photoId)
            state.copy(selectedPhotoIds = next)
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedPhotoIds = emptySet()) }

    /**
     * Stop sharing every selected photo: revoke each public link over a snapshot of the
     * selection, drop the successfully-revoked rows from the section, then leave selection
     * mode. Touches only share metadata — never photo content. Mirrors [removeLink]'s repo
     * call without its single-photo [activeLinkPhotoId] guard.
     */
    fun revokeSelected() {
        val targets = _uiState.value.selectedPhotoIds.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isRevoking = true) }
            val revoked = mutableSetOf<String>()
            var lastError: Throwable? = null
            for (linkId in targets) {
                runCatching { driveRepo.revokePhotoShareLink(userId, linkId) }
                    .onSuccess { revoked += linkId }
                    .onFailure { lastError = it }
            }
            _uiState.update { state ->
                val friendly = lastError?.let { friendlyNetworkError(it, networkObserver.isOnline.value, context) }
                state.copy(
                    sharedByMePhotos = state.sharedByMePhotos.filter { it.linkId !in revoked },
                    selectedPhotoIds = state.selectedPhotoIds - revoked,
                    isRevoking = false,
                    error = when {
                        lastError == null -> state.error
                        friendly != null -> friendly
                        else -> context.getString(R.string.share_stop_failed)
                    },
                )
            }
        }
    }

    fun declineInvitation(invitationId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.declineInvitation(userId, invitationId) }
                .onSuccess { _uiState.update { it.copy(pendingInvitations = it.pendingInvitations.filter { inv -> inv.invitationId != invitationId }) } }
                .onFailure { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update {
                        it.copy(error = friendly ?: context.getString(R.string.shared_decline_failed, sanitizeErrorMessage(e.message)))
                    }
                }
        }
    }

    fun acceptInvitation(invitationId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.acceptInvitation(userId, invitationId) }
                .fold(
                    onSuccess = {
                        // Remove the card from "Pending" immediately so the user gets feedback.
                        _uiState.update { it.copy(
                            pendingInvitations = it.pendingInvitations.filter { inv -> inv.invitationId != invitationId },
                        ) }
                        // The Drive backend materialises the new share member ASYNCHRONOUSLY
                        // after the accept POST. An immediate /sharedwithme query usually
                        // returns the old list. We do a short retry loop (~6 s total) so the
                        // shared album shows up as soon as the server is consistent — without
                        // requiring the user to pull-to-refresh manually.
                        var attempt = 0
                        val previousIds = _uiState.value.sharedWithMeAlbums.map { it.linkId }.toSet()
                        while (attempt < 4) {
                            val delayMs = listOf(800L, 1200L, 2000L, 2500L)[attempt]
                            kotlinx.coroutines.delay(delayMs)
                            val fresh = runCatching { driveRepo.loadSharedWithMeAlbums(userId) }
                                .getOrElse { emptyList() }
                            val gainedNew = fresh.any { it.linkId !in previousIds }
                            if (gainedNew || attempt == 3) {
                                _uiState.update { it.copy(sharedWithMeAlbums = fresh) }
                                if (gainedNew) break
                            }
                            attempt++
                        }
                    },
                    onFailure = { e ->
                        // Surface the real error (API path, crypto failure, network) so we can
                        // actually diagnose Method Not Allowed / 4xx responses from a snackbar.
                        // Friendly network message takes precedence over the raw API path so a
                        // dropped connection doesn't render as a stack-trace fragment.
                        val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                        _uiState.update {
                            it.copy(
                                error = friendly
                                    ?: context.getString(R.string.shared_accept_failed, sanitizeErrorMessage(e.message ?: e::class.simpleName)),
                            )
                        }
                    },
                )
        }
    }

    private fun loadSharedAlbums() {
        if (!networkObserver.isOnline.value) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null) }
            // supervisorScope is required because plain async {} children that fail
            // propagate the exception to the parent scope BEFORE await() resumes — the
            // runCatching never gets a chance to catch the rethrown ApiException.
            // Symptoms: toggling wifi off mid-load caused a FATAL UnknownHostException
            // to escape this block to the top of the launch.
            runCatching {
                kotlinx.coroutines.supervisorScope {
                    val sharedByMeDeferred = async { driveRepo.loadAlbums(userId) }
                    val sharedWithMeDeferred = async { driveRepo.loadSharedWithMeAlbums(userId) }
                    val pendingDeferred = async { runCatching { driveRepo.loadPendingInvitations(userId) }.getOrElse { emptyList() } }
                    // Shared-by-me photos tolerate their own failure: a hiccup on the shares
                    // feed must not blank the albums section, so it resolves to an empty list.
                    val sharedPhotosDeferred = async { runCatching { driveRepo.loadSharedByMePhotos(userId) }.getOrElse { emptyList() } }
                    SharedLoad(
                        albums = sharedByMeDeferred.await(),
                        sharedWithMe = sharedWithMeDeferred.await(),
                        pending = pendingDeferred.await(),
                        sharedPhotos = sharedPhotosDeferred.await(),
                    )
                }
            }.fold(
                onSuccess = { load ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        allAlbums = load.albums,
                        sharedWithMeAlbums = load.sharedWithMe,
                        pendingInvitations = load.pending,
                        sharedByMePhotos = load.sharedPhotos,
                    ) }
                    observeSharedPhotoThumbnails(load.sharedPhotos.map { it.linkId })
                },
                onFailure = { e ->
                    // Network drop → keep error null so the offline banner + the avatar
                    // dot tell the user why nothing showed up. Non-network exceptions
                    // (auth, crypto) still surface a sanitized message in the error sheet.
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = if (friendly != null) null
                                else context.getString(R.string.shared_load_failed, sanitizeErrorMessage(e.message)),
                        )
                    }
                },
            )
        }
    }

    /** Bundle for the parallel shared-tab load so the success branch reads one object. */
    private data class SharedLoad(
        val albums: List<Album>,
        val sharedWithMe: List<Album>,
        val pending: List<PendingInvitation>,
        val sharedPhotos: List<SharedPhoto>,
    )

    // ── Shared-by-me photo thumbnails ───────────────────────────────────────────
    //
    // The shares feed hands back the snapshot thumbnailUrl from the listing DB. Cloud-only
    // shared photos may not have a decrypted thumbnail yet; the grid cells request a decrypt
    // on view, the DAO row updates, and this observation re-emits so the tile fills in without
    // a manual refresh — the same lazy-thumbnail contract the gallery grid uses.

    private var thumbObserveJob: kotlinx.coroutines.Job? = null

    private fun observeSharedPhotoThumbnails(linkIds: List<String>) {
        thumbObserveJob?.cancel()
        if (linkIds.isEmpty()) return
        thumbObserveJob = viewModelScope.launch {
            driveRepo.observeSharedByMePhotos(linkIds).collect { photos ->
                // Only the listing layer knows membership; if the feed has dropped a photo
                // (un-shared elsewhere) the next refresh reconciles it. Here we just merge
                // freshly-decrypted thumbnails onto the rows we already show.
                if (photos.isEmpty()) return@collect
                val byId = photos.associateBy { it.linkId }
                _uiState.update { state ->
                    state.copy(sharedByMePhotos = state.sharedByMePhotos.map { existing ->
                        byId[existing.linkId]?.let { existing.copy(thumbnailUrl = it.thumbnailUrl) } ?: existing
                    })
                }
            }
        }
    }

    /** Cell entered the viewport — queue a lazy thumbnail decrypt for a cloud-only shared photo. */
    fun requestThumbnail(linkId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            driveRepo.requestThumbnailDecrypt(userId, linkId)
        }
    }

    /** Cell left the viewport — cancel the in-flight decrypt so a fast scroll doesn't waste work. */
    fun cancelThumbnail(linkId: String) {
        driveRepo.cancelThumbnailDecrypt(linkId)
    }

    // ── Per-shared-photo public link management ─────────────────────────────────
    //
    // Tapping a shared photo opens the same [ManagePublicLinkSheet] the viewer/gallery use, so
    // the link can be copied, password-protected, or removed straight from the Shared tab.
    // Mirrors GalleryViewModel's link machine: identical [PublicLinkState], the same repo calls,
    // and a captured [activeLinkPhotoId] guard so a second tap mid-request can't apply a result
    // to the wrong photo. Only share metadata is ever touched — never photo content.

    sealed class PublicLinkState {
        data object None : PublicLinkState()
        data object Loading : PublicLinkState()
        data class Active(val url: String, val hasPassword: Boolean = false) : PublicLinkState()
        data class Error(val message: String) : PublicLinkState()
    }

    private val _publicLinkState = MutableStateFlow<PublicLinkState>(PublicLinkState.None)
    val publicLinkState: StateFlow<PublicLinkState> = _publicLinkState.asStateFlow()

    /** The shared photo the manage-link sheet currently acts on; guards async results. */
    private var activeLinkPhotoId: String? = null

    /** Open the manage-link sheet for [linkId]: seed it with the existing public link. */
    fun openLinkManager(linkId: String) {
        activeLinkPhotoId = linkId
        _publicLinkState.value = PublicLinkState.Loading
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (activeLinkPhotoId == linkId) _publicLinkState.value = PublicLinkState.None
                return@launch
            }
            runCatching { driveRepo.getPhotoShareLink(userId, linkId) }
                .onSuccess { url ->
                    if (activeLinkPhotoId != linkId) return@onSuccess
                    _publicLinkState.value = if (url != null) activeLinkState(url) else PublicLinkState.None
                }
                .onFailure {
                    if (activeLinkPhotoId == linkId) _publicLinkState.value = PublicLinkState.None
                }
        }
    }

    fun closeLinkManager() {
        activeLinkPhotoId = null
        _publicLinkState.value = PublicLinkState.None
    }

    /** Re-mint a link if one was removed and the user taps Create again from the sheet. */
    fun createLink() {
        val linkId = activeLinkPhotoId ?: return
        _publicLinkState.value = PublicLinkState.Loading
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _publicLinkState.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { driveRepo.createPhotoShareLink(userId, linkId) }
                .onSuccess { url ->
                    if (activeLinkPhotoId != linkId) return@onSuccess
                    _publicLinkState.value = activeLinkState(url)
                }
                .onFailure { e ->
                    if (activeLinkPhotoId != linkId) return@onFailure
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _publicLinkState.value = PublicLinkState.Error(friendly ?: context.getString(R.string.share_link_failed))
                }
        }
    }

    fun removeLink() {
        val linkId = activeLinkPhotoId ?: return
        _publicLinkState.value = PublicLinkState.Loading
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _publicLinkState.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { driveRepo.revokePhotoShareLink(userId, linkId) }
                .onSuccess {
                    if (activeLinkPhotoId != linkId) return@onSuccess
                    // The photo is no longer shared — drop it from the section and close the sheet.
                    _uiState.update { it.copy(sharedByMePhotos = it.sharedByMePhotos.filter { p -> p.linkId != linkId }) }
                    _publicLinkState.value = PublicLinkState.None
                    activeLinkPhotoId = null
                }
                .onFailure { e ->
                    if (activeLinkPhotoId != linkId) return@onFailure
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _publicLinkState.value = PublicLinkState.Error(friendly ?: context.getString(R.string.share_link_failed))
                }
        }
    }

    fun setLinkPassword(password: String?) {
        val linkId = activeLinkPhotoId ?: return
        _publicLinkState.value = PublicLinkState.Loading
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _publicLinkState.value = PublicLinkState.Error(context.getString(R.string.viewer_not_signed_in))
                return@launch
            }
            runCatching { driveRepo.setPhotoLinkPassword(userId, linkId, password) }
                .onSuccess { url ->
                    if (activeLinkPhotoId != linkId) return@onSuccess
                    _publicLinkState.value = activeLinkState(url)
                }
                .onFailure { e ->
                    if (activeLinkPhotoId != linkId) return@onFailure
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _publicLinkState.value = PublicLinkState.Error(friendly ?: context.getString(R.string.share_password_failed))
                }
        }
    }

    /** The live public-link URL when one is active, for the sheet's copy-to-clipboard. */
    fun currentPublicLinkUrl(): String? = (_publicLinkState.value as? PublicLinkState.Active)?.url

    /** A random anyone-with-the-link URL carries its password in the `#fragment`; a custom-
     *  password URL is bare, so the absence of a fragment means a password is required. */
    private fun activeLinkState(url: String): PublicLinkState.Active =
        PublicLinkState.Active(url = url, hasPassword = !url.contains('#'))
}
