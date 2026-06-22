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

package eu.akoos.photos.presentation.hidden

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

data class HiddenAlbumUiState(
    val isAuthenticated: Boolean = false,
    val items: List<LocalMediaItem> = emptyList(),
    /** hiddenUri → has-cloud-counterpart. Derived from HIDDEN_URI_CLOUD_ID_MAP at load
     *  time so the cell can render a green cloud badge for hidden photos that are also
     *  backed up — without this the user couldn't tell which hidden items are safe to
     *  delete (cloud copy exists) vs which are device-only. */
    val backedUpUris: Set<String> = emptySet(),
    /** URIs the user has selected. Selection mode is active whenever this is non-empty. */
    val selectedUris: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isSelectionMode: Boolean get() = selectedUris.isNotEmpty()
    val selectedCount: Int get() = selectedUris.size
}

@HiltViewModel
class HiddenAlbumViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val localMediaRepo: LocalMediaRepository,
    private val hiddenStorage: HiddenStorageManager,
    private val syncStateRepo: SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HiddenAlbumUiState())
    val uiState: StateFlow<HiddenAlbumUiState> = _uiState.asStateFlow()

    /** Tracks the active DataStore observer so a re-authentication (rare but possible
     *  if the user fails biometrics then retries) cancels the previous collector before
     *  a second one starts — avoids two parallel resolvers racing into [_uiState]. */
    private var observeJob: Job? = null

    fun onAuthenticationSuccess() {
        _uiState.value = _uiState.value.copy(isAuthenticated = true)
        observeHiddenPhotos()
    }

    fun onAuthenticationFailed() {
        _uiState.value = _uiState.value.copy(isAuthenticated = false)
    }

    /**
     * Drop the authenticated state and stop observing the hidden set. The authenticated
     * flag lives here (not in composable state), so it would otherwise survive the screen
     * going to the background and back — leaving the vault open without a fresh unlock.
     * Callers invoke this when the screen leaves the foreground so returning to it requires
     * re-authentication. Items are cleared too so no decoded hidden content lingers in state.
     */
    fun lock() {
        observeJob?.cancel()
        observeJob = null
        _uiState.value = _uiState.value.copy(
            isAuthenticated = false,
            items = emptyList(),
            selectedUris = emptySet(),
        )
    }

    /**
     * Reactive observation of the hidden set. Every edit to [SettingsKeys.HIDDEN_PHOTO_URIS]
     * or [SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] — whether from this VM's [hidePhoto] /
     * [unhidePhoto], or from another VM (e.g. PhotoViewerViewModel's renameLocal which
     * swaps the URI in the set when a hidden file is renamed) — re-runs the resolution
     * pipeline and pushes a fresh list into [_uiState]. Kills the "stale until close +
     * re-open" symptom on rename / add / remove.
     *
     * [collectLatest] cancels an in-flight queryByUri batch if a new emission lands
     * mid-resolution, so the StateFlow can't be overwritten by a slower previous run.
     */
    private fun observeHiddenPhotos() {
        observeJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        observeJob = viewModelScope.launch {
            val urisFlow = context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet() }
                .distinctUntilChanged()
            val backedUpFlow = context.settingsDataStore.data
                .map { prefs ->
                    (prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet())
                        .map { it.substringBefore('|') }
                        .toSet()
                }
                .distinctUntilChanged()
            combine(urisFlow, backedUpFlow) { uris, backedUp -> uris to backedUp }
                .catch { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.value = _uiState.value.copy(
                        error = friendly ?: sanitizeErrorMessage(e.message),
                        isLoading = false,
                    )
                }
                .collectLatest { (hiddenUris, backedUpUris) ->
                    val items = hiddenUris.mapNotNull { uri ->
                        localMediaRepo.queryByUri(uri)
                    }
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        backedUpUris = backedUpUris,
                        isLoading = false,
                    )
                }
        }
    }

    fun hidePhoto(uri: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + uri
            }
            // No explicit reload — observeHiddenPhotos() is watching the set Flow and
            // re-emits on the edit above.
        }
    }

    fun unhidePhoto(uri: String) {
        viewModelScope.launch {
            // Look up the cloud linkId we stashed at hide time so we can transplant the
            // existing SyncState row onto the freshly-restored MediaStore URI — that's
            // what keeps reconcile from treating the restored file as a brand-new local
            // photo and re-uploading it as a duplicate Drive entry.
            val cloudLinkId: String? = run {
                val prefs = context.settingsDataStore.data.first()
                val tokens = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                tokens.firstOrNull { it.startsWith("$uri|") }?.substringAfter('|')
            }
            // If [uri] is an app-private hidden file, restore it to MediaStore so other gallery
            // apps can see it again, then drop it from the hidden set.
            var restoredUri: String? = null
            if (hiddenStorage.isHiddenUri(uri)) {
                val displayName = _uiState.value.items.firstOrNull { it.uri == uri }?.displayName
                restoredUri = withContext(Dispatchers.IO) { hiddenStorage.restore(uri, displayName) }
            }
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - uri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = mapping.filterNot { it.startsWith("$uri|") }.toSet()
            }
            // Synced-photo round-trip: pull the OLD HIDDEN SyncState row (keyed on the
            // pre-hide URI) forward onto the new MediaStore URI, status=SYNCED. Without
            // this, the restored file has no SyncState — reconcile sees existingSync=null,
            // can't match by id/hash, falls through to byName/byNameAndDate, and on any
            // mismatch (e.g. drift across the hide cycle) starts a fresh upload.
            if (cloudLinkId != null && restoredUri != null) {
                runCatching {
                    val userId = accountManager.getPrimaryUserId().first()
                    val oldRow = syncStateRepo.getByCloudId(cloudLinkId)
                    if (oldRow != null && userId != null) {
                        // Drop the stale HIDDEN row (its localUri no longer exists on disk)
                        // and write a fresh SYNCED row keyed on the new MediaStore URI,
                        // carrying over the hash + backed-up timestamp so the row's history
                        // (e.g. "last sync 3 days ago") doesn't reset. deleteLocalOnlyByUris
                        // doesn't fit (status is HIDDEN here), so we re-key by upserting the
                        // new row first then nuking the old localUri via the available
                        // updateStatusAndDeleteLocal helper.
                        syncStateRepo.upsert(
                            oldRow.copy(
                                localUri = restoredUri,
                                status = SyncStatus.SYNCED,
                            ),
                            userId,
                        )
                        // Old HIDDEN row keyed on the dead URI — flip status so it stops
                        // surfacing in any "hidden cloud" listings and gets cleaned up by
                        // the next reconcile pass.
                        syncStateRepo.updateStatusAndDeleteLocal(oldRow.localUri, SyncStatus.CLOUD_ONLY)
                    }
                }
            }
            // No explicit reload — the edit{} above triggers observeHiddenPhotos()'s
            // combine to re-emit, which re-resolves the items list from the freshly
            // persisted set. The previous in-place-filter / stale-cell race is gone
            // because the recompute walks the persisted set every time.
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun toggleSelection(uri: String) {
        val current = _uiState.value.selectedUris
        _uiState.value = _uiState.value.copy(
            selectedUris = if (uri in current) current - uri else current + uri,
        )
    }

    /** Replace the whole selection — used by the drag-select sweep, which sets the swept range each frame. */
    fun setSelectedUris(uris: Set<String>) {
        _uiState.value = _uiState.value.copy(selectedUris = uris)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedUris = emptySet())
    }

    /**
     * Unhide every selected photo, then drop the selection. Loops the per-photo [unhidePhoto]
     * over a snapshot of the current selection — that path is idempotent on the hidden set, so
     * a photo already removed mid-loop is a no-op. The observer re-resolves the grid from the
     * persisted set as each removal lands.
     */
    fun unhideSelected() {
        val snapshot = _uiState.value.selectedUris
        if (snapshot.isEmpty()) return
        snapshot.forEach { unhidePhoto(it) }
        clearSelection()
    }
}
