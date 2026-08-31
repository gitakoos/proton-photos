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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.presentation.common.FavoriteActionState
import eu.akoos.photos.presentation.common.FavoriteWriter
import eu.akoos.photos.presentation.common.StripOutcome
import eu.akoos.photos.presentation.common.UndoAction
import eu.akoos.photos.presentation.common.favoriteTurnsOn
import eu.akoos.photos.presentation.common.withFavoriteSettled
import eu.akoos.photos.presentation.common.buildDeleteUndoAction
import eu.akoos.photos.presentation.common.buildHideUndoAction
import eu.akoos.photos.presentation.common.message
import eu.akoos.photos.presentation.common.stripOutcome
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.ObserveUser
import eu.akoos.photos.R
import eu.akoos.photos.data.hidden.HiddenCloudPhotos
import eu.akoos.photos.data.hidden.HiddenFolderProgress
import eu.akoos.photos.data.hidden.HiddenFolderRecords
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.hidden.HiddenVaultDecisions
import eu.akoos.photos.data.hidden.HiddenVaultDiagnostics
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.presentation.util.dayMonthYearFormat
import eu.akoos.photos.presentation.util.formatBytes
import eu.akoos.photos.presentation.util.monthYearFormat
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.model.PersonSummary
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
import eu.akoos.photos.domain.usecase.InvalidateStrippedLocationsUseCase
import eu.akoos.photos.domain.usecase.MIN_FACES_TO_SHOW_PERSON
import eu.akoos.photos.domain.usecase.MoveToFolderUseCase
import eu.akoos.photos.domain.usecase.PendingMove
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.looksLikeNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.util.computeOnThisDay
import eu.akoos.photos.util.isBatteryLow
import eu.akoos.photos.util.retryOnDbTear
import eu.akoos.photos.worker.SyncWorker
import java.util.Date
import javax.inject.Inject

/** Internal bag for the flows the gallery `combine` chain produces. Lives at top
 *  level so distinctUntilChanged downstream can do structural equality on it. */
private data class GallerySources(
    val items: List<GalleryItem>,
    val hiddenUris: Set<String>,
    val hiddenCloudLinkIds: Set<String>,
    val cloudInAlbum: Set<String>,
    val timelineExcludedBuckets: Set<String>,
)

/**
 * Bucket [items] into ("MMMM yyyy", items) pairs in first-seen order. One [SimpleDateFormat] and one
 * reused [Date] (set via [Date.setTime]) for the whole list — at 52k items the previous
 * `Date(item.captureTimeMs)` per element churned the allocator on every recompute. Label format,
 * locale and first-seen order are identical to the prior inline grouping, so the timeline is
 * unchanged. groupBy yields a LinkedHashMap, preserving encounter order.
 */
private fun groupByMonth(items: List<GalleryItem>): List<Pair<String, List<GalleryItem>>> {
    val fmt = monthYearFormat()
    val scratch = Date()
    return items
        .groupBy { item -> scratch.time = item.captureTimeMs; fmt.format(scratch) }
        .entries.map { it.key to it.value }
}

/**
 * Bucket [items] into ("d MMMM yyyy", items) pairs in first-seen order. Same one-format, one-Date
 * shape as [groupByMonth]; the "d MMMM yyyy" label matches the grid's prior inline Day format, so
 * the 3-column default headers are unchanged. groupBy yields a LinkedHashMap, preserving order.
 */
private fun groupByDay(items: List<GalleryItem>): List<Pair<String, List<GalleryItem>>> {
    val fmt = dayMonthYearFormat()
    val scratch = Date()
    return items
        .groupBy { item -> scratch.time = item.captureTimeMs; fmt.format(scratch) }
        .entries.map { it.key to it.value }
}

/** Output of the off-main gallery computation: the hidden/bucket-filtered list (→ items), the
 *  tab/content-filtered list (→ filteredItems), the local-only pending count, and the month + day +
 *  "On this day" groupings now produced here instead of inside Compose composition. */
