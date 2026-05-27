@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.proton.photos.presentation.settings

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.user.domain.usecase.GetUser
import me.proton.core.user.domain.usecase.ObserveUser
import me.proton.photos.data.preferences.SettingsKeys
import me.proton.photos.data.preferences.settingsDataStore
import me.proton.photos.presentation.lock.AppLockManager
import me.proton.photos.domain.entity.SyncStatus
import kotlinx.coroutines.flow.combine
import me.proton.photos.domain.repository.DrivePhotoRepository
import me.proton.photos.domain.repository.LocalMediaRepository
import me.proton.photos.domain.repository.SyncStateRepository
import me.proton.photos.domain.usecase.FreeUpSpaceUseCase
import me.proton.photos.domain.usecase.ReconcileSyncStateUseCase
import me.proton.photos.domain.usecase.UploadPendingUseCase
import me.proton.photos.domain.usecase.UploadStatus
import me.proton.photos.worker.FreeUpSpaceWorker
import me.proton.photos.worker.SyncWorker
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPrefs()
        observeCurrentUser()
        observeBackedUpBytes()
        observeTrashedCount()
        observeUploadProgress()
        refreshLocalStorage()
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
                listOf("thumbnails", "fullres").forEach { name ->
                    val sub = File(cache, name)
                    if (sub.exists()) sub.deleteRecursively()
                }
                // upload_<fileId> dirs are siblings — enumerate.
                cache.listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name.startsWith("upload_")) {
                        f.deleteRecursively()
                    }
                }
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
            try { getUser(userId, refresh = true) } catch (_: Exception) { }
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
            upload.progress.collectLatest { evt ->
                _uiState.update { current ->
                    when (evt.status) {
                        UploadStatus.Idle -> current.copy(
                            uploadDoneCount = 0,
                            uploadTotalCount = 0,
                            uploadEvents = emptyList(),
                        )
                        else -> {
                            val uiStatus = when (evt.status) {
                                UploadStatus.Uploading -> UploadEventStatus.Uploading
                                UploadStatus.Done -> UploadEventStatus.Done
                                UploadStatus.Failed -> UploadEventStatus.Failed
                                UploadStatus.Queued -> UploadEventStatus.Queued
                                UploadStatus.Idle -> UploadEventStatus.Done // unreachable
                            }
                            // Replace the prior entry for this URI (so an "Uploading" row
                            // becomes "Done" instead of accumulating dupes), then trim head.
                            val withoutPrev = current.uploadEvents.filter { it.uri != evt.uri }
                            val nextEvents = (withoutPrev + UploadEvent(evt.uri, evt.displayName, uiStatus))
                                .takeLast(30)
                            current.copy(
                                uploadDoneCount = evt.doneIdx,
                                uploadTotalCount = evt.totalCount,
                                uploadEvents = nextEvents,
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
            try { getUser(userId, refresh = true) } catch (_: Exception) { /* offline ok */ }
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
                me.proton.photos.data.preferences.ThemePrefsBoot.write(context, migrated)
                written
            } else prefs
            _uiState.update {
                it.copy(
                    autoSync = migratedPrefs[SettingsKeys.AUTO_SYNC] ?: true,
                    syncWifiOnly = migratedPrefs[SettingsKeys.SYNC_WIFI_ONLY] ?: true,
                    syncIntervalMinutes = migratedPrefs[SettingsKeys.SYNC_INTERVAL_MINUTES] ?: 360L,
                    autoBackupNewFolders = migratedPrefs[SettingsKeys.AUTO_BACKUP_NEW_FOLDERS] ?: false,
                    autoFreeUp = migratedPrefs[SettingsKeys.AUTO_FREE_UP] ?: false,
                    freeUpInterval = FreeUpInterval.valueOf(
                        migratedPrefs[SettingsKeys.FREE_UP_INTERVAL] ?: FreeUpInterval.AfterBackup.name
                    ),
                    freeUpWifiOnly = migratedPrefs[SettingsKeys.FREE_UP_WIFI_ONLY] ?: true,
                    themeMode = ThemeMode.fromKey(migratedPrefs[SettingsKeys.THEME_MODE]),
                    lastSyncMs = migratedPrefs[SettingsKeys.LAST_SYNC_MS],
                    language = migratedPrefs[SettingsKeys.LANGUAGE] ?: "system",
                    stripOnUpload = migratedPrefs[SettingsKeys.STRIP_ON_UPLOAD] ?: true,
                    stripGps = migratedPrefs[SettingsKeys.STRIP_GPS] ?: true,
                    stripCameraInfo = migratedPrefs[SettingsKeys.STRIP_CAMERA_INFO] ?: false,
                    stripTimestamp = migratedPrefs[SettingsKeys.STRIP_TIMESTAMP] ?: false,
                    stripSoftwareInfo = migratedPrefs[SettingsKeys.STRIP_SOFTWARE_INFO] ?: false,
                    appLockEnabled = migratedPrefs[SettingsKeys.APP_LOCK_ENABLED] ?: false,
                    appLockTimeoutMinutes = migratedPrefs[SettingsKeys.APP_LOCK_TIMEOUT_MINUTES] ?: 0,
                )
            }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AUTO_SYNC] = enabled }
            _uiState.update { it.copy(autoSync = enabled) }
            if (enabled) {
                SyncWorker.schedule(workManager, _uiState.value.syncWifiOnly, _uiState.value.syncIntervalMinutes)
            } else {
                SyncWorker.cancel(workManager)
            }
        }
    }

    /**
     * Update the periodic-sync cadence. Re-schedules WorkManager so the change takes effect
     * on the next fire (via [ExistingPeriodicWorkPolicy.UPDATE]).
     */
    fun setSyncIntervalMinutes(minutes: Long) {
        viewModelScope.launch {
            val clamped = minutes.coerceAtLeast(SyncWorker.MIN_INTERVAL_MINUTES)
            context.settingsDataStore.edit { it[SettingsKeys.SYNC_INTERVAL_MINUTES] = clamped }
            _uiState.update { it.copy(syncIntervalMinutes = clamped) }
            if (_uiState.value.autoSync) {
                SyncWorker.schedule(workManager, _uiState.value.syncWifiOnly, clamped)
            }
        }
    }

    fun setAutoBackupNewFolders(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.AUTO_BACKUP_NEW_FOLDERS] = enabled }
            _uiState.update { it.copy(autoBackupNewFolders = enabled) }
        }
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.SYNC_WIFI_ONLY] = wifiOnly }
            _uiState.update { it.copy(syncWifiOnly = wifiOnly) }
            if (_uiState.value.autoSync) {
                SyncWorker.schedule(workManager, wifiOnly, _uiState.value.syncIntervalMinutes)
            }
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
                try { cloudRepo.refreshCloudPhotos(userId) } catch (_: Exception) { }
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
                try { getUser(userId, refresh = true) } catch (_: Exception) { }
            } catch (e: Exception) {
                _uiState.update { it.copy(syncError = e.message ?: "Sync failed") }
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
                    is me.proton.photos.domain.usecase.FreeUpSpaceUseCase.FreeUpResult.Done -> {
                        val msg = if (result.freed > 0)
                            "Freed ${result.freed} photo${if (result.freed != 1) "s" else ""} from device"
                        else
                            "No locally cached backed-up photos to remove"
                        _uiState.update { it.copy(isFreeingUp = false, syncError = msg) }
                    }
                    is me.proton.photos.domain.usecase.FreeUpSpaceUseCase.FreeUpResult.NeedsPermission -> {
                        pendingFreeUpLocalUris = result.localUris
                        _uiState.update { it.copy(isFreeingUp = false, freeUpPendingIntent = result.pendingIntent) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isFreeingUp = false, syncError = "Could not free up space: ${e.message}") }
            }
        }
    }

    /** Called when the system delete dialog is confirmed by the user. */
    fun onFreeUpPermissionGranted() {
        viewModelScope.launch {
            // System already deleted the files; update DB accordingly
            pendingFreeUpLocalUris.forEach { localUri ->
                syncStateRepo.updateStatusAndDeleteLocal(localUri, me.proton.photos.domain.entity.SyncStatus.CLOUD_ONLY)
            }
            val freed = pendingFreeUpLocalUris.size
            pendingFreeUpLocalUris = emptyList()
            _uiState.update { it.copy(freeUpPendingIntent = null,
                syncError = "Freed $freed photo${if (freed != 1) "s" else ""} from device") }
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
            me.proton.photos.data.preferences.ThemePrefsBoot.write(context, mode.storageKey)
            // Apply the night-mode to AppCompatDelegate so externally-launched Activities
            // (ProtonCore login/payment) flip immediately, not just our Compose UI.
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeMode.Dark   -> AppCompatDelegate.MODE_NIGHT_YES
                    ThemeMode.Light  -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            _uiState.update { it.copy(themeMode = mode) }
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.LANGUAGE] = lang }
            _uiState.update { it.copy(language = lang) }
            val localeList = if (lang == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }

    fun setStripOnUpload(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[SettingsKeys.STRIP_ON_UPLOAD] = enabled }
            _uiState.update { it.copy(stripOnUpload = enabled) }
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

    fun signOut() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId().firstOrNull()?.let { userId ->
                // Wipe plaintext key material BEFORE the account token disappears so any process
                // that survives the sign-out can't recover the keys from this Singleton's heap.
                cloudRepo.clearCacheForSignOut()
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
