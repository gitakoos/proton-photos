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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.GalleryItem

data class GalleryUiState(
    val isLoading: Boolean = true,
    val items: List<GalleryItem> = emptyList(),
    val filteredItems: List<GalleryItem> = emptyList(),
    val selectedFilter: GalleryFilter = GalleryFilter.All,
    val favoriteIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val permissionState: PermissionState = PermissionState.NotRequested,
    val error: String? = null,
    val storageFullEvent: Boolean = false,
    val userInitial: String = "",
    val contentFilter: ContentFilter = ContentFilter(),
    val cloudUsedBytes: Long = 0L,
    val cloudMaxBytes: Long = 0L,
    val selectedItems: Set<GalleryItem> = emptySet(),
    val multiDeleteState: MultiDeleteState = MultiDeleteState.Idle,
    /**
     * Lifecycle of a multi-photo "move to hidden" run. Mirrors [multiDeleteState]'s
     * sealed shape but is read separately by the selection bar so the Hide spinner
     * surfaces on the More menu without also setting the Delete (trash) button to
     * its in-flight state.
     */
    val multiHideState: MultiDeleteState = MultiDeleteState.Idle,
    /** True when the last hide batch included backed-up photos — the post-hide
     *  snackbar then discloses that the Drive copies remain in the cloud. */
    val hideCloudNoticePending: Boolean = false,
    val pendingDeleteIntent: android.app.PendingIntent? = null,
    val multiDownloadState: MultiDownloadState = MultiDownloadState.Idle,
    val multiShareState: MultiShareState = MultiShareState.Idle,
    val addToAlbumState: AddToAlbumState = AddToAlbumState.Idle,
    val multiStripState: MultiStripState = MultiStripState.Idle,
    /** Android 10+ write-permission dialog for stripping metadata from foreign files in the
     *  selection. Set when a batch strip hits files the OS won't write without consent; the
     *  screen launches it and the granted retry strips the deferred URIs. */
    val pendingStripIntent: android.app.PendingIntent? = null,
    val isSyncing: Boolean = false,
    val pendingUploadCount: Int = 0,
    val uploadedCount: Int = 0,
    val timelineGrouping: TimelineGrouping = TimelineGrouping.Month,
    /** Cloud linkIds whose local twin lives in the Hidden vault. Used by [PhotoCell] to draw
     *  a crossed-out eye overlay so the user can tell at a glance which cloud photos are
     *  hidden on this device. Derived from SyncStateRepo rows with [SyncStatus.HIDDEN]. */
    val hiddenCloudLinkIds: Set<String> = emptySet(),
    /** Cloud linkIds of photos that belong to at least one Drive album. Used by [applyFilter]
     *  to exclude album items from the [GalleryFilter.All] view when the "Hide photos in albums"
     *  toggle is on. Empty when the toggle is off. Non-All filters (Favorites, Screenshots,
     *  Videos, …) ignore this — when the user explicitly picks a tab, they want to see every
     *  matching item, album membership or not. */
    val albumHideCloudIds: Set<String> = emptySet(),
) {
    val storageFraction: Float
        get() = if (cloudMaxBytes > 0L)
            (cloudUsedBytes.toFloat() / cloudMaxBytes).coerceIn(0f, 1f)
        else 0f

    val isSelectionMode: Boolean get() = selectedItems.isNotEmpty()
    val selectedCount: Int get() = selectedItems.size
}

sealed class MultiDeleteState {
    data object Idle    : MultiDeleteState()
    data object Working : MultiDeleteState()
    data object Done    : MultiDeleteState()
    data class  Failed(val message: String) : MultiDeleteState()
}

sealed class MultiDownloadState {
    data object Idle : MultiDownloadState()
    data class  Working(val done: Int, val total: Int) : MultiDownloadState()
    data class  Done(val succeeded: Int, val failed: Int) : MultiDownloadState()
}

/** Lifecycle of the multi-select "Share" action. [Working] tracks how many cloud-only items
 *  in the batch have finished decrypting so the share pill can show determinate progress; the
 *  terminal step is the chooser launch, surfaced through a one-shot intent rather than a state. */
sealed class MultiShareState {
    data object Idle : MultiShareState()
    data class  Working(val done: Int, val total: Int) : MultiShareState()
}

/** Lifecycle of the "Add selected to album" multi-action. Cloud-add and local-move legs run in
 *  the same operation, so the terminal state aggregates both numbers. */
sealed class AddToAlbumState {
    data object Idle : AddToAlbumState()
    data object Working : AddToAlbumState()
    /** [cloudAdded] = photos attached to a cloud album; [localMoved] = files moved to a bucket.
     *  [skipped] = items the picked target couldn't accept (cloud-only items when target is a
     *  local bucket; local-only items with no cloud counterpart when target is a Drive album).
     *  Surfaced as a partial-success snackbar so a mixed selection no longer drops items
     *  silently. */
    data class Done(val cloudAdded: Int, val localMoved: Int, val skipped: Int, val albumName: String) : AddToAlbumState()
    data class Failed(val message: String) : AddToAlbumState()
}

/** Lifecycle of the multi-select EXIF-strip action. Skipped count covers cloud-only items in
 *  the selection (we can't modify cloud bytes here) plus any local file the OS refused to write
 *  in-place (foreign owner under scoped storage). */
sealed class MultiStripState {
    data object Idle : MultiStripState()
    data object Working : MultiStripState()
    data class Done(val stripped: Int, val skipped: Int) : MultiStripState()
    data class Failed(val message: String) : MultiStripState()
}

/**
 * Top-level filters surfaced as quick chips at the top of the gallery.
 * Tag-based filters map to the Drive [PhotoTag] enum (Favorites=0, Screenshots=1, …).
 */
enum class GalleryFilter(val tagId: Int? = null) {
    All,
    Favorites(tagId = 0),
    Screenshots(tagId = 1),
    Videos(tagId = 2),
    LivePhotos(tagId = 3),
    MotionPhotos(tagId = 4),
    Selfies(tagId = 5),
    Portraits(tagId = 6),
    Bursts(tagId = 7),
    Panoramas(tagId = 8),
    Raw(tagId = 9),
}

enum class PermissionState {
    NotRequested,
    Granted,
    Denied,
    PermanentlyDenied,
}

data class ContentFilter(
    val mediaType: MediaType = MediaType.All,
    val syncStatus: SyncStatusFilter = SyncStatusFilter.All,
    val year: Int? = null,
    val month: Int? = null,
)

enum class MediaType { All, PhotosOnly, VideosOnly }
enum class SyncStatusFilter { All, LocalOnly, BackedUp }

enum class TimelineGrouping { None, Day, Month, Year }
