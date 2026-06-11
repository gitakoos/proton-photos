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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.gallery

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.ObserveUser
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.data.repository.drive.PhotoStreamService
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.util.ProtonPhotosStorage
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.looksLikeNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

/** Internal bag for the flows the gallery `combine` chain produces. Lives at top
 *  level so distinctUntilChanged downstream can do structural equality on it. */
private data class GallerySources(
    val items: List<GalleryItem>,
    val hiddenUris: Set<String>,
    val hiddenCloudLinkIds: Set<String>,
    val cloudInAlbum: Set<String>,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val cloudRepo: DrivePhotoRepository,
    private val photoStreamService: PhotoStreamService,
    private val localRepo: LocalMediaRepository,
    private val accountManager: AccountManager,
    private val observeUser: ObserveUser,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val reconcile: ReconcileSyncStateUseCase,
    private val upload: UploadPendingUseCase,
    private val hiddenStorage: HiddenStorageManager,
    private val syncStateRepo: SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumPhotoMembershipDao: eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    /** True when the device has a validated internet connection. Drives the avatar
     *  offline badge in [GalleryScreen] and gates every cloud-side refresh below. */
    val isOnline: StateFlow<Boolean> = networkObserver.isOnline

    /** Cloud linkIds that also have a SYNCED local copy on this device. A photo classified as
     *  [GalleryItem.CloudOnly] in the static item snapshot can become locally available after the
     *  user downloads it (the sync row updates, but the snapshot doesn't). This live set lets the
     *  grid upgrade such a cell's badge to "backed up + on device", matching the viewer. */
    val downloadedCloudLinkIds: StateFlow<Set<String>> = flow {
        val userId = accountManager.getPrimaryUserId().first()
        if (userId == null) { emit(emptySet()); return@flow }
        emitAll(
            syncStateRepo.observeAll(userId).map { states ->
                states.asSequence()
                    .filter { it.status == SyncStatus.SYNCED }
                    .filter { it.cloudFileId != null && it.localUri.isNotBlank() }
                    .map { it.cloudFileId!! }
                    .toSet()
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Atomic guard against re-entry into [doSync]. Read+write on [_uiState.value.isSyncing] is
     *  not race-safe — two coroutines (init's syncOnLaunch and observeFolderSettings) could both
     *  pass the `if (isSyncing) return` gate before either has set it to true. */
    private val syncInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        observeGallery()
        observeUserInitial()
        syncOnLaunch()
        observeFolderSettings()
        loadTimelineGrouping()
        observeFavorites()
        observeBackgroundUploadProgress()
        observePrimaryUserId()
        observeHideInAlbums()
    }

    /**
     * Cached primary userId for synchronous use by PhotoCell's thumbnail decrypt
     * lambdas. Compose can't suspend on a Flow inside its body without forcing
     * recomposition on every emission, so we stash the resolved value here and let
     * cells read it cheaply during the grid scroll. Null until the first emission
     * lands — in that window the cells just won't request thumbnails (the next
     * recomposition triggered by the userId update will queue them).
     */
    @Volatile private var primaryUserId: me.proton.core.domain.entity.UserId? = null

    private fun observePrimaryUserId() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId().collect { primaryUserId = it }
        }
    }

    /**
     * Called from PhotoCell's DisposableEffect.onEnter when a cloud-only or synced photo
     * cell becomes visible. Delegated to [DrivePhotoRepository.requestThumbnailDecrypt]
     * which dedup's repeat calls and bounds concurrent decrypts via ThumbnailDecryptScheduler.
     */
    fun requestThumbnailDecrypt(linkId: String) {
        val userId = primaryUserId ?: return
        cloudRepo.requestThumbnailDecrypt(userId, linkId)
    }

    /**
     * Called from PhotoCell's DisposableEffect.onDispose when a cell leaves the viewport.
     * Cancels any in-flight decrypt for [linkId] so we don't spend JNI cycles on
     * thumbnails the user has scrolled past.
     */
    fun cancelThumbnailDecrypt(linkId: String) {
        cloudRepo.cancelThumbnailDecrypt(linkId)
    }

    /**
     * Look-ahead decrypt for the rows just past the viewport in the scroll direction.
     * Fed by the grid scroll state in [GalleryScreen]; the scheduler queues these at
     * prefetch priority so they never delay an on-screen cell. [linkIds] are the cloud
     * link ids of the upcoming items — local-only rows carry none and are filtered out
     * before this call.
     */
    fun prefetchThumbnails(linkIds: List<String>) {
        val userId = primaryUserId ?: return
        cloudRepo.prefetchThumbnailDecrypt(userId, linkIds)
    }

    /**
     * Visible-priority decrypt for thumbnails that render off the scrolling grid and so
     * never fire a per-cell request — the "On this day" memories row at the top of the
     * timeline. Queued at the same priority as on-screen cells so the card fills as soon
     * as it appears, independent of grid scroll position.
     */
    fun requestThumbnailsVisible(linkIds: List<String>) {
        val userId = primaryUserId ?: return
        cloudRepo.requestThumbnailDecrypt(userId, linkIds)
    }

    /**
     * Mirror [UploadPendingUseCase.progress] into [GalleryUiState.isSyncing] so the avatar
     * spinner reflects ALL upload activity — not just the foreground [doSync] calls. Without
     * this, a SyncWorker tick that started from the MediaStore ContentObserver (camera fired)
     * uploads silently and the user has no in-app indicator that anything is happening.
     *
     * Idle frames clear the flag; per-file Uploading/Queued frames set it.
     */
    private fun observeBackgroundUploadProgress() {
        viewModelScope.launch {
            upload.progress.collect { evt ->
                // isSyncing should stay true for the WHOLE upload lifecycle the user can
                // observe — not just the on-CDN Uploading phase. The Encrypting phase can
                // take several seconds on a large clip, and excluding it dropped the
                // gallery spinner mid-pipeline, making users think the sync stalled.
                // Idle / Done / Failed all flip it back off.
                val syncing = when (evt.status) {
                    eu.akoos.photos.domain.usecase.UploadStatus.Queued -> true
                    eu.akoos.photos.domain.usecase.UploadStatus.Encrypting -> true
                    eu.akoos.photos.domain.usecase.UploadStatus.Uploading -> true
                    eu.akoos.photos.domain.usecase.UploadStatus.Done -> false
                    eu.akoos.photos.domain.usecase.UploadStatus.Failed -> false
                    eu.akoos.photos.domain.usecase.UploadStatus.Idle -> false
                }
                if (_uiState.value.isSyncing != syncing) {
                    _uiState.update { it.copy(isSyncing = syncing) }
                }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.FAVORITE_IDS] ?: emptySet() }
                .collect { favIds ->
                    _uiState.update { state ->
                        state.copy(
                            favoriteIds = favIds,
                            filteredItems = applyFilter(
                                applyContentFilter(state.items, state.contentFilter),
                                state.selectedFilter,
                                favIds,
                            ),
                        )
                    }
                }
        }
    }

    fun toggleFavorite(item: GalleryItem) {
        val id = galleryItemId(item) ?: return
        viewModelScope.launch {
            // Always optimistically update local DataStore so the UI flips immediately and
            // local-only items have a place to live.
            val nowFavorited = context.settingsDataStore.data
                .map { it[SettingsKeys.FAVORITE_IDS] ?: emptySet() }
                .first()
                .let { id !in it }
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.FAVORITE_IDS] ?: emptySet()
                prefs[SettingsKeys.FAVORITE_IDS] = if (id in current) current - id else current + id
            }
            // For cloud-backed items also push the change to Drive so other devices see it.
            val cloudPhoto = when (item) {
                is GalleryItem.Synced    -> item.cloud
                is GalleryItem.CloudOnly -> item.cloud
                is GalleryItem.LocalOnly -> null
            }
            if (cloudPhoto != null) {
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                cloudRepo.setCloudFavorite(userId, cloudPhoto, favorite = nowFavorited)
            }
        }
    }

    private fun galleryItemId(item: GalleryItem): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced    -> item.local.uri
        is GalleryItem.CloudOnly -> item.cloud.linkId
    }

    private fun loadTimelineGrouping() {
        viewModelScope.launch {
            val prefs = context.settingsDataStore.data.first()
            val groupingName = prefs[SettingsKeys.TIMELINE_GROUPING] ?: TimelineGrouping.Month.name
            val grouping = runCatching { TimelineGrouping.valueOf(groupingName) }.getOrDefault(TimelineGrouping.Month)
            _uiState.update { it.copy(timelineGrouping = grouping) }
        }
    }

    private fun observeUserInitial() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId()
                .flatMapLatest { userId ->
                    if (userId != null) observeUser(userId) else flowOf(null)
                }
                .collectLatest { user ->
                    val initial = (user?.displayName?.takeIf { it.isNotBlank() }
                        ?: user?.name?.takeIf { it.isNotBlank() }
                        ?: user?.email?.takeIf { it.isNotBlank() }
                        ?: "?").first().uppercaseChar().toString()
                    _uiState.update {
                        it.copy(
                            userInitial     = initial,
                            cloudUsedBytes  = user?.usedDriveSpace ?: user?.usedSpace ?: 0L,
                            cloudMaxBytes   = user?.maxDriveSpace  ?: user?.maxSpace  ?: 0L,
                        )
                    }
                }
        }
    }

    private fun observeGallery() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch

            val hiddenUrisFlow = context.settingsDataStore.data.map {
                it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
            }

            // SyncStateRepo rows with HIDDEN status carry the cloud linkId of a photo whose
            // local twin lives in the Hidden vault. The gallery shows those cloud photos but
            // marks them with a crossed-out eye overlay so the user can see which cloud
            // entries are "hidden on this device" without opening the Hidden screen.
            val hiddenCloudLinkIdsFlow = syncStateRepo.observeAll(userId).map { states ->
                states.asSequence()
                    .filter { it.status == SyncStatus.HIDDEN && it.cloudFileId != null }
                    .map { it.cloudFileId!! }
                    .toSet()
            }

            val hideInAlbumsFlow = context.settingsDataStore.data.map {
                it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] == true
            }
            // Cloud linkIds in any album, gated by the toggle. When OFF (default),
            // short-circuit to emptySet() so users who don't enable the feature pay
            // zero cost from the cross-table query.
            val cloudAlbumHideSetFlow = hideInAlbumsFlow.flatMapLatest { hide ->
                if (hide) albumPhotoMembershipDao.observeAllAssociatedPhotoLinkIds().map { it.toSet() }
                else flowOf(emptySet())
            }

            // Coalesce bursts of single-row DAO mutations (e.g. 50 thumbnail decrypts
            // landing during a Gallery first-load) into one emit per ~300 ms. Without this,
            // every decrypt completion re-emits the full items list, invalidates the
            // LazyVerticalGrid, and recomposes every cell — scroll stutters until decrypts
            // quiet down. `sample` (not `debounce`) is required: a debounce only emits after
            // a quiet gap, so a continuous decrypt burst (a completion every ~100-200 ms)
            // keeps resetting the timer and the grid never repaints until the burst ends or
            // an unrelated recomposition forces it. `sample` emits the latest snapshot every
            // 300 ms regardless, so freshly-decrypted thumbnails appear live throughout the
            // burst. It sits on the items flow ALONE, not the combined result: the
            // hidden-vault, hide-in-albums toggle and album-membership flows propagate
            // immediately so flipping the toggle re-filters the grid in the very next pass.
            val itemsFlow = getGalleryItems.invoke(userId).sample(300)

            combine(
                itemsFlow,
                hiddenUrisFlow,
                hiddenCloudLinkIdsFlow,
                cloudAlbumHideSetFlow,
            ) { items, hiddenUris, hiddenCloudLinkIds, cloudInAlbum ->
                GallerySources(items, hiddenUris, hiddenCloudLinkIds, cloudInAlbum)
            }
                .distinctUntilChanged()
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { sources ->
                    // Hidden-vault filter (always applied) — items whose local URI is in the
                    // Hidden vault are dropped from the gallery; cloud counterparts stay in
                    // the listing with a dim overlay (see hiddenCloudLinkIds usage in
                    // CloudPhotoCell). Album-hide is NOT applied here — it's applied inside
                    // [applyFilter] so non-All tabs (Favorites, Screenshots, Videos, …) can
                    // bypass it. When the user explicitly picks a tab they expect to see
                    // every item that matches, album membership notwithstanding.
                    val items = sources.items.filter { item ->
                        val localUri = when (item) {
                            is GalleryItem.LocalOnly -> item.local.uri
                            is GalleryItem.Synced -> item.local.uri
                            is GalleryItem.CloudOnly -> null
                        }
                        localUri == null || localUri !in sources.hiddenUris
                    }
                    val pending = items.count { it is GalleryItem.LocalOnly }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            items = items,
                            filteredItems = applyFilter(
                                applyContentFilter(items, state.contentFilter),
                                state.selectedFilter,
                                state.favoriteIds,
                                sources.cloudInAlbum,
                            ),
                            pendingUploadCount = pending,
                            hiddenCloudLinkIds = sources.hiddenCloudLinkIds,
                            albumHideCloudIds = sources.cloudInAlbum,
                        )
                    }
                }
        }
    }

    /**
     * Keep the album-membership table populated whenever "Hide photos in albums" is on.
     *
     * The table only fills when an album is opened or the Albums tab's fire-and-forget
     * prefetch runs. A user who enables the toggle without ever visiting Albums (or who
     * added album members elsewhere since the last prefetch) would otherwise keep seeing
     * those photos in the main grid, because the hide-set built from the table is still
     * empty/stale. Triggering the prefetch here — on launch with the toggle already on,
     * and on every off→on flip — makes the membership rows land, after which the DAO Flow
     * in [observeGallery] re-emits and the grid drops the album photos on its own.
     *
     * The prefetch's own freshness gate skips albums whose cached row count already matches
     * Drive's photoCount, so the steady-state cost is one cheap album-list read; only stale
     * or never-fetched albums hit the network. Runs detached from the toggle write so the
     * Settings switch flips instantly.
     */
    private fun observeHideInAlbums() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] == true }
                .distinctUntilChanged()
                .collect { hide ->
                    if (!hide) return@collect
                    if (!networkObserver.isOnline.value) return@collect
                    val userId = accountManager.getPrimaryUserId().first() ?: return@collect
                    runCatching {
                        val albums = cloudRepo.loadAlbums(userId)
                        cloudRepo.prefetchAlbumsMembership(userId, albums)
                    }
                }
        }
    }

    private fun syncOnLaunch() {
        // The gallery is now on screen, so the early refresh started at app launch no longer
        // needs the gentle (first-screen) cadence — flip the live signal off so any in-flight
        // full refresh speeds back up to the normal pace on its next chunk. Done before the
        // userId await so it takes effect immediately on arrival. The refresh below coalesces
        // onto that same in-flight one via refreshFullMutex (single-flight) — no second refresh.
        photoStreamService.setGentleSync(false)
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            doSync(userId)
        }
    }

    /** Re-run the full reconcile + upload cycle. Safe to call multiple times — concurrent calls
     *  are de-duped via [syncInFlight]. */
    private suspend fun doSync(userId: me.proton.core.domain.entity.UserId) {
        if (!networkObserver.isOnline.value) return
        if (!syncInFlight.compareAndSet(false, true)) return
        _uiState.update { it.copy(isSyncing = true) }
        try {
            // Incremental cloud refresh before reconcile so deleted-cloud photos are removed
            // from the local DB before reconcile reads cloudItems.
            cloudRepo.refreshCloudPhotosIncremental(userId)
            reconcile(userId).collect {}
            // If reconcile found any LOCAL_ONLY items, enqueue a foreground OneTime worker so
            // the upload runs with a visible progress notification — even if the user backgrounds
            // the app mid-upload. The KEEP unique-work policy means this coalesces with any
            // already-running SyncWorker (periodic or oneshot), and the use case's internal
            // mutex prevents double-uploads if the inline upload below also starts.
            val pending = syncStateRepo.observeAll(userId).first()
                .count { it.status == SyncStatus.LOCAL_ONLY }
            if (pending > 0) {
                val wifiOnly = context.settingsDataStore.data
                    .map { it[SettingsKeys.SYNC_WIFI_ONLY] != false }.first()
                SyncWorker.runNow(context, wifiOnly)
            }
            upload(userId)
        } catch (e: Exception) {
            // Rethrow cancellation so the parent coroutine's structured concurrency stays
            // intact — swallowing it would leave the parent thinking the child completed
            // normally and could mask UI state inversions.
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            syncInFlight.set(false)
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    /**
     * Observe folder-selection changes. When the user configures backup folders and comes back,
     * immediately trigger a reconcile+upload without requiring an app restart.
     */
    private fun observeFolderSettings() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.SYNC_FOLDER_NAMES] }
                .distinctUntilChanged()
                .drop(1) // skip the initial emission already handled by syncOnLaunch
                .collect { folders ->
                    val userId = accountManager.getPrimaryUserId().first() ?: return@collect
                    if (folders != null && folders.isNotEmpty()) {
                        doSync(userId)
                    }
                }
        }
    }

    fun onFilterSelected(filter: GalleryFilter) {
        _uiState.update { state ->
            state.copy(
                selectedFilter = filter,
                filteredItems = applyFilter(
                    applyContentFilter(state.items, state.contentFilter),
                    filter,
                    state.favoriteIds,
                    state.albumHideCloudIds,
                ),
            )
        }
    }

    fun setTimelineGrouping(grouping: TimelineGrouping) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.TIMELINE_GROUPING] = grouping.name }
            _uiState.update { it.copy(timelineGrouping = grouping) }
        }
    }

    fun setContentFilter(filter: ContentFilter) {
        _uiState.update { state ->
            state.copy(
                contentFilter = filter,
                filteredItems = applyFilter(
                    applyContentFilter(state.items, filter),
                    state.selectedFilter,
                    state.favoriteIds,
                    state.albumHideCloudIds,
                ),
            )
        }
    }

    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(
                permissionState = when {
                    granted -> PermissionState.Granted
                    permanentlyDenied -> PermissionState.PermanentlyDenied
                    else -> PermissionState.Denied
                }
            )
        }
        // MediaStore ContentObserver does NOT fire on a permission grant; explicitly trigger a
        // re-query so the gallery populates immediately without requiring an app restart.
        if (granted) {
            localRepo.notifyPermissionChanged()
            // Also kick off the first sync, which couldn't read local media before the grant.
            viewModelScope.launch {
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                doSync(userId)
            }
        }
    }

    fun refresh() {
        if (!networkObserver.isOnline.value) {
            // Offline: cached state from observeGallery() keeps the grid rendered. Pop the
            // spinner off immediately so pull-to-refresh doesn't hang.
            _uiState.update { it.copy(isRefreshing = false, isSyncing = false) }
            return
        }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isRefreshing = true, isSyncing = true) }
            runCatching {
                cloudRepo.refreshCloudPhotos(userId)
                reconcile(userId).collect {}
                upload(userId)
            }.onFailure { e ->
                // Silent-swallow for network-shaped failures — the avatar offline dot
                // and the dismissible offline banner already tell the user what's wrong;
                // a popup on top would be redundant noise. Only non-network exceptions
                // (e.g. crypto / DB / quota) reach the error sheet, and those go through
                // sanitizeErrorMessage so a server HTML page or PII-bearing message can't
                // leak into the UI.
                if (!looksLikeNetworkError(e)) {
                    _uiState.update { it.copy(error = sanitizeErrorMessage(e.message)) }
                }
            }
            _uiState.update { it.copy(isRefreshing = false, isSyncing = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── Multi-select ──────────────────────────────────────────────────────────

    fun toggleSelection(item: GalleryItem) {
        _uiState.update { state ->
            val newSet = if (item in state.selectedItems)
                state.selectedItems - item
            else
                state.selectedItems + item
            state.copy(selectedItems = newSet)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedItems = emptySet(), multiDeleteState = MultiDeleteState.Idle) }
    }

    /** Tri-state toggle for a date-header group: if every item in [items] is already selected,
     *  drop the whole group; otherwise add the missing ones so a partially-selected group fills
     *  to complete on the first tap. */
    fun toggleGroup(items: List<GalleryItem>) {
        if (items.isEmpty()) return
        _uiState.update { state ->
            val allSelected = items.all { it in state.selectedItems }
            val newSet = if (allSelected) state.selectedItems - items.toSet()
            else state.selectedItems + items
            state.copy(selectedItems = newSet)
        }
    }

    /** Holds the cloud-delete work that was deferred until the user confirms the Android 11+
     *  system trash dialog. Cleared on commit OR on cancel. */
    private var pendingPermissionResult: DeletePhotoUseCase.Result.NeedsMediaWritePermission? = null

    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Working) }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed("Not signed in")) }
                return@launch
            }
            val result = deletePhotoUseCase(
                userId          = userId,
                items           = items,
                freeUpSpace     = freeUpSpace,
                deleteFromCloud = deleteFromCloud,
            )
            when (result) {
                is DeletePhotoUseCase.Result.Success -> {
                    _uiState.update { it.copy(
                        selectedItems    = emptySet(),
                        multiDeleteState = MultiDeleteState.Done,
                    ) }
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed ->
                    _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed("Could not delete from Drive")) }
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    pendingPermissionResult = result
                    _uiState.update { it.copy(
                        multiDeleteState    = MultiDeleteState.Idle,
                        pendingDeleteIntent = result.pendingIntent,
                    ) }
                }
            }
        }
    }

    fun clearPendingDeleteIntent() {
        // User canceled the system trash dialog — drop the deferred cloud work too. If a
        // hide-flow was in progress, also roll back the private copies (otherwise the photo
        // would end up BOTH in Hidden and in MediaStore — the worst of both worlds).
        pendingPermissionResult = null
        if (pendingHidePrivateUris.isNotEmpty()) rollbackPendingHide()
        _uiState.update { it.copy(pendingDeleteIntent = null) }
    }


    fun onDeletePermissionGranted() {
        val pending = pendingPermissionResult
        pendingPermissionResult = null
        viewModelScope.launch {
            // Run the deferred cloud delete first; surface failure as a multi-delete error.
            val cloudResult = if (pending != null) {
                val userId = accountManager.getPrimaryUserId().first()
                if (userId == null) {
                    DeletePhotoUseCase.Result.CloudDeleteFailed
                } else {
                    deletePhotoUseCase.completeAfterPermissionGranted(
                        userId          = userId,
                        cloudLinkIds    = pending.cloudLinkIds,
                        items           = pending.itemsBeingDeleted,
                        freeUpSpace     = pending.freeUpSpace,
                        hide            = pending.hide,
                    )
                }
            } else DeletePhotoUseCase.Result.Success
            if (cloudResult is DeletePhotoUseCase.Result.CloudDeleteFailed) {
                _uiState.update { it.copy(
                    multiDeleteState    = MultiDeleteState.Failed("Could not delete from Drive"),
                    pendingDeleteIntent = null,
                ) }
                return@launch
            }
            // If a multi-hide flow was waiting on this permission, commit the private URIs now
            // so the photos start appearing in the Hidden album. No-op if no hide was pending.
            if (pendingHidePrivateUris.isNotEmpty()) commitPendingHide()
            _uiState.update { it.copy(
                selectedItems       = emptySet(),
                multiDeleteState    = MultiDeleteState.Done,
                pendingDeleteIntent = null,
            ) }
        }
    }

    fun resetMultiDeleteState() {
        _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Idle) }
    }

    /** Private URIs collected during a multi-select hide, committed once the system delete OK's. */
    private var pendingHidePrivateUris: List<String> = emptyList()

    /**
     * Batch-hide every currently-selected gallery item. Copies each local file to app-private
     * hidden storage, then routes through [DeletePhotoUseCase] with `freeUpSpace=true,
     * deleteFromCloud=false` so the MediaStore originals get removed (one system-trash dialog
     * on Android 11+, all URIs in a single request). Cloud-only items in the selection are
     * skipped — they have no local file to hide.
     */
    fun hideSelected() {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            // Hide writes to its own multiHideState so the selection bar's Delete
            // (trash) icon does not flash a spinner alongside the More-menu spinner.
            // The two operations end with a destructive step but the bar surface
            // should reflect "the action the user just tapped".
            _uiState.update { it.copy(multiHideState = MultiDeleteState.Working) }
            val hideables = items.filter { it is GalleryItem.LocalOnly || it is GalleryItem.Synced }
            if (hideables.isEmpty()) {
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed("No local photos to hide")) }
                return@launch
            }
            // Step 1: copy every local file into app-private hidden storage.
            // Each entry is paired with the cloud linkId (when the item was Synced) so
            // unhide can later restore the SyncState row → reconcile pairs by ID and
            // the photo doesn't get re-uploaded as a duplicate Drive entry.
            val collected = mutableListOf<String>()
            val cloudIdMapping = mutableListOf<Pair<String, String>>() // hiddenUri → cloudLinkId
            for (item in hideables) {
                val uri: String; val name: String; val mime: String; val dateTaken: Long; val cloudLinkId: String?
                when (item) {
                    is GalleryItem.LocalOnly -> {
                        uri = item.local.uri; name = item.local.displayName
                        mime = item.local.mimeType; dateTaken = item.local.dateTaken; cloudLinkId = null
                    }
                    is GalleryItem.Synced -> {
                        uri = item.local.uri; name = item.local.displayName
                        mime = item.local.mimeType; dateTaken = item.local.dateTaken; cloudLinkId = item.cloud.linkId
                    }
                    else -> continue
                }
                val privateUri = hiddenStorage.store(uri, name, mime, captureTimeMs = dateTaken)
                if (privateUri != null) {
                    collected += privateUri
                    if (cloudLinkId != null) cloudIdMapping += (privateUri to cloudLinkId)
                }
            }
            // Persist the hidden→cloudId pairs so unhide can pick up where we left off
            // even if the process gets killed between hide and unhide. Encoded as
            // "hiddenUri|cloudLinkId" tokens in the existing StringSet pref.
            if (cloudIdMapping.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    val existing = prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] =
                        existing + cloudIdMapping.map { (h, c) -> "$h|$c" }
                }
            }
            if (collected.isEmpty()) {
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed("Could not copy to Hidden")) }
                return@launch
            }
            pendingHidePrivateUris = collected

            // Step 2: delete the MediaStore originals (one system-trash dialog on Android 11+).
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                rollbackPendingHide()
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed("Not signed in")) }
                return@launch
            }
            val result = deletePhotoUseCase(
                userId          = userId,
                items           = hideables,
                freeUpSpace     = true,
                deleteFromCloud = false,
                hide            = true,
            )
            when (result) {
                is DeletePhotoUseCase.Result.Success -> {
                    commitPendingHide()
                    _uiState.update { it.copy(
                        selectedItems          = emptySet(),
                        multiHideState         = MultiDeleteState.Done,
                        hideCloudNoticePending = cloudIdMapping.isNotEmpty(),
                    ) }
                }
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    // Stash both the delete intent AND the hide-private URIs — onHidePermissionGranted
                    // commits them once the user confirms the system dialog.
                    pendingPermissionResult = result
                    _uiState.update { it.copy(
                        multiHideState      = MultiDeleteState.Idle,
                        pendingDeleteIntent = result.pendingIntent,
                    ) }
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                    // Shouldn't happen with deleteFromCloud=false — undo the hide if it does.
                    rollbackPendingHide()
                    _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed("Could not move to Hidden")) }
                }
            }
        }
    }

    fun resetMultiHideState() {
        _uiState.update { it.copy(multiHideState = MultiDeleteState.Idle, hideCloudNoticePending = false) }
    }

    /** Persist the pending hide-URIs into HIDDEN_PHOTO_URIS and clear the pending state. */
    private fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_PHOTO_URIS] = current + uris
            }
        }
    }

    /** Roll back the private copies that were created but never committed (rare error path). */
    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        for (u in uris) hiddenStorage.delete(u)
    }

    fun downloadSelected() {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Working(0, items.size)) }
            // Resolve per-photo album folder so cloud-album photos go to Pictures/<AlbumName>/
            // instead of bunching in a generic Proton Photos folder. AlbumService caches the
            // membership map for 5 min so repeated downloads don't re-fetch.
            val memberships: Map<String, String> = runCatching { cloudRepo.getAlbumMemberships(userId) }
                .getOrElse {
                    android.util.Log.w("GalleryVM", "getAlbumMemberships failed: ${it.message}")
                    emptyMap()
                }
                .mapValues { (_, name) -> eu.akoos.photos.util.ProtonPhotosStorage.sanitize(name) }
            val result = downloadPhotos.downloadGalleryItems(
                userId, items,
                folderName = "", // empty = save non-album photos directly into Pictures/ root
                folderByLinkId = memberships,
            ) { progress ->
                _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Working(progress.done, progress.total)) }
            }
            _uiState.update { it.copy(
                multiDownloadState = MultiDownloadState.Done(result.done - result.failed, result.failed),
                selectedItems = emptySet(),
            ) }
        }
    }

    fun resetMultiDownloadState() {
        _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Idle) }
    }

    // ── Add-to-album multi-action ──────────────────────────────────────────────
    //
    // Routes a multi-select to a cloud album. Only Synced and CloudOnly items carry a Drive
    // linkId, so LocalOnly items in the selection are reported as skipped. Albums are
    // references-not-copies on Drive (the photo stays in the Photos root), so there is no
    // file movement and no MediaStore consent dialog.

    /**
     * Begin adding the current selection to a cloud album.
     *
     * @param albumLinkId Drive link ID of the target album.
     * @param albumName   Album display name, used only for the success snackbar.
     */
    fun addSelectedToAlbum(
        albumLinkId: String,
        albumName: String,
    ) {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Working) }
            val userId = accountManager.getPrimaryUserId().first()

            val cloudLinkIds = items.mapNotNull { item ->
                when (item) {
                    is GalleryItem.Synced    -> item.cloud.linkId
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
            // LocalOnly items have no Drive linkId to attach, so they can't join a cloud album
            // until they upload. Report them so the UI can show a partial-success snackbar.
            // (LocalOnly items with a backed-up twin surface as Synced, not here.)
            val skipped = items.count { it is GalleryItem.LocalOnly }

            if (cloudLinkIds.isNotEmpty() && userId == null) {
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Not signed in")) }
                return@launch
            }

            val result = runAddToAlbum(
                userId = userId,
                albumLinkId = albumLinkId,
                albumName = albumName,
                cloudLinkIds = cloudLinkIds,
                skipped = skipped,
            )
            _uiState.update { it.copy(
                selectedItems    = emptySet(),
                addToAlbumState  = result,
            ) }
        }
    }

    fun resetAddToAlbumState() {
        _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Idle) }
    }

    /** Cloud-add helper. Returns the terminal state for the UI. [skipped] is the count of
     *  selected items the album can't accept (local-only, not backed up yet); it flows
     *  through to [AddToAlbumState.Done] so the UI can show a partial-success snackbar. */
    private suspend fun runAddToAlbum(
        userId: me.proton.core.domain.entity.UserId?,
        albumLinkId: String,
        albumName: String,
        cloudLinkIds: List<String>,
        skipped: Int = 0,
    ): AddToAlbumState {
        var cloudAdded = 0
        var cloudFailed = 0
        if (cloudLinkIds.isNotEmpty()) {
            if (userId == null) return AddToAlbumState.Failed("Not signed in")
            try {
                val r = cloudRepo.addPhotosToAlbum(userId, albumLinkId, cloudLinkIds)
                cloudAdded = r.succeededLinkIds.size
                cloudFailed = r.failedLinkIds.size
                // Wake the Albums grid so the target album's cover + count refresh without a
                // manual pull-to-refresh now that it gained photos.
                if (cloudAdded > 0) albumListEvents.notifyChanged()
            } catch (e: Exception) {
                return AddToAlbumState.Failed(e.message ?: "Could not add to cloud album")
            }
        }

        if (cloudAdded == 0 && cloudFailed > 0) {
            return AddToAlbumState.Failed("Could not add to album")
        }
        return AddToAlbumState.Done(cloudAdded, 0, skipped, albumName)
    }

    /**
     * Inline create-album-then-add flow. Always creates a cloud album, adds the cloud-backed
     * items in the selection, then sets the first successfully-added photo as the album cover
     * so the new card isn't blank in the Albums grid.
     */
    fun createAlbumThenAddSelected(name: String) {
        val trimmed = ProtonPhotosStorage.sanitize(name)
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Album name cannot be empty")) }
            return
        }
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Select photos first")) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Working) }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Not signed in")) }
                return@launch
            }
            val newAlbumLinkId = try {
                cloudRepo.createDriveAlbum(userId, trimmed).linkId
            } catch (e: Exception) {
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(
                    "Could not create album: ${e.message}",
                )) }
                return@launch
            }

            val cloudLinkIds = items.mapNotNull { item ->
                when (item) {
                    is GalleryItem.Synced    -> item.cloud.linkId
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
            // LocalOnly items can't join a cloud album until they upload — reported as skipped.
            val skipped = items.count { it is GalleryItem.LocalOnly }

            val addResult = if (cloudLinkIds.isNotEmpty()) {
                try {
                    cloudRepo.addPhotosToAlbum(userId, newAlbumLinkId, cloudLinkIds)
                } catch (e: Exception) {
                    _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(
                        e.message ?: "Could not add to album",
                    )) }
                    return@launch
                }
            } else null

            val cloudAdded = addResult?.succeededLinkIds?.size ?: 0
            // Set the first SUCCEEDED photo as cover — cover failure must not fail the add.
            addResult?.succeededLinkIds?.firstOrNull()?.let { firstSucceeded ->
                runCatching { cloudRepo.setAlbumCover(userId, newAlbumLinkId, firstSucceeded) }
            }
            albumListEvents.notifyChanged()
            _uiState.update { it.copy(
                selectedItems    = emptySet(),
                addToAlbumState  = AddToAlbumState.Done(cloudAdded, 0, skipped, trimmed),
            ) }
        }
    }

    // ── Batch EXIF strip ──────────────────────────────────────────────────────
    //
    // Re-uses the in-place strip path the per-photo metadata sheet drives via
    // [ExifHelper.stripFieldsInPlace]. Cloud-only items in the selection are skipped — mutating
    // cloud bytes needs a re-upload pipeline. Local files the OS refuses to write in-place
    // (foreign owner under scoped storage on Android 10+) are also skipped and surfaced as the
    // "skipped" count in the snackbar so the user knows some files were untouched.
    //
    // Strip-config is read from the Settings preferences (STRIP_GPS / STRIP_CAMERA_INFO /
    // STRIP_TIMESTAMP / STRIP_SOFTWARE_INFO) so the user controls *what* gets stripped from
    // one place. When no strip flag is set we surface a Failed state instead of silently
    // doing nothing.

    /** Strip config + counters carried across an Android 10+ write-permission dialog so the
     *  granted retry strips the deferred foreign URIs and folds them into the final tally. */
    private var pendingStripConfig: MetadataStripConfig? = null
    private var pendingStripUris: List<String> = emptyList()
    private var pendingStripStripped = 0
    private var pendingStripSkipped = 0

    fun stripMetadataSelected() {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(multiStripState = MultiStripState.Working) }
            val prefs = context.settingsDataStore.data.first()
            val config = MetadataStripConfig(
                stripGps          = prefs[SettingsKeys.STRIP_GPS] == true,
                stripCameraInfo   = prefs[SettingsKeys.STRIP_CAMERA_INFO] == true,
                stripTimestamp    = prefs[SettingsKeys.STRIP_TIMESTAMP] == true,
                stripSoftwareInfo = prefs[SettingsKeys.STRIP_SOFTWARE_INFO] == true,
            )
            if (config.isNoOp) {
                _uiState.update { it.copy(multiStripState = MultiStripState.Failed(
                    "Enable a metadata category in Settings → Privacy first",
                )) }
                return@launch
            }
            // Cloud-only items get bucketed straight into "skipped" since we can't mutate the
            // cloud bytes here. Synced and LocalOnly both have a writable local URI we can try.
            val localUris = mutableListOf<String>()
            var skippedCloud = 0
            for (item in items) {
                when (item) {
                    is GalleryItem.LocalOnly -> localUris += item.local.uri
                    is GalleryItem.Synced    -> localUris += item.local.uri
                    is GalleryItem.CloudOnly -> skippedCloud++
                }
            }
            runStripPass(config, localUris, baseStripped = 0, baseSkipped = skippedCloud)
        }
    }

    /**
     * Strips [uris] in place, then either finishes (Done) or — when some URIs are foreign files
     * the OS refuses to write — defers them into a single [MediaStore.createWriteRequest] and
     * surfaces [GalleryUiState.pendingStripIntent]. [baseStripped] / [baseSkipped] seed the running
     * tally so a retry adds to (rather than overwrites) the counts from the first pass.
     */
    private suspend fun runStripPass(
        config: MetadataStripConfig,
        uris: List<String>,
        baseStripped: Int,
        baseSkipped: Int,
    ) {
        val needsPermission = mutableListOf<String>()
        val (stripped, failed) = withContext(Dispatchers.IO) {
            var ok = 0
            var failedCount = 0
            for (uri in uris) {
                when (ExifHelper.stripFieldsInPlace(context, uri, config)) {
                    is StripResult.Stripped       -> ok++
                    is StripResult.NeedsPermission -> needsPermission += uri
                    is StripResult.Failed         -> failedCount++
                }
            }
            ok to failedCount
        }
        val totalStripped = baseStripped + stripped
        val totalSkipped  = baseSkipped + failed

        // Android 11+: ask once for write access to every foreign URI in this pass, then replay
        // the strip on just those in [retryPendingStrip]. The consent intent is API 30 — older
        // scoped-storage devices have no batch-consent flow, so those count as skipped.
        if (needsPermission.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingStripConfig   = config
            pendingStripUris     = needsPermission
            pendingStripStripped = totalStripped
            pendingStripSkipped  = totalSkipped
            val pi = MediaStore.createWriteRequest(
                context.contentResolver, needsPermission.map(Uri::parse),
            )
            _uiState.update { it.copy(
                multiStripState   = MultiStripState.Idle,
                pendingStripIntent = pi,
            ) }
            return
        }
        _uiState.update { it.copy(
            selectedItems    = emptySet(),
            multiStripState  = MultiStripState.Done(totalStripped, totalSkipped + needsPermission.size),
        ) }
    }

    /** Re-runs the strip on the foreign URIs after the user granted the write-permission dialog. */
    fun retryPendingStrip() {
        val config = pendingStripConfig ?: return
        val uris = pendingStripUris
        val baseStripped = pendingStripStripped
        val baseSkipped = pendingStripSkipped
        clearPendingStripState()
        viewModelScope.launch {
            _uiState.update { it.copy(multiStripState = MultiStripState.Working, pendingStripIntent = null) }
            runStripPass(config, uris, baseStripped, baseSkipped)
        }
    }

    /** User canceled the write-permission dialog — count the deferred URIs as skipped, no retry. */
    fun clearPendingStripIntent() {
        val baseStripped = pendingStripStripped
        val deferred = pendingStripUris.size + pendingStripSkipped
        clearPendingStripState()
        _uiState.update { it.copy(
            selectedItems     = emptySet(),
            multiStripState   = MultiStripState.Done(baseStripped, deferred),
            pendingStripIntent = null,
        ) }
    }

    private fun clearPendingStripState() {
        pendingStripConfig = null
        pendingStripUris = emptyList()
        pendingStripStripped = 0
        pendingStripSkipped = 0
    }

    fun resetMultiStripState() {
        _uiState.update { it.copy(multiStripState = MultiStripState.Idle) }
    }

    private fun applyFilter(
        items: List<GalleryItem>,
        filter: GalleryFilter,
        favoriteIds: Set<String> = emptySet(),
        albumHideCloudIds: Set<String> = emptySet(),
    ): List<GalleryItem> {
        // Album-membership filter only applies on the [GalleryFilter.All] view. Non-All tabs
        // (Favorites, Screenshots, Videos, …) bypass it — when the user explicitly picks a
        // category, they want every matching item, regardless of whether the photo is also
        // in an album. The set is empty when the "Hide photos in albums" toggle is off,
        // so this is a no-op for users who don't enable the feature.
        val baseItems = if (filter == GalleryFilter.All) {
            items.filter { item ->
                val cloudLinkId = when (item) {
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.Synced -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
                val inCloudAlbum = cloudLinkId != null && cloudLinkId in albumHideCloudIds
                !inCloudAlbum
            }
        } else items
        return when (filter) {
            GalleryFilter.All -> baseItems
            GalleryFilter.Favorites -> baseItems.filter { item ->
                // Favorite = locally flagged (DataStore) OR has the Drive Favorite tag (id 0).
                val localId = when (item) {
                    is GalleryItem.LocalOnly -> item.local.uri
                    is GalleryItem.Synced    -> item.local.uri
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                }
                val localFlag = localId in favoriteIds
                val cloudFlag = CategorizeItem.belongsTo(item, tagId = 0)
                localFlag || cloudFlag
            }
            // All other tag filters: server-side tags win when set, but for freshly-uploaded photos
            // and local-only items we also try a client-side heuristic (mime type, filename pattern,
            // aspect ratio, bucket name) so Videos / Screenshots / Panoramas / RAW work immediately.
            else -> {
                val tagId = filter.tagId ?: return baseItems
                baseItems.filter { CategorizeItem.belongsTo(it, tagId) }
            }
        }
    }

    private fun applyContentFilter(items: List<GalleryItem>, filter: ContentFilter): List<GalleryItem> {
        var result = items

        // Sync status
        result = when (filter.syncStatus) {
            SyncStatusFilter.All      -> result
            SyncStatusFilter.LocalOnly -> result.filterIsInstance<GalleryItem.LocalOnly>()
            SyncStatusFilter.BackedUp  -> result.filter { it is GalleryItem.Synced || it is GalleryItem.CloudOnly }
        }

        // Media type
        result = when (filter.mediaType) {
            MediaType.All        -> result
            MediaType.PhotosOnly -> result.filter { item ->
                val mime = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced    -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mime.startsWith("image/")
            }
            MediaType.VideosOnly -> result.filter { item ->
                val mime = when (item) {
                    is GalleryItem.LocalOnly -> item.local.mimeType
                    is GalleryItem.Synced    -> item.local.mimeType
                    is GalleryItem.CloudOnly -> item.cloud.mimeType
                }
                mime.startsWith("video/")
            }
        }

        // Date
        if (filter.year != null || filter.month != null) {
            result = result.filter { item ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = item.captureTimeMs }
                val yearMatch  = filter.year  == null || cal.get(java.util.Calendar.YEAR) == filter.year
                val monthMatch = filter.month == null || (cal.get(java.util.Calendar.MONTH) + 1) == filter.month
                yearMatch && monthMatch
            }
        }

        return result
    }
}
