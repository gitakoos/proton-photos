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

package eu.akoos.photos.presentation.shared

import android.content.Context
import android.util.Log
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
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
import eu.akoos.photos.domain.entity.SharedPhoto
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.presentation.gallery.SharedFilter
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

private const val TAG = "SharedVM"

/**
 * How long the shared-album prefetch waits after the tab's refresh lands, matching the delay the
 * owned albums' own deferred prefetch takes for the same reason: foreground decrypts and the network
 * semaphore go to what the user is looking at first.
 */
private const val SHARED_PREFETCH_DELAY_MS = 5_000L

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

/**
 * Whether the Shared tab's cache read left anything on screen.
 *
 * False is what still owes a skeleton, so the skeleton appears only on a device that has never
 * listed a share — not on every open. Both sections count, because one refresh feeds both filters
 * and the grid decides per filter whether it has rows to draw.
 *
 * An owned album counts only when it is shared: the tab lists what this user shared out, not
 * everything they own.
 *
 * Pure lists → no DI, no DB, no network.
 */
internal fun sharedTabHasCachedContent(
    cachedOwnAlbums: List<Album>,
    cachedSharedWithMeAlbums: List<Album>,
): Boolean = cachedSharedWithMeAlbums.isNotEmpty() || cachedOwnAlbums.any { it.isShared }

/**
 * The message a failed Shared-tab refresh is allowed to surface.
 *
 * Null on the two failures the user is already told about another way: a list painted from cache is
 * still there to read, and a network drop is what the offline banner and the avatar dot explain.
 * Anything else — an auth or crypto fault with nothing on screen — has no other reporter, so it
 * reaches the error sheet.
 *
 * Pure values → no DI, no context.
 */
internal fun sharedRefreshError(
    hasPaintedContent: Boolean,
    isNetworkFailure: Boolean,
    message: String,
): String? = when {
    hasPaintedContent -> null
    isNetworkFailure -> null
    else -> message
}

/**
 * The shared list with the covers a prefetch pass has since resolved filled in.
 *
 * Only the blank tiles change. [resolved] is a cache read taken after the fetch, so letting it write
 * over a cover already on screen would swap one working image for another and repaint for nothing.
 * Rows are neither added nor dropped either, so a grid the user is looking at cannot shift under them
 * on the strength of a background pass.
 *
 * Pure values → no DI, no DB, no network.
 */
