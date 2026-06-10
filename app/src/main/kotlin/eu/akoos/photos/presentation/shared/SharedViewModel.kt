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
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.PendingInvitation
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
    val pendingInvitations: List<PendingInvitation> = emptyList(),
    val filter: SharedFilter = SharedFilter.SharedWithMe,
    val error: String? = null,
) {
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
        _uiState.update { it.copy(filter = filter) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun declineInvitation(invitationId: String) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            runCatching { driveRepo.declineInvitation(userId, invitationId) }
                .onSuccess { _uiState.update { it.copy(pendingInvitations = it.pendingInvitations.filter { inv -> inv.invitationId != invitationId }) } }
                .onFailure { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update {
                        it.copy(error = friendly ?: "Failed to decline: ${sanitizeErrorMessage(e.message)}")
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
                                    ?: "Accept failed: ${sanitizeErrorMessage(e.message ?: e::class.simpleName)}",
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
                    Triple(sharedByMeDeferred.await(), sharedWithMeDeferred.await(), pendingDeferred.await())
                }
            }.fold(
                onSuccess = { (albums, sharedWithMe, pending) ->
                    _uiState.update { it.copy(isLoading = false, allAlbums = albums, sharedWithMeAlbums = sharedWithMe, pendingInvitations = pending) }
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
                                else "Failed to load shared albums: ${sanitizeErrorMessage(e.message)}",
                        )
                    }
                },
            )
        }
    }
}
