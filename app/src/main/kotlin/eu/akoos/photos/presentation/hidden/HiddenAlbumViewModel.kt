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
import kotlinx.coroutines.flow.update
import eu.akoos.photos.domain.entity.SyncStatus
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenFolderProgress
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.data.hidden.HiddenVaultRecords
import eu.akoos.photos.data.hidden.HiddenVaultRestorer
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.presentation.albums.DeviceFolder
import eu.akoos.photos.presentation.albums.DeviceFolderCards
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
    /** Device folders the user hid, resolved from HIDDEN_FOLDER_NAMES to the same cards the Albums
     *  grid draws, so the vault can reveal and reopen them. Their photos are in the vault, so the
     *  count and the cover come from what the vault recorded rather than from a device scan. */
    val hiddenFolders: List<DeviceFolder> = emptyList(),
    /** done/total of a folder being restored out of the vault, or null when none is. */
    val folderRestore: HiddenFolderProgress? = null,
    /** Individually-hidden cloud photos, resolved from HIDDEN_CLOUD_PHOTO_IDS to displayable cards
     *  so the vault can show them, open them in the viewer, and unhide them. The id set is small, so
     *  CloudPhoto (already the lean display projection, no crypto material) is safe to hold here. */
    val hiddenCloudPhotos: List<CloudPhoto> = emptyList(),
    /** The vaulted photos that still have a Drive copy, read live off the vault's own cloud-id
     *  records so the cell can draw the green cloud over them. It is the one thing separating a photo
     *  the user can get back from Drive from one whose only bytes are the vault file, which is what
     *  makes deleting it here reversible or final. */
    val backedUpUris: Set<String> = emptySet(),
    /**
     * linkIds among [hiddenCloudPhotos] whose device copy is still sitting in the phone's gallery.
     *
     * Hiding a backed-up photo once meant filtering its cloud copy and leaving the device file where
     * it was; it now moves that file into the vault. Both kinds survive an update and both stay
     * hidden here, but only the newer kind is out of reach of the phone's other gallery apps. This
     * set is what lets the screen say so instead of showing two different protections as one.
     *
     * The test is a `SYNCED` sync row against a hidden linkId: a vaulted photo's row reads `HIDDEN`,
     * and a photo with no device copy at all has no local uri to pair, so `SYNCED` names exactly the
     * one that still has its file on the device.
     */
    val looseDeviceCopyLinkIds: Set<String> = emptySet(),
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

    /** Every photo the grid can select: the device tiles plus the hidden cloud ones. The album and
     *  folder cards are not photos, so they stay out of it. */
    val selectableCount: Int get() = items.size + hiddenCloudPhotos.size

    /** True once the selection holds every selectable photo, so the header's control can offer to
     *  clear it instead. */
    val allSelected: Boolean get() = selectableCount > 0 && selectedCount == selectableCount
}