private data class GalleryComputed(
    val items: List<GalleryItem>,
    val filtered: List<GalleryItem>,
    val monthGroups: List<Pair<String, List<GalleryItem>>>,
    val dayGroups: List<Pair<String, List<GalleryItem>>>,
    val onThisDay: List<Pair<Int, List<GalleryItem>>>,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val cloudRepo: DrivePhotoRepository,
    private val photoStreamService: PhotoStreamService,
    private val thumbnailUrlStore: eu.akoos.photos.data.repository.drive.ThumbnailUrlStore,
    private val localRepo: LocalMediaRepository,
    private val accountManager: AccountManager,
    private val observeUser: ObserveUser,
    private val deletePhotoUseCase: DeletePhotoUseCase,
    private val downloadPhotos: DownloadPhotosUseCase,
    private val reconcile: ReconcileSyncStateUseCase,
    private val upload: UploadPendingUseCase,
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val invalidateStrippedLocations: InvalidateStrippedLocationsUseCase,
    private val hiddenStorage: HiddenStorageManager,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val offlineStore: OfflineStorageManager,
    private val transferCenter: TransferCenter,
    private val undoController: eu.akoos.photos.presentation.common.UndoController,
    private val syncStateRepo: SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumPhotoMembershipDao: eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao,
    private val photoListingDao: eu.akoos.photos.data.db.dao.PhotoListingDao,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val updateOrchestrator: eu.akoos.photos.presentation.updater.UpdateOrchestrator,
    private val newsRepository: eu.akoos.photos.domain.repository.NewsRepository,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
    private val favoriteWriter: FavoriteWriter,
    private val cryptoServiceClient: eu.akoos.photos.crypto.CryptoServiceClient,
    private val faceDao: eu.akoos.photos.data.db.dao.FaceDao,
    private val observePeopleUseCase: eu.akoos.photos.domain.usecase.ObservePeopleUseCase,
    private val addPhotosToPersonUseCase: eu.akoos.photos.domain.usecase.AddPhotosToPersonUseCase,
    private val moveToFolder: MoveToFolderUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    /**
     * One-shot carrier for the multi-select share intent. A ViewModel can't call startActivity,
     * so [GalleryScreen] collects this and launches the chooser. replay=0 + single-buffer so a
     * paused screen never blocks the emit; the in-flight progress is tracked separately via
     * [GalleryUiState.multiShareState].
     */
    private val _shareIntent = MutableSharedFlow<android.content.Intent>(replay = 0, extraBufferCapacity = 1)
    val shareIntent: SharedFlow<android.content.Intent> = _shareIntent.asSharedFlow()

    /** One-shot count of photos successfully pinned by a "Make available offline" batch, so
     *  [GalleryScreen] can show a result snackbar. replay=0 + single-buffer mirrors [shareIntent];
     *  the optimistic pins drive the per-cell offline badge live, this only reports the outcome. */
    private val _offlineBatchResult = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    val offlineBatchResult: SharedFlow<Int> = _offlineBatchResult.asSharedFlow()

    /** Why a batch favourite did not reach every photo it was pressed for. A clean run says nothing
     *  (the hearts do), so only a refused write emits here. */
    private val _favoriteFailure = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val favoriteFailure: SharedFlow<String> = _favoriteFailure.asSharedFlow()

    /** Existing device folders the "Move to folder" picker offers as targets, grouped from the local
     *  photos already in the feed (device-only + synced), most-populated first, one row per bucket.
     *  The whole chain runs off-Main so regrouping a large library never touches the collector's
     *  thread, and it only recomputes when the item set genuinely changes (same-reference emissions
     *  short-circuit), so keeping it warm for the picker costs nothing between changes. */
    val moveTargetFolders: StateFlow<List<DeviceFolderChoice>> =
        uiState.map { it.items }
            .distinctUntilChanged()
            .map { items ->
                deviceFolderChoices(
                    items.mapNotNull { item ->
                        when (item) {
                            is GalleryItem.LocalOnly -> item.local
                            is GalleryItem.Synced -> item.local
                            is GalleryItem.CloudOnly -> null
                        }
                    },
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One-shot carrier for the system write-consent request a move needs when the selection holds a
     *  file the app does not own. [GalleryScreen] launches it and calls [onMovePermissionGranted] on
     *  approval; null the rest of the time. */
    private val _pendingMoveIntent = MutableStateFlow<IntentSender?>(null)
    val pendingMoveIntent: StateFlow<IntentSender?> = _pendingMoveIntent.asStateFlow()

    /** One-shot confirmation for a completed move, carrying the destination folder name for the
     *  snackbar. Mirrors [favoriteFailure]: replay=0 + single buffer so a paused screen never blocks. */
    private val _moveConfirmation = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val moveConfirmation: SharedFlow<String> = _moveConfirmation.asSharedFlow()

    /** True when the device has a validated internet connection. Drives the avatar
     *  offline badge in [GalleryScreen] and gates every cloud-side refresh below. */
    val isOnline: StateFlow<Boolean> = networkObserver.isOnline

    /** linkId → freshly-decrypted thumbnail `file://` URL, fed straight from the singleton
     *  [eu.akoos.photos.data.repository.drive.ThumbnailUrlStore]. The timeline projection no longer
     *  carries thumbnailUrl (a per-row write would re-emit the whole 50k list on every decrypt during
     *  a sustained scroll, the large-library OOM), so a cloud cell reads its URL from this map and
     *  repaints just itself when its own entry lands. Threaded into the grid alongside the other live
     *  per-cell sets. */
    val thumbnailUrls: StateFlow<Map<String, String>> = thumbnailUrlStore.urls

    /** DEBUG-only "usedMB / maxMB" Java-heap readout, sampled ~1/s, so a tester can watch memory while
     *  scrolling a large library and confirm the sustained-scroll heap stays flat. Empty (and never
     *  collected) in release; the overlay that reads it is BuildConfig.DEBUG-guarded too. */
    val debugHeapStat: StateFlow<String> =
        if (eu.akoos.photos.BuildConfig.DEBUG) {
            flow {
                while (true) {
                    val rt = Runtime.getRuntime()
                    val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
                    val maxMb = rt.maxMemory() / 1_048_576L
                    emit("${usedMb}/${maxMb} MB")
                    delay(1_000)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), "")
        } else {
            MutableStateFlow("").asStateFlow()
        }

    /** Persistent "an update is available" signal driving the avatar update dot. Stays lit across
     *  dialog dismissal and relaunch (see [UpdateOrchestrator.updateAvailable]); clears only when a
     *  check confirms the app is up to date. */
    val updateAvailable: StateFlow<Boolean> = updateOrchestrator.updateAvailable

    /** Unread-news signal driving the small dot on the avatar. True when the cached feed holds an
     *  entry the user has not opened yet, and always false while news is switched off. */
    val newsUnread: StateFlow<Boolean> = newsRepository.observeUnreadCount()
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Avatar dot tap → re-run a check, which re-opens the update dialog if an update is still
     *  available (the Activity's UpdaterHost renders it). Works after a relaunch where the in-memory
     *  pending update was lost but the dot is lit from the persisted marker. */
    fun openUpdateFromDot() {
        viewModelScope.launch { runCatching { updateOrchestrator.runManualCheck() } }
    }

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
    }
        .retryOnDbTear("GalleryDownloadedIds")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Atomic guard against re-entry into [doSync]. Read+write on [_uiState.value.isSyncing] is
     *  not race-safe — two coroutines (init's syncOnLaunch and observeFolderSettings) could both
     *  pass the `if (isSyncing) return` gate before either has set it to true. */
    private val syncInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // A photo stranded between hidden and visible is missing from THIS screen, and the person
        // looking for it has no reason to open the hidden area, which was the only place the repair
        // ran. It belongs where the absence shows.
        viewModelScope.launch { runCatching { hiddenVaultJournal.reconcile() } }
        observeGallery()
        observePendingUploadCount()
        observeUserInitial()
        syncOnLaunch()
        observeFolderSettings()
        observeGridPreferences()
        observeFavorites()
        observeOfflinePins()
        observeUndoRestores()
        observeBackgroundUploadProgress()
        observeActiveTransfers()
        observePrimaryUserId()
        observeHideInAlbums()
        observeHiddenAlbumIds()
        observePeople()
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
            accountManager.getPrimaryUserId().collect { userId ->
                primaryUserId = userId
                _uiState.update { it.copy(isSignedIn = userId != null) }
            }
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
    // The avatar's UP arrow has two independent sources: the backup pipeline (upload.progress) and
    // any TransferCenter UPLOAD transfer (an editor edit-upload). Each is tracked separately and the
    // two are ORed into hasActiveUpload, so a frame from one source never clears what the other set.
    private var backupUploading = false
    private var transferUploading = false

    /** Drives the avatar's DOWN arrow from the TransferCenter (downloads + offline pins), and folds a
     *  TransferCenter UPLOAD transfer into the UP arrow alongside the backup pipeline. */
    private fun observeActiveTransfers() {
        viewModelScope.launch {
            transferCenter.active.collect { active ->
                val down = active.any {
                    it.kind == TransferCenter.Kind.DOWNLOAD || it.kind == TransferCenter.Kind.OFFLINE
                }
                transferUploading = active.any { it.kind == TransferCenter.Kind.UPLOAD }
                _uiState.update {
                    it.copy(hasActiveDownload = down, hasActiveUpload = backupUploading || transferUploading)
                }
            }
        }
    }

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
                    eu.akoos.photos.domain.usecase.UploadStatus.WaitingForWifi -> false
                    eu.akoos.photos.domain.usecase.UploadStatus.PreparingBackup -> false
                    eu.akoos.photos.domain.usecase.UploadStatus.StorageFull -> false
                }
                backupUploading = syncing
                _uiState.update {
                    it.copy(
                        isSyncing = syncing,
                        // The up arrow ORs the backup pipeline with any TransferCenter UPLOAD (an
                        // editor edit-upload); downloads/offline come from observeActiveTransfers.
                        hasActiveUpload = backupUploading || transferUploading,
                        uploadDoneIdx = if (syncing) evt.doneIdx else 0,
                        uploadTotalCount = if (syncing) evt.totalCount else 0,
                    )
                }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.FAVORITE_IDS] ?: emptySet() }
                .distinctUntilChanged()
                .collect { favIds ->
                    val state = _uiState.value
                    // Must carry the live album-hide set, otherwise re-filtering here (on any
                    // favourites change) would drop it and un-hide every album photo until the next
                    // gallery emission re-applied it — a visible flicker while "Hide photos in
                    // albums" is on.
                    val filtered = applyFilter(
                        applyContentFilter(state.items, state.contentFilter),
                        state.selectedFilter,
                        favIds,
                        state.albumHideCloudIds,
                        state.offlinePinIds,
                    )
                    _uiState.update { it.copy(favoriteIds = favIds, filteredItems = filtered) }
                    recomputeMonthGroups(filtered)
                }
        }
    }

    /** Offline-pinned cloud linkIds drive the per-cell offline badge everywhere and the Offline
     *  filter's membership. The badge tracks [GalleryUiState.offlinePinIds] directly, so a pin/un-pin
     *  reflects live without re-filtering; applyFilter only needs re-running while the Offline tab
     *  itself is showing (otherwise its list would go stale on an un-pin). */
    private fun observeOfflinePins() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet() }
                .distinctUntilChanged()
                .collect { ids ->
                    val state = _uiState.value
                    if (state.selectedFilter == GalleryFilter.Offline) {
                        val filtered = applyFilter(
                            applyContentFilter(state.items, state.contentFilter),
                            state.selectedFilter,
                            state.favoriteIds,
                            state.albumHideCloudIds,
                            ids,
                        )
                        _uiState.update { it.copy(offlinePinIds = ids, filteredItems = filtered) }
                        recomputeMonthGroups(filtered)
                    } else {
                        _uiState.update { it.copy(offlinePinIds = ids) }
                    }
                }
        }
    }

    /**
     * People (face clusters) for the current account, surfaced as the round-thumbnail People rail on
     * the Photos tab. Collected only while the master AI switch is on; an off switch, a signed-out
     * account, or no indexed faces yet all resolve to an empty list, so the People chip stays hidden
     * and the feature costs nothing on the common (AI-off) path. Recombined with the item set so each
     * cover face box can be normalised against its cover photo's own dimensions for a face crop; the
     * item set changes only when photos are added or removed, not on a thumbnail decrypt, so this does
     * not rebuild while scrolling. If the list empties while a person filter is active (the switch was
     * turned off), the filter is cleared so the feed does not stay narrowed with no way back.
     */
    private fun observePeople() {
        val peopleFlow = combine(
            context.settingsDataStore.data
                .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.FACE_ENABLED] == true }
                .distinctUntilChanged(),
            accountManager.getPrimaryUserId(),
        ) { aiOn, userId -> aiOn to userId }
            .flatMapLatest { (aiOn, userId) ->
                if (!aiOn || userId == null) flowOf(emptyList<PersonSummary>())
                else observePeopleUseCase(userId, uiState.map { it.items }.distinctUntilChanged())
            }
        viewModelScope.launch {
            peopleFlow.collectLatest { summaries ->
                // Only NAMED people ride the timeline rail, so unnamed and junk clusters never appear
                // as filter chips; unnamed clusters are named from the People page.
                val uiPeople = summaries
                    .filter { !it.displayName.isNullOrBlank() }
                    .mapNotNull { it.toPersonUi() }
                val cleared = uiPeople.isEmpty() && _uiState.value.selectedPersonId != null
                _uiState.update { st ->
                    if (cleared) st.copy(people = uiPeople, selectedPersonId = null, personPhotoKeys = emptySet())
                    else st.copy(people = uiPeople)
                }
                if (cleared) recomputeFilteredForPerson()
            }
        }
    }

    /** Adapt a domain [PersonSummary] to the gallery's [PersonUi]. A summary carries a nullable cover
     *  key for reuse by screens that tolerate one, so a null cover is skipped here; [observePeopleUseCase]
     *  already drops people with no resolvable cover, so the People rail never loses a tile to this. */
    private fun PersonSummary.toPersonUi(): PersonUi? {
        val cover = coverPhotoKey ?: return null
        return PersonUi(
            personId = personId,
            displayName = displayName,
            coverPhotoKey = cover,
            faceBox = faceBox?.let { FaceBox(it.left, it.top, it.right, it.bottom) },
            faceCount = faceCount,
        )
    }

    /**
     * Filter the timeline to one person's photos, or clear the person filter when [personId] is null.
     * Loads the person's photo keys once into state and re-applies the filter; the membership test in
     * [applyFilter] then composes with every other active filter exactly like the Offline pinned set.
     */
    fun onPersonSelected(personId: Long?) {
        viewModelScope.launch {
            if (personId == null) {
                _uiState.update { it.copy(selectedPersonId = null, personPhotoKeys = emptySet()) }
                recomputeFilteredForPerson()
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val keys = try {
                faceDao.photoKeysForPerson(userId.id, personId).first().toSet()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet<String>()
            }
            _uiState.update { it.copy(selectedPersonId = personId, personPhotoKeys = keys) }
            recomputeFilteredForPerson()
        }
    }

    /** Re-run the full filter chain after the person selection changed, keeping the person membership
     *  (read inside [applyFilter]) in lockstep with the category, content, favourite and album filters. */
    private fun recomputeFilteredForPerson() {
        val state = _uiState.value
        val filtered = applyFilter(
            applyContentFilter(state.items, state.contentFilter),
            state.selectedFilter,
            state.favoriteIds,
            state.albumHideCloudIds,
            state.offlinePinIds,
        )
        _uiState.update { it.copy(filteredItems = filtered) }
        recomputeMonthGroups(filtered)
    }

    private fun observeGridPreferences() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { prefs ->
                    GridPrefs(
                        rememberLast = prefs[SettingsKeys.GRID_REMEMBER_LAST] ?: false,
                        defaultColumns = prefs[SettingsKeys.GRID_DEFAULT_COLUMNS] ?: GridZoom.DEFAULT_COLUMNS,
                        lastLevel = (prefs[SettingsKeys.GRID_LAST_LEVEL] ?: GridZoom.DEFAULT_LEVEL)
                            .coerceIn(0, GridZoom.LEVELS.lastIndex),
                        denseWarningDismissed = prefs[SettingsKeys.DENSE_GRID_WARNING_DISMISSED] ?: false,
                    )
                }
                .distinctUntilChanged()
                .collect { p ->
                    val initialLevel = if (p.rememberLast) p.lastLevel else GridZoom.levelForColumns(p.defaultColumns)
                    _uiState.update {
                        it.copy(
                            timelineGrouping = GridZoom.groupingForLevel(initialLevel),
                            initialZoomLevel = initialLevel,
                            gridRememberLast = p.rememberLast,
                            gridDefaultColumns = p.defaultColumns,
                            denseGridWarningDismissed = p.denseWarningDismissed,
                        )
                    }
                }
        }
    }

    private data class GridPrefs(
        val rememberLast: Boolean,
        val defaultColumns: Int,
        val lastLevel: Int,
        val denseWarningDismissed: Boolean,
    )

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

    /**
     * Repair the thumbnail store after an OS-level "Clear cache" wiped cacheDir/thumbnails/ with no
     * in-app hook running: the DB rows still carry stale file:// paths, so seeding them straight would
     * leave every previously-decrypted cell blank forever (the scheduler skips a re-decrypt while a
     * row's thumbnailUrl is non-null). Detect that one case cheaply: the thumbnails dir is
     * missing/holds no thumb_*.jpg AND the DB still has seed rows, and null the stale URLs once in
     * bulk (indexed SQL, no per-row File walk), clear the store, and skip the seed (nothing valid to
     * seed). Every stale cell then falls back to null and re-decrypts lazily on scroll. A normal launch
     * with a populated cache takes neither branch, so seeding runs as before.
     */
    private suspend fun reconcileThumbnailCacheThenSeed(userId: me.proton.core.domain.entity.UserId) {
        val thumbsDir = java.io.File(context.cacheDir, "thumbnails")
        val cacheEmpty = thumbsDir.listFiles { f ->
            f.name.startsWith("thumb_") && f.name.endsWith(".jpg")
        }?.isEmpty() ?: true // null = dir missing/unreadable, treat as empty
        if (cacheEmpty) {
            val hasSeedRows = runCatching { photoListingDao.getThumbnailUrlSeed(userId.id).isNotEmpty() }
                .getOrDefault(false)
            if (hasSeedRows) {
                runCatching { photoListingDao.clearCachedThumbnailUrls() }
                thumbnailUrlStore.clear()
                // The forced re-decrypt of the whole visible set is a cold-open storm; damp it 1-wide.
                cryptoServiceClient.armColdOpenDamping()
                return
            }
        }
        thumbnailUrlStore.seed(userId)
    }

    private fun observeGallery() {
        viewModelScope.launch {
            // Re-subscribe when the primary account changes rather than capturing the first value: a
            // signed-in user emits one stable id (the pipeline below is unchanged), but a no-account
            // session starts null and a later sign-in swaps the feed in place. collectLatest cancels the
            // previous run and rebuilds against the new id.
            accountManager.getPrimaryUserId().distinctUntilChanged().collectLatest { userId ->
                observeGalleryForUser(userId)
            }
        }
    }

    private suspend fun observeGalleryForUser(userId: me.proton.core.domain.entity.UserId?) = coroutineScope {
        // Prime the thumbnail-URL store from the DB once, off the Main thread, so cells decrypted in
        // a previous session paint immediately (the timeline projection no longer carries the URL).
        // Detached so it never delays the item stream below; the DAO read is off-Main already.
        if (userId != null) launch(Dispatchers.Default) {
            runCatching { reconcileThumbnailCacheThenSeed(userId) }
        }

        val hiddenUrisFlow = context.settingsDataStore.data.map {
            it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
        }.catch {
            // A DataStore read hiccup degrades to "nothing hidden" rather than
            // throwing into combine and killing the whole timeline.
            android.util.Log.w("GalleryVM", "hiddenUris source failed: ${it.message}")
            emit(emptySet())
        }

        // SyncStateRepo rows with HIDDEN status carry the cloud linkId of a photo whose
        // local twin lives in the Hidden vault. The gallery shows those cloud photos but
        // marks them with a crossed-out eye overlay so the user can see which cloud
        // entries are "hidden on this device" without opening the Hidden screen.
        val hiddenCloudLinkIdsFlow = if (userId != null) syncStateRepo.observeAll(userId).map { states ->
            states.asSequence()
                .filter { it.status == SyncStatus.HIDDEN && it.cloudFileId != null }
                .map { it.cloudFileId!! }
                .toSet()
        }.catch {
            // A transient DAO read during a write burst degrades to "no overlays"
            // instead of throwing into combine.
            android.util.Log.w("GalleryVM", "hiddenCloudLinkIds source failed: ${it.message}")
            emit(emptySet())
        } else flowOf(emptySet())

        val hideInAlbumsFlow = context.settingsDataStore.data.map {
            it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] == true
        }
        // Cloud album linkIds the user hid individually (per-album timeline toggle), separate
        // from the master "hide all album photos" switch above.
        val excludedAlbumIdsFlow = context.settingsDataStore.data.map {
            it[SettingsKeys.TIMELINE_EXCLUDED_ALBUM_IDS] ?: emptySet()
        }
        // Cloud linkIds to drop from the timeline: the master toggle hides every photo in any
        // album; otherwise only photos in the individually-hidden albums. Master OFF + no
        // per-album hides short-circuits to emptySet() so non-users pay zero query cost.
        val cloudAlbumHideSetFlow = combine(hideInAlbumsFlow, excludedAlbumIdsFlow) { hideAll, excludedIds ->
            hideAll to excludedIds
        }.flatMapLatest { (hideAll, excludedIds) ->
            when {
                hideAll -> albumPhotoMembershipDao.observeAllAssociatedPhotoLinkIds().map { it.toSet() }
                excludedIds.isNotEmpty() ->
                    albumPhotoMembershipDao.observeAssociatedPhotoLinkIdsForAlbums(excludedIds).map { it.toSet() }
                else -> flowOf(emptySet())
            }
        }.catch {
            // The membership cross-table is written in bursts when the Albums tab
            // prefetches; a read landing mid-burst degrades to "hide nothing" rather
            // than throwing into combine and tearing the timeline down.
            android.util.Log.w("GalleryVM", "albumHideSet source failed: ${it.message}")
            emit(emptySet())
        }

        // Bucket display names the user chose to hide from the timeline. Display-only:
        // matching items stay on the device and keep backing up — they're just dropped
        // from every tab below. A DataStore read hiccup degrades to "exclude nothing".
        val timelineExcludedBucketsFlow = context.settingsDataStore.data.map {
            it[SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES] ?: emptySet()
        }.distinctUntilChanged().catch {
            android.util.Log.w("GalleryVM", "timelineExcludedBuckets source failed: ${it.message}")
            emit(emptySet())
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
        val itemsFlow = if (userId != null) getGalleryItems.invoke(userId).sample(300) else getGalleryItems.invokeLocalOnly().sample(300)

        combine(
            itemsFlow,
            hiddenUrisFlow,
            hiddenCloudLinkIdsFlow,
            cloudAlbumHideSetFlow,
            timelineExcludedBucketsFlow,
        ) { items, hiddenUris, hiddenCloudLinkIds, cloudInAlbum, timelineExcludedBuckets ->
            GallerySources(items, hiddenUris, hiddenCloudLinkIds, cloudInAlbum, timelineExcludedBuckets)
        }
            .distinctUntilChanged()
            .retryWhen { cause, attempt ->
                // A throw in any combined source (e.g. itemsFlow's native crypto, or a
                // DAO read landing mid-write-burst) must NOT permanently empty the
                // timeline. `catch` would terminate the flow here, freezing the grid
                // until a new ViewModel is created; `retryWhen` re-subscribes the whole
                // pipeline so the stream keeps running and the grid refills on the next
                // emission. Surface the failure as an error frame without dropping the
                // current items, then back off (capped) so a persistently-failing source
                // can't spin the CPU in a tight re-subscribe loop.
                android.util.Log.w("GalleryVM", "gallery stream failed (attempt $attempt), retrying: ${cause.message}")
                _uiState.update { it.copy(isLoading = false, error = cause.message) }
                delay((500L * (attempt + 1)).coerceAtMost(5_000L))
                true
            }
            .collect { sources ->
                // Hidden-vault filter (always applied) — items whose local URI is in the
                // Hidden vault are dropped from the gallery; cloud counterparts stay in
                // the listing with a dim overlay (see hiddenCloudLinkIds usage in
                // CloudPhotoCell). Album-hide is NOT applied here — it's applied inside
                // [applyFilter] so non-All tabs (Favorites, Screenshots, Videos, …) can
                // bypass it. When the user explicitly picks a tab they expect to see
                // every item that matches, album membership notwithstanding.
                // The hidden/folder filter plus applyContentFilter + applyFilter (three passes
                // over the full library) run on Default, off the collector's Main thread; only
                // the finished lists reach the Main-thread state update. A thumbnail-decrypt
                // re-emission therefore can't stall the UI thread with filtering work.
                // contentFilter/selectedFilter/favoriteIds are snapshotted from the current
                // state — the dedicated filter-change handlers recompute filteredItems
                // themselves, so last-writer-wins here is unchanged from the inline version.
                val snapshot = _uiState.value
                val computed = withContext(Dispatchers.Default) {
                    val items = sources.items.filter { item ->
                        // Both the hidden-vault and the timeline-folder filters read off the
                        // local twin; CloudOnly has neither a local URI nor a bucket so it's
                        // never dropped by either. The folder filter is display-only — excluded
                        // buckets still back up and stay browsable, they just don't show here.
                        val local = when (item) {
                            is GalleryItem.LocalOnly -> item.local
                            is GalleryItem.Synced -> item.local
                            is GalleryItem.CloudOnly -> null
                        }
                        val notHidden = local == null || local.uri !in sources.hiddenUris
                        val bucket = local?.bucketName
                        // Backed-up photos (Synced) show through the folder filter even from a hidden
                        // folder; only not-yet-uploaded locals are hidden. Otherwise seeing one photo
                        // you uploaded from a hidden folder would force un-hiding the whole folder.
                        val notExcludedBucket = item is GalleryItem.Synced ||
                            bucket == null || bucket !in sources.timelineExcludedBuckets
                        notHidden && notExcludedBucket
                    }
                    val filtered = applyFilter(
                        applyContentFilter(items, snapshot.contentFilter),
                        snapshot.selectedFilter,
                        snapshot.favoriteIds,
                        sources.cloudInAlbum,
                        snapshot.offlinePinIds,
                    )
                    // Month- and day-bucket the filtered list and compute "On this day" here, off
                    // the Main thread, rather than inside Compose composition on every list
                    // re-emission (which costs a ~680 ms hitch from a thumbnail-decrypt burst at
                    // 8500+ photos). Day feeds the 3-column default zoom; month feeds the 4-col
                    // level. The label format/locale, item field and encounter order are kept
                    // identical to the grid's expected grouping, so the rendered timeline is
                    // unchanged. groupBy yields a LinkedHashMap, preserving first-seen order.
                    // "On this day" reads the unfiltered [items] to match the carousel's
                    // filter-independent source (the grid binds allItems = state.items).
                    val monthGroups = groupByMonth(filtered)
                    val dayGroups = groupByDay(filtered)
                    val onThisDay = computeOnThisDay(items)
                    GalleryComputed(items, filtered, monthGroups, dayGroups, onThisDay)
                }
                // Feed the library size into the perf diagnostics buffer (count only, no content)
                // so a tester's copied diagnostics show the heap against the library it walked.
                eu.akoos.photos.util.PerfDiagnostics.libraryPhotoCount = computed.items.size
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        items = computed.items,
                        filteredItems = computed.filtered,
                        monthGroups = computed.monthGroups,
                        dayGroups = computed.dayGroups,
                        onThisDayGroups = computed.onThisDay,
                        hiddenCloudLinkIds = sources.hiddenCloudLinkIds,
                        albumHideCloudIds = sources.cloudInAlbum,
                    )
                }
            }
    }

    /**
     * Live pending-upload badge, counted straight from sync_state: the photos that are LOCAL_ONLY
     * AND carry a queued upload intent. Sourced from the DB (not from a `count { it is LocalOnly }`
     * over the merged gallery list) so it means exactly the same set as the upload processor's
     * selector and the Activity screen's pending list. A cancelled manual "back up now" that was
     * de-queued drops out of all three at once, and a LOCAL_ONLY row with no intent never inflates
     * the badge. Counting in SQL also keeps it off the gallery merge/sort hot path.
     */
    private fun observePendingUploadCount() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId().distinctUntilChanged().flatMapLatest { userId ->
                if (userId != null) syncStateRepo.countPendingUploads(userId) else flowOf(0)
            }.collect { count ->
                _uiState.update { it.copy(pendingUploadCount = count) }
            }
        }
    }

    /**
     * Surface the client-side hidden cloud album ids to the UI. A hidden album stays a valid
     * add-to-album target, so the picker still lists it; it reads this set to mark the hidden
     * rows with a lock. Mirrors the TIMELINE_EXCLUDED_ALBUM_IDS DataStore read in [observeGallery].
     */
    private fun observeHiddenAlbumIds() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet() }
                .distinctUntilChanged()
                .collect { ids -> _uiState.update { state -> state.copy(hiddenAlbumIds = ids) } }
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
        // onto that same in-flight one (the walk is single-flighted) — no second refresh.
        photoStreamService.setGentleSync(false)
        viewModelScope.launch {
            accountManager.getPrimaryUserId().filterNotNull().distinctUntilChanged().collectLatest { userId ->
                doSync(userId)
            }
        }
    }

    /** Re-run the full reconcile + upload cycle. Safe to call multiple times — concurrent calls
     *  are de-duped via [syncInFlight]. */
    private suspend fun doSync(userId: me.proton.core.domain.entity.UserId) {
        if (!networkObserver.isOnline.value) return
        if (!syncInFlight.compareAndSet(false, true)) return
        // The avatar sync ring reflects real upload activity only (driven by
        // observeBackgroundUploadProgress), not this passive cloud-listing + reconcile pass —
        // otherwise it spun blue for the whole cold-listing walk while nothing was uploading.
        try {
            // Incremental cloud refresh before reconcile so deleted-cloud photos are removed
            // from the local DB before reconcile reads cloudItems.
            cloudRepo.refreshCloudPhotosIncremental(userId)
            reconcile(userId).collect {}
            // If reconcile queued any LOCAL_ONLY items, enqueue a foreground OneTime worker so
            // the upload runs with a visible progress notification — even if the user backgrounds
            // the app mid-upload. The worker is the SOLE upload owner: uploading inline here (in
            // viewModelScope) would run a second batch WorkManager cannot cancel, so the cancel
            // button would be a no-op against it. Routing every trigger through the worker keeps
            // cancellation working (mirrors the download worker design). Count only queued rows: an
            // out-of-scope LOCAL_ONLY row is not upload work, so it must not keep waking the worker.
            val pending = syncStateRepo.observeAll(userId).first()
                .count { it.status == SyncStatus.LOCAL_ONLY && it.queued }
            if (pending > 0) {
                val wifiOnly = context.settingsDataStore.data
                    .map { it[SettingsKeys.SYNC_WIFI_ONLY] != false }.first()
                SyncWorker.runNow(context, wifiOnly)
            }
        } catch (e: Exception) {
            // Rethrow cancellation so the parent coroutine's structured concurrency stays
            // intact — swallowing it would leave the parent thinking the child completed
            // normally and could mask UI state inversions.
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            syncInFlight.set(false)
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

    /** The in-flight off-main month + day re-bucket from a filter toggle; cancelled when a newer
     *  toggle supersedes it so the last filter always wins. */
    private var monthGroupsJob: kotlinx.coroutines.Job? = null

    /** Re-bucket the date-grouped zoom levels off the main thread after a filter toggle updates
     *  [GalleryUiState.filteredItems]. The streaming combine keeps month + day groups in lockstep
     *  with the list as it loads; the explicit filter handlers update filteredItems synchronously for
     *  instant feedback, so they re-bucket here too — without this the Month- and Day-grouped zoom
     *  levels keep rendering the pre-toggle buckets while the flat level (which reads filteredItems
     *  directly) updates correctly. Both are computed in one Default pass and set in one state copy. */
    private fun recomputeMonthGroups(filtered: List<GalleryItem>) {
        monthGroupsJob?.cancel()
        monthGroupsJob = viewModelScope.launch {
            val (months, days) = withContext(Dispatchers.Default) {
                groupByMonth(filtered) to groupByDay(filtered)
            }
            _uiState.update { it.copy(monthGroups = months, dayGroups = days) }
        }
    }

    fun onFilterSelected(filter: GalleryFilter) {
        val state = _uiState.value
        val filtered = applyFilter(
            applyContentFilter(state.items, state.contentFilter),
            filter,
            state.favoriteIds,
            state.albumHideCloudIds,
            state.offlinePinIds,
        )
        _uiState.update { it.copy(selectedFilter = filter, filteredItems = filtered) }
        recomputeMonthGroups(filtered)
    }

    /**
     * Pinch changed the timeline zoom. The grouping follows immediately so the grid re-renders;
     * the level is persisted only when "remember last used" is on, so otherwise the next launch
     * still opens at the fixed default and an in-session pinch stays session-only.
     */
    fun setZoomLevel(level: Int) {
        val clamped = level.coerceIn(0, GridZoom.LEVELS.lastIndex)
        _uiState.update { it.copy(timelineGrouping = GridZoom.groupingForLevel(clamped)) }
        if (_uiState.value.gridRememberLast) {
            viewModelScope.launch {
                context.settingsDataStore.edit { it[SettingsKeys.GRID_LAST_LEVEL] = clamped }
            }
        }
    }

    fun dismissDenseGridWarning() {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.DENSE_GRID_WARNING_DISMISSED] = true }
            _uiState.update { it.copy(denseGridWarningDismissed = true) }
        }
    }

    fun setContentFilter(filter: ContentFilter) {
        val state = _uiState.value
        val filtered = applyFilter(
            applyContentFilter(state.items, filter),
            state.selectedFilter,
            state.favoriteIds,
            state.albumHideCloudIds,
            state.offlinePinIds,
        )
        _uiState.update { it.copy(contentFilter = filter, filteredItems = filtered) }
        recomputeMonthGroups(filtered)
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
            localRepo.notifyMediaChanged()
            // Also kick off the first sync, which couldn't read local media before the grant.
            viewModelScope.launch {
                val userId = accountManager.getPrimaryUserId().first() ?: return@launch
                doSync(userId)
            }
        }
    }

    fun refresh(force: Boolean = true) {
        if (!networkObserver.isOnline.value) {
            // Offline: cached state from observeGallery() keeps the grid rendered. Pop the
            // spinner off immediately so pull-to-refresh doesn't hang.
            _uiState.update { it.copy(isRefreshing = false, isSyncing = false) }
            return
        }
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            // isRefreshing drives the pull-to-refresh spinner; isSyncing (avatar ring) is left
            // to observeBackgroundUploadProgress so it only spins during real uploads, not the
            // long full cloud listing this refresh kicks off.
            _uiState.update { it.copy(isRefreshing = true) }
            runCatching {
                cloudRepo.refreshCloudPhotos(userId, force = force)
                // Battery gate covers ONLY the whole-library walks below: they run on
                // process-lifetime scopes, so unlike the sync workers they carry no
                // setRequiresBatteryNotLow and would keep decrypting the whole account long after
                // the user leaves the screen. The visible and prefetch thumbnail bands stay ungated
                // on purpose — gating those would leave blank tiles on a screen the user is looking at.
                if (!context.isBatteryLow()) {
                    // Whole listing is fresh — warm the rest of the library's thumbnails in the
                    // background (lowest priority) so a large account fills in without the user
                    // scrolling past every photo. Idempotent + idle-only, so it never blocks scroll.
                    cloudRepo.backfillThumbnails(userId)
                    // Recover cloud video durations off the read path so the grid can show a duration
                    // pill. Bounded + self-collapsing; launched detached so it never delays reconcile
                    // or the upload kick-off below.
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { cloudRepo.backfillVideoDurations(userId) }
                    }
                    // Read the device photos' own EXIF for the GPS the map plots and for a capture
                    // date MediaStore holds wrong. Launched detached for the same reason: a whole
                    // library of header reads must never hold up reconcile or the upload kick-off.
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { cloudRepo.backfillLocalExif(userId) }
                    }
                    // Index faces for the People grouping. The scheduler no-ops when the AI features
                    // are off, paused, or the models are absent; gate on the master and face switches
                    // here so the common (faces-off) path never even launches a coroutine that returns
                    // at once.
                    val facePrefs = context.settingsDataStore.data.first()
                    if (facePrefs[SettingsKeys.AI_FEATURES_ENABLED] == true &&
                        facePrefs[SettingsKeys.FACE_ENABLED] == true
                    ) {
                        viewModelScope.launch(Dispatchers.IO) {
                            runCatching { cloudRepo.backfillFaces(userId) }
                        }
                    }
                }
                reconcile(userId).collect {}
                // Enqueue the upload on the worker rather than running it inline in viewModelScope:
                // the worker is the sole upload owner so the in-app cancel can actually stop it.
                val wifiOnly = context.settingsDataStore.data
                    .map { it[SettingsKeys.SYNC_WIFI_ONLY] != false }.first()
                SyncWorker.runNow(context, wifiOnly)
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
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Stop the in-flight background back-up from the in-app progress pill. Cooperative: the photo
     *  currently in transit finishes and backs up, remaining queued items are not started and stay
     *  pending for a later trigger. Never cancels the worker, so the in-flight native crypto is never
     *  interrupted. Scheduled backups stay armed. */
    fun cancelUpload() {
        upload.requestStop()
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

    /** Replace the whole selected set in one update. Backs drag-to-select: the gesture recomputes
     *  the full set (pre-drag snapshot ± the currently swept range) on every paint, so dragging back
     *  reverts the un-covered cells to their pre-drag state. Entering / leaving selection mode
     *  follows from [items] becoming non-empty / empty. */
    fun setSelection(items: Set<GalleryItem>) {
        _uiState.update { it.copy(selectedItems = items) }
    }

    // ── Move to a device folder ───────────────────────────────────────────────
    // Physically relocates the selected device photos into DCIM/<name>/ so other gallery apps see
    // them there. Works signed-in and out; cloud-only photos have no device file and are skipped.

    /** The write-consent request stashed while the user approves the system dialog, replayed by
     *  [onMovePermissionGranted] and dropped by [clearPendingMove]. */
    private var stashedMove: PendingMove? = null

    /** Move the selected device photos (device-only + synced, cloud-only skipped) into [folderName]
     *  under DCIM/. A blank name or a selection with no device file is a no-op. */
    fun moveSelectedToFolder(folderName: String) {
        if (folderName.isBlank()) return
        val uris = _uiState.value.selectedItems.mapNotNull { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced -> item.local.uri
                is GalleryItem.CloudOnly -> null
            }
        }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            handleMoveResult(moveToFolder(uris, folderName), folderName)
        }
    }

    /**
     * Create a real device folder "born with photos" for the logged-out Albums-tab pill: move the
     * picked local [uris] into DCIM/[name]/ through the very same [MoveToFolderUseCase] path
     * [moveSelectedToFolder] uses, so the write-consent request and the completion snackbar are shared
     * (routed through [handleMoveResult]). A blank name or an empty selection is a no-op. There is no
     * DB table; the folder exists purely because MediaStore now holds files under that bucket.
     */
    fun createFolderWithPhotos(name: String, uris: List<String>) {
        if (name.isBlank() || uris.isEmpty()) return
        viewModelScope.launch {
            handleMoveResult(moveToFolder(uris, name), name)
        }
    }

    /** Replay the deferred move on the foreign files after the user granted the system write request. */
    fun onMovePermissionGranted() {
        val pending = stashedMove ?: run { _pendingMoveIntent.value = null; return }
        viewModelScope.launch {
            handleMoveResult(moveToFolder.completeAfterPermissionGranted(pending), pending.folderName)
        }
    }

    /** User cancelled the system write dialog; drop the deferred files, nothing moves. */
    fun clearPendingMove() {
        stashedMove = null
        _pendingMoveIntent.value = null
    }

    private suspend fun handleMoveResult(result: MoveToFolderUseCase.Result, folderName: String) {
        when (result) {
            is MoveToFolderUseCase.Result.Moved -> {
                stashedMove = null
                _pendingMoveIntent.value = null
                _uiState.update { it.copy(selectedItems = emptySet()) }
                _moveConfirmation.emit(folderName)
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

    /**
     * Force every not-yet-backed-up (LocalOnly) photo in the current selection to upload to
     * Drive — the same path the device-folder view uses. Offered only when the selection holds
     * at least one local-only photo. Clears the selection and reports how many were queued.
     */
    fun backUpSelected(onResult: (queued: Int) -> Unit) {
        val localUris = _uiState.value.selectedItems
            .filterIsInstance<GalleryItem.LocalOnly>()
            .map { it.local.uri }
        if (localUris.isEmpty()) return
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val queued = forceUploadLocalUris.forceUpload(userId, localUris)
            _uiState.update { it.copy(selectedItems = emptySet()) }
            onResult(queued)
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
                _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed(context.getString(R.string.viewer_not_signed_in))) }
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
                    // No system-trash dialog was needed, so any device copies were either untouched or
                    // removed permanently (pre-R). Only a cloud trash is reversible, so the undo carries
                    // no local URIs: localRecoverable = false.
                    buildDeleteUndoAction(items, freeUpSpace, deleteFromCloud, hide = false, localRecoverable = false)
                        ?.let { undoController.offer(it) }
                    _uiState.update { it.copy(
                        selectedItems    = emptySet(),
                        multiDeleteState = MultiDeleteState.Done,
                    ) }
                }
                is DeletePhotoUseCase.Result.CloudDeleteFailed ->
                    _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Failed(context.getString(R.string.viewer_delete_drive_failed))) }
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
        // Unguarded: rollback owns both halves and no-ops when there is nothing pending, so a
        // condition on the private copies alone would skip a cloud-only half that is still hidden.
        pendingPermissionResult = null
        rollbackPendingHide()
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
                    multiDeleteState    = MultiDeleteState.Failed(context.getString(R.string.viewer_delete_drive_failed)),
                    pendingDeleteIntent = null,
                ) }
                return@launch
            }
            // If a multi-hide flow was waiting on this permission, commit the private URIs now
            // so the photos start appearing in the Hidden album. No-op if no hide was pending.
            // Snapshot the URIs first — commitPendingHide() clears the pending list.
            val hideUris = pendingHidePrivateUris
            val hideCloudIds = pendingHideCloudLinkIds
            // The cloud-only half is the whole hide here and it landed, so the folder stays
            // hidden and its pending name is released rather than rolled back. Left set, it
            // outlives this hide on a long-lived screen and the next rollback for anything
            // else reads it as its own, revealing a folder the user never asked to reveal.
            pendingHideCloudLinkIds = emptyList()
            pendingHideFolderName = null
            if (hideUris.isNotEmpty()) commitPendingHide()
            // Offer Undo for whichever reversible action just landed: a hide (restore the
            // vault URIs and drop the client-side filter) or a cloud-trash delete (restore the
            // Drive linkIds). A plain local free-up-space delete carries no undo. A cancelled
            // dialog never reaches here.
            val undo: UndoAction? = when {
                hideUris.isNotEmpty() -> buildHideUndoAction(hideUris, hideCloudIds)
                pending != null && !pending.hide ->
                    // The system trash keeps the local files for ~30 days, so a confirmed delete (cloud
                    // and/or device) is reversible: localRecoverable = true.
                    buildDeleteUndoAction(
                        pending.itemsBeingDeleted,
                        pending.freeUpSpace,
                        deleteFromCloud = pending.cloudLinkIds.isNotEmpty(),
                        hide = false,
                        localRecoverable = true,
                    )
                else -> null
            }
            if (undo != null) undoController.offer(undo)
            _uiState.update { it.copy(
                selectedItems       = emptySet(),
                multiDeleteState    = MultiDeleteState.Done,
                pendingDeleteIntent = null,
            ) }
        }
    }

    fun resetMultiDeleteState() {
        _uiState.update { it.copy(multiDeleteState = MultiDeleteState.Idle, hideFailureCount = 0) }
    }

    /** Private URIs of a hide whose intent is journalled and whose system delete has not confirmed
     *  yet. Published into HIDDEN_PHOTO_URIS once it does. */
    private var pendingHidePrivateUris: List<String> = emptyList()

    /** The client-side half of the same in-flight hide, held so the Undo offered once the delete
     *  confirms reverses the whole hide rather than only the photos that were vaulted. */
    private var pendingHideCloudLinkIds: List<String> = emptyList()

    /**
     * The two halves the current selection's hide would act on, for the confirmation that fronts it.
     *
     * The very split [runHide] runs on, read from the same selection, so the sheet describes exactly
     * what the Hide button is about to do rather than what hiding does in general.
     */
    fun hideSplitForSelection(): HiddenFolderRecords.HideSplit =
        HiddenFolderRecords.hideSplit(_uiState.value.selectedItems.toList())

    /**
     * Batch-hide every currently-selected gallery item. Every photo with a device file — backed up
     * or not — copies to app-private hidden storage, then routes through [DeletePhotoUseCase] with
     * `freeUpSpace=true, deleteFromCloud=false` so the MediaStore originals get removed (one
     * system-trash dialog on Android 11+, all URIs in a single request). A cloud-only item has no
     * device file to move and hides by its cloud linkId instead. Nothing on Drive changes either way.
     *
     * The intent is journalled BEFORE that delete and confirmed after it, so an interruption between
     * the two leaves a repairable record instead of bytes nothing refers to — see [HiddenVaultJournal].
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
            // A selection is short enough to watch behind the blocking sheet the state above raises,
            // so it reports no count of its own; [hideFolder] runs the same body with progress.
            runHide(HiddenFolderRecords.hideSplit(items), reportProgress = false)
        }
    }

    /**
     * The one hide body both entry points run, whether handed a selection or a whole device folder.
     *
     * [split] carries both halves of the hide, decided by [HiddenFolderRecords] so no caller routes
     * a photo its own way: every photo with a device file moves into the vault, and the cloud-only
     * ones, which have no file to move, hide by their cloud linkId. Acting on both is what makes the
     * hide cover everything the user asked for.
     *
     * [reportProgress] publishes done/total into [folderHideProgress] and polls the stop flag between
     * copies, which is what makes a folder of thousands watchable and stoppable. A stop is honoured
     * only during the copies: what has already been copied still goes through the journal and the
     * delete, so those photos end up in the vault rather than copied for nothing, and everything
     * after the stop is left untouched on the device.
     */
    private suspend fun runHide(split: HiddenFolderRecords.HideSplit, reportProgress: Boolean) {
        val vaultable = split.vaultable
        HiddenVaultDiagnostics.hideStarted(split)
        // Client-side hide for the cloud-only members: add their linkIds to the hidden set so the
        // shared merge filter drops them from every listing. They have no device file at all, so
        // nothing moves and unhide is a pure toggle with no re-pairing.
        HiddenCloudPhotos.hide(context, split.cloudLinkIds)
        pendingHideCloudLinkIds = split.cloudLinkIds
        if (vaultable.isEmpty()) {
            // Nothing to copy, so the client-side hide above is the whole operation and it is
            // already done: finish cleanly rather than raising a count that would never move. The
            // Undo bar is raised for it exactly as it is for a vaulting hide, so the same button
            // stays reversible whichever kind of photo it was pressed on.
            pendingHideCloudLinkIds = emptyList()
            pendingHideFolderName = null
            buildHideUndoAction(emptyList(), split.cloudLinkIds)?.let { undoController.offer(it) }
            _uiState.update { it.copy(
                selectedItems          = emptySet(),
                multiHideState         = MultiDeleteState.Done,
                hideCloudNoticePending = split.cloudLinkIds.isNotEmpty(),
            ) }
            return
        }
        // Step 1: refuse up front when the copies cannot fit. A hide holds both the originals and
        // the vault copies at once, so a volume that runs out mid-batch fails per file with
        // nothing the user can act on.
        val shortfall = hiddenVaultJournal.spaceShortfallBytes(vaultable.sumOf { it.sizeBytes })
        if (shortfall > 0L) {
            rollbackPendingHide()
            _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(
                context.getString(R.string.gallery_hide_needs_free_space, formatBytes(shortfall)),
            )) }
            return
        }
        // Step 2: copy every device file into app-private hidden storage. A backed-up photo also
        // stashes its cloud linkId, which is what lets the reveal re-pair it to its Drive copy
        // instead of uploading a second one.
        val collected = mutableListOf<HiddenVaultJournal.Entry>()
        var hideFailures = 0
        if (reportProgress) {
            _folderHideProgress.value = HiddenFolderProgress(0, vaultable.size, restoring = false)
        }
        for (target in vaultable) {
            if (reportProgress && stopFolderHide.get()) break
            val local = target.local
            val sourceFolder = hiddenStorage.sourceFolderFor(local.uri, local.bucketName)
            val privateUri = hiddenStorage.store(
                local.uri, local.displayName, local.mimeType, captureTimeMs = target.captureTimeMs,
            )
            if (privateUri != null) {
                collected += HiddenVaultJournal.Entry(
                    privateUri = privateUri,
                    sourceUri = local.uri,
                    sourceFolder = sourceFolder,
                    originalName = local.displayName,
                    cloudLinkId = target.cloudLinkId,
                )
            } else {
                // store() already logged the reason (privacy-safe, no file name).
                hideFailures++
            }
            if (reportProgress) {
                _folderHideProgress.value =
                    HiddenFolderProgress(collected.size, vaultable.size, restoring = false)
            }
        }
        HiddenVaultDiagnostics.copied(collected.size, hideFailures)
        if (collected.isEmpty()) {
            rollbackPendingHide()
            _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.gallery_copy_to_hidden_failed))) }
            return
        }
        // Step 3: record the intent BEFORE anything is deleted, so an interruption during the
        // delete leaves a recoverable state rather than orphaned bytes.
        if (!hiddenVaultJournal.journal(collected)) {
            hiddenVaultJournal.discard(collected.map { it.privateUri })
            rollbackPendingHide()
            _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.gallery_move_to_hidden_failed))) }
            return
        }
        pendingHidePrivateUris = collected.map { it.privateUri }
        // Carry the failure count into state so the terminal snackbar can report it, whether
        // the hide commits synchronously (pre-Q) or after the system-permission dialog.
        _uiState.update { it.copy(hideFailureCount = hideFailures) }

        // Step 4: delete the MediaStore originals of exactly what was copied (one system-trash
        // dialog on Android 11+). Narrowing to the copied files is what leaves a photo whose copy
        // failed, and everything a stopped pass never reached, where the user can still see it.
        val deleting = HiddenVaultDecisions.deletableOriginals(vaultable, collected)
        val userId = accountManager.getPrimaryUserId().first() ?: run {
            rollbackPendingHide()
            _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.viewer_not_signed_in))) }
            return
        }
        val result = deletePhotoUseCase(
            userId          = userId,
            items           = deleting,
            freeUpSpace     = true,
            deleteFromCloud = false,
            hide            = true,
        )
        when (result) {
            is DeletePhotoUseCase.Result.Success -> {
                HiddenVaultDiagnostics.originalsRemoved(deleting.size, neededConsent = false)
                // Snapshot both halves before commitPendingHide() clears the pending list, so Undo
                // reverses exactly the hide that just landed.
                val hideUris = pendingHidePrivateUris
                val hideCloudIds = pendingHideCloudLinkIds
                pendingHideCloudLinkIds = emptyList()
                commitPendingHide()
                buildHideUndoAction(hideUris, hideCloudIds)?.let { undoController.offer(it) }
                _uiState.update { it.copy(
                    selectedItems          = emptySet(),
                    multiHideState         = MultiDeleteState.Done,
                ) }
            }
            is DeletePhotoUseCase.Result.NeedsMediaWritePermission -> {
                HiddenVaultDiagnostics.originalsAwaitingConsent(deleting.size)
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
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.gallery_move_to_hidden_failed))) }
            }
        }
    }

    /** done/total of a device folder moving into the vault, or null when none is. Drives the pill
     *  this screen already shows background work in: a folder can hold thousands and each file is
     *  copied on its own, so the user watches the count and stops it from where they pressed. */
    private val _folderHideProgress = MutableStateFlow<HiddenFolderProgress?>(null)
    val folderHideProgress: StateFlow<HiddenFolderProgress?> = _folderHideProgress.asStateFlow()

    /** The running folder hide, so a second tap cannot start a parallel one. */
    private var folderHideJob: kotlinx.coroutines.Job? = null

    /** Raised by [cancelFolderHide] and polled between photos. A flag rather than a job cancel, so a
     *  stopped hide still records and deletes the originals of everything it already copied. */
    private val stopFolderHide = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * A folder hide the user has been asked about and has not answered yet.
     *
     * [split] is null until the folder's photos have been read, which takes a device query, so the
     * sheet can be raised the instant the card is tapped and fill its counts in when they land.
     * Nothing about the folder is written while this stands: what is on screen is a question.
     */
    data class FolderHideRequest(
        val bucketName: String,
        val split: HiddenFolderRecords.HideSplit? = null,
    )

    private val _folderHideRequest = MutableStateFlow<FolderHideRequest?>(null)
    /** The folder hide awaiting confirmation, or null when none is. */
    val folderHideRequest: StateFlow<FolderHideRequest?> = _folderHideRequest.asStateFlow()

    /**
     * Ask about hiding [bucketName], and start reading what that would move.
     *
     * A card on the Albums grid is one tap away from a hide that ends in a permanent delete of every
     * device original it vaults, so the question comes first and the folder is read only to answer
     * it. The answer is filed against the folder it was asked for, so a second card tapped while the
     * first is still counting cannot have the first folder's numbers land under its name.
     */
    fun requestHideFolder(bucketName: String) {
        if (bucketName.isEmpty() || folderHideJob?.isActive == true) return
        _folderHideRequest.value = FolderHideRequest(bucketName)
        viewModelScope.launch {
            val split = folderHideSplit(bucketName)
            _folderHideRequest.update { current ->
                if (current?.bucketName == bucketName) current.copy(split = split) else current
            }
        }
    }

    /** Drop the question. The folder is untouched: nothing is written until [confirmHideFolder]. */
    fun dismissHideFolderRequest() {
        _folderHideRequest.value = null
    }

    /** Run the folder hide the user just agreed to, on the very photos the sheet named. */
    fun confirmHideFolder() {
        val request = _folderHideRequest.value ?: return
        val split = request.split ?: return
        _folderHideRequest.value = null
        hideFolder(request.bucketName, split)
    }

    /**
     * Hide a device folder from where its card is: the card leaves the Albums grid, the folder's
     * device-only photos move into the vault and its backed-up ones drop out of every listing this
     * app draws, so a hidden folder is genuinely put away rather than merely unlisted.
     *
     * [split] is what the confirmation was worded from, so the hide moves exactly the photos the
     * user was shown a count of. A shot taken between the two is left on the device, which is the
     * safe side of that gap: it stays where its owner can see it.
     *
     * The folder's name is recorded BEFORE the first copy, so a photo in the vault always belongs to
     * a folder that is listed as hidden. A hide the user stops part-way therefore leaves a folder
     * that is hidden, holds what was already copied, and still shows the rest on the device — never a
     * set of vaulted photos with no folder to reach them by. It is also the first thing this hide
     * writes at all: everything before the confirmation only reads.
     */
    private fun hideFolder(bucketName: String, split: HiddenFolderRecords.HideSplit) {
        if (bucketName.isEmpty() || folderHideJob?.isActive == true) return
        stopFolderHide.set(false)
        folderHideJob = viewModelScope.launch {
            try {
                // The flag goes in before the copies start so a fully-vaulted folder still has a
                // name to show. That makes it one more thing a failed hide has to give back, so the
                // name is held until the hide is known to have landed.
                pendingHideFolderName = bucketName
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()
                    prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] = current + bucketName
                }
                if (!split.isEmpty) runHide(split, reportProgress = true)
            } finally {
                _folderHideProgress.value = null
            }
        }
    }

    /** Stop a folder hide between photos. Cooperative: the file in transit finishes, and what is
     *  already copied still goes through the journal and the delete, so those photos land in the
     *  vault rather than being copied for nothing. */
    fun cancelFolderHide() {
        stopFolderHide.set(true)
    }

    /**
     * Both halves of a hide of [bucketName]: the photos that move into the vault, and the cloud
     * linkIds that hide client-side.
     *
     * Read for the one bucket rather than filtered out of the timeline: the feed drops a folder the
     * user keeps out of it, so hiding such a folder from the grid would find nothing there, and a
     * whole-library scan is what this screen already works hardest to avoid. The device rows come
     * from a bucket-scoped MediaStore query and one uris-bounded sync-state read says which Drive
     * copy each of them is paired to, which is the only thing a device row cannot say about itself
     * and the one record a reveal needs to re-pair rather than re-upload. [HiddenFolderRecords] then
     * routes them, exactly as it does for the same folder opened from its own screen.
     */
    private suspend fun folderHideSplit(bucketName: String): HiddenFolderRecords.HideSplit {
        val userId = accountManager.getPrimaryUserId().first() ?: return HiddenFolderRecords.HideSplit.EMPTY
        val bucketItems = localRepo.queryByBucket(bucketName)
        if (bucketItems.isEmpty()) return HiddenFolderRecords.HideSplit.EMPTY
        val pairedLinkIds = syncStateRepo.cloudPairedLinkIds(userId, bucketItems.map { it.uri })
        return HiddenFolderRecords.folderHideSplit(
            items = bucketItems.map { GalleryItem.LocalOnly(it) },
            bucketName = bucketName,
            cloudLinkIdByUri = pairedLinkIds,
        )
    }

    fun resetMultiHideState() {
        _uiState.update { it.copy(multiHideState = MultiDeleteState.Idle, hideCloudNoticePending = false, hideFailureCount = 0) }
    }

    /** A Hide restore lands its files back in MediaStore off-screen, so repaint the feed when the
     *  shared [UndoController] reports one finished (a cloud-trash restore refreshes its own stream). */
    private fun observeUndoRestores() {
        viewModelScope.launch {
            undoController.restored.collect { action ->
                if (action is UndoAction.Hide || action is UndoAction.Delete) refresh(force = false)
            }
        }
    }

    /** Publish the journalled hide now that the delete has confirmed, and clear the pending state. */
    /** The folder a running hide flagged as hidden, cleared once that hide has landed. Null when no
     *  folder hide is in flight, which is every selection hide. */
    private var pendingHideFolderName: String? = null

    private fun commitPendingHide() {
        val uris = pendingHidePrivateUris
        pendingHidePrivateUris = emptyList()
        // The hide landed, so the folder keeps the flag this run wrote.
        pendingHideFolderName = null
        if (uris.isEmpty()) return
        viewModelScope.launch { hiddenVaultJournal.confirm(uris) }
    }

    /** Undo a hide that did not land: drop the private copies and everything journalled for them, and
     *  put the cloud-only half back in every listing. Those ids are written before the device half is
     *  even attempted, so leaving them set is what made a refused hide still take photos away. The
     *  originals are untouched, so the photos stay where the user already sees them. */
    private fun rollbackPendingHide() {
        val uris = pendingHidePrivateUris
        val cloudIds = pendingHideCloudLinkIds
        val folderName = pendingHideFolderName
        pendingHidePrivateUris = emptyList()
        pendingHideCloudLinkIds = emptyList()
        pendingHideFolderName = null
        if (uris.isEmpty() && cloudIds.isEmpty() && folderName == null) return
        viewModelScope.launch {
            if (uris.isNotEmpty()) hiddenVaultJournal.discard(uris)
            HiddenCloudPhotos.reveal(context, cloudIds)
            // Nothing was vaulted, so a folder left flagged would list in the vault holding no
            // photos while its card is gone from Albums, with no way back except revealing it.
            if (folderName != null) context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] ?: emptySet()
                prefs[SettingsKeys.HIDDEN_FOLDER_NAMES] = current - folderName
            }
        }
    }

    fun downloadSelected() {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        var job: kotlinx.coroutines.Job? = null
        job = viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Working(0, items.size)) }
            // One thumbnail URI per selected photo so the Activity screen lists them individually.
            val thumbUris = items.map { thumbUriFor(it) }
            val transferId = transferCenter.start(
                TransferCenter.Kind.DOWNLOAD, items.size, items = thumbUris,
                onCancel = { job?.cancel() },
            )
            // Resolve per-photo album folder so cloud-album photos go to DCIM/<AlbumName>/
            // instead of bunching in a generic Proton Photos folder. AlbumService caches the
            // membership map for 20 min so repeated downloads don't re-fetch.
            val memberships: Map<String, String> = runCatching { cloudRepo.getAlbumMemberships(userId) }
                .getOrElse {
                    android.util.Log.w("GalleryVM", "getAlbumMemberships failed: ${it.message}")
                    emptyMap()
                }
                .mapValues { (_, name) -> eu.akoos.photos.util.ProtonPhotosStorage.sanitize(name) }
            val savedUris = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val result = try {
                downloadPhotos.downloadGalleryItems(
                    userId, items,
                    folderName = "", // empty = save non-album photos directly into DCIM/Camera
                    folderByLinkId = memberships,
                    onSaved = { savedUris.add(it) },
                ) { progress ->
                    _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Working(progress.done, progress.total)) }
                    transferCenter.progress(transferId, progress.done)
                }
            } finally {
                transferCenter.finish(transferId)
            }
            transferCenter.log(
                TransferCenter.Kind.DOWNLOAD, result.done - result.failed, uris = savedUris.toList(),
            )
            _uiState.update { it.copy(
                multiDownloadState = MultiDownloadState.Done(result.done - result.failed, result.failed),
                selectedItems = emptySet(),
            ) }
        }
        // Stopping the batch from the Activity screen cancels this job; clear the gallery's
        // in-progress download indicator so it does not stay spinning.
        job?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Idle, selectedItems = emptySet()) }
            }
        }
    }

    /** Thumbnail URI for one gallery item: the on-device file for local/synced photos, the cached
     *  cloud thumbnail (which may not exist yet) for cloud-only ones. */
    private fun thumbUriFor(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.uri
        is GalleryItem.Synced -> item.local.uri
        is GalleryItem.CloudOnly ->
            "file://" + java.io.File(context.cacheDir, "thumbnails/thumb_${item.cloud.linkId}.jpg").absolutePath
    }

    fun resetMultiDownloadState() {
        _uiState.update { it.copy(multiDownloadState = MultiDownloadState.Idle) }
    }

    fun resetMultiShareState() {
        _uiState.update { it.copy(multiShareState = MultiShareState.Idle) }
    }

    /**
     * Pin every cloud-only photo in the current selection for offline viewing — downloads each
     * full-res blob into app-private offline storage. Offered only when the selection holds at
     * least one [GalleryItem.CloudOnly]; Synced/LocalOnly items already have their bytes on the
     * device and are skipped. The pin set in [SettingsKeys.OFFLINE_PIN_IDS] is added optimistically
     * (one edit, mirroring [PhotoViewerViewModel.toggleOfflinePin]) so the per-cell badge reflects
     * immediately; each download runs sequentially and is wrapped so one failure doesn't abort the
     * rest, and any item that fails to download has its pin and blob reverted so it can't show as
     * pinned with no bytes. Clears the selection and reports the success count via
     * [offlineBatchResult].
     */
    /**
     * Toggles the offline state of the selected cloud-only photos, mirroring the viewer's per-photo
     * toggle. If EVERY selected cloud photo is already offline it removes them (un-pins + drops the
     * blobs, no network); otherwise it pins the ones that aren't offline yet (downloading each
     * full-res blob). The result event is the pin count, or a negative count for a removal.
     */
    fun toggleSelectedOffline() {
        val cloudItems = _uiState.value.selectedItems.filterIsInstance<GalleryItem.CloudOnly>()
        if (cloudItems.isEmpty()) return
        val pinned = _uiState.value.offlinePinIds
        val allOffline = cloudItems.all { it.cloud.linkId in pinned }

        if (allOffline) {
            // Remove from offline — instant, no network: drop the pins and their blobs.
            val linkIds = cloudItems.map { it.cloud.linkId }
            viewModelScope.launch {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - linkIds.toSet()
                }
                linkIds.forEach { offlineStore.delete(it) }
                _uiState.update { it.copy(selectedItems = emptySet()) }
                // Negative = "removed" so the screen shows the un-pin message, not a pin count.
                _offlineBatchResult.emit(-linkIds.size)
            }
            return
        }

        // Pin only the ones not already offline.
        val toPin = cloudItems.filter { it.cloud.linkId !in pinned }
        val linkIds = toPin.map { it.cloud.linkId }
        viewModelScope.launch {
            // Optimistic pin: add every linkId in one edit so the badges light up before any byte is
            // fetched; failures below remove the ones that didn't land.
            context.settingsDataStore.edit { prefs ->
                val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                prefs[SettingsKeys.OFFLINE_PIN_IDS] = current + linkIds
            }
            _uiState.update { it.copy(selectedItems = emptySet()) }
            val userId = accountManager.getPrimaryUserId().first()
            var succeeded = 0
            val failedLinkIds = mutableListOf<String>()
            val savedPaths = mutableListOf<String>()
            val transferId = transferCenter.start(TransferCenter.Kind.OFFLINE, toPin.size)
            try {
                for (item in toPin) {
                    val linkId = item.cloud.linkId
                    try {
                        val uid = userId ?: error("Not signed in")
                        val file = cloudRepo.downloadFullResPhoto(uid, item.cloud)
                        val stored = offlineStore.store(linkId, file)
                        savedPaths += "file://${stored.absolutePath}"
                        succeeded++
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("GalleryVM", "offline pin failed: ${e.message}")
                        failedLinkIds += linkId
                    }
                    transferCenter.progress(transferId, succeeded + failedLinkIds.size)
                }
            } finally {
                transferCenter.finish(transferId)
            }
            // Revert the optimistic pin for anything that didn't download, so a failed item never
            // looks pinned with no blob behind it.
            if (failedLinkIds.isNotEmpty()) {
                context.settingsDataStore.edit { prefs ->
                    val current = prefs[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
                    prefs[SettingsKeys.OFFLINE_PIN_IDS] = current - failedLinkIds.toSet()
                }
                failedLinkIds.forEach { offlineStore.delete(it) }
            }
            // Drop any blob whose pin was removed (un-pinned, or a sign-out) while its download was
            // in flight, so a blob never outlives its pin.
            val finalPins = context.settingsDataStore.data.first()[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
            (linkIds - failedLinkIds.toSet()).forEach { id ->
                if (id !in finalPins) offlineStore.delete(id)
            }
            transferCenter.log(TransferCenter.Kind.OFFLINE, succeeded, uris = savedPaths)
            _offlineBatchResult.emit(succeeded)
        }
    }

    /**
     * Resolve every selected item to a shareable URI and emit a single ACTION_SEND_MULTIPLE
     * intent for [GalleryScreen] to hand to the chooser. Local items (LocalOnly / Synced) reuse
     * their MediaStore content URI directly — no copy. Cloud-only items are decrypted to
     * cacheDir/fullres/ one at a time (single-flighted, cache-hit-cheap) and exposed through the
     * share FileProvider, with [MultiShareState.Working] advancing per resolved item so the share
     * pill shows progress. Items that fail to resolve are skipped so one bad photo doesn't sink
     * the whole batch. Temp files are left for the fullres TTL sweep to reclaim.
     */
    fun shareSelected() {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(multiShareState = MultiShareState.Working(0, items.size)) }
            val userId = accountManager.getPrimaryUserId().first()
            val uris = ArrayList<Uri>(items.size)
            var done = 0
            for (item in items) {
                runCatching {
                    when (item) {
                        is GalleryItem.LocalOnly -> Uri.parse(item.local.uri)
                        is GalleryItem.Synced    -> Uri.parse(item.local.uri)
                        is GalleryItem.CloudOnly -> {
                            val uid = userId ?: error("Not signed in")
                            val file = cloudRepo.downloadFullResPhoto(uid, item.cloud)
                            androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.share.fileprovider", file,
                            ).also {
                                // Report the real filename to the receiver instead of the linkId.
                                eu.akoos.photos.util.ShareFileProvider.putDisplayName(it, item.cloud.displayName)
                            }
                        }
                    }
                }.onSuccess { uris.add(it) }
                    .onFailure { android.util.Log.w("GalleryVM", "share resolve failed: ${it.message}") }
                done++
                _uiState.update { it.copy(multiShareState = MultiShareState.Working(done, items.size)) }
            }
            if (uris.isNotEmpty()) {
                val mime = eu.akoos.photos.util.ShareIntentBuilder.shareableMime(items)
                _shareIntent.tryEmit(
                    eu.akoos.photos.util.ShareIntentBuilder.buildSendIntent(context, uris, mime),
                )
            }
            _uiState.update { it.copy(
                // A photo that could not be resolved never reaches the chooser, so the screen says
                // how many did rather than letting the selection clear on silence.
                multiShareState = MultiShareState.Done(uris.size, items.size - uris.size),
                selectedItems = emptySet(),
            ) }
        }
    }

    /**
     * Puts the whole selection into the state [favoriteTurnsOn] picks for it: on if any selected photo
     * is not a favourite yet, off once they all are.
     *
     * The selection is kept rather than cleared. This is the one dock action that is a toggle whose
     * direction the button itself shows, so holding the selection is what lets a second press take
     * back the first, and the button flipping is the confirmation a one-way action gets from its
     * snackbar. The tiles repaint on their own: a device-only heart re-emits through
     * [observeFavorites], a cloud one through the listing row [FavoriteWriter] updates.
     *
     * The selected items are re-stated through [withFavoriteSettled] for the photos the write
     * landed on. A backed-up photo's heart is a tag on the item, and these copies were taken when
     * the photos were picked, so without that pass the direction of the next press would keep being
     * read off the state they were in before the write. A photo Drive refused is left as it is.
     */
    fun toggleSelectedFavorite() {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty() || _uiState.value.favoriteState !is FavoriteActionState.Idle) return
        val turnOn = favoriteTurnsOn(items, _uiState.value.favoriteIds)
        viewModelScope.launch {
            _uiState.update { it.copy(favoriteState = FavoriteActionState.Working(0, items.size)) }
            val settledIds = HashSet<String>(items.size)
            val outcome = favoriteWriter.write(
                items = items,
                favorite = turnOn,
                onProgress = { done ->
                    _uiState.update {
                        it.copy(favoriteState = FavoriteActionState.Working(done, items.size))
                    }
                },
                onSettled = { settledIds += it.stableId },
            )
            // One pass over the selection once the batch is done rather than one per photo, which
            // on a large selection would be a full rebuild per settled write.
            _uiState.update { state ->
                state.copy(
                    favoriteState = FavoriteActionState.Idle,
                    selectedItems = if (settledIds.isEmpty()) state.selectedItems
                    else state.selectedItems.mapTo(LinkedHashSet(state.selectedItems.size)) {
                        withFavoriteSettled(it, settledIds, turnOn)
                    },
                )
            }
            outcome.message()?.let { _favoriteFailure.tryEmit(it.resolve(context)) }
        }
    }

    // ── Per-photo public link (selection of exactly one cloud photo) ────────────
    //
    // The unified share drawer offers a "Public link" row only when the selection is a single
    // cloud-backed photo (see [showPublicLink] gating in GalleryScreen). The link machine itself
    // lives in the shared [PublicLinkController] so the gallery and the viewer behave identically;
    // the methods below resolve the selected linkId / local uri and delegate.

    /** Single-photo public-link state surfaced in the manage-link sheet, owned by [publicLink]. */
    val publicLinkState: StateFlow<eu.akoos.photos.presentation.viewer.PublicLinkState> = publicLink.state

    /** The single cloud-backed (Synced/CloudOnly) linkId in the current selection, or null when
     *  the selection is empty, larger than one, or contains a local-only item. Drives both the
     *  share drawer's [showPublicLink] gating and which photo the manage-link sheet operates on. */
    fun singleSelectedCloudLinkId(): String? {
        val selected = _uiState.value.selectedItems
        if (selected.size != 1) return null
        return when (val only = selected.first()) {
            is GalleryItem.Synced    -> only.cloud.linkId
            is GalleryItem.CloudOnly -> only.cloud.linkId
            is GalleryItem.LocalOnly -> null
        }
    }

    /** Look up any existing public link for the single selected cloud photo and seed
     *  [publicLinkState]. Called when the manage-link sheet opens. A failed lookup falls back to
     *  None so the user can still create one instead of getting stuck on an error. */
    fun loadPublicLink() = publicLink.load(viewModelScope, singleSelectedCloudLinkId(), setLoading = true)

    /** Mint a public link for the photo the sheet is acting on. */
    fun createPublicLink() = publicLink.create(viewModelScope)

    /** Revoke the photo's public link. Re-fetches to confirm the delete stuck before reporting
     *  None, so a silently-failed revoke keeps showing the live link rather than lying. */
    fun revokePublicLink() = publicLink.revoke(viewModelScope)

    /** Set ([password] non-blank) or clear ([password] null/blank) the custom password on the
     *  photo's public link. No-op if no link exists yet. */
    fun setLinkPassword(password: String?) = publicLink.setPassword(viewModelScope, password)

    /** The live public-link URL if one is currently active, for the screen's copy-to-clipboard. */
    fun currentPublicLinkUrl(): String? = publicLink.currentUrl()

    /** The single selected LocalOnly photo's URI, or null when the selection isn't a single
     *  not-yet-backed-up local photo. */
    fun singleSelectedLocalUri(): String? {
        val selected = _uiState.value.selectedItems
        if (selected.size != 1) return null
        return (selected.first() as? GalleryItem.LocalOnly)?.local?.uri
    }

    /** Upload the single selected local photo, wait for its cloud id, then mint a public link.
     *  The manage-link sheet shows the shared Loading spinner throughout, then the live link. */
    fun uploadAndCreateSelectedLink() {
        singleSelectedLocalUri()?.let { publicLink.uploadAndCreate(viewModelScope, it) }
    }

    // ── Add-to-album multi-action ──────────────────────────────────────────────
    //
    // Routes a multi-select to a cloud album. Synced and CloudOnly items carry a Drive linkId and
    // join the album immediately. LocalOnly items have no linkId yet, so they are queued: the
    // upload intent is stamped on the sync_state row (queued / ALBUM_ADD) and the target album is
    // recorded in the upload_album_target table, so the upload pipeline backs the file up (even from
    // a non-backup folder) and joins it to the album once the cloud id is known. Albums are
    // references-not-copies on Drive (the photo stays in the Photos root), so there is no file
    // movement and no MediaStore consent dialog.

    /**
     * Begin adding the current selection to a cloud album.
     *
     * @param albumLinkId Drive link ID of the target album.
     * @param albumName   Album display name, used only for the success snackbar.
     */
    /** Attach the current selection to a named person, then exit selection. The membership survives a
     *  rescan (stored against the person's name); an unnamed person is a no-op inside the use case. */
    fun addSelectedToPerson(personId: Long) {
        val keys = _uiState.value.selectedItems.map { it.stableId }
        if (keys.isEmpty()) return
        viewModelScope.launch {
            addPhotosToPersonUseCase(personId, keys)
            clearSelection()
        }
    }

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
            // LocalOnly items have no Drive linkId yet, so they upload first and join the album
            // afterwards. (LocalOnly items with a backed-up twin surface as Synced, not here.)
            val localUris = items.mapNotNull { (it as? GalleryItem.LocalOnly)?.local?.uri }

            if ((cloudLinkIds.isNotEmpty() || localUris.isNotEmpty()) && userId == null) {
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(context.getString(R.string.viewer_not_signed_in))) }
                return@launch
            }

            val result = runAddToAlbum(
                userId = userId,
                albumLinkId = albumLinkId,
                albumName = albumName,
                cloudLinkIds = cloudLinkIds,
                localUris = localUris,
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

    /** Cloud-add helper. Cloud-backed items join [albumLinkId] now; [localUris] are queued to
     *  upload then join afterwards via [queueLocalAddsToAlbum]. The queued count flows through
     *  [AddToAlbumState.Done.localMoved] so the UI can show an "added now, more after backup"
     *  snackbar. */
    private suspend fun runAddToAlbum(
        userId: me.proton.core.domain.entity.UserId?,
        albumLinkId: String,
        albumName: String,
        cloudLinkIds: List<String>,
        localUris: List<String> = emptyList(),
    ): AddToAlbumState {
        var cloudAdded = 0
        var cloudFailed = 0
        if (cloudLinkIds.isNotEmpty()) {
            if (userId == null) return AddToAlbumState.Failed(context.getString(R.string.viewer_not_signed_in))
            try {
                val r = cloudRepo.addPhotosToAlbum(userId, albumLinkId, cloudLinkIds)
                cloudAdded = r.succeededLinkIds.size
                cloudFailed = r.failedLinkIds.size
                // Wake the Albums grid so the target album's cover + count refresh without a
                // manual pull-to-refresh now that it gained photos.
                if (cloudAdded > 0) albumListEvents.notifyChanged()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return AddToAlbumState.Failed(e.message ?: context.getString(R.string.gallery_add_to_cloud_album_failed))
            }
        }

        if (cloudAdded == 0 && cloudFailed > 0 && localUris.isEmpty()) {
            return AddToAlbumState.Failed(context.getString(R.string.gallery_add_to_album_failed))
        }

        val localQueued = if (userId != null && localUris.isNotEmpty()) {
            queueLocalAddsToAlbum(userId, albumLinkId, localUris)
        } else 0
        return AddToAlbumState.Done(cloudAdded, localQueued, skipped = 0, albumName)
    }

    /**
     * Queue [localUris] (local-only photos) to join [albumLinkId] after they back up. Delegates
     * to [ForceUploadLocalUrisUseCase], which stamps the upload intent on the sync_state row
     * (queued / ALBUM_ADD) and records the target album in the upload_album_target table, forces a
     * LOCAL_ONLY SyncState row so the upload pipeline backs the file up regardless of the backup
     * folder selection, and kicks an upload pass. The upload pipeline joins the freshly uploaded
     * file to the album once its cloud id is known. Returns the number queued.
     */
    private suspend fun queueLocalAddsToAlbum(
        userId: me.proton.core.domain.entity.UserId,
        albumLinkId: String,
        localUris: List<String>,
    ): Int = forceUploadLocalUris.queueForAlbum(userId, albumLinkId, localUris)

    /**
     * Inline create-album-then-add flow. Always creates a cloud album, adds the cloud-backed
     * items in the selection, then sets the first successfully-added photo as the album cover
     * so the new card isn't blank in the Albums grid.
     */
    fun createAlbumThenAddSelected(name: String) {
        val trimmed = ProtonPhotosStorage.sanitize(name)
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(context.getString(R.string.albums_name_empty))) }
            return
        }
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty()) {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(context.getString(R.string.gallery_select_photos_first))) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Working) }
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(context.getString(R.string.viewer_not_signed_in))) }
                return@launch
            }
            val newAlbumLinkId = try {
                cloudRepo.createDriveAlbum(userId, trimmed).linkId
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(
                    context.getString(R.string.gallery_create_album_failed, e.message ?: ""),
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
            // LocalOnly items upload first, then join the just-created album afterwards.
            val localUris = items.mapNotNull { (it as? GalleryItem.LocalOnly)?.local?.uri }

            val addResult = if (cloudLinkIds.isNotEmpty()) {
                try {
                    cloudRepo.addPhotosToAlbum(userId, newAlbumLinkId, cloudLinkIds)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _uiState.update { it.copy(addToAlbumState = AddToAlbumState.Failed(
                        e.message ?: context.getString(R.string.gallery_add_to_album_failed),
                    )) }
                    return@launch
                }
            } else null

            val cloudAdded = addResult?.succeededLinkIds?.size ?: 0
            // Set the first SUCCEEDED photo as cover — cover failure must not fail the add.
            addResult?.succeededLinkIds?.firstOrNull()?.let { firstSucceeded ->
                runCatching { cloudRepo.setAlbumCover(userId, newAlbumLinkId, firstSucceeded) }
            }
            val localQueued = if (localUris.isNotEmpty()) {
                queueLocalAddsToAlbum(userId, newAlbumLinkId, localUris)
            } else 0
            albumListEvents.notifyChanged()
            _uiState.update { it.copy(
                selectedItems    = emptySet(),
                addToAlbumState  = AddToAlbumState.Done(cloudAdded, localQueued, skipped = 0, trimmed),
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
    // The manual strip's config comes from the multi-select picker, independent of the upload-strip
    // settings. The picker gates its confirm on at least one field, so an empty config never reaches
    // here and counts as a silent no-op.

    /** Strip config + counters carried across an Android 10+ write-permission dialog so the
     *  granted retry strips the deferred foreign URIs and folds them into the final tally. */
    private var pendingStripConfig: MetadataStripConfig? = null
    private var pendingStripUris: List<String> = emptyList()
    private var pendingStripStripped = 0
    private var pendingStripSkipped = 0
    private var pendingStripFailed = 0

    fun stripMetadataSelected(config: MetadataStripConfig) {
        val items = _uiState.value.selectedItems.toList()
        if (items.isEmpty() || config.isNoOp) return
        viewModelScope.launch {
            _uiState.update { it.copy(multiStripState = MultiStripState.Working) }
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
            runStripPass(config, localUris, baseStripped = 0, baseSkipped = skippedCloud, baseFailed = 0)
        }
    }

    /**
     * Strips [uris] in place, then either finishes (Done) or — when some URIs are foreign files
     * the OS refuses to write — defers them into a single [MediaStore.createWriteRequest] and
     * surfaces [GalleryUiState.pendingStripIntent]. [baseStripped] / [baseSkipped] / [baseFailed]
     * seed the running tally so a retry adds to (rather than overwrites) the counts from the first pass.
     */
    private suspend fun runStripPass(
        config: MetadataStripConfig,
        uris: List<String>,
        baseStripped: Int,
        baseSkipped: Int,
        baseFailed: Int,
    ) {
        val needsPermission = mutableListOf<String>()
        // The URIs that took the strip, not just a count: each one is a live MediaStore file whose
        // stored GPS fix a location strip makes stale. A deferred URI keeps its fix until the retry
        // pass strips it and lands here itself.
        val strippedUris = mutableListOf<String>()
        val failed = withContext(Dispatchers.IO) {
            var failedCount = 0
            for (uri in uris) {
                when (ExifHelper.stripFieldsInPlace(context, uri, config)) {
                    is StripResult.Stripped       -> strippedUris += uri
                    is StripResult.NeedsPermission -> needsPermission += uri
                    is StripResult.Failed         -> failedCount++
                }
            }
            failedCount
        }
        invalidateStrippedLocations(config, strippedUris)
        val totalStripped = baseStripped + strippedUris.size
        val totalSkipped  = baseSkipped
        val totalFailed   = baseFailed + failed

        // Android 11+: ask once for write access to every foreign URI in this pass, then replay
        // the strip on just those in [retryPendingStrip]. The consent intent is API 30 — older
        // scoped-storage devices have no batch-consent flow, so those count as skipped.
        if (needsPermission.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingStripConfig   = config
            pendingStripUris     = needsPermission
            pendingStripStripped = totalStripped
            pendingStripSkipped  = totalSkipped
            pendingStripFailed   = totalFailed
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
            multiStripState  = terminalStripState(
                totalStripped, totalSkipped + needsPermission.size, totalFailed,
            ),
        ) }
    }

    /** Re-runs the strip on the foreign URIs after the user granted the write-permission dialog. */
    fun retryPendingStrip() {
        val config = pendingStripConfig ?: return
        val uris = pendingStripUris
        val baseStripped = pendingStripStripped
        val baseSkipped = pendingStripSkipped
        val baseFailed = pendingStripFailed
        clearPendingStripState()
        viewModelScope.launch {
            _uiState.update { it.copy(multiStripState = MultiStripState.Working, pendingStripIntent = null) }
            runStripPass(config, uris, baseStripped, baseSkipped, baseFailed)
        }
    }

    /** User canceled the write-permission dialog — count the deferred URIs as skipped, no retry. */
    fun clearPendingStripIntent() {
        val baseStripped = pendingStripStripped
        val deferred = pendingStripUris.size + pendingStripSkipped
        val baseFailed = pendingStripFailed
        clearPendingStripState()
        _uiState.update { it.copy(
            selectedItems     = emptySet(),
            multiStripState   = terminalStripState(baseStripped, deferred, baseFailed),
            pendingStripIntent = null,
        ) }
    }

    /** A file the strip tried and could not write reads as a failure, not as a deliberate skip. */
    private fun terminalStripState(stripped: Int, skipped: Int, failed: Int): MultiStripState =
        when (val outcome = stripOutcome(stripped, skipped, failed)) {
            is StripOutcome.Done -> MultiStripState.Done(outcome.stripped, outcome.skipped)
            is StripOutcome.Failed -> MultiStripState.Failed(outcome.message().resolve(context))
        }

    private fun clearPendingStripState() {
        pendingStripConfig = null
        pendingStripUris = emptyList()
        pendingStripStripped = 0
        pendingStripSkipped = 0
        pendingStripFailed = 0
    }

    fun resetMultiStripState() {
        _uiState.update { it.copy(multiStripState = MultiStripState.Idle) }
    }

    private fun applyFilter(
        items: List<GalleryItem>,
        filter: GalleryFilter,
        favoriteIds: Set<String> = emptySet(),
        albumHideCloudIds: Set<String> = emptySet(),
        offlinePinIds: Set<String> = emptySet(),
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
        val categoryFiltered = when (filter) {
            GalleryFilter.All -> baseItems
            // Favourite = the device-side flag for a photo that lives only on the device, the Drive
            // Favorite tag (id 0) for a backed-up one. Same rule the grid hearts and the viewer use,
            // through the one helper, so a tab and a heart can never disagree.
            GalleryFilter.Favorites -> baseItems.filter { isItemFavorite(it, favoriteIds) }
            // All other tag filters: server-side tags win when set, but for freshly-uploaded photos
            // and local-only items we also try a client-side heuristic (mime type, filename pattern,
            // aspect ratio, bucket name) so Videos / Screenshots / Panoramas / RAW work immediately.
            // Live Photos (iOS, tag 3) and Motion Photos (Android, tag 4) are the same concept on
            // different platforms and share one "Live Photos" chip in the UI, so either one matches
            // both tags.
            GalleryFilter.LivePhotos, GalleryFilter.MotionPhotos -> baseItems.filter {
                CategorizeItem.belongsTo(it, tagId = 3) || CategorizeItem.belongsTo(it, tagId = 4)
            }
            // Offline = the cloud photos pinned for offline (their linkId is in OFFLINE_PIN_IDS).
            // Not a server tag, so it filters on the pinned set rather than CategorizeItem.
            GalleryFilter.Offline -> baseItems.filter { item ->
                (item as? GalleryItem.CloudOnly)?.cloud?.linkId?.let { id -> id in offlinePinIds } == true
            }
            else -> {
                val tagId = filter.tagId
                if (tagId == null) baseItems else baseItems.filter { CategorizeItem.belongsTo(it, tagId) }
            }
        }
        // Person filter (People rail): keep only items in the selected person's photo set. An
        // in-memory membership test over the already-filtered list, the same shape as the Offline
        // pinned-set test, so it composes on top of whichever category/content filter is active. A
        // no-op when no person is selected. Read from state so every applyFilter caller composes it.
        val personId = _uiState.value.selectedPersonId
        return if (personId == null) categoryFiltered
        else {
            val personKeys = _uiState.value.personPhotoKeys
            categoryFiltered.filter { it.stableId in personKeys }
        }
    }

    private fun applyContentFilter(items: List<GalleryItem>, filter: ContentFilter): List<GalleryItem> {
        var result = items

        // Sync status
        result = when (filter.syncStatus) {
            SyncStatusFilter.All       -> result
            SyncStatusFilter.LocalOnly -> result.filterIsInstance<GalleryItem.LocalOnly>()
            SyncStatusFilter.BackedUp  -> result.filterIsInstance<GalleryItem.Synced>()
            SyncStatusFilter.CloudOnly -> result.filterIsInstance<GalleryItem.CloudOnly>()
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
        if (filter.year != null || filter.month != null || filter.day != null) {
            result = result.filter { item ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = item.captureTimeMs }
                val dom = cal.get(java.util.Calendar.DAY_OF_MONTH)
                val yearMatch  = filter.year  == null || cal.get(java.util.Calendar.YEAR) == filter.year
                val monthMatch = filter.month == null || (cal.get(java.util.Calendar.MONTH) + 1) == filter.month
                val dayMatch   = filter.day   == null ||
                    (if (filter.dayEnd != null) dom in filter.day..filter.dayEnd else dom == filter.day)
                yearMatch && monthMatch && dayMatch
            }
        }

        return result
    }
}
