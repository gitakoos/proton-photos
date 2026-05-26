package me.proton.photos.presentation.gallery

import me.proton.photos.domain.entity.GalleryItem

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
    val pendingDeleteIntent: android.app.PendingIntent? = null,
    val multiDownloadState: MultiDownloadState = MultiDownloadState.Idle,
    val addToAlbumState: AddToAlbumState = AddToAlbumState.Idle,
    val isSyncing: Boolean = false,
    val pendingUploadCount: Int = 0,
    val uploadedCount: Int = 0,
    val timelineGrouping: TimelineGrouping = TimelineGrouping.Month,
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

/** Lifecycle of the "Add selected to album" multi-action. Cloud-add and local-move legs run in
 *  the same operation, so the terminal state aggregates both numbers. */
sealed class AddToAlbumState {
    data object Idle : AddToAlbumState()
    data object Working : AddToAlbumState()
    /** [cloudAdded] = photos attached to a cloud album; [localMoved] = files moved to a bucket. */
    data class Done(val cloudAdded: Int, val localMoved: Int, val albumName: String) : AddToAlbumState()
    data class Failed(val message: String) : AddToAlbumState()
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

enum class TimelineGrouping { Day, Month, Year }