internal fun mergeResolvedCovers(current: List<Album>, resolved: Map<String, String>): List<Album> {
    if (resolved.isEmpty()) return current
    return current.map { album ->
        if (!album.coverThumbnailUrl.isNullOrBlank()) album
        else resolved[album.linkId]?.let { album.copy(coverThumbnailUrl = it) } ?: album
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

    /**
     * Leaves an album someone shared with this user, straight from the Shared grid.
     *
     * The same call the album screen makes, offered here so a guest can drop an album without
     * opening it first. The list reloads afterwards rather than removing the row optimistically,
     * so a server-side refusal never leaves a phantom gap in the grid.
     */
    fun leaveSharedAlbum(album: Album) {
        val shareId = album.sharingShareId ?: run {
            _uiState.update { it.copy(error = context.getString(R.string.album_leave_missing_details)) }
            return
        }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.leaveSharedAlbum(userId, shareId, album.linkId) }
                .onSuccess { refresh() }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "leaveSharedAlbum failed: ${e.message}")
                    _uiState.update { it.copy(error = context.getString(R.string.album_leave_failed)) }
                }
        }
    }

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
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            // Phase 1: instant cache read so the tab paints on open, airplane-mode starts included.
            // An empty read leaves whatever is on screen alone, so a refresh never blanks the grid.
            val cachedOwn = runCatching { driveRepo.loadAlbumsCached() }.getOrNull().orEmpty()
            val cachedSharedWithMe = runCatching { driveRepo.loadSharedWithMeAlbumsCached() }.getOrNull().orEmpty()
            val painted = sharedTabHasCachedContent(cachedOwn, cachedSharedWithMe)
            _uiState.update { state ->
                state.copy(
                    isLoading = !painted,
                    allAlbums = cachedOwn.ifEmpty { state.allAlbums },
                    sharedWithMeAlbums = cachedSharedWithMe.ifEmpty { state.sharedWithMeAlbums },
                )
            }

            // Phase 2: network refresh, online only — else the painted cache stands.
            if (!networkObserver.isOnline.value) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            refreshOwnAlbums(userId)
            // supervisorScope is required because plain async {} children that fail
            // propagate the exception to the parent scope BEFORE await() resumes — the
            // runCatching never gets a chance to catch the rethrown ApiException.
            // Symptoms: toggling wifi off mid-load caused a FATAL UnknownHostException
            // to escape this block to the top of the launch.
            runCatching {
                kotlinx.coroutines.supervisorScope {
                    val sharedWithMeDeferred = async { driveRepo.loadSharedWithMeAlbums(userId) }
                    val pendingDeferred = async { runCatching { driveRepo.loadPendingInvitations(userId) }.getOrElse { emptyList() } }
                    // Shared-by-me photos tolerate their own failure: a hiccup on the shares
                    // feed must not blank the albums section, so it resolves to an empty list.
                    val sharedPhotosDeferred = async { runCatching { driveRepo.loadSharedByMePhotos(userId) }.getOrElse { emptyList() } }
                    SharedLoad(
                        sharedWithMe = sharedWithMeDeferred.await(),
                        pending = pendingDeferred.await(),
                        sharedPhotos = sharedPhotosDeferred.await(),
                    )
                }
            }.fold(
                onSuccess = { load ->
                    _uiState.update { it.copy(
                        isLoading = false,
                        sharedWithMeAlbums = load.sharedWithMe,
                        pendingInvitations = load.pending,
                        sharedByMePhotos = load.sharedPhotos,
                    ) }
                    observeSharedPhotoThumbnails(load.sharedPhotos.map { it.linkId })
                    prefetchSharedAlbums(userId, load.sharedWithMe)
                },
                onFailure = { e ->
                    // Every list already on screen stays: a refresh that failed knows nothing that
                    // would justify clearing what the cache painted.
                    val isNetworkFailure = friendlyNetworkError(e, networkObserver.isOnline.value, context) != null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = sharedRefreshError(
                                hasPaintedContent = painted,
                                isNetworkFailure = isNetworkFailure,
                                message = context.getString(R.string.shared_load_failed, sanitizeErrorMessage(e.message)),
                            ),
                        )
                    }
                },
            )
        }
    }

    private var ownAlbumsRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * The user's own albums, refreshed alongside the tab rather than ahead of it.
     *
     * This tab reads that list for one thing only: which of the user's albums are shared BY them.
     * The refresh behind it lists every album and prefetches a cover for each, which is a poor thing
     * to hold a paint on, so the first paint comes from [DrivePhotoRepository.loadAlbumsCached] and
     * this converges behind it. A failure leaves the cached list in place.
     *
     * One pass at a time: the tab reloads on every share-state change, and stacking full album
     * walks on that would cost far more than the answer is worth.
     */
    private fun refreshOwnAlbums(userId: UserId) {
        if (ownAlbumsRefreshJob?.isActive == true) return
        ownAlbumsRefreshJob = viewModelScope.launch {
            runCatching { driveRepo.loadAlbums(userId) }
                .onSuccess { albums -> _uiState.update { it.copy(allAlbums = albums) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "own album refresh failed: ${e.message}")
                }
        }
    }

    private var sharedPrefetchJob: kotlinx.coroutines.Job? = null

    /**
     * What a shared album needs to be worth looking at before it is opened: the cover its tile draws,
     * and the membership rows its grid enumerates from.
     *
     * Off the critical path on purpose: the walk this follows was taken off it so the tab paints from
     * cache, and the fetches are on another user's volume and through the same crypto gate the visible
     * grid decrypts on. The delay hands the refresh the user is waiting for, and whatever is on
     * screen, first claim on both. A cache-only re-read after the covers is what puts them on a tab
     * the user never left; it adds and removes no rows.
     *
     * The covers go first because they are the half the user can see from here. The membership pass
     * behind them is what an album-open reads instead of waiting on a round trip, and both are
     * bounded per pass by the repository, since every request is charged to the album's owner.
     *
     * One pass at a time, since the tab reloads on resume and on every share-state change.
     */
    private fun prefetchSharedAlbums(userId: UserId, albums: List<Album>) {
        if (sharedPrefetchJob?.isActive == true) return
        sharedPrefetchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SHARED_PREFETCH_DELAY_MS)
            runCatching { driveRepo.prefetchSharedAlbumCovers(userId, albums) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "shared cover prefetch failed: ${e.message}")
                }
            val resolved = runCatching { driveRepo.loadSharedWithMeAlbumsCached() }
                .getOrNull().orEmpty()
                .mapNotNull { album -> album.coverThumbnailUrl?.takeIf { it.isNotBlank() }?.let { album.linkId to it } }
                .toMap()
            _uiState.update { it.copy(sharedWithMeAlbums = mergeResolvedCovers(it.sharedWithMeAlbums, resolved)) }
            runCatching { driveRepo.prefetchSharedAlbumsMembership(userId, albums) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "shared membership prefetch failed: ${e.message}")
                }
        }
    }

    /** Bundle for the parallel shared-tab load so the success branch reads one object. */
    private data class SharedLoad(
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
