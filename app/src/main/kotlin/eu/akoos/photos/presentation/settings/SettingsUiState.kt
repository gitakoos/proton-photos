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

package eu.akoos.photos.presentation.settings

data class SettingsUiState(
    val autoSync: Boolean = true,
    val syncWifiOnly: Boolean = true,
    /** When true (default), the viewer holds off auto full-res downloads while the
     *  device is on a metered network. Thumbnails always load. Independent from
     *  [syncWifiOnly] which governs upload. */
    val fullresWifiOnly: Boolean = true,
    val autoBackupNewFolders: Boolean = false,
    /** Backup-everything mode: when true, every MediaStore image/video is auto-uploaded,
     *  regardless of which folders the user picked. Folder picker becomes informational
     *  only. Toggle lives next to the existing folder-mode toggles in Settings. */
    val backupEverything: Boolean = false,
    /** Names of MediaStore buckets the user carved out of [backupEverything]. Only
     *  surfaced in the UI / consulted by reconcile while [backupEverything] is ON.
     *  Empty = no exclusions, back up everything. */
    val excludedFolderNames: Set<String> = emptySet(),
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
    val palette: ThemePalette = ThemePalette.Default,
    /** Grid-layout settings (Appearance → Grid layout). [gridRememberLast] on = the timeline
     *  remembers the last pinched zoom; off = it opens at [gridDefaultColumns], which also sets
     *  the album / device-folder / hidden grids. 3 = the default columns-per-row baseline. */
    val gridRememberLast: Boolean = false,
    val gridDefaultColumns: Int = 3,
    val userDisplayName: String = "",
    val userEmail: String = "",
    val cloudUsedBytes: Long = 0L,
    val cloudMaxBytes: Long = 0L,
    val language: String = "system",
    // Metadata stripping
    val stripOnUpload: Boolean = true,
    val renameToCaptureDate: Boolean = false,
    /** When true, the upload pipeline removes the local MediaStore copy once Drive has
     *  the upload. Off by default — opting in delegates "long-term storage" to Proton Drive. */
    val deleteLocalAfterBackup: Boolean = false,
    val stripGps: Boolean = true,
    val stripCameraInfo: Boolean = false,
    val stripTimestamp: Boolean = false,
    val stripSoftwareInfo: Boolean = false,
    // App lock
    val appLockEnabled: Boolean = false,
    /** Lock-on-return timeout in minutes. 0 = immediate; common picks: 5 / 10 / 15 / 60. */
    val appLockTimeoutMinutes: Int = 0,
    /** Privacy opt-in: wipe `cacheDir/fullres/` on every process backgrounding. Off by
     *  default — the 30-min TTL + offline-grace sweeper is the regular behaviour. */
    val clearCacheOnAppClose: Boolean = false,
    /** When true, the main Photos timeline hides every photo already filed into an
     *  album. Off by default. The Albums + Shared tabs are unaffected. */
    val hidePhotosInAlbums: Boolean = false,
    // Trash
    val trashedCount: Int = 0,
    /** Drive (cloud) trash count. `null` = unknown — UI then falls back to the
     *  device-only subtitle. A successful fetch populates an Int; transient failures
     *  preserve the prior value so a brief offline blip doesn't flicker the row
     *  back to device-only. */
    val cloudTrashCount: Int? = null,
    /** Epoch ms of the last successful Drive trash fetch — the TTL gate inside
     *  [SettingsViewModel.loadCloudTrashCount] compares against this. 0 means "never
     *  fetched (or just signed out)". */
    val lastCloudTrashFetchMs: Long = 0L,
    // Free-up space: non-null when system delete dialog should be launched
    val freeUpPendingIntent: android.app.PendingIntent? = null,
    // ── Per-file upload progress (Sync card progress bar + expandable list) ────
    /** 1-based count of completed (or attempted) uploads in the current batch. */
    val uploadDoneCount: Int = 0,
    /** Total files in the current batch (0 when idle). */
    val uploadTotalCount: Int = 0,
    /** Per-file status feed for the expandable list. Most recent activity last. */
    val uploadEvents: List<UploadEvent> = emptyList(),
    /** Running bytes-per-second for the current batch. Null when no batch is active
     *  or the first file hasn't completed yet. Computed as cumulative-done-bytes /
     *  elapsed-since-batch-start so it's stable across parallel uploads. */
    val uploadBytesPerSecond: Long? = null,
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
    /** Plaintext file size of the item this event is about — surfaced in the per-file
     *  row so the user can tell a 4 GB video from a 4 MB photo at a glance, and gets
     *  *some* feedback while a video upload sits in the row for minutes. */
    val sizeBytes: Long = 0L,
)

enum class UploadEventStatus { Uploading, Queued, Encrypting, Done, Failed }

enum class ThemeMode(val storageKey: String, val labelRes: Int) {
    System("system", eu.akoos.photos.R.string.theme_mode_system),
    Light ("light",  eu.akoos.photos.R.string.theme_mode_light),
    Dark  ("dark",   eu.akoos.photos.R.string.theme_mode_dark);

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: System
    }
}

/**
 * Accent-color palette. Orthogonal to [ThemeMode] — picking Forest doesn't switch
 * between light and dark, only the accent/accent2 tokens shift. [Default] is the
 * original Proton purple kept around for backward compatibility (no visual change
 * for users who never open the new picker).
 */
enum class ThemePalette(val storageKey: String, val labelRes: Int) {
    Default("default", eu.akoos.photos.R.string.palette_default),
    Forest ("forest",  eu.akoos.photos.R.string.palette_forest),
    Sunset ("sunset",  eu.akoos.photos.R.string.palette_sunset),
    Sea    ("sea",     eu.akoos.photos.R.string.palette_sea),
    Sepia  ("sepia",   eu.akoos.photos.R.string.palette_sepia),
    Mono   ("mono",    eu.akoos.photos.R.string.palette_mono);

    companion object {
        fun fromKey(key: String?): ThemePalette = entries.firstOrNull { it.storageKey == key } ?: Default
    }
}

enum class FreeUpInterval(val labelRes: Int, val ms: Long) {
    AfterBackup(eu.akoos.photos.R.string.settings_free_up_interval_after_backup, 0L),
    OneDay(eu.akoos.photos.R.string.settings_free_up_interval_1_day, 86_400_000L),
    OneWeek(eu.akoos.photos.R.string.settings_free_up_interval_1_week, 604_800_000L),
    OneMonth(eu.akoos.photos.R.string.settings_free_up_interval_1_month, 2_592_000_000L),
}
