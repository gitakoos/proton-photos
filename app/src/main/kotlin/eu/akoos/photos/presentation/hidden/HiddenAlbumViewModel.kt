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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/** Grid key prefix for a hidden cloud-photo tile in the vault, so the one shared drag-select can tell
 *  a cloud selection key from a device uri. The LazyGrid item key and the selection key use this. */
internal const val HIDDEN_CLOUD_SELECTION_PREFIX = "hidden_cloud_"

data class HiddenAlbumUiState(
    val isAuthenticated: Boolean = false,
    val items: List<LocalMediaItem> = emptyList(),
    /** Cloud albums the user hid client-side, resolved from HIDDEN_ALBUM_IDS to full cards
     *  so the vault can reveal and reopen them. Empty until the album source resolves. */
    val hiddenAlbums: List<Album> = emptyList(),
    /** Individually-hidden cloud photos, resolved from HIDDEN_CLOUD_PHOTO_IDS to displayable cards
     *  so the vault can show them, open them in the viewer, and unhide them. The id set is small, so
     *  CloudPhoto (already the lean display projection, no crypto material) is safe to hold here. */
    val hiddenCloudPhotos: List<CloudPhoto> = emptyList(),
    /** hiddenUri → has-cloud-counterpart. Derived from HIDDEN_URI_CLOUD_ID_MAP at load
     *  time so the cell can render a green cloud badge for hidden photos that are also
     *  backed up — without this the user couldn't tell which hidden items are safe to
     *  delete (cloud copy exists) vs which are device-only. */
    val backedUpUris: Set<String> = emptySet(),
    /** URIs the user has selected. Selection mode is active whenever this is non-empty. */
    val selectedUris: Set<String> = emptySet(),
    /** linkIds of individually-hidden cloud photos the user has selected. Shares the one selection
     *  mode with [selectedUris] so a mixed device + cloud selection reveals together from the dock. */
    val selectedCloudLinkIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isSelectionMode: Boolean get() = selectedUris.isNotEmpty() || selectedCloudLinkIds.isNotEmpty()
    val selectedCount: Int get() = selectedUris.size + selectedCloudLinkIds.size
}

