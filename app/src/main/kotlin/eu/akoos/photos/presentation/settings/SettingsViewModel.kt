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

package eu.akoos.photos.presentation.settings

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import coil.imageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.telemetry.domain.usecase.IsTelemetryEnabled
import me.proton.core.user.domain.usecase.GetUser
import me.proton.core.user.domain.usecase.ObserveUser
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.FaceScanDao
import eu.akoos.photos.data.db.dao.PersonDao
import eu.akoos.photos.data.db.dao.NotPersonDao
import eu.akoos.photos.data.db.dao.PersonCoverDao
import eu.akoos.photos.data.db.dao.PersonManualPhotoDao
import eu.akoos.photos.data.face.FaceEmbeddingModelManager
import eu.akoos.photos.data.face.FaceIndexingProgress
import eu.akoos.photos.data.face.FaceIndexingScheduler
import eu.akoos.photos.data.face.FaceIndexingState
import eu.akoos.photos.data.face.FaceModelManager
import eu.akoos.photos.data.face.FaceModelPreparation
import eu.akoos.photos.data.ocr.OcrModelComponent
import eu.akoos.photos.data.ocr.OcrModelManager
import eu.akoos.photos.data.ocr.OcrModelOutcome
import eu.akoos.photos.data.hidden.HiddenVaultJournal
import eu.akoos.photos.data.hidden.HiddenVaultLeftovers
import eu.akoos.photos.data.hidden.HiddenVaultRecords
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.AccountScopedPreferences
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.preferences.syncEffectivelyEnabled
import eu.akoos.photos.presentation.lock.AppLockManager
import eu.akoos.photos.service.ScreenshotOverlayService
import eu.akoos.photos.domain.entity.SyncStatus
import eu.akoos.photos.domain.entity.UploadCompressionTier
import kotlinx.coroutines.flow.combine
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.NewsRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.domain.usecase.ClusterFacesUseCase
import eu.akoos.photos.domain.usecase.ExportFaceIndexUseCase
import eu.akoos.photos.domain.usecase.FaceIndexImportOutcome
import eu.akoos.photos.domain.usecase.ImportFaceIndexUseCase
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.domain.usecase.UploadStatus
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.util.DeviceHealthPolicy
import eu.akoos.photos.util.HealthBlockReason
import eu.akoos.photos.util.heavyMlBlockReason
import eu.akoos.photos.util.retryOnDbTear
import eu.akoos.photos.worker.FreeUpSpaceWorker
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

/**
 * The face card's state after folding [FaceIndexingProgress] together with the live device health.
 * [blockReason] names why heavy indexing is parked, which the scheduler alone cannot tell the card: it
 * keeps reporting [FaceIndexingState.Running] while it stands down for health. [actionEnabled] is false
 * while a persistent block holds, so the card greys out a pause / resume that would not lift it.
 */
