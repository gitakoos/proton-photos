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

package eu.akoos.photos.presentation.albums

import android.content.Context
import android.content.IntentSender
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.AlbumSortMode
import eu.akoos.photos.domain.usecase.MoveToFolderUseCase
import eu.akoos.photos.domain.usecase.PendingMove
import eu.akoos.photos.domain.usecase.decodeAlbumOrder
import eu.akoos.photos.domain.usecase.encodeAlbumOrder
import eu.akoos.photos.domain.usecase.sortAlbums
import eu.akoos.photos.util.FolderCoverMap
import eu.akoos.photos.util.friendlyNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import javax.inject.Inject

/** Outcome of an album rename/delete surfaced to the long-press sheets. */
sealed interface AlbumActionResult {
    data object Done : AlbumActionResult
    data class Failed(val message: String) : AlbumActionResult
}

/** A MediaStore bucket surfaced as a card under the "Folders on this device" section. */
data class DeviceFolder(
    val name: String,
    val coverUri: String?,
    val itemCount: Int,
)

/**
 * The per-folder preferences a device-folder card can change without being opened. Every set keys
 * on the bucket name, the same way the Settings pickers and the folder screen store them, so a
 * change made from the grid is the same change made anywhere else.
 *
 * Held as one value so the settings store's emissions can be de-duplicated across all four at once:
 * the store also emits on unrelated writes, and each of these lands in the same drawer.
 */
data class DeviceFolderPrefs(
    val mirroredAsAlbum: Set<String> = emptySet(),
    val excludedFromBackup: Set<String> = emptySet(),
    val hiddenFromTimeline: Set<String> = emptySet(),
    val sortMode: AlbumPhotoSortMode = AlbumPhotoSortMode.Default,
)