@HiltViewModel
class HiddenAlbumViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val localMediaRepo: LocalMediaRepository,
    private val hiddenStorage: HiddenStorageManager,
    private val syncStateRepo: SyncStateRepository,
    private val driveRepo: DrivePhotoRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HiddenAlbumUiState())
    val uiState: StateFlow<HiddenAlbumUiState> = _uiState.asStateFlow()

    /** Tracks the active DataStore observer so a re-authentication (rare but possible
     *  if the user fails biometrics then retries) cancels the previous collector before
     *  a second one starts — avoids two parallel resolvers racing into [_uiState]. */
    private var observeJob: Job? = null

    /** Twin of [observeJob] for the hidden cloud-album resolver, cancelled together on [lock]. */
    private var albumsJob: Job? = null

    /** Twin of [albumsJob] for the individually-hidden cloud-photo resolver, cancelled on [lock]. */
    private var cloudPhotosJob: Job? = null

    /** Cached primary userId for the on-demand thumbnail-decrypt requests, kept fresh from the account flow. */
    @Volatile private var primaryUserId: UserId? = null

    init {
        accountManager.getPrimaryUserId().onEach { primaryUserId = it }.launchIn(viewModelScope)
    }

    fun onAuthenticationSuccess() {
        _uiState.value = _uiState.value.copy(isAuthenticated = true)
        observeHiddenPhotos()
        observeHiddenAlbums()
        observeHiddenCloudPhotos()
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
        albumsJob?.cancel()
        albumsJob = null
        cloudPhotosJob?.cancel()
        cloudPhotosJob = null
        _uiState.value = _uiState.value.copy(
            isAuthenticated = false,
            items = emptyList(),
            hiddenAlbums = emptyList(),
            hiddenCloudPhotos = emptyList(),
            selectedUris = emptySet(),
            selectedCloudLinkIds = emptySet(),
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
                    // The vault file lives under a private UUID name, so queryByUri reports that
                    // code as the display name. Surface the original filename recorded at hide
                    // time instead, when one is present.
                    val nameMap = context.settingsDataStore.data.first()[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
                    val items = hiddenUris.mapNotNull { uri ->
                        localMediaRepo.queryByUri(uri)?.let { item ->
                            val original = nameMap.firstOrNull { it.startsWith("$uri|") }?.substringAfter('|')
                            if (!original.isNullOrBlank()) item.copy(displayName = original) else item
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        backedUpUris = backedUpUris,
                        isLoading = false,
                    )
                }
        }
    }

    /**
     * Cloud album cards with covers, loaded the way the Albums grid loads them: an instant cache
     * read first so the section paints offline, then a network refresh when online. Loaded here
     * only to resolve which hidden ids map to real albums, so a failure just keeps the cache.
     */
    private fun albumsSource(): Flow<List<Album>> = flow {
        val cached = runCatching { driveRepo.loadAlbumsCached() }.getOrNull().orEmpty()
        if (cached.isNotEmpty()) emit(cached)
        if (!networkObserver.isOnline.value) {
            if (cached.isEmpty()) emit(emptyList())
            return@flow
        }
        val userId = accountManager.getPrimaryUserId().first()
        if (userId == null) {
            if (cached.isEmpty()) emit(emptyList())
            return@flow
        }
        val fresh = runCatching { driveRepo.loadAlbums(userId) }.getOrNull()
        if (fresh != null) emit(fresh) else if (cached.isEmpty()) emit(emptyList())
    }

    /**
     * Resolve the hidden cloud-album ids into full [Album] cards and keep the list live. Combines
     * the album source with the [SettingsKeys.HIDDEN_ALBUM_IDS] flow, so unhiding an album (the id
     * leaves the set) drops its card on the next emission without a manual reload.
     */
    private fun observeHiddenAlbums() {
        albumsJob?.cancel()
        albumsJob = viewModelScope.launch {
            val hiddenIdsFlow = context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet() }
                .distinctUntilChanged()
            combine(albumsSource(), hiddenIdsFlow) { albums, hiddenIds ->
                albums.filter { it.linkId in hiddenIds }
            }
                .catch { emit(emptyList()) }
                .collect { hidden ->
                    _uiState.value = _uiState.value.copy(hiddenAlbums = hidden)
                }
        }
    }

    /**
     * Resolve the individually-hidden cloud-photo ids into displayable [CloudPhoto] cards and keep
     * the list live. The id set (HIDDEN_CLOUD_PHOTO_IDS) is small, so the full-entity
     * observePhotosByLinkIds read is acceptable here; its toDomain drops the encrypted material, so
     * only the lean display projection lands in state. An empty set short-circuits without a query,
     * and [collectLatest] cancels the inner observation when the set changes so a slower previous run
     * can't overwrite the list. Unhiding a photo (its id leaves the set) drops its cell on the next
     * emission.
     */
    private fun observeHiddenCloudPhotos() {
        cloudPhotosJob?.cancel()
        cloudPhotosJob = viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet() }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    if (ids.isEmpty()) {
                        _uiState.value = _uiState.value.copy(hiddenCloudPhotos = emptyList())
                    } else {
                        driveRepo.observePhotosByLinkIds(ids.toList())
                            .catch { emit(emptyList()) }
                            .collect { photos ->
                                _uiState.value = _uiState.value.copy(hiddenCloudPhotos = photos)
                            }
                    }
                }
        }
    }

    /**
     * Reveal a hidden cloud album by dropping its linkId from [SettingsKeys.HIDDEN_ALBUM_IDS]. The
     * combine in [observeHiddenAlbums] re-emits on the edit, so the card leaves this section and the
     * album's photos return to the rest of the app. Mirrors [unhidePhoto]'s DataStore edit.
     */
    fun unhideAlbum(linkId: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_ALBUM_IDS] = current - linkId
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
            // Source folder recorded at hide time so the file returns to where it came from
            // instead of the Pictures/Movies root. Absent for items hidden before this map
            // existed — restore then keeps its root default.
            // Original filename recorded at hide time so the restored entry keeps its name.
            // Absent for items hidden before this map existed — restore then generates a name.
            val sourceFolder: String?
            val storedName: String?
            run {
                val prefs = context.settingsDataStore.data.first()
                sourceFolder = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP]
                    ?.firstOrNull { it.startsWith("$uri|") }
                    ?.substringAfter('|')
                storedName = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP]
                    ?.firstOrNull { it.startsWith("$uri|") }
                    ?.substringAfter('|')
            }
            // If [uri] is an app-private hidden file, restore it to MediaStore so other gallery
            // apps can see it again, then drop it from the hidden set.
            var restoredUri: String? = null
            if (hiddenStorage.isHiddenUri(uri)) {
                // Prefer the name persisted at hide time — the in-memory item's displayName is
                // derived from the private UUID file, so it would otherwise restore as a code.
                val displayName = storedName ?: _uiState.value.items.firstOrNull { it.uri == uri }?.displayName
                restoredUri = withContext(Dispatchers.IO) {
                    hiddenStorage.restore(uri, displayName, albumFolderName = sourceFolder)
                }
            }
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current - uri
                val mapping = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] = mapping.filterNot { it.startsWith("$uri|") }.toSet()
                val folders = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] = folders.filterNot { it.startsWith("$uri|") }.toSet()
                val names = prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_URI_ORIGINAL_NAME_MAP] = names.filterNot { it.startsWith("$uri|") }.toSet()
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

    /** Reveal a client-side-hidden cloud photo by dropping its linkId from HIDDEN_CLOUD_PHOTO_IDS, so
     *  it returns to every listing. Nothing on Drive changes. The reveal screen calls this. */
    fun unhideCloudPhoto(linkId: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] = current - linkId
            }
        }
    }

    /** Enqueue an on-demand thumbnail decrypt for a hidden cloud cell; deduped by linkId, no-op until
     *  primaryUserId lands. Mirrors the gallery/offline lazy-thumbnail path, so nothing decrypts up front. */
    fun requestCloudThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        driveRepo.requestThumbnailDecrypt(userId, linkId)
    }

    /** Cancel any in-flight decrypt for [linkId] when its hidden cloud cell scrolls off-screen. */
    fun cancelCloudThumbnailDecrypt(linkId: String) {
        driveRepo.cancelThumbnailDecrypt(linkId)
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

    /** Toggle a hidden cloud photo in the shared selection (tap in selection mode). */
    fun toggleCloudSelection(linkId: String) {
        val current = _uiState.value.selectedCloudLinkIds
        _uiState.value = _uiState.value.copy(
            selectedCloudLinkIds = if (linkId in current) current - linkId else current + linkId,
        )
    }

    /** Replace the whole selection from the drag-select sweep, which sets the swept range each frame.
     *  The grid mixes device tiles (keyed by uri) and hidden cloud tiles (keyed by
     *  [HIDDEN_CLOUD_SELECTION_PREFIX] + linkId), so split the swept keys back into the two sets. */
    fun setSelectionFromKeys(keys: Set<String>) {
        val cloud = mutableSetOf<String>()
        val device = mutableSetOf<String>()
        for (key in keys) {
            if (key.startsWith(HIDDEN_CLOUD_SELECTION_PREFIX)) cloud += key.removePrefix(HIDDEN_CLOUD_SELECTION_PREFIX)
            else device += key
        }
        _uiState.value = _uiState.value.copy(selectedUris = device, selectedCloudLinkIds = cloud)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedUris = emptySet(), selectedCloudLinkIds = emptySet())
    }

    /**
     * Unhide every selected photo (device vault copies + individually-hidden cloud photos), then drop
     * the selection. Each per-photo path ([unhidePhoto] / [unhideCloudPhoto]) is idempotent on its
     * hidden set, so a photo already removed mid-loop is a no-op. The observer re-resolves the grid
     * from the persisted sets as each removal lands.
     */
    fun unhideSelected() {
        val state = _uiState.value
        val uris = state.selectedUris
        val cloudLinkIds = state.selectedCloudLinkIds
        if (uris.isEmpty() && cloudLinkIds.isEmpty()) return
        uris.forEach { unhidePhoto(it) }
        cloudLinkIds.forEach { unhideCloudPhoto(it) }
        clearSelection()
    }
}