@HiltViewModel
class HiddenAlbumViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val localMediaRepo: LocalMediaRepository,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val hiddenVaultRestorer: HiddenVaultRestorer,
    private val driveRepo: DrivePhotoRepository,
    private val syncStateRepo: eu.akoos.photos.domain.repository.SyncStateRepository,
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
    private var looseCopiesJob: Job? = null

    /** Twin of [albumsJob] for the hidden device-folder resolver, cancelled on [lock]. */
    private var foldersJob: Job? = null

    /** The running folder restore, so a second tap on the same card cannot start a parallel one. */
    private var folderRestoreJob: Job? = null

    /** Raised by [cancelFolderRestore] and polled between photos. A flag rather than a job cancel,
     *  so the restore stops at a photo boundary and still clears the folder when it emptied. */
    private val stopFolderRestore = java.util.concurrent.atomic.AtomicBoolean(false)

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
        observeLooseDeviceCopies()
        observeHiddenFolders()
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
        looseCopiesJob?.cancel()
        looseCopiesJob = null
        foldersJob?.cancel()
        foldersJob = null
        _uiState.value = _uiState.value.copy(
            isAuthenticated = false,
            items = emptyList(),
            hiddenAlbums = emptyList(),
            hiddenFolders = emptyList(),
            hiddenCloudPhotos = emptyList(),
            looseDeviceCopyLinkIds = emptySet(),
            selectedUris = emptySet(),
            selectedCloudLinkIds = emptySet(),
        )
    }

    /**
     * Reactive observation of the hidden set. Every edit to [SettingsKeys.HIDDEN_PHOTO_URIS]
     * or [SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] — whether from this VM's [unhidePhoto],
     * or from another VM (e.g. PhotoViewerViewModel's renameLocal which
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
            // Repair whatever an interrupted hide left behind before the first read resolves, so a
            // photo whose delete landed but whose record did not shows up here instead of sitting
            // invisible until sign-out takes it. Runs once per process and writes through the same
            // preferences the observer below collects, so any change it makes re-emits on its own.
            hiddenVaultJournal.reconcile()
            // The photos hidden on their own. One vaulted along with its whole folder is reached
            // inside that folder's card below, so listing it here too would put the same photo on the
            // screen twice; [HiddenFolderRecords.looseVaultedUris] is the one rule that splits them.
            val urisFlow = context.settingsDataStore.data
                .map { prefs ->
                    HiddenFolderRecords.looseVaultedUris(
                        vaultedUris = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet(),
                        sourceFolderEntries = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet(),
                        hiddenFolderNames = prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet(),
                    )
                }
                .distinctUntilChanged()
            val backedUpFlow = context.settingsDataStore.data
                .map { prefs ->
                    HiddenVaultRecords.pairedUris(prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet())
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
                    // Newest first, matching the hidden cloud photos this same grid shows beside these
                    // (PhotoStreamService.observePhotosByLinkIds orders on captureTime descending), so
                    // one screen does not run on two orders. The stored set has no order of its own.
                    // The sort is stable, so a vault entry old enough to record no capture time keeps a
                    // fixed place instead of moving between reads.
                    val items = hiddenUris.mapNotNull { uri ->
                        localMediaRepo.queryByUri(uri)?.let { item ->
                            val original = nameMap.firstOrNull { it.startsWith("$uri|") }?.substringAfter('|')
                            if (!original.isNullOrBlank()) item.copy(displayName = original) else item
                        }
                    }.sortedByDescending { it.dateTaken }
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
    /**
     * Track which hidden cloud photos still have their device file on the phone.
     *
     * Runs off the same hidden-id set the cards are drawn from, joined against the sync rows, so the
     * answer changes the moment either does: revealing a photo drops it, and vaulting its file flips
     * its row off `SYNCED` and drops it too. Reads the lean sync rows the app already observes rather
     * than touching MediaStore, so it costs nothing on a large library.
     */
    private fun observeLooseDeviceCopies() {
        looseCopiesJob?.cancel()
        looseCopiesJob = viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val hiddenIds = context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_CLOUD_PHOTO_IDS] ?: emptySet() }
                .distinctUntilChanged()
            combine(hiddenIds, syncStateRepo.observeAll(userId)) { ids, states ->
                if (ids.isEmpty()) {
                    emptySet()
                } else {
                    states.asSequence()
                        .filter { it.status == SyncStatus.SYNCED }
                        .mapNotNull { it.cloudFileId }
                        .filter { it in ids }
                        .toSet()
                }
            }
                .distinctUntilChanged()
                .catch { emit(emptySet()) }
                .collect { loose -> _uiState.update { it.copy(looseDeviceCopyLinkIds = loose) } }
        }
    }

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
     * Resolve the hidden device-folder names into cards and keep the list live.
     *
     * Both the list and every count on it come from the vault's own records: the stored names say
     * which folders are hidden, and the `"vaultUri|sourceFolder"` records say what the vault holds for
     * each. A device scan cannot answer either question — a folder the vault holds entirely leaves no
     * MediaStore row carrying its bucket name, and a row that IS still there after a hide belongs to a
     * photo that was never hidden.
     *
     * An empty name set short-circuits: nothing is hidden in the common case, and there is no work to
     * do for it.
     */
    private fun observeHiddenFolders() {
        foldersJob?.cancel()
        foldersJob = viewModelScope.launch {
            context.settingsDataStore.data
                .map { prefs ->
                    val names = prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()
                    if (names.isEmpty()) emptyList() else DeviceFolderCards.hidden(
                        hiddenNames = names,
                        vaultedByFolder = HiddenFolderRecords.vaultedByBucket(
                            sourceFolderEntries = prefs[SettingsKeys.HIDDEN_URI_SOURCE_FOLDER_MAP] ?: emptySet(),
                            vaultedUris = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet(),
                        ),
                    )
                }
                .distinctUntilChanged()
                .catch { emit(emptyList()) }
                .collect { folders ->
                    _uiState.value = _uiState.value.copy(hiddenFolders = folders)
                }
        }
    }

    /**
     * Reveal a hidden device folder: every photo the vault holds for it returns to the device, then
     * its name leaves [SettingsKeys.HIDDEN_FOLDER_NAMES] and its card returns to the Albums grid.
     *
     * A folder can hold thousands, so the work reports progress and [cancelFolderRestore] stops it.
     * A stopped restore leaves the photos it already returned on the device and the rest in the
     * vault, with the folder still listed here so they can be reached again.
     */
    fun unhideFolder(folderName: String) {
        if (folderRestoreJob?.isActive == true) return
        stopFolderRestore.set(false)
        folderRestoreJob = viewModelScope.launch {
            try {
                val failed = hiddenVaultRestorer.restoreFolder(
                    bucketName = folderName,
                    onProgress = { done, total ->
                        _uiState.value = _uiState.value.copy(
                            folderRestore = HiddenFolderProgress(done, total, restoring = true),
                        )
                    },
                    shouldStop = { stopFolderRestore.get() },
                )
                if (failed > 0) {
                    _uiState.value = _uiState.value.copy(
                        error = context.resources.getQuantityString(
                            R.plurals.hidden_restore_failed_some, failed, failed,
                        ),
                    )
                }
            } finally {
                _uiState.value = _uiState.value.copy(folderRestore = null)
            }
        }
    }

    /** Stop a folder restore between photos. Cooperative: the file in transit finishes returning to
     *  the device rather than being interrupted half-written. */
    fun cancelFolderRestore() {
        stopFolderRestore.set(true)
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

    /**
     * Return one vaulted photo to the device. [HiddenVaultRestorer] owns the whole round trip — the
     * recorded name, folder and cloud pairing — so a photo revealed from here, from the folder screen
     * or by an undo comes back the same way.
     *
     * The in-memory display name is passed as a fallback for a vault entry old enough to have
     * recorded none; without it the private code the file is stored under would land on the device.
     *
     * No explicit reload: the restorer's DataStore edit re-emits into [observeHiddenPhotos], which
     * re-resolves the grid from the persisted set. A photo that could not be written back stays in
     * the vault with everything it owns, so the only thing left to do is say so.
     */
    fun unhidePhoto(uri: String) {
        viewModelScope.launch {
            val restored = hiddenVaultRestorer.restorePhoto(
                uri,
                fallbackDisplayName = _uiState.value.items.firstOrNull { it.uri == uri }?.displayName,
            )
            if (!restored) {
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.hidden_restore_failed))
            }
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

    /** Select every photo the grid lists, across both groups, or clear the selection when it already
     *  holds them all. The same one control the other grids put in their selection header. */
    fun toggleSelectAll() {
        val state = _uiState.value
        _uiState.value = if (state.allSelected) {
            state.copy(selectedUris = emptySet(), selectedCloudLinkIds = emptySet())
        } else {
            state.copy(
                selectedUris = state.items.map { it.uri }.toSet(),
                selectedCloudLinkIds = state.hiddenCloudPhotos.map { it.linkId }.toSet(),
            )
        }
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

    /**
     * Destroy every selected vault photo, bytes and records together.
     *
     * The hide removed the device original, so the vault copy is the whole photo and this cannot be
     * undone — which is why the screen confirms first. [HiddenVaultRestorer.deletePhoto] is the one
     * deleter, shared with the viewer, so a photo destroyed from here leaves exactly as little
     * behind as one destroyed a page at a time.
     *
     * Only the device half of the selection is touched: a hidden cloud photo is a filter over a file
     * that is still on Drive, so nothing here would be deleting it. The selection is dropped up
     * front so the grid stops offering actions on photos already on their way out, and a photo that
     * would not go says so with a count.
     */
    fun deleteSelectedVaulted() {
        val uris = _uiState.value.selectedUris
        if (uris.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            var failed = 0
            uris.forEach { uri ->
                val gone = try {
                    hiddenVaultRestorer.deletePhoto(uri)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }
                if (!gone) failed++
            }
            if (failed > 0) {
                _uiState.value = _uiState.value.copy(
                    error = context.resources.getQuantityString(
                        R.plurals.hidden_delete_failed_some, failed, failed,
                    ),
                )
            }
        }
    }
}
