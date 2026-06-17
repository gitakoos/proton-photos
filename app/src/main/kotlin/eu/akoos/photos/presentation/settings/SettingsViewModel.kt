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

package eu.akoos.photos.presentation.settings

import android.content.Context
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.GetUser
import me.proton.core.user.domain.usecase.ObserveUser
import eu.akoos.photos.R
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.presentation.lock.AppLockManager
import eu.akoos.photos.domain.entity.SyncStatus
import kotlinx.coroutines.flow.combine
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.repository.SyncStateRepository
import eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase
import eu.akoos.photos.domain.usecase.ReconcileSyncStateUseCase
import eu.akoos.photos.domain.usecase.UploadPendingUseCase
import eu.akoos.photos.domain.usecase.UploadStatus
import eu.akoos.photos.worker.FreeUpSpaceWorker
import eu.akoos.photos.worker.SyncWorker
import javax.inject.Inject

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
    private val syncStateRepo: SyncStateRepository,
    private val appLockManager: AppLockManager,
    private val localMediaRepo: LocalMediaRepository,
    private val cloudRepo: DrivePhotoRepository,
    private val cloudTrashService: eu.akoos.photos.data.repository.drive.CloudTrashService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPrefs()
        observeCurrentUser()
        observeBackedUpBytes()
        observeTrashedCount()
        observeCloudTrashOnSession()
        observeUploadProgress()
        observeExcludedFolders()
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
                Triple(total, free, cacheBytes)
            }
            _uiState.update {
                it.copy(
                    deviceTotalBytes = measured.first,
                    deviceFreeBytes  = measured.second,
                    appCacheBytes    = measured.third,
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
                            current.copy(uploadBytesPerSecond = null)
                        }
                        else -> {
                            val uiStatus = when (evt.status) {
                                UploadStatus.Uploading -> UploadEventStatus.Uploading
                                UploadStatus.Encrypting -> UploadEventStatus.Encrypting
                                UploadStatus.Done -> UploadEventStatus.Done
                                UploadStatus.Failed -> UploadEventStatus.Failed
                                UploadStatus.Queued -> UploadEventStatus.Queued
                                UploadStatus.Idle -> UploadEventStatus.Done // unreachable
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
                    if (userId != null) observeUser(userId) else flowOf(null)
                }
                .collectLatest { user ->
                    _uiState.update {
                        it.copy(
                            userDisplayName = user?.displayName?.takeIf { n -> n.isNotBlank() }
                                ?: user?.name?.takeIf { n -> n.isNotBlank() }
                                ?: "",
                            userEmail = user?.email ?: "",
                            cloudUsedBytes = user?.usedDriveSpace ?: user?.usedSpace ?: 0L,
                            cloudMaxBytes = user?.maxDriveSpace ?: user?.maxSpace ?: 0L,
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
     * Observes counts shown in the Sync card.
     *
     * - **Backed up** = total photos that live on Proton Drive. We observe the cloud-photo
     *   listing directly (Room Flow), so the number reacts immediately when a fresh upload
     *   lands or when the user deletes a cloud photo / removes a Drive-only album. The previous
     *   implementation counted only SyncState rows in SYNCED status — that misses photos that
     *   exist purely in the cloud (no on-device twin), and never updates after device deletes.
     *
     * - **Pending** = on-device photos that are scoped for backup but still LOCAL_ONLY in
     *   SyncState — i.e. genuinely waiting to upload.
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
                    val pendingCount  = syncStates.count { it.status == SyncStatus.LOCAL_ONLY }
                    BackedUpSnapshot(backedUpPhotos, backedUpVideos, backedUpBytes, pendingCount)
                }.collectLatest { snap ->
                    _uiState.update {
                        it.copy(
                            backedUpBytes     = snap.bytes,
                            syncedCount       = snap.photos + snap.videos,
                            syncedPhotoCount  = snap.photos,
                            syncedVideoCount  = snap.videos,
                            notSyncedCount    = snap.pending,
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
     * Public [refreshCloudTrashCount] bypasses the TTL for pull-to-refresh use sites.
     */
    private fun loadCloudTrashCount(force: Boolean = false) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val last = _uiState.value.lastCloudTrashFetchMs
            if (!force && last > 0L && (now - last) < CLOUD_TRASH_TTL_MS) return@launch
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

    /** Force-refresh the Drive trash count, bypassing the in-memory TTL. */
    fun refreshCloudTrashCount() {
        loadCloudTrashCount(force = true)
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
                    freeUpInterval = FreeUpInterval.valueOf(
                        migratedPrefs[SettingsKeys.FREE_UP_INTERVAL] ?: FreeUpInterval.AfterBackup.name
                    ),
                    freeUpWifiOnly = migratedPrefs[SettingsKeys.FREE_UP_WIFI_ONLY] ?: true,
                    themeMode = ThemeMode.fromKey(migratedPrefs[SettingsKeys.THEME_MODE]),
                    palette = ThemePalette.fromKey(migratedPrefs[SettingsKeys.THEME_PALETTE]),
                    lastSyncMs = migratedPrefs[SettingsKeys.LAST_SYNC_MS],
                    language = migratedPrefs[SettingsKeys.LANGUAGE] ?: "system",
                    stripOnUpload = migratedPrefs[SettingsKeys.STRIP_ON_UPLOAD] ?: true,
                    renameToCaptureDate = migratedPrefs[SettingsKeys.RENAME_TO_CAPTURE_DATE] ?: false,
                    deleteLocalAfterBackup = migratedPrefs[SettingsKeys.DELETE_LOCAL_AFTER_BACKUP] ?: false,
                    stripGps = migratedPrefs[SettingsKeys.STRIP_GPS] ?: true,
                    stripCameraInfo = migratedPrefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
                    stripTimestamp = migratedPrefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
                    stripSoftwareInfo = migratedPrefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false,
                    appLockEnabled = migratedPrefs[SettingsKeys.APP_LOCK_ENABLED] ?: false,
                    appLockTimeoutMinutes = migratedPrefs[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] ?: 0,
                    clearCacheOnAppClose = migratedPrefs[SettingsKeys.CLEAR_CACHE_ON_APP_CLOSE] ?: false,
                    hidePhotosInAlbums = migratedPrefs[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] ?: false,
                )
            }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AUTO_SYNC] = enabled }
            _uiState.update { it.copy(autoSync = enabled) }
            if (enabled) {
                // Hardcoded 15-min floor — the OS content URI trigger covers fresh photos
                // within seconds; periodic is just the Doze/OEM-throttle safety net.
                SyncWorker.schedule(workManager, _uiState.value.syncWifiOnly, SyncWorker.MIN_INTERVAL_MINUTES)
                // Bring BackgroundSyncService back so the in-process MediaStore observer
                // resumes catching fresh photos within seconds of capture. Without this,
                // re-enabling Continuous backup would only restart the 15-min periodic
                // worker — the user-perceived "instant upload" only returns at next
                // app-foreground.
                eu.akoos.photos.service.BackgroundSyncService.start(context)
            } else {
                SyncWorker.cancel(workManager)
                // Stop the persistent foreground service so the LOW "Watching for new
                // photos" notification disappears immediately. Leaving it running would
                // ALSO keep the ContentObserver firing SyncWorker.runNow on every photo
                // write — that worker would early-return (LOCAL_ONLY empty when autoSync
                // is off), but the wasted wake-ups burn battery and confuse users.
                eu.akoos.photos.service.BackgroundSyncService.stop(context)
            }
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
            // Kick a sync run so the new mode takes effect immediately instead of waiting
            // for the next periodic SyncWorker tick.
            if (_uiState.value.autoSync) SyncWorker.runNow(context, _uiState.value.syncWifiOnly)
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
                reconcile(userId).collect {}
                // 2. Upload: send LOCAL_ONLY photos to Proton Drive
                //    (uploadFile() now immediately saves each photo to the local DB)
                upload(userId)
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
            } catch (e: Exception) {
                _uiState.update { it.copy(syncError = e.message ?: context.getString(R.string.settings_sync_failed)) }
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun clearSyncError() = _uiState.update { it.copy(syncError = null) }

    fun setAutoFreeUp(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AUTO_FREE_UP] = enabled }
            _uiState.update { it.copy(autoFreeUp = enabled) }
            if (enabled) {
                FreeUpSpaceWorker.schedule(
                    workManager,
                    _uiState.value.freeUpWifiOnly,
                    _uiState.value.freeUpInterval.ms,
                )
            } else {
                FreeUpSpaceWorker.cancel(workManager)
            }
        }
    }

    fun setFreeUpInterval(interval: FreeUpInterval) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FREE_UP_INTERVAL] = interval.name }
            _uiState.update { it.copy(freeUpInterval = interval) }
        }
    }

    fun setFreeUpWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.FREE_UP_WIFI_ONLY] = wifiOnly }
            _uiState.update { it.copy(freeUpWifiOnly = wifiOnly) }
        }
    }

    // Tracks URIs pending system delete dialog confirmation (set by freeUpNow, cleared by onFreeUpPermissionGranted)
    private var pendingFreeUpLocalUris: List<String> = emptyList()

    fun freeUpNow() {
        viewModelScope.launch {
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            _uiState.update { it.copy(isFreeingUp = true) }
            try {
                when (val result = freeUpSpace(userId, Long.MAX_VALUE)) {
                    is eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase.FreeUpResult.Done -> {
                        val msg = if (result.freed > 0)
                            context.getString(R.string.settings_freed_photos, result.freed)
                        else
                            context.getString(R.string.settings_free_up_none)
                        _uiState.update { it.copy(isFreeingUp = false, syncError = msg) }
                    }
                    is eu.akoos.photos.domain.usecase.FreeUpSpaceUseCase.FreeUpResult.NeedsPermission -> {
                        pendingFreeUpLocalUris = result.localUris
                        _uiState.update { it.copy(isFreeingUp = false, freeUpPendingIntent = result.pendingIntent) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isFreeingUp = false, syncError = context.getString(R.string.settings_free_up_error, e.message ?: "")) }
            }
        }
    }

    /** Called when the system delete dialog returns RESULT_OK. */
    fun onFreeUpPermissionGranted() {
        viewModelScope.launch {
            // System already deleted the files; update DB accordingly
            pendingFreeUpLocalUris.forEach { localUri ->
                syncStateRepo.updateStatusAndDeleteLocal(localUri, eu.akoos.photos.domain.entity.SyncStatus.CLOUD_ONLY)
            }
            val freed = pendingFreeUpLocalUris.size
            pendingFreeUpLocalUris = emptyList()
            _uiState.update { it.copy(freeUpPendingIntent = null,
                syncError = context.getString(R.string.settings_freed_photos, freed)) }
        }
    }

    fun clearFreeUpIntent() {
        pendingFreeUpLocalUris = emptyList()
        _uiState.update { it.copy(freeUpPendingIntent = null) }
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

    /** Persist the "hide photos already in albums" Photos-tab filter. The gallery
     *  observes the key directly via its combine chain; this setter is just for
     *  immediate UI feedback in Settings. */
    fun setHidePhotosInAlbums(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.HIDE_PHOTOS_IN_ALBUMS] = enabled }
            _uiState.update { it.copy(hidePhotosInAlbums = enabled) }
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
                //    thumbnail of the signed-out user stays readable. MediaStore originals, the
                //    encrypted in-flight upload spill, and the local hidden vault are left alone.
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        listOf("thumbnails", "fullres", "fullres-session", "editor", "video_editor", "motion")
                            .forEach { File(context.cacheDir, it).deleteRecursively() }
                        context.cacheDir.listFiles { f -> f.name.startsWith("album_dl_") }?.forEach { it.delete() }
                        val loader = context.imageLoader
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                    }
                }
                // 4. Clear account-tied DataStore state so a sign-in as a different user can't
                //    inherit the previous account's sync state, folder selection, recent upload
                //    ids, or favourite list. We keep UI preferences (theme, palette, language) and
                //    machine-level flags (onboarding-complete, app-lock, update throttle).
                runCatching {
                    context.settingsDataStore.edit { prefs ->
                        val accountTied = setOf<androidx.datastore.preferences.core.Preferences.Key<*>>(
                            SettingsKeys.LAST_SYNC_MS,
                            SettingsKeys.SYNC_FOLDER_NAMES,
                            SettingsKeys.BACKUP_EVERYTHING,
                            SettingsKeys.EXCLUDED_FOLDER_NAMES,
                            SettingsKeys.HIDDEN_URI_CLOUD_ID_MAP,
                            SettingsKeys.MANUAL_LOCAL_FOLDER_NAMES,
                            SettingsKeys.ALBUM_BUCKET_MAP,
                            SettingsKeys.PENDING_DELETE_URIS,
                            SettingsKeys.HIDDEN_PHOTO_URIS,
                            SettingsKeys.FAVORITE_IDS,
                            SettingsKeys.RECENT_UPLOAD_IDS,
                            // Hide-photos-in-albums is a per-user view preference. Without
                            // adding it here, a shared device that signs out → signs back in
                            // as a different account inherits the previous user's choice.
                            SettingsKeys.HIDE_PHOTOS_IN_ALBUMS,
                            SettingsKeys.HIDE_DEVICE_FOLDERS_IN_ALBUMS,
                            // Timeline folder filter is likewise a per-user view preference.
                            SettingsKeys.TIMELINE_EXCLUDED_FOLDER_NAMES,
                        )
                        accountTied.forEach { prefs.remove(it) }
                        // Per-user dynamic keys (one per volume): the events anchor + the photo
                        // listing resume cursor/complete flag. A stale events anchor surviving
                        // sign-out is what returned a 404 on the next login until the cache was
                        // cleared by hand.
                        val dynamicPrefixes = listOf(
                            "event_anchor_${userId.id}_",
                            "photo_listing_cursor_${userId.id}_",
                            "photo_listing_complete_${userId.id}_",
                        )
                        prefs.asMap().keys
                            .filter { key -> dynamicPrefixes.any { key.name.startsWith(it) } }
                            .toList()
                            .forEach { prefs.remove(it) }
                    }
                }
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

private const val CLOUD_TRASH_TTL_MS = 5L * 60L * 1000L
