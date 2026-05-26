package me.proton.photos.presentation.settings

data class SettingsUiState(
    val autoSync: Boolean = true,
    val syncWifiOnly: Boolean = true,
    /** Periodic-sync cadence in minutes; clamped to WorkManager's 15-minute floor. */
    val syncIntervalMinutes: Long = 15L,
    val autoBackupNewFolders: Boolean = false,
    val lastSyncMs: Long? = null,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val autoFreeUp: Boolean = false,
    val freeUpInterval: FreeUpInterval = FreeUpInterval.AfterBackup,
    val freeUpWifiOnly: Boolean = true,
    val isFreeingUp: Boolean = false,
    val deviceStorageBytes: Long = 0L,
    // ── Local storage scopes (visibility-only, no quota write-backs) ──────────
    /** Total bytes on the device data partition (StatFs.totalBytes). */
    val deviceTotalBytes: Long = 0L,
    /** Available bytes on the device data partition (StatFs.availableBytes). */
    val deviceFreeBytes: Long = 0L,
    /** Sum of all files under context.cacheDir (computed off-thread, refreshed on entry). */
    val appCacheBytes: Long = 0L,
    val backedUpBytes: Long = 0L,
    val syncedCount: Int = 0,
    val notSyncedCount: Int = 0,
    /** Backed-up split — Settings sync card uses these to render "X photos, Y videos". */
    val syncedPhotoCount: Int = 0,
    val syncedVideoCount: Int = 0,
    val themeMode: ThemeMode = ThemeMode.System,
    val userDisplayName: String = "",
    val userEmail: String = "",
    val cloudUsedBytes: Long = 0L,
    val cloudMaxBytes: Long = 0L,
    val language: String = "system",
    // Metadata stripping
    val stripOnUpload: Boolean = true,
    val stripGps: Boolean = true,
    val stripCameraInfo: Boolean = false,
    val stripTimestamp: Boolean = false,
    val stripSoftwareInfo: Boolean = false,
    // App lock
    val appLockEnabled: Boolean = false,
    // Trash
    val trashedCount: Int = 0,
    // Free-up space: non-null when system delete dialog should be launched
    val freeUpPendingIntent: android.app.PendingIntent? = null,
    // ── Per-file upload progress (Sync card progress bar + expandable list) ────
    /** 1-based count of completed (or attempted) uploads in the current batch. */
    val uploadDoneCount: Int = 0,
    /** Total files in the current batch (0 when idle). */
    val uploadTotalCount: Int = 0,
    /** Per-file status feed for the expandable list. Most recent activity last. */
    val uploadEvents: List<UploadEvent> = emptyList(),
)

/**
 * Tiny UI-side view of the per-file events that [UploadPendingUseCase] emits. Decoupled from
 * the domain type so we can drop / coalesce duplicate `Uploading` frames without leaking
 * domain logic into the composable.
 */
data class UploadEvent(
    val uri: String,
    val displayName: String,
    val status: UploadEventStatus,
)

enum class UploadEventStatus { Uploading, Queued, Done, Failed }

enum class ThemeMode(val storageKey: String, val labelRes: Int) {
    System("system", me.proton.photos.R.string.theme_mode_system),
    Light ("light",  me.proton.photos.R.string.theme_mode_light),
    Dark  ("dark",   me.proton.photos.R.string.theme_mode_dark);

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: System
    }
}

enum class FreeUpInterval(val labelRes: Int, val ms: Long) {
    AfterBackup(me.proton.photos.R.string.settings_free_up_interval_after_backup, 0L),
    OneDay(me.proton.photos.R.string.settings_free_up_interval_1_day, 86_400_000L),
    OneWeek(me.proton.photos.R.string.settings_free_up_interval_1_week, 604_800_000L),
    OneMonth(me.proton.photos.R.string.settings_free_up_interval_1_month, 2_592_000_000L),
}