data class FaceIndexingUi(
    val state: FaceIndexingState,
    val indexed: Int,
    val total: Int,
    val blockReason: HealthBlockReason,
    val actionEnabled: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val freeUpSpace: FreeUpSpaceUseCase,
    private val accountManager: AccountManager,
    private val reconcile: ReconcileSyncStateUseCase,
    private val upload: UploadPendingUseCase,
    private val observeUser: ObserveUser,
    private val getUser: GetUser,
    private val isTelemetryEnabled: IsTelemetryEnabled,
    private val syncStateRepo: SyncStateRepository,
    private val appLockManager: AppLockManager,
    private val localMediaRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val cloudTrashService: eu.akoos.photos.data.repository.drive.CloudTrashService,
    private val offlineStore: OfflineStorageManager,
    private val hiddenStorage: eu.akoos.photos.data.hidden.HiddenStorageManager,
    private val hiddenVaultJournal: HiddenVaultJournal,
    private val faceIndexingScheduler: FaceIndexingScheduler,
    private val deviceHealth: DeviceHealthPolicy,
    private val personDao: PersonDao,
    private val faceDao: FaceDao,
    private val faceScanDao: FaceScanDao,
    private val personManualPhotoDao: PersonManualPhotoDao,
    private val notPersonDao: NotPersonDao,
    private val personCoverDao: PersonCoverDao,
    private val clusterFacesUseCase: ClusterFacesUseCase,
    private val exportFaceIndexUseCase: ExportFaceIndexUseCase,
    private val importFaceIndexUseCase: ImportFaceIndexUseCase,
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val newsRepository: NewsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** True while there is unread news, for the banner above the Settings page's News row. Mirrors the
     *  settings-icon dot (0 while news is off), so reading the news clears both. */
    val newsUnread: StateFlow<Boolean> = newsRepository.observeUnreadCount()
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** Model-presence checks for the per-feature AI gates. Built from [context] rather than injected,
     *  matching the editor and the indexing scheduler, since they carry no state worth sharing and are
     *  only ever asked whether their files are on disk. */
    private val ocrModelManager by lazy { OcrModelManager(context) }
    private val faceModelManager by lazy { FaceModelManager(context) }
    private val faceEmbeddingModelManager by lazy { FaceEmbeddingModelManager(context) }

    /** Where the background face indexer stands, surfaced verbatim from the scheduler so the AI
     *  settings panel can label its state and offer pause / resume. */
    val faceIndexingProgress: StateFlow<FaceIndexingProgress> = faceIndexingScheduler.progress

    /** The face card's view, folding the scheduler's progress together with the live device health so
     *  the card can name a health pause the walk itself never reports (it keeps emitting
     *  [FaceIndexingState.Running] while parked for health) and grey out a control that pressing would
     *  not help. A persistent block (low battery, warm, power saver) disables the action; a transient
     *  interaction pause or a clear device leaves it enabled. */
    val faceIndexingUi: StateFlow<FaceIndexingUi> =
        combine(faceIndexingScheduler.progress, deviceHealth.snapshot) { p, snap ->
            val blockReason = heavyMlBlockReason(snap)
            FaceIndexingUi(
                state = p.state,
                indexed = p.indexed,
                total = p.total,
                blockReason = blockReason,
                actionEnabled = !blockReason.isPersistent,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            FaceIndexingUi(FaceIndexingState.Idle, 0, 0, HealthBlockReason.NONE, actionEnabled = true),
        )

    /** How many people the face clustering has grouped for the active account, 0 when signed out.
     *  Re-resolves on an account switch so the panel never carries the previous account's count. */
    val peopleCount: StateFlow<Int> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId != null) {
                // Count exactly the named people the tiles beside this figure show, so the header and
                // the row agree: unnamed clusters have no tile, and the Unsorted bucket is not a person.
                personDao.observePeopleForUser(userId.id)
                    .map { namedPeopleCount(it) }
            } else {
                flowOf(0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    /** The active account's clustered people resolved to face-crop tiles for the AI panel, empty when
     *  signed out. Feeds the gallery's own [ObservePeopleUseCase] against the shared library, so the
     *  panel shows the same faces the People rail does, and re-resolves on an account switch. */
    val people: StateFlow<List<PersonUi>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else observePeopleUseCase(userId, getGalleryItems.invoke(userId))
                .map { list -> list.mapNotNull { it.toPersonUi() }.filter { !it.displayName.isNullOrBlank() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Adapt a domain [PersonSummary] to the gallery's [PersonUi]; a person with no resolvable cover is
     *  dropped, matching how the People rail maps them. */
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

    init {
        loadPrefs()
        resolveFaceRecognitionAvailability()
        observeCurrentUser()
        observeTelemetryEnabled()
        observeBackedUpBytes()
        observeTrashedCount()
        observeCloudTrashOnSession()
        observeUploadProgress()
        observeExcludedFolders()
        observeVaultedCount()
        refreshLocalStorage()
        loadCloudTrashCount()
    }

    /**
     * Keep [SettingsUiState.excludedFolderNames] in lockstep with DataStore so changes
     * made inside [ExcludedFoldersScreen] (its own ViewModel) propagate back to the
     * SyncSettings card the moment the user pops the back stack. Without this, the
     * "N folders excluded" subtitle would stale-cache until process restart.
     */
    private fun observeExcludedFolders() {
        viewModelScope.launch {
            context.settingsDataStore.data
                .map { it[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet() }
                .distinctUntilChanged()
                .collectLatest { excluded ->
                    _uiState.update { it.copy(excludedFolderNames = excluded) }
                }
        }
    }

    /**
     * Count what a sign-out takes with it: the photos the vault holds FILES for.
     *
     * Only a vault entry counts. An entry hiding a photo that is still on the device or on Drive is a
     * filter, and sign-out only drops the filter — nothing of the photo is lost, so warning about it
     * would be a warning the user cannot act on.
     *
     * Three things together keep the figure from ever sitting below what the wipe destroys.
     * Reconciliation is awaited first, so a hide interrupted between removing its original and
     * recording its entry is already settled — published as a vault entry, or dropped because its
     * original is proven to still be on the device — before any number reaches the screen. The count
     * itself then comes from the files on disk as well as the index, because the wipe takes the vault
     * directory whole whether or not anything refers to a file in it. And
     * [SettingsUiState.vaultedCountSettled] holds the confirmation back until the first real figure
     * lands, so the dialog cannot open on a number the user then watches change.
     *
     * Both the reconciliation and the directory listing run off the main thread, and the listing is
     * repeated only when the index itself changes.
     */
    private fun observeVaultedCount() {
        viewModelScope.launch {
            hiddenVaultJournal.reconcile()
            context.settingsDataStore.data
                .map { prefs ->
                    (prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) to
                        HiddenVaultRecords.pairedUris(prefs[SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP] ?: emptySet())
                }
                .distinctUntilChanged()
                // An unreadable preference store leaves the count at zero, but the confirmation is
                // still better shown than withheld: a user who cannot sign out has no way forward.
                .catch { _uiState.update { state -> state.copy(vaultedCountSettled = true) } }
                .collectLatest { (indexed, paired) ->
                    val recorded = indexed.filterTo(HashSet()) { hiddenStorage.isHiddenUri(it) }
                    val everything = try {
                        withContext(Dispatchers.IO) {
                            HiddenVaultLeftovers.vaultedUris(
                                blobUris = hiddenStorage.vaultBlobUris(),
                                recordedVaultUris = recorded,
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        android.util.Log.w("SettingsViewModel", "vault count: directory unreadable", e)
                        recorded
                    }
                    _uiState.update {
                        it.copy(
                            vaultedPhotoCount = everything.size,
                            // A vault file no record names cannot name a Drive copy either, so the
                            // intersection is exactly the photos a sign-out leaves recoverable.
                            vaultedCloudBackedCount = everything.count { uri -> uri in paired },
                            vaultedCountSettled = true,
                        )
                    }
                }
        }
    }

    /**
     * Pull a fresh vault snapshot into [SettingsUiState.vaultDiagnostics] for the shared diagnostics
     * bundle, which the screen assembles from state alone.
     *
     * Read on demand rather than observed: it walks the vault directory, so a screen that kept it live
     * would re-walk it on every preference change to answer a question nobody has asked yet. The
     * diagnostics chooser opening is exactly when the answer is wanted.
     */
    fun refreshVaultDiagnostics() {
        viewModelScope.launch {
            val snapshot = hiddenVaultJournal.vaultSnapshot()
            _uiState.update { it.copy(vaultDiagnostics = snapshot) }
        }
    }

    /**
     * Recomputes device free/total bytes (StatFs on /data) and walks context.cacheDir to size up
     * the on-disk app cache. Called once at init and again from [refresh] / after [clearAppCache].
     *
     * StatFs is cheap (a single statvfs syscall), but the cache walk can touch hundreds of files
     * — keep it off the main thread via Dispatchers.IO.
     */
    fun refreshLocalStorage() {
        viewModelScope.launch {
            val measured = withContext(Dispatchers.IO) {
                // /data partition — same partition where cacheDir lives, so it's the relevant
                // "free space" number for users worrying about the app filling their device.
                val statFs = StatFs(Environment.getDataDirectory().absolutePath)
                val total = statFs.totalBytes
                val free  = statFs.availableBytes
                val cacheBytes = computeCacheBytes(context.cacheDir)
                val offlineBytes = offlineStore.computeSizeBytes()
                LocalStorage(total, free, cacheBytes, offlineBytes)
            }
            _uiState.update {
                it.copy(
                    deviceTotalBytes = measured.total,
                    deviceFreeBytes  = measured.free,
                    appCacheBytes    = measured.cacheBytes,
                    offlineBytes     = measured.offlineBytes,
                )
            }
        }
    }

    /**
     * Walks [dir] and sums file lengths. Defensive against deletions racing with the walk —
     * dead File handles return length() == 0. Skips symlinks implicitly because File.length()
     * follows links and returns the target size (we never create symlinks in cacheDir).
     */
    private fun computeCacheBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walk()
            .filter { it.isFile }
            .map { runCatching { it.length() }.getOrDefault(0L) }
            .sum()
    }

    /**
     * Wipes the three subdirs we manage under cacheDir:
     * - `thumbnails/` — decrypted photo thumbnails
     * - `fullres/`    — full-res photo + streamed video downloads
     * - `upload_<id>/` — temp encrypted block dirs from in-flight uploads
     *
     * Everything else under cacheDir (including OkHttp's HTTP cache and Android's `code_cache/`)
     * is left alone — those are managed by their owners and silently rebuilt, but blindly nuking
     * them risks interfering with concurrent IO. After delete we recompute `appCacheBytes` so
     * the UI reflects the new value.
     */
    fun clearAppCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cache = context.cacheDir
                listOf("thumbnails", "fullres", "fullres-session").forEach { name ->
                    val sub = File(cache, name)
                    if (sub.exists()) sub.deleteRecursively()
                }
                // upload_<fileId> dirs are siblings — enumerate.
                cache.listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name.startsWith("upload_")) {
                        f.deleteRecursively()
                    }
                }
                // The decrypted thumbnail files are gone but the DB still points at them;
                // null those paths so visible cells re-request a decrypt off the persisted
                // crypto material instead of staying blank until a full library refresh.
                cloudRepo.clearCachedThumbnailUrls()
            }
            refreshLocalStorage()
        }
    }

    /**
     * Wipes the offline-pinned full-res blobs ([OfflineStorageManager.clearAll]) AND drops the
     * pin set ([SettingsKeys.OFFLINE_PIN_IDS]) so the two stay consistent — deleting blobs while
     * leaving the set would leave "pinned" photos with no blob backing them. Recomputes
     * `offlineBytes` afterwards so the row reflects the cleared figure.
     */
    fun clearOfflineStorage() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                offlineStore.clearAll()
                context.settingsDataStore.edit { it.remove(SettingsKeys.OFFLINE_PIN_IDS) }
            }
            refreshLocalStorage()
        }
    }

    /**
     * Manual refresh hook — currently invoked by the storage-card refresh affordance.
     * Also re-pulls cloud usage from the Drive API so the Proton bar reflects fresh usage.
     */
    fun refresh() {
        refreshLocalStorage()
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().firstOrNull() ?: return@launch
            try {
                getUser(userId, refresh = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SettingsViewModel", "refresh: getUser failed", e)
            }
        }
    }

    /**
     * Mirror the use-case's per-file upload events into UiState so the Sync card can render
     * a progress bar + expandable list without the composable having to subscribe to the
     * SharedFlow directly. We keep the last ~30 events to bound memory while still showing a
     * meaningful trail when the user expands the list.
     */
    private fun observeUploadProgress() {
        viewModelScope.launch {
            // Local batch-window state — only the observer touches it, so plain
            // vals are fine. Reset on Idle, accumulated as files progress.
            var batchStartMs = 0L
            // Live per-URI byte counter — Stage 3 plumbed `evt.doneBytes` via the
            // per-block onProgress callback. Tracking each file's *current* upload
            // bytes (not just file-completion totals) means the MB/s read-out
            // tracks the real network throughput LIVE during one large upload
            // instead of bumping in jumps at each Done event. A small photo batch
            // no longer reads as "150 KB/s" just because the per-file overhead
            // dominates the cumulative average.
            val uploadedBytesByUri = mutableMapOf<String, Long>()
            // Use `collect` (NOT `collectLatest`) — with parallel uploads two or
            // three events can arrive in the same millisecond, and `collectLatest`
            // would cancel the in-flight `_uiState.update` block before its copy()
            // returned, leaving the Sync card stuck on a stale row.
            upload.progress.collect { evt ->
                _uiState.update { current ->
                    when (evt.status) {
                        UploadStatus.Idle -> {
                            // End-of-batch: KEEP the events list visible so the user can
                            // re-enter Settings and see what was just uploaded. Clear only
                            // the live counters (bytes/sec, in-flight tally). The next
                            // Uploading event with doneIdx=0 will reset the panel for a
                            // fresh batch.
                            batchStartMs = 0L
                            uploadedBytesByUri.clear()
                            current.copy(uploadBytesPerSecond = null, uploadDeferReason = null)
                        }
                        UploadStatus.WaitingForWifi,
                        UploadStatus.PreparingBackup,
                        UploadStatus.StorageFull -> {
                            // Deferral frame — no per-file payload. Surface the reason as a
                            // one-line note in the Sync card without touching the events list
                            // or byte counters. StorageFull also ends the batch, so it clears the
                            // live counters the way Idle does; the reason itself has to survive,
                            // because nothing will resolve it until the user frees space.
                            val reason = when (evt.status) {
                                UploadStatus.WaitingForWifi -> R.string.sync_deferred_waiting_wifi
                                UploadStatus.StorageFull -> R.string.sync_deferred_storage_full
                                else -> R.string.sync_deferred_preparing
                            }
                            if (evt.status == UploadStatus.StorageFull) {
                                batchStartMs = 0L
                                uploadedBytesByUri.clear()
                                current.copy(uploadBytesPerSecond = null, uploadDeferReason = reason)
                            } else {
                                current.copy(uploadDeferReason = reason)
                            }
                        }
                        else -> {
                            val uiStatus = when (evt.status) {
                                UploadStatus.Uploading -> UploadEventStatus.Uploading
                                UploadStatus.Encrypting -> UploadEventStatus.Encrypting
                                UploadStatus.Done -> UploadEventStatus.Done
                                UploadStatus.Failed -> UploadEventStatus.Failed
                                UploadStatus.Queued -> UploadEventStatus.Queued
                                UploadStatus.Idle,
                                UploadStatus.WaitingForWifi,
                                UploadStatus.PreparingBackup,
                                UploadStatus.StorageFull -> UploadEventStatus.Done // unreachable
                            }
                            // New-batch detection: a fresh Uploading/Encrypting event that
                            // arrives when the previous batch fully completed (done == total > 0)
                            // signals the start of a new run. Wipe the stale events + bytes map
                            // so the panel doesn't merge two batches into one row list.
                            val firstPerFileStatus = evt.status == UploadStatus.Uploading ||
                                evt.status == UploadStatus.Encrypting
                            val isNewBatch = firstPerFileStatus &&
                                current.uploadTotalCount > 0 &&
                                current.uploadDoneCount >= current.uploadTotalCount
                            val carryEvents = if (isNewBatch) emptyList() else current.uploadEvents
                            if (isNewBatch) uploadedBytesByUri.clear()

                            // Replace the prior entry for this URI (so an "Uploading" row
                            // becomes "Done" instead of accumulating dupes), then trim head.
                            val withoutPrev = carryEvents.filter { it.uri != evt.uri }
                            val nextEvents = (withoutPrev + UploadEvent(
                                uri = evt.uri,
                                displayName = evt.displayName,
                                status = uiStatus,
                                sizeBytes = evt.sizeBytes,
                            )).takeLast(30)

                            // Start the batch timer on the FIRST per-file signal of the run
                            // — Encrypting fires before Uploading so a CPU-bound first phase
                            // doesn't get hidden from the bytes/sec window.
                            if (batchStartMs == 0L && firstPerFileStatus) {
                                batchStartMs = System.currentTimeMillis()
                            }

                            // Live byte counter — only Uploading + Done contribute to the
                            // NETWORK byte count. Encrypting is CPU-only work; counting its
                            // doneBytes would inflate the speed during the pre-network phase.
                            // Done overrides the live counter with the full file size so a
                            // file's contribution is exact at completion.
                            when (evt.status) {
                                UploadStatus.Uploading -> {
                                    val live = evt.doneBytes.coerceAtLeast(0L)
                                    // Don't go backwards if a late throttled tick reports an
                                    // older value than the previous one.
                                    val prev = uploadedBytesByUri[evt.uri] ?: 0L
                                    uploadedBytesByUri[evt.uri] = maxOf(prev, live)
                                }
                                UploadStatus.Done -> {
                                    uploadedBytesByUri[evt.uri] = evt.sizeBytes.coerceAtLeast(0L)
                                }
                                UploadStatus.Failed -> {
                                    // Leave the partial count — bytes that already left the
                                    // wire still count toward the realised speed.
                                }
                                else -> Unit
                            }

                            val totalLiveBytes = uploadedBytesByUri.values.sum()
                            val bps: Long? = if (batchStartMs > 0L && totalLiveBytes > 0L) {
                                val elapsedMs = (System.currentTimeMillis() - batchStartMs).coerceAtLeast(1L)
                                (totalLiveBytes * 1000L / elapsedMs)
                            } else null

                            current.copy(
                                uploadDoneCount = evt.doneIdx,
                                uploadTotalCount = evt.totalCount,
                                uploadEvents = nextEvents,
                                uploadBytesPerSecond = bps,
                                uploadDeferReason = null,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeCurrentUser() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId()
                .flatMapLatest { userId ->
                    val userFlow = if (userId != null) observeUser(userId) else flowOf(null)
                    userFlow.map { user -> userId to user }
                }
                .collectLatest { (userId, user) ->
                    _uiState.update {
                        it.copy(
                            isSignedIn = userId != null,
                            userDisplayName = user?.displayName?.takeIf { n -> n.isNotBlank() }
                                ?: user?.name?.takeIf { n -> n.isNotBlank() }
                                ?: "",
                            userEmail = user?.email ?: "",
                            cloudUsedBytes = user?.usedDriveSpace ?: user?.usedSpace ?: 0L,
                            cloudMaxBytes = user?.maxDriveSpace ?: user?.maxSpace ?: 0L,
                            // First emission resolves the account skeleton, even if the value
                            // is empty (genuinely signed-out / nameless), so the shimmer ends.
                            accountLoading = false,
                        )
                    }
                }
        }
        // One-shot refresh on entry so the Storage card reflects current Drive usage rather
        // than the cached value from app cold-start. ObserveUser auto-emits once GetUser
        // updates the local cache, so we don't need to feed the result back manually.
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().firstOrNull() ?: return@launch
            try {
                getUser(userId, refresh = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SettingsViewModel", "observeCurrentUser: getUser failed (offline ok)", e)
            }
        }
    }

    /**
     * Mirrors the ProtonCore telemetry preference into [SettingsUiState.telemetryEnabled] for the
     * read-only Privacy row. [IsTelemetryEnabled] suspends on the account settings (network / DB),
     * so it resolves off the render path and the row shows a neutral placeholder until it lands.
     * The value re-resolves per primary user, and an account switch drops back to the placeholder
     * rather than carrying the previous account's answer over.
     *
     * A failure keeps `null` (placeholder), never `false`: [IsTelemetryEnabled] itself falls back
     * to enabled when it cannot read the setting, so rendering "Off" would assure the user of a
     * privacy state they do not actually have.
     */
    private fun observeTelemetryEnabled() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId()
                .distinctUntilChanged()
                .collectLatest { userId ->
                    _uiState.update { it.copy(telemetryEnabled = null) }
                    val enabled = try {
                        isTelemetryEnabled(userId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("SettingsViewModel", "observeTelemetryEnabled: resolve failed", e)
                        null
                    }
                    _uiState.update { it.copy(telemetryEnabled = enabled) }
                }
        }
    }

    /**
     * Observes counts shown in the Sync card.
     *
     * - **Backed up** = total photos that live on Proton Drive. Sourced from the cloud-photo
     *   listing directly (Room Flow), so the number reacts immediately when a fresh upload
     *   lands or when the user deletes a cloud photo / removes a Drive-only album. The previous
     *   implementation counted only SyncState rows in SYNCED status — that misses photos that
     *   exist purely in the cloud (no on-device twin), and never updates after device deletes.
     *
     * - **Pending** = on-device photos that are LOCAL_ONLY AND carry a queued upload intent in
     *   SyncState (i.e. genuinely waiting to upload), the same set the upload processor selects.
     *
     * - **Backed-up bytes** = sum of cloud-photo sizes. Cleaner than the SyncState sum because
     *   it survives device-side cleanup (Free up space).
     */
    private fun observeBackedUpBytes() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId().collectLatest { userId ->
                if (userId == null) return@collectLatest
                combine(
                    cloudRepo.observeCloudPhotos(userId),
                    syncStateRepo.observeAll(userId),
                ) { cloudPhotos, syncStates ->
                    val backedUpVideos = cloudPhotos.count { it.mimeType.startsWith("video/") }
                    val backedUpPhotos = cloudPhotos.size - backedUpVideos
                    val backedUpBytes = cloudPhotos.sumOf { it.sizeBytes }
                    // Only rows that are LOCAL_ONLY AND queued are genuinely waiting to back up, the
                    // same set the upload processor and the Activity screen use. A LOCAL_ONLY row with
                    // no queue intent (an out-of-scope local file) must not inflate the pending count.
                    val pendingCount  = syncStates.count { it.status == SyncStatus.LOCAL_ONLY && it.queued }
                    BackedUpSnapshot(backedUpPhotos, backedUpVideos, backedUpBytes, pendingCount)
                }
                    .retryOnDbTear("SettingsBackedUp")
                    .collectLatest { snap ->
                    _uiState.update {
                        it.copy(
                            backedUpBytes     = snap.bytes,
                            syncedCount       = snap.photos + snap.videos,
                            syncedPhotoCount  = snap.photos,
                            syncedVideoCount  = snap.videos,
                            notSyncedCount    = snap.pending,
                            // First snapshot resolves the counts skeleton, even when all
                            // counts are 0 (genuinely empty), so the shimmer ends.
                            countsLoading     = false,
                        )
                    }
                }
            }
        }
    }

    private fun observeTrashedCount() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            viewModelScope.launch {
                localMediaRepo.observeTrashedMedia().collectLatest { items ->
                    _uiState.update { it.copy(trashedCount = items.size) }
                }
            }
        }
    }

    /**
     * Reset the cached Drive trash count whenever the active account changes (sign-out
     * emits null, sign-in or account switch emits a new UserId). Keeping the previous
     * account's count around after a sign-out would surface a stale "N on Drive" subtitle
     * for the next account that pairs the app. The companion [loadCloudTrashCount] is
     * invoked for non-null transitions so the new account's count populates without
     * waiting for the next manual entry into Sync settings.
     */
    private fun observeCloudTrashOnSession() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId()
                .distinctUntilChanged()
                .collectLatest { userId ->
                    _uiState.update {
                        it.copy(cloudTrashCount = null, lastCloudTrashFetchMs = 0L)
                    }
                    if (userId != null) loadCloudTrashCount()
                }
        }
    }

    /**
     * Pull the Drive trash list and surface its size in UiState. There's no count-only
     * endpoint, so we fetch the full list and read `.size` — same approach the Trash
     * screen takes. Failures (offline, network error) leave `cloudTrashCount` at its
     * prior value or null; the UI then falls back to the device-only subtitle.
     *
     * Cached in memory with a 5-minute TTL via [SettingsUiState.lastCloudTrashFetchMs]
     * so re-entering the Settings screen doesn't fire a Drive call on every navigation.
     */
    private fun loadCloudTrashCount() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val last = _uiState.value.lastCloudTrashFetchMs
            if (last > 0L && (now - last) < CLOUD_TRASH_TTL_MS) return@launch
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val result = runCatching { cloudTrashService.getCloudTrash(userId) }
            _uiState.update {
                it.copy(
                    cloudTrashCount = result.getOrNull()?.size ?: it.cloudTrashCount,
                    lastCloudTrashFetchMs = if (result.isSuccess) now else it.lastCloudTrashFetchMs,
                )
            }
        }
    }

    private fun loadPrefs() {
        viewModelScope.launch {
            // One-shot migration: if THEME_MODE is absent but the legacy DARK_MODE bool is set,
            // copy the value into the new key and drop the bool so the migration only fires once.
            val prefs = context.settingsDataStore.data.first()
            val needsThemeMigration = prefs[SettingsKeys.THEME_MODE] == null &&
                prefs[SettingsKeys.DARK_MODE] != null
            val migratedPrefs = if (needsThemeMigration) {
                val migrated = if (prefs[SettingsKeys.DARK_MODE] == true) "dark" else "light"
                val written = context.settingsDataStore.edit { mut ->
                    mut[SettingsKeys.THEME_MODE] = migrated
                    mut.remove(SettingsKeys.DARK_MODE)
                }
                // Seed the boot mirror so the next cold start applies the migrated value
                // without falling back to "system".
                eu.akoos.photos.data.preferences.ThemePrefsBoot.write(context, migrated)
                written
            } else prefs
            _uiState.update {
                it.copy(
                    autoSync = migratedPrefs[SettingsKeys.AUTO_SYNC] ?: true,
                    syncWifiOnly = migratedPrefs[SettingsKeys.SYNC_WIFI_ONLY] ?: true,
                    fullresWifiOnly = migratedPrefs[SettingsKeys.FULLRES_WIFI_ONLY] ?: true,
                    backupEverything = migratedPrefs[SettingsKeys.BACKUP_EVERYTHING] ?: false,
                    excludedFolderNames = migratedPrefs[SettingsKeys.EXCLUDED_FOLDER_NAMES] ?: emptySet(),
                    autoFreeUp = migratedPrefs[SettingsKeys.AUTO_FREE_UP] ?: false,
                    freeUpInterval = FreeUpInterval.fromKey(migratedPrefs[SettingsKeys.FREE_UP_INTERVAL]),
                    themeMode = ThemeMode.fromKey(migratedPrefs[SettingsKeys.THEME_MODE]),
                    palette = ThemePalette.fromKey(migratedPrefs[SettingsKeys.THEME_PALETTE]),
                    amoledBlack = migratedPrefs[SettingsKeys.AMOLED_BLACK] ?: false,
                    aiFeaturesEnabled = migratedPrefs[SettingsKeys.AI_FEATURES_ENABLED] ?: false,
                    // Absent OCR opt-in defaults to whether the reader's models are already on disk, so
                    // a device that has fetched them opens with Copy text on rather than off.
                    ocrEnabled = migratedPrefs[SettingsKeys.OCR_ENABLED] ?: ocrModelManager.filesPresentQuick(),
                    faceEnabled = migratedPrefs[SettingsKeys.FACE_ENABLED] ?: false,
                    landingTab = LandingTab.fromIndex(migratedPrefs[SettingsKeys.LANDING_TAB]),
                    lastSyncMs = migratedPrefs[SettingsKeys.LAST_SYNC_MS],
                    language = migratedPrefs[SettingsKeys.LANGUAGE] ?: "system",
                    stripOnUpload = migratedPrefs[SettingsKeys.STRIP_ON_UPLOAD] ?: false,
                    compressOnUpload = migratedPrefs[SettingsKeys.COMPRESS_ON_UPLOAD] ?: false,
                    compressVideosOnUpload = migratedPrefs[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] ?: false,
                    compressTier = UploadCompressionTier.fromOrdinalOrDefault(
                        migratedPrefs[SettingsKeys.COMPRESS_UPLOAD_TIER] ?: UploadCompressionTier.BALANCED.ordinal
                    ),
                    mirrorStripToLocal = migratedPrefs[SettingsKeys.MIRROR_STRIP_TO_LOCAL] ?: false,
                    mirrorCompressToLocal = migratedPrefs[SettingsKeys.MIRROR_COMPRESS_TO_LOCAL] ?: false,
                    renameToCaptureDate = migratedPrefs[SettingsKeys.RENAME_TO_CAPTURE_DATE] ?: false,
                    deleteLocalAfterBackup = migratedPrefs[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] ?: false,
                    stripGps = migratedPrefs[SettingsKeys.STRIP_GPS] ?: false,
                    stripCameraInfo = migratedPrefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
                    stripTimestamp = migratedPrefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
                    stripSoftwareInfo = migratedPrefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false,
                    stripOnShare = migratedPrefs[SettingsKeys.STRIP_ON_SHARE] ?: false,
                    stripShareGps = migratedPrefs[SettingsKeys.STRIP_SHARE_GPS] ?: true,
                    stripShareCameraInfo = migratedPrefs[SettingsKeys.STRIP_SHARE_CAMERA_INFO] ?: false,
                    stripShareTimestamp = migratedPrefs[SettingsKeys.STRIP_SHARE_TIMESTAMP] ?: false,
                    stripShareSoftwareInfo = migratedPrefs[SettingsKeys.STRIP_SHARE_SOFTWARE_INFO] ?: false,
                    stripShareAuthorship = migratedPrefs[SettingsKeys.STRIP_SHARE_AUTHORSHIP] ?: false,
                    appLockEnabled = migratedPrefs[SettingsKeys.APP_LOCK_ENABLED] ?: false,
                    appLockTimeoutMinutes = migratedPrefs[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] ?: 0,
                    clearCacheOnAppClose = migratedPrefs[SettingsKeys.CLEAR_CACHE_ON_APP_CLOSE] ?: false,
                    screenshotOverlayEnabled = migratedPrefs[SettingsKeys.SCREENSHOT_OVERLAY_ENABLED] ?: false,
                    showScrollDate = migratedPrefs[SettingsKeys.SHOW_SCROLL_DATE] ?: false,
                    reverseTimelineOrder = migratedPrefs[SettingsKeys.REVERSE_TIMELINE_ORDER] ?: false,
                    mosaicGrid = migratedPrefs[SettingsKeys.MOSAIC_GRID] ?: false,
                    seamlessGrid = migratedPrefs[SettingsKeys.SEAMLESS_GRID] ?: false,
                    gridRememberLast = migratedPrefs[SettingsKeys.GRID_REMEMBER_LAST] ?: false,
                    gridDefaultColumns = migratedPrefs[SettingsKeys.GRID_DEFAULT_COLUMNS] ?: 3,
                    keepScrollOnTabSwitch = migratedPrefs[SettingsKeys.KEEP_SCROLL_ON_TAB_SWITCH] == true,
                )
            }
        }
    }

    /**
     * Resolve whether both face models (the SCRFD detector and the embedder) are already on disk, so
     * the settings panel can show the face toggle as usable rather than offering a switch with no model
     * behind it. Network-free: both [FaceModelManager.onDisk] and [FaceEmbeddingModelManager.onDisk]
     * verify a local copy without fetching.
     */
    private fun resolveFaceRecognitionAvailability() {
        viewModelScope.launch {
            val available =
                faceModelManager.onDisk() != null && faceEmbeddingModelManager.onDisk() != null
            _uiState.update { it.copy(faceRecognitionAvailable = available) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AUTO_SYNC] = enabled }
            _uiState.update { it.copy(autoSync = enabled) }
            // One place arms or tears down ALL background triggers (periodic worker, content
            // observer, opt-in keep-alive service) from the just-written prefs. On disable it also
            // cancels the content observer — leaving it armed would keep firing SyncWorker on every
            // photo write and burn wake-ups even though nothing uploads.
            SyncWorker.reconcileBackgroundWork(context)
        }
    }

    /** Flip the global "back up every MediaStore image/video" mode. When ON, the
     *  reconcile + upload pipeline ignores the folder selection — handy for users who
     *  don't want to micromanage which buckets sync. Off keeps the existing per-folder
     *  picker behaviour. */
    fun setBackupEverything(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.BACKUP_EVERYTHING] = enabled }
            _uiState.update { it.copy(backupEverything = enabled) }
            // Turning this on can make backup effectively-on with no folders selected (arm the
            // triggers); turning it off with no folders selected makes it effectively-off (tear
            // them down). reconcile covers both.
            SyncWorker.reconcileBackgroundWork(context)
            // Kick a sync run so the new mode takes effect immediately instead of waiting
            // for the next periodic SyncWorker tick.
            if (syncEffectivelyEnabled(context)) SyncWorker.runNow(context, _uiState.value.syncWifiOnly)
        }
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.SYNC_WIFI_ONLY] = wifiOnly }
            _uiState.update { it.copy(syncWifiOnly = wifiOnly) }
            if (_uiState.value.autoSync) {
                SyncWorker.schedule(workManager, wifiOnly, SyncWorker.MIN_INTERVAL_MINUTES)
            }
        }
    }

    /** Persist the Wi-Fi-only-for-fullres preference. Affects only the viewer's auto
     *  download — explicit user actions (Save to device, Edit) ignore this setting. */
    fun setFullresWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FULLRES_WIFI_ONLY] = wifiOnly }
            _uiState.update { it.copy(fullresWifiOnly = wifiOnly) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isSyncing = true, syncError = null) }
            eu.akoos.photos.util.SyncDiagnostics.log("sync now: manual refresh requested")
            // Names which step is running so a thrown failure reports the phase that broke
            // ("Couldn't reconcile" vs "Couldn't upload") instead of one generic "Sync failed".
            var failedPhaseRes = R.string.settings_sync_failed
            try {
                // 0. Fresh cloud photo listing — pulls the current Photos stream and DELETES
                //    `photo_listing` rows for items no longer on Drive. Without this step the
                //    subsequent reconcile would see stale SYNCED entries (cloud-deleted items
                //    still mapped from a previous refresh) and keep them green in the UI.
                //    Best-effort: a network failure here shouldn't abort the rest of sync.
                try {
                    cloudRepo.refreshCloudPhotos(userId, force = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("SettingsViewModel", "syncNow: refreshCloudPhotos failed (best-effort)", e)
                }
                // 1. Reconcile: refresh cloud DB + mark unsynced local photos as LOCAL_ONLY,
                //    demote SYNCED → CLOUD_ONLY for cloud-deleted items.
                failedPhaseRes = R.string.settings_sync_failed_reconcile
                reconcile(userId).collect {}
                // 2. Upload: hand the LOCAL_ONLY photos to the SyncWorker rather than uploading
                //    inline here. The worker is the sole upload owner so the in-app cancel can stop
                //    the batch; an inline upload in this viewModelScope would be uncancellable. The
                //    live per-file progress still renders via observeUploadProgress (it reads the
                //    shared use-case progress flow regardless of who triggered the run), and the
                //    worker writes LAST_SYNC_MS itself. allowLowBattery = true: the user asked.
                failedPhaseRes = R.string.settings_sync_failed_upload
                // Mark the run as asked-for before handing it over, so it still uploads with
                // auto-backup switched off. This row is a one-tap action, not a setting: gating it
                // on the switch would leave a button that reconciles and then quietly does nothing.
                upload.requestManualRun()
                SyncWorker.runNow(context, _uiState.value.syncWifiOnly, allowLowBattery = true)
                val now = System.currentTimeMillis()
                context.settingsDataStore.edit { it[SettingsKeys.LAST_SYNC_MS] = now }
                _uiState.update { it.copy(lastSyncMs = now) }
                // 3. Force-refresh user data so storage bar reflects the new usage.
                //    ObserveUser emits automatically once GetUser updates the local cache.
                try {
                    getUser(userId, refresh = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("SettingsViewModel", "syncNow: post-sync getUser failed", e)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(syncError = context.getString(failedPhaseRes)) }
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun clearSyncError() = _uiState.update { it.copy(syncError = null) }

    /**
     * Applies the free-up settings now in state to the periodic sweep: it stays enqueued only while
     * [SettingsUiState.autoFreeUp] is on, and always carries the interval the UI shows. Callers run
     * it after their own state update, so the value it reads is the new one.
     */
    private fun applyFreeUpSchedule() {
        val state = _uiState.value
        if (state.autoFreeUp) {
            FreeUpSpaceWorker.schedule(
                workManager,
                state.freeUpInterval.ms,
            )
        } else {
            FreeUpSpaceWorker.cancel(workManager)
        }
    }

    fun setAutoFreeUp(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AUTO_FREE_UP] = enabled }
            _uiState.update { it.copy(autoFreeUp = enabled) }
            applyFreeUpSchedule()
        }
    }

    fun setFreeUpInterval(interval: FreeUpInterval) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FREE_UP_INTERVAL] = interval.name }
            _uiState.update { it.copy(freeUpInterval = interval) }
            applyFreeUpSchedule()
        }
    }


    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit {
                it[SettingsKeys.THEME_MODE] = mode.storageKey
                it.remove(SettingsKeys.DARK_MODE)
            }
            // Mirror the value to the boot SharedPreferences cache so the next cold start
            // can apply the theme without blocking on DataStore. See ThemePrefsBoot for
            // why we keep two stores in lockstep instead of relying on DataStore alone.
            eu.akoos.photos.data.preferences.ThemePrefsBoot.write(context, mode.storageKey)
            // We DELIBERATELY skip AppCompatDelegate.setDefaultNightMode here. That call
            // forces an Activity recreate (visible as a scale-from-center animation) any
            // time the new mode differs from the currently-applied one. The Compose tree
            // re-themes via the DataStore flow MainActivity collects, so the in-app
            // surfaces flip instantly without a recreate. ProtonCore login Activities
            // pick up the right theme at the next cold start through
            // App.applyStoredThemeMode — they don't re-launch within the same session.
            _uiState.update { it.copy(themeMode = mode) }
        }
    }

    /**
     * Persist the chosen accent palette and update the in-memory state so the active
     * `ProtonPhotosTheme` re-collects the new key. Note: NOT mirrored to ThemePrefsBoot
     * and does NOT touch AppCompatDelegate — palette is purely an in-Compose accent
     * swap and doesn't affect light/dark, system-bar styling, or cold-start theming.
     */
    fun setThemePalette(palette: ThemePalette) {
        viewModelScope.launch {
            context.settingsDataStore.edit {
                it[SettingsKeys.THEME_PALETTE] = palette.storageKey
            }
            _uiState.update { it.copy(palette = palette) }
        }
    }

    /**
     * Persist the AMOLED pure-black switch. Like the palette, this is a pure in-Compose surface
     * swap: MainActivity re-collects the key and re-themes live, so no boot mirror and no
     * AppCompatDelegate recreate are needed.
     */
    fun setAmoledBlack(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AMOLED_BLACK] = enabled }
            _uiState.update { it.copy(amoledBlack = enabled) }
        }
    }


    /**
     * Persist the master AI-features opt-in. Off keeps every on-device model unfetched and hides the
     * Copy text and Hide faces entry points; the People grouping added later reads the same gate. The
     * editor and viewer observe [SettingsKeys.AI_FEATURES_ENABLED] directly, so flipping it takes
     * effect the next time either surface is opened.
     */
    fun setAiFeaturesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AI_FEATURES_ENABLED] = enabled }
            _uiState.update { it.copy(aiFeaturesEnabled = enabled) }
            if (enabled) {
                // Opt-in begins indexing straight away, so there is no separate manual start step: new
                // photos are then picked up automatically by the library's own indexing trigger.
                accountManager.getPrimaryUserId().first()?.let { faceIndexingScheduler.requestIndex(it) }
            } else {
                // Turning AI off stands any running scan down promptly rather than waiting for its next
                // per-photo check to notice the flag.
                faceIndexingScheduler.reset()
            }
        }
    }

    /**
     * Handle the Copy text per-feature switch. Independent of the master AI switch: it gates only the
     * read-the-text gesture, leaving the other AI features running. Turning it on manages the model
     * rather than just flipping a flag: with the model already on disk it enables at once, and without
     * it, it raises the download-consent drawer instead of enabling against a model that is not there.
     * Turning it off disables at once and then asks whether to remove the downloaded model.
     */
    fun setOcrEnabled(enabled: Boolean) {
        if (enabled) enableOcr() else disableOcr()
    }

    /** Model on disk already: flip Copy text on. Otherwise hold it off and ask to fetch the model first. */
    private fun enableOcr() {
        viewModelScope.launch {
            val present = withContext(Dispatchers.IO) { ocrModelManager.filesPresentQuick() }
            if (present) {
                context.settingsDataStore.edit { it[SettingsKeys.OCR_ENABLED] = true }
                _uiState.update { it.copy(ocrEnabled = true, ocrModelDownloadFailed = false) }
            } else {
                _uiState.update {
                    it.copy(ocrModelPrompt = OcrModelPrompt.Download, ocrModelDownloadFailed = false)
                }
            }
        }
    }

    /** Flipping Copy text off opens the drawer while the feature stays on, so a tap outside leaves
     *  everything as it was. Keep or Remove in the drawer are what actually switch it off. */
    private fun disableOcr() {
        _uiState.update {
            it.copy(ocrModelPrompt = OcrModelPrompt.Remove, ocrModelDownloadFailed = false)
        }
    }

    /** The drawer's Keep action: switch Copy text off but leave the downloaded model in place. */
    fun disableOcrKeepingModel() {
        _uiState.update { it.copy(ocrModelPrompt = OcrModelPrompt.None) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.OCR_ENABLED] = false }
            _uiState.update { it.copy(ocrEnabled = false) }
        }
    }

    /**
     * Accept the Copy text model download. Records the consent the reader itself also checks, fetches
     * both model halves off the main thread while the panel shows progress, and turns the feature on
     * only once both verify. A failed fetch leaves it off and surfaces the failure on the row.
     */
    fun confirmOcrModelDownload() {
        _uiState.update {
            it.copy(
                ocrModelPrompt = OcrModelPrompt.None,
                ocrModelDownloading = true,
                ocrModelDownloadFailed = false,
            )
        }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.OCR_MODEL_DOWNLOAD_ALLOWED] = true }
            // The reader needs both the detection and the recognition halves, so both are fetched before
            // the switch flips on; the detection half comes first, so the larger one is not fetched when
            // the smaller one cannot be.
            val ready = listOf(OcrModelComponent.Detection, OcrModelComponent.Recognition)
                .all { ocrModelManager.ensure(it) is OcrModelOutcome.Ready }
            if (ready) {
                context.settingsDataStore.edit { it[SettingsKeys.OCR_ENABLED] = true }
                _uiState.update { it.copy(ocrEnabled = true, ocrModelDownloading = false) }
            } else {
                _uiState.update { it.copy(ocrModelDownloading = false, ocrModelDownloadFailed = true) }
            }
        }
    }

    /**
     * The drawer's Remove action: switch Copy text off AND delete the downloaded model from the device.
     * Deleting the files also resets the download consent so a later re-enable asks again.
     */
    fun confirmOcrModelRemoval() {
        _uiState.update { it.copy(ocrModelPrompt = OcrModelPrompt.None) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.OCR_ENABLED] = false }
            ocrModelManager.deleteAll()
            context.settingsDataStore.edit { it[SettingsKeys.OCR_MODEL_DOWNLOAD_ALLOWED] = false }
            _uiState.update { it.copy(ocrEnabled = false, ocrModelDownloadFailed = false) }
        }
    }

    /** Close either OCR drawer with no change: a tap outside leaves the switch and the model exactly as
     *  they were, so an accidental toggle undoes itself rather than turning the feature off unasked. */
    fun dismissOcrModelPrompt() {
        _uiState.update { it.copy(ocrModelPrompt = OcrModelPrompt.None) }
    }

    /**
     * The face-features per-feature opt-in, managing the models rather than just flipping a flag.
     * Turning it on with the models on disk enables at once and starts a scan; without them it raises
     * the download-consent drawer instead of switching on against models that are not there. Turning it
     * off with the models on disk opens the Keep-or-Remove drawer; with no models on disk there is
     * nothing to remove, so it just switches off. Independent of the master switch, so it gates the face
     * pipeline on its own.
     */
    fun setFaceEnabled(enabled: Boolean) {
        if (enabled) enableFace() else disableFace()
    }

    /** Both models on disk already: flip face recognition on and start a scan. Otherwise hold it off and
     *  ask to fetch the models first, so the switch never turns on against models that are not there. */
    private fun enableFace() {
        viewModelScope.launch {
            val present = withContext(Dispatchers.IO) {
                faceModelManager.onDisk() != null && faceEmbeddingModelManager.onDisk() != null
            }
            if (present) {
                context.settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = true }
                _uiState.update {
                    it.copy(
                        faceEnabled = true,
                        faceRecognitionAvailable = true,
                        faceModelPrompt = FaceModelPrompt.None,
                        faceModelDownloadFailed = false,
                    )
                }
                accountManager.getPrimaryUserId().first()?.let { faceIndexingScheduler.requestIndex(it) }
            } else {
                _uiState.update {
                    it.copy(faceModelPrompt = FaceModelPrompt.Download, faceModelDownloadFailed = false)
                }
            }
        }
    }

    /** Flipping face recognition off with the models on disk opens the Keep-or-Remove drawer while the
     *  feature stays on, so a tap outside leaves everything as it was. With no models on disk there is
     *  nothing to remove, so it switches off at once, which is also the way out of a stranded on state
     *  left by a model that is no longer there. */
    private fun disableFace() {
        viewModelScope.launch {
            val present = withContext(Dispatchers.IO) {
                faceModelManager.onDisk() != null && faceEmbeddingModelManager.onDisk() != null
            }
            if (present) {
                _uiState.update { it.copy(faceModelPrompt = FaceModelPrompt.Remove) }
            } else {
                context.settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = false }
                faceIndexingScheduler.reset()
                _uiState.update {
                    it.copy(
                        faceEnabled = false,
                        faceRecognitionAvailable = false,
                        faceModelPrompt = FaceModelPrompt.None,
                    )
                }
            }
        }
    }

    /**
     * Accept the face model download. Records the consent the scanner itself also checks, fetches both
     * the detector and the embedder off the main thread while the row shows progress, and turns the
     * feature on only once both verify. The small detector comes first, so the larger embedder is not
     * fetched when the detector cannot be. A failed fetch leaves it off and surfaces the failure on the row.
     */
    fun confirmFaceModelDownload() {
        _uiState.update {
            it.copy(
                faceModelPrompt = FaceModelPrompt.None,
                faceModelDownloading = true,
                faceModelDownloadFailed = false,
            )
        }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FACE_MODEL_DOWNLOAD_ALLOWED] = true }
            val ready = withContext(Dispatchers.IO) {
                faceModelManager.prepare() is FaceModelPreparation.Ready &&
                    faceEmbeddingModelManager.prepare() is FaceModelPreparation.Ready
            }
            if (ready) {
                context.settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = true }
                _uiState.update {
                    it.copy(
                        faceEnabled = true,
                        faceRecognitionAvailable = true,
                        faceModelDownloading = false,
                    )
                }
                accountManager.getPrimaryUserId().first()?.let { faceIndexingScheduler.requestIndex(it) }
            } else {
                _uiState.update {
                    it.copy(faceModelDownloading = false, faceModelDownloadFailed = true)
                }
            }
        }
    }

    /** The drawer's Keep action: switch face recognition off but leave the model and the face data. */
    fun disableFaceKeepingData() {
        _uiState.update { it.copy(faceModelPrompt = FaceModelPrompt.None) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = false }
            _uiState.update { it.copy(faceEnabled = false) }
            faceIndexingScheduler.reset()
        }
    }

    /**
     * The drawer's Remove action: switch face recognition off AND delete the model and the account's
     * face data from this device. It stands any running walk down, clears every face and person table in
     * the sign-out order, deletes both model files, and marks face recognition unavailable so the toggle
     * has nothing to switch on until a model is side-loaded again.
     */
    fun confirmFaceModelRemoval() {
        _uiState.update { it.copy(faceModelPrompt = FaceModelPrompt.None) }
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FACE_ENABLED] = false }
            faceIndexingScheduler.reset()
            accountManager.getPrimaryUserId().first()?.let { userId ->
                faceDao.clearForUser(userId.id)
                personDao.clearForUser(userId.id)
                faceScanDao.clearForUser(userId.id)
                personManualPhotoDao.clearForUser(userId.id)
                notPersonDao.clearForUser(userId.id)
                personCoverDao.clearForUser(userId.id)
            }
            faceModelManager.deleteAll()
            faceEmbeddingModelManager.deleteAll()
            _uiState.update { it.copy(faceEnabled = false, faceRecognitionAvailable = false) }
        }
    }

    /** The first drawer's Remove asks for a final confirmation before anything is deleted, so an
     *  accidental tap cannot wipe the model and face data. */
    fun requestFaceModelRemoval() {
        _uiState.update { it.copy(faceModelPrompt = FaceModelPrompt.ConfirmRemove) }
    }

    /** The final confirmation's Cancel steps back to the Keep-or-Remove drawer rather than committing,
     *  so the earlier choice is not lost. */
    fun backToFaceRemovePrompt() {
        _uiState.update { it.copy(faceModelPrompt = FaceModelPrompt.Remove) }
    }

    /** Close the face drawer with no change: a tap outside leaves the switch on and the model and data in
     *  place, so an accidental toggle undoes itself rather than switching the feature off unasked. */
    fun dismissFaceModelPrompt() {
        _uiState.update { it.copy(faceModelPrompt = FaceModelPrompt.None) }
    }

    /**
     * Persist the face-indexing pause switch. Pausing lets the running walk stop itself on its next
     * per-photo check; resuming kicks a fresh pass for the last account. It never auto-restarts while
     * paused, so only an explicit resume here re-arms it.
     */
    fun setFaceIndexingPaused(paused: Boolean) {
        viewModelScope.launch { faceIndexingScheduler.setPaused(paused) }
    }

    /**
     * Wipe every detected face and clustered person for the account. Stands the running walk down
     * first, mirroring the sign-out order, then clears the face and person tables; the next indexing
     * run detects faces again from scratch. The photos themselves are untouched.
     */
    fun clearFaceIndex() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            faceIndexingScheduler.reset()
            faceDao.clearForUser(userId.id)
            personDao.clearForUser(userId.id)
            faceScanDao.clearForUser(userId.id)
            personManualPhotoDao.clearForUser(userId.id)
            notPersonDao.clearForUser(userId.id)
            personCoverDao.clearForUser(userId.id)
        }
    }

    /**
     * Re-detect every photo from scratch at the current detector resolution, KEEPING the user's manual
     * attachments and "not this person" feedback (both name-keyed), so a detector-quality change is
     * picked up without discarding curation. Cluster names reset (they are tied to the old face ids);
     * re-naming a cluster re-attaches its manual adds by name.
     */
    fun rescanFaces() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            faceIndexingScheduler.reset()
            faceDao.clearForUser(userId.id)
            personDao.clearForUser(userId.id)
            faceScanDao.clearForUser(userId.id)
            // person_manual_photo and not_person are intentionally kept (name-keyed curation).
            faceIndexingScheduler.requestIndex(userId)
        }
    }

    private val _faceTransferMsg = MutableStateFlow<String?>(null)
    val faceTransferMsg: StateFlow<String?> = _faceTransferMsg.asStateFlow()

    fun clearFaceTransferMsg() { _faceTransferMsg.value = null }

    /** True while an export or import runs, so the transfer rows read as busy and cannot be tapped
     *  again mid-run. */
    private val _faceTransferInProgress = MutableStateFlow(false)
    val faceTransferInProgress: StateFlow<Boolean> = _faceTransferInProgress.asStateFlow()

    /** Write the account's portable face index to [uri] (a document the user just chose). Reports the
     *  face count, or a failure, through [faceTransferMsg]. */
    fun exportFaceIndex(uri: Uri) {
        viewModelScope.launch {
            _faceTransferInProgress.value = true
            try {
                val count = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { exportFaceIndexUseCase(it) } ?: -1
                }.getOrElse { -1 }
                _faceTransferMsg.value = when {
                    count > 0 -> context.getString(R.string.settings_ai_export_done, count)
                    count == 0 -> context.getString(R.string.settings_ai_export_empty)
                    else -> context.getString(R.string.settings_ai_transfer_failed)
                }
            } finally {
                _faceTransferInProgress.value = false
            }
        }
    }

    /** Read a face index from [uri] and fold it into the account, then recluster so the imported faces
     *  form people. Reports the counts, or a failure, through [faceTransferMsg]. */
    fun importFaceIndex(uri: Uri) {
        viewModelScope.launch {
            _faceTransferInProgress.value = true
            try {
                val userId = accountManager.getPrimaryUserId().firstOrNull() ?: return@launch
                // Stand any in-flight walk down first, so it cannot re-detect and overwrite the faces we are
                // about to import (which would wipe their name labels), mirroring rescan's order.
                faceIndexingScheduler.reset()
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { importFaceIndexUseCase(it) }
                }.getOrNull()
                when (result) {
                    is FaceIndexImportOutcome.Success -> {
                        clusterFacesUseCase(userId)
                        _faceTransferMsg.value = context.getString(
                            R.string.settings_ai_import_done, result.faces, result.people,
                        )
                        // Resume indexing: the imported photos are marked scanned, so the walk skips them and
                        // only picks up anything new, reclustering at the end without disturbing the faces.
                        faceIndexingScheduler.requestIndex(userId)
                    }
                    is FaceIndexImportOutcome.ReattachScheduled -> {
                        // A different model: the names are staged, and a fresh scan puts them back on this
                        // device's own faces by where each one sat, so start indexing and say what will happen.
                        _faceTransferMsg.value =
                            context.getString(R.string.settings_ai_import_reattach, result.people)
                        faceIndexingScheduler.requestIndex(userId)
                    }
                    // A wrong account is a mismatch the user can act on, unlike the generic failure an
                    // unreadable or missing file (a null result) gets.
                    FaceIndexImportOutcome.WrongAccount -> {
                        _faceTransferMsg.value = context.getString(R.string.settings_ai_import_wrong_account)
                        // A refused import folded in no faces, so re-arm the walk the reset above stood
                        // down; otherwise a rejected file leaves indexing idle.
                        faceIndexingScheduler.requestIndex(userId)
                    }
                    else -> {
                        _faceTransferMsg.value = context.getString(R.string.settings_ai_transfer_failed)
                        faceIndexingScheduler.requestIndex(userId)
                    }
                }
            } finally {
                _faceTransferInProgress.value = false
            }
        }
    }

    /** Persist which top-level tab the gallery opens on at app start. The gallery reads the key
     *  once on first composition; this setter is for immediate UI feedback in Settings. */
    fun setLandingTab(tab: LandingTab) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.LANDING_TAB] = tab.index }
            _uiState.update { it.copy(landingTab = tab) }
        }
    }

    fun setGridRememberLast(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.GRID_REMEMBER_LAST] = enabled }
            _uiState.update { it.copy(gridRememberLast = enabled) }
        }
    }

    fun setGridDefaultColumns(columns: Int) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.GRID_DEFAULT_COLUMNS] = columns }
            _uiState.update { it.copy(gridDefaultColumns = columns) }
        }
    }

    fun setKeepScrollOnTabSwitch(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.KEEP_SCROLL_ON_TAB_SWITCH] = enabled }
            _uiState.update { it.copy(keepScrollOnTabSwitch = enabled) }
        }
    }

    /**
     * Language switch — canonical DataStore write plus boot-mirror update so the
     * next cold start hands the right locale to ProtonCore login Activities.
     * Does NOT call `AppCompatDelegate.setApplicationLocales`: that triggers an
     * Activity recreate which loses navigation state, bounces the user back to
     * Gallery, and plays a reload animation. The in-app Compose tree re-resolves
     * strings via `LocaleOverride` in `MainActivity` — DataStore change → flow
     * re-emits → override key changes → recomposition picks up the new locale.
     */
    fun setLanguage(lang: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.LANGUAGE] = lang }
            eu.akoos.photos.data.preferences.LanguagePrefsBoot.write(context, lang)
            _uiState.update { it.copy(language = lang) }
        }
    }

    fun setStripOnUpload(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_ON_UPLOAD] = enabled }
            _uiState.update { it.copy(stripOnUpload = enabled) }
        }
    }

    fun setCompressOnUpload(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.COMPRESS_ON_UPLOAD] = enabled }
            _uiState.update { it.copy(compressOnUpload = enabled) }
        }
    }

    fun setCompressVideosOnUpload(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.COMPRESS_VIDEO_ON_UPLOAD] = enabled }
            _uiState.update { it.copy(compressVideosOnUpload = enabled) }
        }
    }

    fun setCompressTier(tier: UploadCompressionTier) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.COMPRESS_UPLOAD_TIER] = tier.ordinal }
            _uiState.update { it.copy(compressTier = tier) }
        }
    }

    fun setMirrorStripToLocal(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.MIRROR_STRIP_TO_LOCAL] = enabled }
            _uiState.update { it.copy(mirrorStripToLocal = enabled) }
        }
    }

    fun setMirrorCompressToLocal(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.MIRROR_COMPRESS_TO_LOCAL] = enabled }
            _uiState.update { it.copy(mirrorCompressToLocal = enabled) }
        }
    }

    fun setRenameToCaptureDate(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.RENAME_TO_CAPTURE_DATE] = enabled }
            _uiState.update { it.copy(renameToCaptureDate = enabled) }
        }
    }

    fun setDeleteLocalAfterBackup(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] = enabled }
            _uiState.update { it.copy(deleteLocalAfterBackup = enabled) }
            // Kick off an immediate sync run when the user just turned the toggle on,
            // so the post upload sweep evicts every already SYNCED file without
            // waiting for the next periodic tick. Mirrors how Save reacts to a
            // user request: the user expects "Delete after backup" to take visible
            // effect the moment they enable it.
            if (enabled) {
                val wifiOnly = context.settingsDataStore.data.first()[SettingsKeys.SYNC_WIFI_ONLY] != false
                SyncWorker.runNow(context, wifiOnly)
            }
        }
    }

    fun setStripGps(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_GPS] = enabled }
            _uiState.update { it.copy(stripGps = enabled) }
        }
    }

    fun setStripCameraInfo(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_CAMERA_INFO] = enabled }
            _uiState.update { it.copy(stripCameraInfo = enabled) }
        }
    }

    fun setStripTimestamp(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_TIMESTAMP] = enabled }
            _uiState.update { it.copy(stripTimestamp = enabled) }
        }
    }

    fun setStripSoftwareInfo(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_SOFTWARE_INFO] = enabled }
            _uiState.update { it.copy(stripSoftwareInfo = enabled) }
        }
    }

    fun setStripOnShare(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_ON_SHARE] = enabled }
            _uiState.update { it.copy(stripOnShare = enabled) }
        }
    }

    fun setStripShareGps(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_SHARE_GPS] = enabled }
            _uiState.update { it.copy(stripShareGps = enabled) }
        }
    }

    fun setStripShareCameraInfo(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_SHARE_CAMERA_INFO] = enabled }
            _uiState.update { it.copy(stripShareCameraInfo = enabled) }
        }
    }

    fun setStripShareTimestamp(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_SHARE_TIMESTAMP] = enabled }
            _uiState.update { it.copy(stripShareTimestamp = enabled) }
        }
    }

    fun setStripShareSoftwareInfo(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_SHARE_SOFTWARE_INFO] = enabled }
            _uiState.update { it.copy(stripShareSoftwareInfo = enabled) }
        }
    }

    fun setStripShareAuthorship(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_SHARE_AUTHORSHIP] = enabled }
            _uiState.update { it.copy(stripShareAuthorship = enabled) }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appLockManager.setLockEnabled(enabled)
            _uiState.update { it.copy(appLockEnabled = enabled) }
        }
    }

    fun setAppLockTimeoutMinutes(minutes: Int) {
        viewModelScope.launch {
            appLockManager.setLockTimeoutMinutes(minutes)
            _uiState.update { it.copy(appLockTimeoutMinutes = minutes) }
        }
    }

    /** Persist the "clear full-res cache on app close" privacy switch. The actual
     *  wipe is performed by the ProcessLifecycleOwner observer registered in App.kt,
     *  which reads this flag at ON_STOP. */
    fun setClearCacheOnAppClose(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.CLEAR_CACHE_ON_APP_CLOSE] = enabled }
            _uiState.update { it.copy(clearCacheOnAppClose = enabled) }
        }
    }

    /** Persist the screenshot quick-action overlay opt-in and start or stop the watcher
     *  service to match. The overlay permission is checked by the caller before enabling;
     *  [ScreenshotOverlayService.start] also self-guards it. */
    fun setScreenshotOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.SCREENSHOT_OVERLAY_ENABLED] = enabled }
            _uiState.update { it.copy(screenshotOverlayEnabled = enabled) }
            if (enabled) {
                ScreenshotOverlayService.start(context)
            } else {
                ScreenshotOverlayService.stop(context)
            }
        }
    }

    /** Persist the "show a floating date while scrolling the timeline" toggle. The Photos grid
     *  observes the key directly; this setter is for immediate UI feedback in Settings. */
    fun setShowScrollDate(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.SHOW_SCROLL_DATE] = enabled }
            _uiState.update { it.copy(showScrollDate = enabled) }
        }
    }

    /** Persist the "reverse timeline order" toggle. The Photos grid observes the key directly;
     *  this setter is for immediate UI feedback in Settings. */
    fun setReverseTimelineOrder(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.REVERSE_TIMELINE_ORDER] = enabled }
            _uiState.update { it.copy(reverseTimelineOrder = enabled) }
        }
    }

    /** Persist the "mosaic (staggered) grid" toggle. The Photos grid observes the key directly;
     *  this setter is for immediate UI feedback in Settings. */
    fun setMosaicGrid(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.MOSAIC_GRID] = enabled }
            _uiState.update { it.copy(mosaicGrid = enabled) }
        }
    }

    /** Persist the "edge to edge (seamless) grid" toggle. The Photos grid observes the key directly;
     *  this setter is for immediate UI feedback in Settings. */
    fun setSeamlessGrid(enabled: Boolean) {
        // Mirror to the synchronous boot cache immediately so a grid opened right after the toggle
        // renders the new layout on the first frame, before the DataStore write propagates.
        eu.akoos.photos.data.preferences.SeamlessGridPrefsBoot.write(context, enabled)
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.SEAMLESS_GRID] = enabled }
            _uiState.update { it.copy(seamlessGrid = enabled) }
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun signOut() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId().firstOrNull()?.let { userId ->
                // 1. Stop anything that could re-decrypt content back into the caches we are about
                //    to wipe, and stop the persistent process-keeper service.
                runCatching {
                    SyncWorker.cancel(workManager)
                    workManager.cancelUniqueWork(SyncWorker.NAME_ONESHOT)
                    workManager.cancelUniqueWork(SyncWorker.NAME_CONTENT_OBSERVER)
                    workManager.cancelAllWorkByTag(eu.akoos.photos.worker.AlbumDownloadWorker.TAG)
                    eu.akoos.photos.service.BackgroundSyncService.stop(context)
                }
                // 2. Wipe plaintext key material + this user's cached cloud rows BEFORE the account
                //    token disappears (heap can't recover keys; the re-login starts from a clean
                //    fetch instead of replaying stale rows that showed up with black thumbnails).
                cloudRepo.clearCacheForSignOut(userId)
                // 3. Delete the decrypted on-disk caches + Coil's caches so no decrypted photo or
                //    thumbnail of the signed-out user stays readable, and drop the diagnostics
                //    buffers, which are process-lifetime and would otherwise carry into a sign-in as
                //    a different user. The offline blobs, the hidden vault and the key material are
                //    wiped inside clearCacheForSignOut above so EVERY sign-out route (including a
                //    server-side force-logout) covers them; MediaStore originals and the encrypted
                //    in-flight upload spill are left alone.
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        listOf("thumbnails", "fullres", "fullres-session", "editor", "video_editor", "motion")
                            .forEach { File(context.cacheDir, it).deleteRecursively() }
                        context.cacheDir.listFiles { f -> f.name.startsWith("album_dl_") }?.forEach { it.delete() }
                        // Sign-out only: a widget's cached bitmap is a decrypted cloud photo, and the
                        // CLOUD_* modes cannot re-fetch one without auth. Sweeping it on any other
                        // cache-clear route would blank the launcher with nothing able to redraw it.
                        context.cacheDir.listFiles { f -> f.name.startsWith("widget_") }?.forEach { it.delete() }
                        eu.akoos.photos.widget.PhotoWidget.clearBitmapCache()
                        val loader = context.imageLoader
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        eu.akoos.photos.util.SyncDiagnostics.clear()
                        eu.akoos.photos.util.PerfDiagnostics.clear()
                        File(context.filesDir, "diagnostics").deleteRecursively()
                        // Drop the Glance state still pointing at the swept bitmaps so each widget
                        // redraws its placeholder now rather than on its next natural tick.
                        val glanceManager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
                        glanceManager.getGlanceIds(eu.akoos.photos.widget.PhotoWidget::class.java)
                            .forEach { glanceId ->
                                androidx.glance.appwidget.state.updateAppWidgetState(
                                    context,
                                    androidx.glance.state.PreferencesGlanceStateDefinition,
                                    glanceId,
                                ) { prefs ->
                                    prefs.toMutablePreferences().also { mp ->
                                        mp.remove(eu.akoos.photos.widget.PhotoWidgetKeys.CACHED_BITMAP_PATH)
                                        mp.remove(eu.akoos.photos.widget.PhotoWidgetKeys.CURRENT_URI)
                                    }
                                }
                                eu.akoos.photos.widget.PhotoWidget().update(context, glanceId)
                            }
                    }
                }
                // 4. Clear account-tied DataStore state so a sign-in as a different user can't
                //    inherit the previous account's sync state, folder selection, recent upload
                //    ids, or favourite list. We keep UI preferences (theme, palette, language) and
                //    machine-level flags (onboarding-complete, app-lock, update throttle).
                runCatching { AccountScopedPreferences.clear(context, userId) }
                // Reset the MainActivity-held lock timestamps BEFORE disableAccount triggers
                // the route change. A re-login by a different user inherits a fresh
                // sinceBackground window so the next resume can't fire the previous user's
                // re-lock check.
                appLockManager.notifyResetLockTimestamps()
                accountManager.disableAccount(userId)
            }
        }
    }
}

/**
 * Internal aggregate used inside [SettingsViewModel.observeBackedUpBytes] so the combine block
 * can return one strongly-typed value instead of a 4-element Tuple — keeps the upsert site
 * readable when there are more than 3 derived counters.
 */
private data class BackedUpSnapshot(
    val photos: Int,
    val videos: Int,
    val bytes: Long,
    val pending: Int,
)

/**
 * Off-thread measurement bundle for [SettingsViewModel.refreshLocalStorage] — folds the four
 * storage figures into one strongly-typed value instead of a Tuple.
 */
private data class LocalStorage(
    val total: Long,
    val free: Long,
    val cacheBytes: Long,
    val offlineBytes: Long,
)

private const val CLOUD_TRASH_TTL_MS = 5L * 60L * 1000L
