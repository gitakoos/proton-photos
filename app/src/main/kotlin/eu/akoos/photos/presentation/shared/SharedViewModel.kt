package eu.akoos.photos.presentation.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    init {
        loadSharedAlbums()
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
                .onFailure { e -> _uiState.update { it.copy(error = "Failed to decline: ${e.message}") } }
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
                        _uiState.update { it.copy(error = "Accept failed: ${e.message ?: e::class.simpleName}") }
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
            runCatching {
                val sharedByMeDeferred = async { driveRepo.loadAlbums(userId) }
                val sharedWithMeDeferred = async { driveRepo.loadSharedWithMeAlbums(userId) }
                val pendingDeferred = async { runCatching { driveRepo.loadPendingInvitations(userId) }.getOrElse { emptyList() } }
                Triple(sharedByMeDeferred.await(), sharedWithMeDeferred.await(), pendingDeferred.await())
            }.fold(
                onSuccess = { (albums, sharedWithMe, pending) ->
                    _uiState.update { it.copy(isLoading = false, allAlbums = albums, sharedWithMeAlbums = sharedWithMe, pendingInvitations = pending) }
                },
                onFailure = { e ->
                    val networkErr = e.javaClass.name.contains("UnknownHost") ||
                        e.javaClass.name.contains("SocketTimeout") ||
                        e.javaClass.name.contains("SocketException")
                    _uiState.update {
                        it.copy(isLoading = false,
                            error = if (networkErr) null else "Failed to load shared albums: ${e.message}")
                    }
                },
            )
        }
    }
}
