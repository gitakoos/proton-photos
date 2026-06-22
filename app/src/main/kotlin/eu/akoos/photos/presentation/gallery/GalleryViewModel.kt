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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.ObserveUser
import eu.akoos.photos.R
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
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.util.ExifHelper
import eu.akoos.photos.util.StripResult
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.looksLikeNetworkError
import eu.akoos.photos.util.sanitizeErrorMessage
import eu.akoos.photos.util.computeOnThisDay
import eu.akoos.photos.worker.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

/** Output of the off-main gallery computation: the hidden/bucket-filtered list (→ items), the
 *  tab/content-filtered list (→ filteredItems), the local-only pending count, and the month +
 *  "On this day" groupings now produced here instead of inside Compose composition. */
private data class GalleryComputed(
    val items: List<GalleryItem>,
    val filtered: List<GalleryItem>,
    val pending: Int,
    val monthGroups: List<Pair<String, List<GalleryItem>>>,
    val onThisDay: List<Pair<Int, List<GalleryItem>>>,
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
    private val forceUploadLocalUris: ForceUploadLocalUrisUseCase,
    private val hiddenStorage: HiddenStorageManager,
    private val syncStateRepo: SyncStateRepository,
    private val networkObserver: eu.akoos.photos.util.NetworkObserver,
    private val albumPhotoMembershipDao: eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao,
    private val albumListEvents: eu.akoos.photos.util.AlbumListEventBus,
    private val updateOrchestrator: eu.akoos.photos.presentation.updater.UpdateOrchestrator,
    private val publicLink: eu.akoos.photos.presentation.common.PublicLinkController,
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

    /** True when the device has a validated internet connection. Drives the avatar
     *  offline badge in [GalleryScreen] and gates every cloud-side refresh below. */
    val isOnline: StateFlow<Boolean> = networkObserver.isOnline

    /** Version of an available update the user pushed aside with "Not now", surfaced as the
     *  gallery update banner (null = no banner). Lives in the singleton [UpdateOrchestrator] so it
     *  survives screen recreation; the dialog re-open / hard-dismiss route back through it. */
    val updateBannerVersion: StateFlow<String?> = updateOrchestrator.banner

    /** Banner tap → re-open the update dialog (the Activity's UpdaterHost renders it). */
    fun openUpdateFromBanner() = updateOrchestrator.reopenFromBanner()

    /** Banner X → dismiss this version so the silent check stops re-offering it. */
    fun dismissUpdateBanner() = updateOrchestrator.hardDismissBanner(viewModelScope)

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
        observeGridPreferences()
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
                    eu.akoos.photos.domain.usecase.UploadStatus.WaitingForWifi -> false
                    eu.akoos.photos.domain.usecase.UploadStatus.PreparingBackup -> false
                }
                _uiState.update {
                    it.copy(
                        isSyncing = syncing,
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
                    )
                    _uiState.update { it.copy(favoriteIds = favIds, filteredItems = filtered) }
                    recomputeMonthGroups(filtered)
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

    private fun observeGallery() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch

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
            val hiddenCloudLinkIdsFlow = syncStateRepo.observeAll(userId).map { states ->
                states.asSequence()
                    .filter { it.status == SyncStatus.HIDDEN && it.cloudFileId != null }
                    .map { it.cloudFileId!! }
                    .toSet()
            }.catch {
                // A transient DAO read during a write burst degrades to "no overlays"
                // instead of throwing into combine.
                android.util.Log.w("GalleryVM", "hiddenCloudLinkIds source failed: ${it.message}")
                emit(emptySet())
            }

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
            val itemsFlow = getGalleryItems.invoke(userId).sample(300)

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
                        )
                        val pending = items.count { it is GalleryItem.LocalOnly }
                        // Month-bucket the filtered list and compute "On this day" here, off the
                        // Main thread, rather than inside Compose composition on every list
                        // re-emission (which costs a ~680 ms hitch from a thumbnail-decrypt burst at
                        // 8500+ photos). The label format/locale, item field and encounter order are
                        // kept identical to the grid's expected grouping, so the rendered timeline is
                        // unchanged. groupBy yields a LinkedHashMap, preserving first-seen month
                        // order. "On this day" reads the unfiltered [items] to match the carousel's
                        // filter-independent source (the grid binds allItems = state.items).
                        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                        val monthGroups = filtered
                            .groupBy { item -> monthFormat.format(Date(item.captureTimeMs)) }
                            .entries.map { it.key to it.value }
                        val onThisDay = computeOnThisDay(items)
                        GalleryComputed(items, filtered, pending, monthGroups, onThisDay)
                    }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            items = computed.items,
                            filteredItems = computed.filtered,
                            pendingUploadCount = computed.pending,
                            monthGroups = computed.monthGroups,
                            onThisDayGroups = computed.onThisDay,
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
        // The avatar sync ring reflects real upload activity only (driven by
        // observeBackgroundUploadProgress), not this passive cloud-listing + reconcile pass —
        // otherwise it spun blue for the whole cold-listing walk while nothing was uploading.
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

    /** The in-flight off-main month re-bucket from a filter toggle; cancelled when a newer toggle
     *  supersedes it so the last filter always wins. */
    private var monthGroupsJob: kotlinx.coroutines.Job? = null

    /** Re-bucket the Month-grouped zoom levels off the main thread after a filter toggle updates
     *  [GalleryUiState.filteredItems]. The streaming combine keeps month groups in lockstep with the
     *  list as it loads; the explicit filter handlers update filteredItems synchronously for instant
     *  feedback, so they re-bucket here too — without this the Month-grouped zoom levels keep
     *  rendering the pre-toggle buckets while the flatter levels (which read filteredItems directly)
     *  update correctly. */
    private fun recomputeMonthGroups(filtered: List<GalleryItem>) {
        monthGroupsJob?.cancel()
        monthGroupsJob = viewModelScope.launch {
            val groups = withContext(Dispatchers.Default) {
                val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                filtered.groupBy { item -> fmt.format(Date(item.captureTimeMs)) }
                    .entries.map { it.key to it.value }
            }
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    fun onFilterSelected(filter: GalleryFilter) {
        val state = _uiState.value
        val filtered = applyFilter(
            applyContentFilter(state.items, state.contentFilter),
            filter,
            state.favoriteIds,
            state.albumHideCloudIds,
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
            localRepo.notifyPermissionChanged()
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
                // Whole listing is fresh — warm the rest of the library's thumbnails in the
                // background (lowest priority) so a large account fills in without the user
                // scrolling past every photo. Idempotent + idle-only, so it never blocks scroll.
                cloudRepo.backfillThumbnails(userId)
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
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Cancel the in-flight background back-up from the in-app progress pill — same effect as
     *  the upload notification's cancel (cancels the unique SyncWorker). */
    fun cancelUpload() {
        SyncWorker.cancel(androidx.work.WorkManager.getInstance(context))
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
                    multiDeleteState    = MultiDeleteState.Failed(context.getString(R.string.viewer_delete_drive_failed)),
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
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.gallery_no_local_to_hide))) }
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
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.gallery_copy_to_hidden_failed))) }
                return@launch
            }
            pendingHidePrivateUris = collected

            // Step 2: delete the MediaStore originals (one system-trash dialog on Android 11+).
            val userId = accountManager.getPrimaryUserId().first() ?: run {
                rollbackPendingHide()
                _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.viewer_not_signed_in))) }
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
                    _uiState.update { it.copy(multiHideState = MultiDeleteState.Failed(context.getString(R.string.gallery_move_to_hidden_failed))) }
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
                multiShareState = MultiShareState.Idle,
                selectedItems = emptySet(),
            ) }
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
    // join the album immediately. LocalOnly items have no linkId yet, so they are queued: a
    // "localUri=albumLinkId" entry is written to PENDING_ALBUM_ADDS and a LOCAL_ONLY SyncState row
    // is forced so the upload pipeline backs the file up (even from a non-backup folder) and joins
    // it to the album once the cloud id is known. Albums are references-not-copies on Drive (the
    // photo stays in the Photos root), so there is no file movement and no MediaStore consent dialog.

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
     * to [ForceUploadLocalUrisUseCase], which writes a "localUri=albumLinkId" entry into
     * PENDING_ALBUM_ADDS, forces a LOCAL_ONLY SyncState row so the upload pipeline backs the file
     * up regardless of the backup folder selection, and kicks an upload pass. The upload pipeline
     * joins the freshly uploaded file to the album once its cloud id is known. Returns the number
     * queued.
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
                    context.getString(R.string.gallery_enable_metadata_category),
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
            // Live Photos (iOS, tag 3) and Motion Photos (Android, tag 4) are the same concept on
            // different platforms and share one "Live Photos" chip in the UI, so either one matches
            // both tags.
            GalleryFilter.LivePhotos, GalleryFilter.MotionPhotos -> baseItems.filter {
                CategorizeItem.belongsTo(it, tagId = 3) || CategorizeItem.belongsTo(it, tagId = 4)
            }
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
        if (filter.year != null || filter.month != null || filter.day != null) {
            result = result.filter { item ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = item.captureTimeMs }
                val yearMatch  = filter.year  == null || cal.get(java.util.Calendar.YEAR) == filter.year
                val monthMatch = filter.month == null || (cal.get(java.util.Calendar.MONTH) + 1) == filter.month
                val dayMatch   = filter.day   == null || cal.get(java.util.Calendar.DAY_OF_MONTH) == filter.day
                yearMatch && monthMatch && dayMatch
            }
        }

        return result
    }
}