data class AlbumsUiState(
    val isLoading: Boolean = true,
    val albums: List<Album> = emptyList(),
    val deviceFolders: List<DeviceFolder> = emptyList(),
    val hiddenAlbumIds: Set<String> = emptySet(),
    /** Albums whose photos are kept out of the main feed alone. Whole set rather than a resolved
     *  flag, for the same reason [folderPrefs] is: the grid shows every album at once and the drawer
     *  asks about whichever card was held. */
    val timelineExcludedAlbumIds: Set<String> = emptySet(),
    val error: String? = null,
    val isCreatingAlbum: Boolean = false,
    val createAlbumError: String? = null,
    /** Albums someone else shared with this user and granted edit rights on. */
    val sharedAddableAlbums: List<Album> = emptyList(),
    /** Set to an album's linkId when the server refused to delete it because that would destroy
     *  photos held nowhere else, so the screen can put the choice to the user. */
    val deleteWouldLosePhotosFor: String? = null,
    /** The per-folder preferences the device-folder long-press drawer reads and writes. Whole sets
     *  rather than a resolved flag, because the grid shows every folder at once and the drawer asks
     *  about whichever card was held. */
    val folderPrefs: DeviceFolderPrefs = DeviceFolderPrefs(),
    /** The direction every album lists its photos in. One global choice, so the album long-press
     *  drawer needs it here to tick the one in force without opening the album. */
    val albumPhotoSortMode: AlbumPhotoSortMode = AlbumPhotoSortMode.Default,
) {
    /** Cloud albums hidden client-side drop off the Albums grid. */
    val visibleCloudAlbums: List<Album> get() = albums.filter { it.linkId !in hiddenAlbumIds }

    /** Device folders that still hold a photo. A folder the vault holds entirely produces no card,
     *  so the hidden set never has to be subtracted here. */
    val visibleDeviceFolders: List<DeviceFolder>
        get() = DeviceFolderCards.visible(deviceFolders)

    /**
     * Every album a selected photo may be added to: the user's own, plus the shared ones they hold
     * edit rights on.
     *
     * Deliberately separate from [albums]. The Albums grid is about what the user owns, and shared
     * albums have their own tab, so folding them in there would move things around unasked. A
     * picker is the one place both belong, because the question there is "where may this photo go".
     */
    val addableAlbums: List<Album> get() = visibleCloudAlbums + sharedAddableAlbums
}

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountManager: AccountManager,
    private val driveRepo: DrivePhotoRepository,
    private val localMediaRepo: LocalMediaRepository,
    private val moveToFolder: MoveToFolderUseCase,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    /** Whether a Proton account is signed in. A local-only session has no Drive to back up to, so the
     *  device-folder drawer drops its cloud rows. Defaults to signed-in so nothing flickers before the
     *  first emit. */
    val isSignedIn: StateFlow<Boolean> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** One-shot carrier for the system write-consent request a device-folder rename needs when the
     *  folder holds a file the app does not own. [AlbumsScreen] launches it and calls
     *  [onMovePermissionGranted] on approval; null the rest of the time. */
    private val _pendingMoveIntent = MutableStateFlow<IntentSender?>(null)
    val pendingMoveIntent: StateFlow<IntentSender?> = _pendingMoveIntent.asStateFlow()

    /** One-shot confirmation for a completed device-folder rename, carrying the new folder name for
     *  the snackbar. replay=0 + single buffer so a paused screen never blocks the emit. */
    private val _folderRenameConfirmation = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val folderRenameConfirmation: SharedFlow<String> = _folderRenameConfirmation.asSharedFlow()

    /** The write-consent request stashed while the user approves the system dialog, replayed by
     *  [onMovePermissionGranted] and dropped by [clearPendingMove]. */
    private var stashedMove: PendingMove? = null

    /** The grid order in force. Every album list this ViewModel publishes goes through [applySort],
     *  so the cached paint and the network refresh can never disagree about the order. */
    private val sortMode = MutableStateFlow(AlbumSortMode.Default)

    /** The user's own arrangement, read only by [AlbumSortMode.Custom]. */
    private val customOrder = MutableStateFlow<List<String>>(emptyList())

    private fun applySort(albums: List<Album>): List<Album> =
        sortAlbums(albums, sortMode.value, customOrder.value)

    init {
        observeSortMode()
        observeAlbumPhotoSortMode()
        loadAlbums()
        observeDeviceFolders()
        // Re-fetch on share-state changes so the grid badge updates without a manual pull-to-refresh.
        viewModelScope.launch {
            albumListEvents.changes.collect { loadAlbums() }
        }
        // A cover change patches only that album's thumbnail in place, so the grid card flips without
        // reloading and flashing the whole list.
        viewModelScope.launch {
            albumListEvents.coverChanges.collect { (albumId, coverUrl) ->
                if (coverUrl == null) return@collect
                _uiState.update { st ->
                    st.copy(albums = st.albums.map {
                        if (it.linkId == albumId) it.copy(coverThumbnailUrl = coverUrl) else it
                    })
                }
            }
        }
    }

    /**
     * Track the persisted grid order, both the mode and the user's own arrangement, and re-sort what
     * is already on screen when either changes, so a new order takes effect without waiting for a
     * reload. Read as one pair because both come from the same store and feed the same sort;
     * [kotlinx.coroutines.flow.distinctUntilChanged] keeps writes to unrelated settings, which the
     * store also emits on, from re-sorting the grid for nothing.
     */
    private fun observeSortMode() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { prefs ->
                    AlbumSortMode.fromOrdinal(prefs[SettingsKeys.ALBUMS_SORT_MODE]) to
                        decodeAlbumOrder(prefs[SettingsKeys.ALBUMS_CUSTOM_ORDER])
                }
                .distinctUntilChanged()
                .catch { emit(AlbumSortMode.Default to emptyList()) }
                .collect { (mode, order) ->
                    sortMode.value = mode
                    customOrder.value = order
                    _uiState.update { it.copy(albums = applySort(it.albums)) }
                }
        }
    }

    /**
     * Track the direction albums list their photos in, so a card's drawer can tick the one in force.
     * Its own collector rather than a member of the pair above: that pair orders the grid, this
     * orders what is inside an album, and the two are set from different places.
     */
    private fun observeAlbumPhotoSortMode() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { AlbumPhotoSortMode.fromOrdinal(it[SettingsKeys.ALBUM_PHOTO_SORT_MODE]) }
                .distinctUntilChanged()
                .catch { emit(AlbumPhotoSortMode.Default) }
                .collect { mode -> _uiState.update { it.copy(albumPhotoSortMode = mode) } }
        }
    }

    /**
     * Persist the user's own album arrangement AND switch the grid to [AlbumSortMode.Custom].
     *
     * The two are deliberately one write. An arrangement stored while a name or last-activity sort
     * is in force would be re-sorted away the instant it was read back, so the card would spring
     * out of the slot it was just put in and the arrangement would look discarded. Keeping the pair
     * here rather than at the call site means no caller can save an arrangement that the active
     * sort silently overrides. One [androidx.datastore.preferences.core.edit] block, so the store
     * emits both together and the grid re-sorts once.
     *
     * [orderedLinkIds] replaces the stored arrangement outright, so it must carry every album the
     * user has placed rather than only the ones the grid is currently showing. Hidden albums are
     * filtered out after ordering and shared albums render from another surface, so narrowing this
     * to what is visible would drop those albums' slots on every save.
     */
    fun saveAlbumArrangement(orderedLinkIds: List<String>) {
        viewModelScope.launch {
            runCatching {
                context.settingsDataStore.edit {
                    it[SettingsKeys.ALBUMS_CUSTOM_ORDER] = encodeAlbumOrder(orderedLinkIds)
                    it[SettingsKeys.ALBUMS_SORT_MODE] = AlbumSortMode.Custom.ordinal
                }
            }
        }
    }

    /**
     * Group MediaStore items into device-folder cards (bucket → pinned or newest cover + count, hidden-vault excluded).
     * Own collector touching only [AlbumsUiState.deviceFolders], so a MediaStore refresh never disturbs cloud state.
     */
    private fun observeDeviceFolders() {
        viewModelScope.launch {
            // Both come from the same store and feed the same cards, so they are read as one pair;
            // distinctUntilChanged keeps writes to unrelated settings from re-grouping for nothing.
            val folderPrefsFlow = context.settingsDataStore.data
                .map { prefs ->
                    (prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) to
                        FolderCoverMap.parse(prefs[SettingsKeys.FOLDER_COVER_URI_MAP])
                }
                .distinctUntilChanged()
            combine(localMediaRepo.observeLocalMedia(), folderPrefsFlow) { items, (hiddenUris, pinnedCovers) ->
                DeviceFolderCards.build(items, hiddenUris, pinnedCovers)
            }
                .catch { emit(emptyList()) }
                .collect { folders ->
                    _uiState.update { it.copy(deviceFolders = folders) }
                }
        }
        // The per-folder preferences the long-press drawer offers. Read as one snapshot for the same
        // reason the cover pair above is: one store, one destination, and distinctUntilChanged keeps
        // writes to unrelated settings from re-emitting them.
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { prefs ->
                    DeviceFolderPrefs(
                        mirroredAsAlbum = prefs[SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES] ?: emptySet(),
                        excludedFromBackup = prefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet(),
                        hiddenFromTimeline = prefs[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES] ?: emptySet(),
                        sortMode = AlbumPhotoSortMode.fromOrdinal(prefs[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE]),
                    )
                }
                .distinctUntilChanged()
                .catch { emit(DeviceFolderPrefs()) }
                .collect { prefs -> _uiState.update { it.copy(folderPrefs = prefs) } }
        }
        // The two per-album id sets the grid and its long-press drawer read: the client-side hidden
        // albums, filtered out of the grid via [AlbumsUiState.visibleCloudAlbums], and the weaker
        // timeline exclusion the drawer ticks. Read as one pair for the same reason the folder
        // preferences above are, and distinctUntilChanged keeps writes to unrelated settings, which
        // the store also emits on, from re-publishing them.
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { prefs ->
                    (prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet()) to
                        (prefs[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] ?: emptySet())
                }
                .distinctUntilChanged()
                .catch { emit(emptySet<String>() to emptySet()) }
                .collect { (hidden, timelineExcluded) ->
                    _uiState.update {
                        it.copy(hiddenAlbumIds = hidden, timelineExcludedAlbumIds = timelineExcluded)
                    }
                }
        }
        // When the hidden-photo set changes, re-resolve album covers from cache so an album whose
        // cover is now a hidden photo switches to a non-hidden one (and back on unhide), live and
        // without a full network reload. The initial covers are already resolved by the load above.
        viewModelScope.launch {
            driveRepo.observeHiddenAlbumMemberLinkIds()
                .drop(1)
                .catch { emit(emptySet()) }
                .collect {
                    val recached = runCatching { driveRepo.loadAlbumsCached() }.getOrNull().orEmpty()
                    if (recached.isEmpty()) return@collect
                    val coverById = recached.associate { a -> a.linkId to a.coverThumbnailUrl }
                    _uiState.update { st ->
                        st.copy(albums = st.albums.map { a ->
                            if (a.linkId in coverById) a.copy(coverThumbnailUrl = coverById[a.linkId]) else a
                        })
                    }
                }
        }
    }

    /** The one writer for the grid's per-folder preference sets. Reading and writing inside the same
     *  edit keeps a flip atomic, matching the folder screen's own writer. */
    private suspend fun toggleFolderName(
        key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>,
        folderName: String,
    ) {
        if (folderName.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (folderName in current) current - folderName else current + folderName
        }
    }

    /** Opt [folderName] in or out of also surfacing as a Drive album. */
    fun toggleFolderMirrorAsAlbum(folderName: String) {
        viewModelScope.launch { toggleFolderName(SettingsKeys.ALBUM_OPT_IN_FOLDER_NAMES, folderName) }
    }

    /** Carve [folderName] out of "Back up everything", or put it back in. */
    fun toggleFolderExcludedFromBackup(folderName: String) {
        viewModelScope.launch {
            toggleFolderName(SettingsKeys.EXCLUDED_FOLDER_NAMES, folderName)
            // Same follow-up the Settings picker runs: a fresh reconcile drops rows that just landed
            // in the excluded set before an in-flight sync pass can upload them.
            val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
            eu.akoos.photos.worker.SyncWorker.runNow(context, wifiOnly)
        }
    }

    /** Show or hide [folderName]'s photos in the main timeline. Display only, so nothing to
     *  reconcile: the gallery observes the key and re-filters on the next emission. */
    fun toggleFolderHiddenFromTimeline(folderName: String) {
        viewModelScope.launch { toggleFolderName(SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES, folderName) }
    }

    /** Persist the direction every device folder lists its photos in. */
    fun setDeviceFolderSortMode(mode: AlbumPhotoSortMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.DEVICE_FOLDER_PHOTO_SORT_MODE] = mode.ordinal }
        }
    }

    /** Persist the direction every album lists its photos in, the same key the album's own screen
     *  writes, so a direction set from a card and one set inside an album are one choice. */
    fun setAlbumPhotoSortMode(mode: AlbumPhotoSortMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.ALBUM_PHOTO_SORT_MODE] = mode.ordinal }
        }
    }

    fun refresh() = loadAlbums()

    fun loadAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            // Phase 1: instant cache read so the grid paints on cold/airplane-mode launch.
            val cached = runCatching { driveRepo.loadAlbumsCached() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(isLoading = false, albums = applySort(cached)) }
            }
            // Cache-only, so it costs nothing on this path and the add-to-album picker has its
            // destinations ready without the shared-with-me walk. Refreshed by the Shared tab.
            // Null means the read itself failed, and only then is the painted cache worth keeping.
            // An empty ANSWER is real news: the user left the album or lost edit rights on it, and
            // holding the old list left it in the add-to-album picker, where tapping it addressed a
            // foreign album against this user's own volume.
            // The read answers with a list either way, an empty one included, so an empty answer is
            // taken at face value. That is the point: holding the old list left an album the user had
            // left sitting in the add-to-album picker. A failure inside the read also surfaces as
            // empty and briefly clears the picker, which the next successful read restores.
            val sharedAddable = runCatching { driveRepo.loadSharedAddableAlbumsCached() }.getOrDefault(emptyList())
            _uiState.update { it.copy(sharedAddableAlbums = sharedAddable) }

            // Phase 2: network refresh, online only — else keep the painted cache.
            if (!networkObserver.isOnline.value) {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                if (cached.isEmpty()) _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            // Skeleton only when there's nothing painted yet.
            if (cached.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            runCatching { driveRepo.loadAlbums(userId) }.fold(
                onSuccess = { albums ->
                    _uiState.update { it.copy(isLoading = false, albums = applySort(albums)) }
                    // Fire-and-forget membership prefetch so a later album-open paints from cache.
                    // Delayed 5 s so foreground gallery decrypts get first crack at the network semaphore.
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(5_000)
                        runCatching { driveRepo.prefetchAlbumsMembership(userId, albums) }
                    }
                },
                onFailure = { e ->
                    val friendly = friendlyNetworkError(e, networkObserver.isOnline.value, context)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // Swallow the refresh error when a cached snapshot is already on screen.
                            error = if (cached.isNotEmpty()) null
                            else friendly ?: sanitizeErrorMessage(e.message),
                        )
                    }
                },
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun createAlbum(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isCreatingAlbum = true, createAlbumError = null) }
            try {
                val album = driveRepo.createDriveAlbum(userId, trimmed)
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    albums = (listOf(album) + it.albums),
                ) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(
                    isCreatingAlbum = false,
                    createAlbumError = e.message ?: context.getString(R.string.albums_create_failed),
                ) }
            }
        }
    }

    fun clearCreateAlbumError() = _uiState.update { it.copy(createAlbumError = null) }

    /**
     * Rename a cloud album, exposed as a Flow so the long-press sheet can drive an in-flight + snackbar cycle.
     * Also re-keys the bucket→linkId cache (ALBUM_BUCKET_MAP); without it a folder-mirror upload would orphan
     * the original and create a new album under the new name.
     */
    fun renameCloudAlbum(linkId: String, currentName: String, newName: String): Flow<AlbumActionResult> = flow {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            emit(AlbumActionResult.Failed(context.getString(R.string.albums_name_empty)))
            return@flow
        }
        if (trimmed == currentName) {
            emit(AlbumActionResult.Failed(context.getString(R.string.albums_rename_same_name)))
            return@flow
        }
        // Re-throw CancellationException (caught below) — converting it to a Failed result violates Flow transparency.
        val result = runCatching {
            val userId = accountManager.getPrimaryUserId().first()
                ?: throw IllegalStateException(context.getString(R.string.viewer_not_signed_in))
            driveRepo.renameAlbum(userId, linkId, trimmed)
            _uiState.update { state ->
                state.copy(albums = state.albums.map {
                    if (it.linkId == linkId) it.copy(name = trimmed) else it
                })
            }
            // Re-key the bucket→linkId cache. Match BOTH name and linkId so a same-named bucket isn't hijacked.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.ALBUM_BUCKET_MAP] ?: emptySet()
                val rebuilt = current.mapNotNull { entry ->
                    val idx = entry.indexOf('=')
                    if (idx <= 0) return@mapNotNull entry
                    val bucket = entry.substring(0, idx)
                    val storedLinkId = entry.substring(idx + 1)
                    if (bucket.equals(currentName, ignoreCase = true) && storedLinkId == linkId) {
                        "$trimmed=$linkId"
                    } else entry
                }.toSet()
                if (rebuilt != current) {
                    prefs[SettingsKeys.ALBUM_BUCKET_MAP] = rebuilt
                }
            }
        }
        result.fold(
            onSuccess = { emit(AlbumActionResult.Done) },
            onFailure = { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                emit(AlbumActionResult.Failed(e.message ?: context.getString(R.string.albums_rename_failed)))
            },
        )
    }

    /**
     * Deletes an album.
     *
     * [deletePhotosToo] false is the safe first attempt: the server refuses it when the album holds
     * the only copy of some photos, and that refusal is surfaced as a question rather than an error
     * so the user decides. Passing true is that decision, made knowingly.
     */
    fun deleteAlbum(albumLinkId: String, deletePhotosToo: Boolean = false) {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            try {
                driveRepo.deleteAlbum(userId, albumLinkId, deletePhotosToo)
                _uiState.update { state ->
                    state.copy(
                        albums = state.albums.filter { it.linkId != albumLinkId },
                        deleteWouldLosePhotosFor = null,
                    )
                }
            } catch (e: eu.akoos.photos.domain.entity.AlbumDeleteWouldLosePhotos) {
                // Not a failure: the album still exists and nothing was touched. Hand the choice up.
                _uiState.update { it.copy(deleteWouldLosePhotosFor = albumLinkId) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The dialog is its own window and the message lands on the scaffold behind it, so
                // leaving it up hides the only feedback there is and the button reads as inert.
                _uiState.update { it.copy(deleteWouldLosePhotosFor = null, error = context.getString(R.string.albums_delete_failed, e.message ?: "")) }
            }
        }
    }

    fun dismissDeleteWouldLosePhotos() =
        _uiState.update { it.copy(deleteWouldLosePhotosFor = null) }

    /**
     * Hide a cloud album client-side by adding its linkId to [SettingsKeys.HIDDEN_ALBUM_IDS]. The
     * card leaves the Albums grid and the album's photos drop from every other list. Nothing on
     * Drive changes, so the album stays intact and returns on unhide.
     */
    fun hideAlbum(albumLinkId: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_ALBUM_IDS] = current + albumLinkId
            }
        }
    }

    /**
     * Show or hide [albumLinkId]'s photos in the main feed, the album's counterpart of
     * [toggleFolderHiddenFromTimeline] and the same key the Settings picker writes.
     *
     * Display only, so nothing to reconcile: the gallery observes the key and re-filters on the next
     * emission. A separate set from [hideAlbum]'s, so this leaves the card on the grid and leaves the
     * photos in search, on the map, in the calendar and in every picker. Reading and writing inside
     * the same edit keeps the flip atomic.
     */
    fun toggleAlbumHiddenFromTimeline(albumLinkId: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] ?: emptySet()
                prefs[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] =
                    AlbumTimelineHide.toggled(current, albumLinkId)
            }
        }
    }

    // ── Rename a device folder ────────────────────────────────────────────────
    // Android has no folder-rename under scoped storage, so a folder rename physically relocates every
    // photo in the bucket into DCIM/<newName>/ via [MoveToFolderUseCase]. Offered only in a logged-out
    // session, the local counterpart of [renameCloudAlbum]; a foreign file needs one-shot write consent,
    // handled exactly like the timeline's move.

    /** Rename the device folder [bucketName] by moving its photos into [newName] under DCIM/. A blank
     *  or unchanged name is a no-op, as is a folder with no readable device file. */
    fun renameDeviceFolder(bucketName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == bucketName) return
        viewModelScope.launch {
            val uris = localMediaRepo.queryByBucket(bucketName).map { it.uri }
            if (uris.isEmpty()) return@launch
            handleRenameResult(moveToFolder(uris, trimmed), trimmed)
        }
    }

    /** Replay the deferred move on the foreign files after the user granted the system write request. */
    fun onMovePermissionGranted() {
        val pending = stashedMove ?: run { _pendingMoveIntent.value = null; return }
        viewModelScope.launch {
            handleRenameResult(moveToFolder.completeAfterPermissionGranted(pending), pending.folderName)
        }
    }

    /** User cancelled the system write dialog; drop the deferred files, nothing moves. */
    fun clearPendingMove() {
        stashedMove = null
        _pendingMoveIntent.value = null
    }

    private suspend fun handleRenameResult(result: MoveToFolderUseCase.Result, newName: String) {
        when (result) {
            is MoveToFolderUseCase.Result.Moved -> {
                stashedMove = null
                _pendingMoveIntent.value = null
                _folderRenameConfirmation.emit(newName)
            }
            is MoveToFolderUseCase.Result.NeedsPermission -> {
                stashedMove = result.pending
                _pendingMoveIntent.value = result.intentSender
            }
            is MoveToFolderUseCase.Result.Failed,
            MoveToFolderUseCase.Result.NothingToDo -> {
                stashedMove = null
                _pendingMoveIntent.value = null
            }
        }
    }
}
