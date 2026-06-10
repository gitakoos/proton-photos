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

package eu.akoos.photos.presentation.settings

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.repository.drive.CloudTrashService
import eu.akoos.photos.data.repository.drive.ThumbnailDecryptScheduler
import eu.akoos.photos.domain.entity.CloudTrashItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.LocalMediaRepository
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepo: LocalMediaRepository,
    private val cloudTrashService: CloudTrashService,
    private val accountManager: AccountManager,
    private val thumbnailScheduler: ThumbnailDecryptScheduler,
) : ViewModel() {

    data class UiState(
        val device: DeviceTrashState = DeviceTrashState(),
        val cloud: CloudTrashState = CloudTrashState(),
    )

    data class DeviceTrashState(
        val items: List<LocalMediaItem> = emptyList(),
        val isLoading: Boolean = true,
        val apiUnsupported: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        val selectedUris: Set<String> = emptySet(),
    ) {
        val selectedCount: Int get() = selectedUris.size
        val isSelectionMode: Boolean get() = selectedUris.isNotEmpty()
        val allSelected: Boolean get() =
            items.isNotEmpty() && items.all { it.uri in selectedUris }
    }

    data class CloudTrashState(
        val items: List<CloudTrashItem> = emptyList(),
        val selectedLinkIds: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val toastMessage: String? = null,
        val lastFetchedAtMs: Long = 0L,
        /** linkId → decrypted file:// URI of the thumbnail on disk. Empty entry means
         *  "decrypt in flight or not started"; a non-null value lets the cell render the
         *  actual JPEG via Coil. Survives the in-memory list as a side map so swapping
         *  items doesn't lose the cached URLs. */
        val decryptedThumbnails: Map<String, String> = emptyMap(),
    ) {
        val selectedCount: Int get() = selectedLinkIds.size
        val isSelectionMode: Boolean get() = selectedLinkIds.isNotEmpty()
        val allSelected: Boolean get() =
            items.isNotEmpty() && items.all { it.linkId in selectedLinkIds }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val cacheTtlMs = 5L * 60L * 1000L

    init {
        loadDeviceTrash()
        loadCloudTrash()
    }

    // ── Device trash ────────────────────────────────────────────────────────────

    private fun loadDeviceTrash() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModelScope.launch {
                localMediaRepo.observeTrashedMedia().collectLatest { items ->
                    _uiState.update {
                        it.copy(device = it.device.copy(items = items, isLoading = false))
                    }
                }
            }
        } else {
            _uiState.update { it.copy(device = it.device.copy(isLoading = false)) }
        }
    }

    fun toggleDeviceSelection(uri: String) {
        _uiState.update { state ->
            val current = state.device.selectedUris
            val new = if (uri in current) current - uri else current + uri
            state.copy(device = state.device.copy(selectedUris = new))
        }
    }

    fun selectAllDevice() {
        _uiState.update { state ->
            state.copy(device = state.device.copy(selectedUris = state.device.items.map { it.uri }.toSet()))
        }
    }

    fun clearDeviceSelection() {
        _uiState.update { it.copy(device = it.device.copy(selectedUris = emptySet())) }
    }

    fun buildRestoreDeviceIntent(): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val device = _uiState.value.device
        val uris = device.selectedUris
            .ifEmpty { device.items.map { it.uri }.toSet() }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return null
        return MediaStore.createTrashRequest(context.contentResolver, uris, false)
    }

    fun buildDeleteDeviceForeverIntent(): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val device = _uiState.value.device
        val uris = device.selectedUris
            .ifEmpty { device.items.map { it.uri }.toSet() }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    fun onDeviceActionCompleted() {
        _uiState.update { it.copy(device = it.device.copy(selectedUris = emptySet())) }
    }

    // ── Cloud trash ─────────────────────────────────────────────────────────────

    fun loadCloudTrash(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val current = _uiState.value.cloud
            if (!forceRefresh && current.items.isNotEmpty() &&
                System.currentTimeMillis() - current.lastFetchedAtMs < cacheTtlMs
            ) {
                return@launch
            }
            _uiState.update {
                it.copy(cloud = it.cloud.copy(isLoading = true, errorMessage = null))
            }
            val userId = accountManager.getPrimaryUserId().first()
            if (userId == null) {
                _uiState.update {
                    it.copy(cloud = it.cloud.copy(
                        isLoading = false,
                        errorMessage = "Not signed in",
                    ))
                }
                return@launch
            }
            val result = runCatching { cloudTrashService.getCloudTrash(userId) }
            result.fold(
                onSuccess = { items ->
                    _uiState.update {
                        it.copy(cloud = it.cloud.copy(
                            items = items,
                            isLoading = false,
                            errorMessage = null,
                            lastFetchedAtMs = System.currentTimeMillis(),
                        ))
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(cloud = it.cloud.copy(
                            isLoading = false,
                            errorMessage = e.message ?: "Couldn't load cloud trash",
                        ))
                    }
                },
            )
        }
    }

    fun toggleCloudSelection(linkId: String) {
        _uiState.update { state ->
            val current = state.cloud.selectedLinkIds
            val updated = if (linkId in current) current - linkId else current + linkId
            state.copy(cloud = state.cloud.copy(selectedLinkIds = updated))
        }
    }

    fun clearCloudSelection() {
        _uiState.update { it.copy(cloud = it.cloud.copy(selectedLinkIds = emptySet())) }
    }

    fun selectAllCloud() {
        _uiState.update { state ->
            state.copy(cloud = state.cloud.copy(selectedLinkIds = state.cloud.items.map { it.linkId }.toSet()))
        }
    }

    fun restoreSelectedCloud() {
        viewModelScope.launch {
            val state = _uiState.value
            val linkIds = state.cloud.selectedLinkIds.toList()
            if (linkIds.isEmpty()) return@launch
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val result = runCatching { cloudTrashService.restoreFromCloudTrash(userId, linkIds) }
            result.fold(
                onSuccess = { outcome ->
                    // Drop only the links the server actually restored; keep rejected ones
                    // selected so the user can retry them. Partial failures and a failed
                    // post-restore gallery refresh each get their own toast so the user
                    // knows whether to retry here or pull-to-refresh the gallery.
                    val message = when {
                        outcome.failedLinkIds.isNotEmpty() -> context.getString(
                            R.string.trash_cloud_restore_partial,
                            outcome.restoredLinkIds.size,
                            outcome.failedLinkIds.size,
                        )
                        outcome.galleryRefreshFailed -> context.getString(R.string.trash_cloud_restore_refresh_failed)
                        else -> context.getString(R.string.trash_cloud_restore_done, outcome.restoredLinkIds.size)
                    }
                    _uiState.update { st ->
                        st.copy(cloud = st.cloud.copy(
                            items = st.cloud.items.filterNot { it.linkId in outcome.restoredLinkIds },
                            selectedLinkIds = outcome.failedLinkIds,
                            toastMessage = message,
                        ))
                    }
                },
                onFailure = { e ->
                    _uiState.update { st ->
                        st.copy(cloud = st.cloud.copy(
                            errorMessage = e.message ?: context.getString(R.string.trash_cloud_restore_failed),
                        ))
                    }
                },
            )
        }
    }

    fun emptyCloudSelected() {
        viewModelScope.launch {
            val state = _uiState.value
            val linkIds = state.cloud.selectedLinkIds.takeIf { it.isNotEmpty() }?.toList()
                ?: state.cloud.items.map { it.linkId }
            if (linkIds.isEmpty()) return@launch
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val result = runCatching { cloudTrashService.deleteFromCloudForever(userId, linkIds) }
            result.fold(
                onSuccess = { outcome ->
                    // Drop only the links the server actually removed; keep rejected ones
                    // selected for retry and surface a partial-failure toast when any
                    // stayed in trash.
                    val message = if (outcome.failedLinkIds.isNotEmpty()) {
                        context.getString(
                            R.string.trash_cloud_empty_partial,
                            outcome.deletedLinkIds.size,
                            outcome.failedLinkIds.size,
                        )
                    } else {
                        context.getString(R.string.trash_cloud_emptied)
                    }
                    _uiState.update { st ->
                        st.copy(cloud = st.cloud.copy(
                            items = st.cloud.items.filterNot { it.linkId in outcome.deletedLinkIds },
                            selectedLinkIds = outcome.failedLinkIds,
                            toastMessage = message,
                        ))
                    }
                },
                onFailure = { e ->
                    _uiState.update { st ->
                        st.copy(cloud = st.cloud.copy(
                            errorMessage = e.message ?: context.getString(R.string.trash_cloud_empty_failed),
                        ))
                    }
                },
            )
        }
    }

    fun emptyAllCloud() = emptyCloudSelected()

    fun consumeCloudToast() {
        _uiState.update { it.copy(cloud = it.cloud.copy(toastMessage = null)) }
    }

    fun clearCloudError() {
        _uiState.update { it.copy(cloud = it.cloud.copy(errorMessage = null)) }
    }

    /**
     * Lazy thumbnail decrypt for a single cloud-trash entry. Called by the cell when it
     * enters composition and the URL isn't already cached. The scheduler handles
     * concurrency (3-permit semaphore shared with the gallery) and disk caching, so a
     * second call for the same linkId is cheap. Failures are swallowed — the cell falls
     * back to the placeholder.
     */
    fun requestCloudThumbnail(item: CloudTrashItem) {
        val state = _uiState.value.cloud
        if (state.decryptedThumbnails.containsKey(item.linkId)) return
        val serverUrl = item.thumbnailUrl ?: return
        val ckp = item.contentKeyPacket ?: return
        val encNodeKey = item.encNodeKey ?: return
        val encNodePass = item.encNodePassphrase ?: return
        val parentLinkId = item.parentLinkId ?: return
        val volumeId = item.volumeId ?: return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val fileUrl = runCatching {
                thumbnailScheduler.decryptThumbnailToFileBounded(
                    userId = userId,
                    linkId = item.linkId,
                    volumeId = volumeId,
                    serverUrl = serverUrl,
                    serverToken = item.thumbnailToken,
                    contentKeyPacketBase64 = ckp,
                    encNodeKey = encNodeKey,
                    encNodePass = encNodePass,
                    parentLinkId = parentLinkId,
                )
            }.getOrNull() ?: return@launch
            _uiState.update { st ->
                st.copy(cloud = st.cloud.copy(
                    decryptedThumbnails = st.cloud.decryptedThumbnails + (item.linkId to fileUrl),
                ))
            }
        }
    }
}
