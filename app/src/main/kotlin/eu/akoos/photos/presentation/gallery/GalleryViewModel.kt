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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val cloudRepo: DrivePhotoRepository,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    /** True when the device has a validated internet connection. Drives the avatar
     *  offline badge in [GalleryScreen] and gates every cloud-side refresh below. */
    val isOnline: StateFlow<Boolean> = networkObserver.isOnline

    /** Walks the Throwable cause chain looking for a network-layer failure so we can
     *  swallow it silently instead of surfacing a useless error to the user. */
    private fun looksLikeNetworkError(t: Throwable?): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val n = cur.javaClass.name
            if (n.contains("UnknownHost") || n.contains("SocketTimeout") ||
                n.contains("SocketException") || n.contains("SSLException") ||
                n.contains("InterruptedIOException")) return true
            cur = cur.cause
        }
        return false
    }

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

            combine(
                getGalleryItems.invoke(userId),
                hiddenUrisFlow,
                hiddenCloudLinkIdsFlow,
            ) { items, hiddenUris, hiddenCloudLinkIds ->
                Triple(items, hiddenUris, hiddenCloudLinkIds)
            }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { (allItems, hiddenUris, hiddenCloudLinkIds) ->
                    val items = allItems.filter { item ->
                        val uri = when (item) {
                            is GalleryItem.LocalOnly -> item.local.uri
                            is GalleryItem.Synced -> item.local.uri
                            is GalleryItem.CloudOnly -> null
                        }
                        // Only filter by local-side hide; the cloud counterpart stays in
                        // the listing with a dim overlay. Surfaced via a dim overlay + eye
                        // badge in PhotoCell (see hiddenCloudLinkIds usage in the cell).
                        // CloudOnly items intentionally pass through; the dim overlay is
                        // enough.
                        uri == null || uri !in hiddenUris
                    }
                    val pending = items.count { it is GalleryItem.LocalOnly }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            items = items,
                            filteredItems = applyFilter(applyContentFilter(items, state.contentFilter), state.selectedFilter, state.favoriteIds),
                            pendingUploadCount = pending,
                            hiddenCloudLinkIds = hiddenCloudLinkIds,
                        )
                    }
                }
        }
    }

    private fun syncOnLaunch() {
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
                filteredItems = applyFilter(applyContentFilter(state.items, state.contentFilter), filter, state.favoriteIds),
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
                filteredItems = applyFilter(applyContentFilter(state.items, filter), state.selectedFilter, state.favoriteIds),
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
                if (!looksLikeNetworkError(e)) {
                    _uiState.update { it.copy(error = e.message) }
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
            _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Working) }
            val hideables = items.filter { it is GalleryItem.LocalOnly || it is GalleryItem.Synced }
            if (hideables.isEmpty()) {
                _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed("No local photos to hide")) }
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
                _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed("Could not copy to Hidden")) }
                return@launch
            }
            pendingHidePrivateUris = collected

            // Step 2: delete the MediaStore originals (one system-trash dialog on Android 11+).
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                rollbackPendingHide()
                _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed("Not signed in")) }
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
                        selectedItems    = emptySet(),
                        multiDeleteState = MultiDeleteState.Done,
                    ) }
                }
                is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                    // Stash both the delete intent AND the hide-private URIs — onHidePermissionGranted
                    // commits them once the user confirms the system dialog.
                    pendingPermissionResult = result
                    _uiState.update { it.copy(
                        multiDeleteState    = MultiDeleteState.Idle,
                        pendingDeleteIntent = result.pendingIntent,
                    ) }
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed -> {
                    // Shouldn't happen with deleteFromCloud=false — undo the hide if it does.
                    rollbackPendingHide()
                    _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed("Could not move to Hidden")) }
                }
            }
        }
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
    // Routes a multi-select to an album. Selection items can be three types:
    //   - CloudOnly   → cloud-album add only (DrivePhotoRepository.addPhotosToAlbum)
    //   - LocalOnly   → virtual-membership append (DataStore only — no file movement)
    //   - Synced      → both legs (cloud add + virtual-membership append)
    //
    // No MediaStore consent dialog: add-to-album is a DataStore append, not a file move.

    /**
     * Begin adding the current selection to an album.
     *
     * @param albumLinkId Drive link ID when the target is a cloud (or merged) album, else null.
     * @param albumName   Bucket name for the local-move leg. For a brand-new album this is the
     *                    name the user just typed; for an existing target use the existing name.
     * @param targetIsLocalBucket When true, every Synced/LocalOnly item also gets its MediaStore
     *                            file moved into the bucket. False = "cloud-only target" — the
     *                            local file stays where it is.
     */
    fun addSelectedToAlbum(
        albumLinkId: String?,
        albumName: String,
        targetIsLocalBucket: Boolean,
    ) {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        val sanitizedName = ProtonPhotosStorage.sanitize(albumName).ifEmpty { albumName }
        viewModelScope.launch {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Working) }
            val userId = accountManager.getPrimaryUserId().first()

            // Split selection into cloud-side and local-side work.
            val cloudLinkIds = items.mapNotNull { item ->
                when (item) {
                    is GalleryItem.Synced    -> item.cloud.linkId
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
            }
            val localUris: List<Pair<Uri, String>> = if (!targetIsLocalBucket) emptyList() else
                items.mapNotNull { item ->
                    when (item) {
                        is GalleryItem.Synced    -> Uri.parse(item.local.uri) to item.local.mimeType
                        is GalleryItem.LocalOnly -> Uri.parse(item.local.uri) to item.local.mimeType
                        is GalleryItem.CloudOnly -> null
                    }
                }

            // Count items the chosen target can't accept so the UI can surface a partial-success
            // warning instead of silently dropping them.
            //   - Target is a local bucket (albumLinkId == null) → CloudOnly items are skipped
            //     because they have no MediaStore URI to fold into the virtual-membership map.
            //   - Target is a Drive album (albumLinkId != null) → LocalOnly items are skipped
            //     because they have no Drive linkId to attach. (LocalOnly items with a backed-up
            //     twin would surface as Synced instead, so they don't fall into this bucket.)
            val skipped = if (albumLinkId == null) {
                items.count { it is GalleryItem.CloudOnly }
            } else {
                items.count { it is GalleryItem.LocalOnly }
            }

            // If a cloud add is needed but we don't have a userId or albumLinkId, fail early.
            if (cloudLinkIds.isNotEmpty() && albumLinkId != null && userId == null) {
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Not signed in")) }
                return@launch
            }

            val result = runAddToAlbum(
                userId = userId,
                albumLinkId = albumLinkId,
                albumName = sanitizedName,
                cloudLinkIds = cloudLinkIds,
                localUris = localUris,
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

    /** Cloud-add + local-bucket-move helper used by both the immediate path and the post-consent
     *  resume. Returns the terminal state for the UI. [skipped] is the pre-computed count of
     *  items the chosen target couldn't accept (see caller); it's passed through to the
     *  terminal [AddToAlbumState.Done] so the UI can show a partial-success snackbar. */
    private suspend fun runAddToAlbum(
        userId: me.proton.core.domain.entity.UserId?,
        albumLinkId: String?,
        albumName: String,
        cloudLinkIds: List<String>,
        localUris: List<Pair<Uri, String>>,
        skipped: Int = 0,
    ): AddToAlbumState {
        // Cloud leg
        var cloudAdded = 0
        var cloudFailed = 0
        if (albumLinkId != null && cloudLinkIds.isNotEmpty()) {
            if (userId == null) return AddToAlbumState.Failed("Not signed in")
            try {
                val r = cloudRepo.addPhotosToAlbum(userId, albumLinkId, cloudLinkIds)
                cloudAdded = r.succeededLinkIds.size
                cloudFailed = r.failedLinkIds.size
            } catch (e: Exception) {
                return AddToAlbumState.Failed(e.message ?: "Could not add to cloud album")
            }
        }

        // Local leg — *virtual* album membership only. We deliberately DO NOT move the file
        // on disk anymore, because:
        //
        //   1. A RELATIVE_PATH update triggers MediaProvider to re-scan the file's metadata
        //      from disk and overwrite DATE_TAKEN with the EXIF value (or NULL if missing).
        //   2. MediaProvider on Q+ refuses to let an unprivileged app write DATE_TAKEN for a
        //      file it doesn't own — it logs "W MediaProvider: Ignoring mutation of datetaken
        //      from <pkg>" and silently drops that column from our ContentValues update.
        //
        // The combined effect was: every "Add to album" of an existing camera photo collapsed
        // its capture date to today. Cloud albums already work as references-not-copies
        // (photos stay in the Photos root), so we mirror that here: we append the URIs to a
        // DataStore-backed virtual membership map and let `AlbumsViewModel.observeLocalAlbums`
        // assemble the on-screen album by combining real buckets + virtual entries.
        //
        // The file stays in its original bucket (DCIM/Camera, etc.) with DATE_TAKEN intact.
        var localMoved = 0
        val localFailed = 0
        if (localUris.isNotEmpty()) {
            try {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] ?: emptySet()
                    val additions = localUris.map { (uri, _) -> "$albumName||$uri" }
                    prefs[SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP] = current + additions
                }
                localMoved = localUris.size
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // DataStore write failed — count as failed so the UI surfaces an error.
                // (Caller still records the manual-album marker below so the album shows up.)
            }
        }

        // Persist the bucket name as a manual local album marker so it shows up even if every
        // file move failed (so the user still sees their newly-typed bucket).
        if (localUris.isNotEmpty() || albumLinkId == null) {
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
                prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = current + albumName
            }
        }

        // Cloud-mirror-by-name: if some of the local URIs are backed up (have a cloud
        // counterpart in SyncStateRepository), also add them to the matching cloud album.
        // Run *after* the local DataStore write so the local state is authoritative.
        // Best-effort, silent on failure — the local virtual membership is what drives the UI.
        if (userId != null && localUris.isNotEmpty()) {
            mirrorLocalAddToCloudByName(userId, albumName, localUris, alreadyAddedLinkIds = cloudLinkIds.toSet())
        }

        // Failure summary — surface a string when nothing made it across either leg.
        val nothingHappened = cloudAdded == 0 && localMoved == 0 &&
            (cloudFailed > 0 || localFailed > 0)
        if (nothingHappened) {
            return AddToAlbumState.Failed("Could not add to album")
        }
        return AddToAlbumState.Done(cloudAdded, localMoved, skipped, albumName)
    }

    /**
     * For each local URI in [localUris], look up the backing cloud fileId via
     * [SyncStateRepository.getByUri]. Photos that were already added via [albumLinkId] on the
     * cloud leg ([alreadyAddedLinkIds]) are skipped to avoid double-add. If any cloud ids
     * remain, find the matching cloud album by name (case-insensitive) or create it, then
     * call addPhotosToAlbum. Every failure is swallowed — the visible local result already
     * reflects success, and the next album-list refresh will reconcile any cloud divergence.
     */
    private suspend fun mirrorLocalAddToCloudByName(
        userId: me.proton.core.domain.entity.UserId,
        albumName: String,
        localUris: List<Pair<Uri, String>>,
        alreadyAddedLinkIds: Set<String>,
    ) {
        try {
            // Resolve every local URI to its backing cloud linkId (null when not yet backed up).
            val cloudIds = localUris.mapNotNull { (uri, _) ->
                syncStateRepo.getByUri(uri.toString())?.cloudFileId
            }.filterNot { it in alreadyAddedLinkIds }
                .distinct()
            if (cloudIds.isEmpty()) return

            // Locate or create the cloud album. Case-insensitive lookup against the current
            // server list (one extra round-trip — acceptable for an add-to-album action).
            val albums = try { cloudRepo.loadAlbums(userId) } catch (_: Exception) { return }
            val matching = albums.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
            val targetLinkId = matching?.linkId ?: try {
                cloudRepo.createDriveAlbum(userId, albumName).linkId
            } catch (e: Exception) {
                android.util.Log.w("GalleryVM", "Cloud album create-on-mirror failed: ${e.message}")
                return
            }
            try {
                cloudRepo.addPhotosToAlbum(userId, targetLinkId, cloudIds)
            } catch (e: Exception) {
                android.util.Log.w("GalleryVM", "Cloud mirror add-to-album failed: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.w("GalleryVM", "Cloud mirror unexpected: ${e.message}")
        }
    }

    /**
     * Inline create-album-then-add flow. Creates the target album first (cloud when any selected
     * item is cloud-backed, else local-only marker), then forwards to [addSelectedToAlbum].
     */
    fun createAlbumThenAddSelected(name: String) {
        val trimmed = ProtonPhotosStorage.sanitize(name)
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Album name cannot be empty")) }
            return
        }
        val items = _uiState.value.selectedItems
        if (items.isEmpty()) {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Select photos first")) }
            return
        }
        val anyCloudBacked = items.any { it is GalleryItem.Synced || it is GalleryItem.CloudOnly }
        val anyLocal = items.any { it is GalleryItem.LocalOnly || it is GalleryItem.Synced }
        viewModelScope.launch {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Working) }
            var newAlbumLinkId: String? = null
            if (anyCloudBacked) {
                val userId = accountManager.getPrimaryUserId().first() ?: run {
                    _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed("Not signed in")) }
                    return@launch
                }
                try {
                    newAlbumLinkId = cloudRepo.createDriveAlbum(userId, trimmed).linkId
                } catch (e: Exception) {
                    _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(
                        "Could not create album: ${e.message}",
                    )) }
                    return@launch
                }
            }
            // Always register the marker so the local-bucket version shows in the Albums tab
            // even for cloud-only selections (so users can move local stuff into it later).
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] ?: emptySet()
                prefs[SettingsKeys.MANUAL_LOCAL_ALBUMS] = current + trimmed
            }
            // Now forward to the normal add flow. targetIsLocalBucket = true when there's any
            // local file in the selection that should move into the new bucket.
            addSelectedToAlbum(
                albumLinkId = newAlbumLinkId,
                albumName = trimmed,
                targetIsLocalBucket = anyLocal,
            )
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
            val (stripped, skippedLocal) = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                for (uri in localUris) {
                    if (ExifHelper.stripFieldsInPlace(context, uri, config)) ok++ else failed++
                }
                ok to failed
            }
            _uiState.update { it.copy(
                selectedItems    = emptySet(),
                multiStripState  = MultiStripState.Done(stripped, skippedCloud + skippedLocal),
            ) }
        }
    }

    fun resetMultiStripState() {
        _uiState.update { it.copy(multiStripState = MultiStripState.Idle) }
    }

    private fun applyFilter(
        items: List<GalleryItem>,
        filter: GalleryFilter,
        favoriteIds: Set<String> = emptySet(),
    ): List<GalleryItem> = when (filter) {
        GalleryFilter.All -> items
        GalleryFilter.Favorites -> items.filter { item ->
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
            val tagId = filter.tagId ?: return items
            items.filter { CategorizeItem.belongsTo(it, tagId) }
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
