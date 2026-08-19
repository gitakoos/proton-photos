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

package eu.akoos.photos.presentation.gallery


import eu.akoos.photos.domain.entity.GalleryItem

data class GalleryUiState(
    val isLoading: Boolean = true,
    val items: List<GalleryItem> = emptyList(),
    val filteredItems: List<GalleryItem> = emptyList(),
    /** Month buckets of [filteredItems] ("MMMM yyyy" label → items, encounter order) precomputed
     *  off the main thread in the ViewModel. The timeline grid iterates this directly instead of
     *  running SimpleDateFormat per item inside composition on every list re-emission. */
    val monthGroups: List<Pair<String, List<GalleryItem>>> = emptyList(),
    /** Day buckets of [filteredItems] ("d MMMM yyyy" label → items, encounter order) precomputed
     *  off the main thread in the ViewModel, mirroring [monthGroups]. Drives the 3-column default
     *  zoom so the opening view does not run SimpleDateFormat per item inside composition. */
    val dayGroups: List<Pair<String, List<GalleryItem>>> = emptyList(),
    /** "On this day" memories (year → items, most-recent-first) precomputed off the main thread
     *  from the unfiltered [items], matching the carousel's filter-independent source. */
    val onThisDayGroups: List<Pair<Int, List<GalleryItem>>> = emptyList(),
    val selectedFilter: GalleryFilter = GalleryFilter.All,
    val favoriteIds: Set<String> = emptySet(),
    /** Cloud linkIds pinned for offline; the grid draws a download badge on each matching cell. */
    val offlinePinIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val permissionState: PermissionState = PermissionState.NotRequested,
    val error: String? = null,
    val userInitial: String = "",
    val contentFilter: ContentFilter = ContentFilter(),
    val cloudUsedBytes: Long = 0L,
    val cloudMaxBytes: Long = 0L,
    val selectedItems: Set<GalleryItem> = emptySet(),
    val multiDeleteState: MultiDeleteState = MultiDeleteState.Idle,
    /** Separate from [multiDeleteState] so the Hide spinner shows on the More menu without
     *  also putting the Delete (trash) button into its in-flight state. */
    val multiHideState: MultiDeleteState = MultiDeleteState.Idle,
    /** Last hide batch included backed-up photos — the snackbar then notes the Drive copies stay. */
    val hideCloudNoticePending: Boolean = false,
    /** Count of items in the last hide batch that couldn't be copied into the vault. Surfaced as a
     *  partial-result snackbar when the batch finishes; 0 means every selected item hid cleanly. */
    val hideFailureCount: Int = 0,
    val pendingDeleteIntent: android.app.PendingIntent? = null,
    val multiDownloadState: MultiDownloadState = MultiDownloadState.Idle,
    val multiShareState: MultiShareState = MultiShareState.Idle,
    val addToAlbumState: AddToAlbumState = AddToAlbumState.Idle,
    val multiStripState: MultiStripState = MultiStripState.Idle,
    /** Progress of a batch favourite from the selection dock. */
    val favoriteState: eu.akoos.photos.presentation.common.FavoriteActionState =
        eu.akoos.photos.presentation.common.FavoriteActionState.Idle,
    /** Android 10+ write-permission dialog for stripping foreign files; the granted retry strips
     *  the deferred URIs. */
    val pendingStripIntent: android.app.PendingIntent? = null,
    val isSyncing: Boolean = false,
    // Active transfers from the TransferCenter, split by direction so the avatar can show an
    // upload arrow, a download arrow, or both when they run at once.
    val hasActiveUpload: Boolean = false,
    val hasActiveDownload: Boolean = false,
    // uploadTotalCount > 0 (with isSyncing) means a back-up is in flight.
    val uploadDoneIdx: Int = 0,
    val uploadTotalCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val uploadedCount: Int = 0,
    val timelineGrouping: TimelineGrouping = TimelineGrouping.Month,
    /** Resolved opening zoom level for the timeline grid (from the grid-layout preference). */
    val initialZoomLevel: Int = GridZoom.DEFAULT_LEVEL,
    /** When true, the timeline persists + restores the last pinched zoom; default columns ignored. */
    val gridRememberLast: Boolean = false,
    /** Fixed default columns per row when [gridRememberLast] is off; also drives album grids. */
    val gridDefaultColumns: Int = GridZoom.DEFAULT_COLUMNS,
    val denseGridWarningDismissed: Boolean = false,
    /** Cloud linkIds whose local twin is in the Hidden vault; [PhotoCell] draws a crossed-out
     *  eye overlay. Derived from SyncStateRepo rows with [SyncStatus.HIDDEN]. */
    val hiddenCloudLinkIds: Set<String> = emptySet(),
    /** Cloud linkIds in at least one Drive album. [applyFilter] excludes these from [GalleryFilter.All]
     *  when "Hide photos in albums" is on (empty when off); non-All tabs ignore it. */
    val albumHideCloudIds: Set<String> = emptySet(),
    /** Cloud album linkIds the user hid client-side. The add-to-album picker marks these rows with
     *  a lock so a hidden album reads as hidden while staying fully selectable. */
    val hiddenAlbumIds: Set<String> = emptySet(),
    /** Indexed people (face clusters) for the People rail, most-photographed first. Empty when the
     *  AI features are off or nothing has been indexed yet, which keeps the People chip hidden. */
    val people: List<PersonUi> = emptyList(),
    /** The person the timeline is filtered to, or null for no person filter. */
    val selectedPersonId: Long? = null,
    /** Photo keys ([GalleryItem.stableId]) the selected person appears in. [applyFilter] keeps only
     *  feed items in this set while [selectedPersonId] is non-null, composing like the Offline set. */
    val personPhotoKeys: Set<String> = emptySet(),
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

/** [Working] tracks how many cloud-only items have finished decrypting (determinate progress);
 *  the terminal chooser launch is a one-shot intent, not a state. [Done] carries how the batch
 *  ended so a photo that never made it into the chooser is reported instead of dropped. */
sealed class MultiShareState {
    data object Idle : MultiShareState()
    data class  Working(val done: Int, val total: Int) : MultiShareState()
    data class  Done(val shared: Int, val failed: Int) : MultiShareState()
}

sealed class AddToAlbumState {
    data object Idle : AddToAlbumState()
    data object Working : AddToAlbumState()
    /** [cloudAdded] attached to a cloud album; [localMoved] queued to back up then join; [skipped]
     *  the target couldn't accept. Surfaced as a partial-success snackbar for mixed selections. */
    data class Done(val cloudAdded: Int, val localMoved: Int, val skipped: Int, val albumName: String) : AddToAlbumState()
    data class Failed(val message: String) : AddToAlbumState()
}

/** Skipped count covers cloud-only items (no local bytes to modify) plus local files the OS
 *  refused to write in-place (foreign owner under scoped storage). */
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
    // Not a Drive tag — locally computed from OFFLINE_PIN_IDS, like Favorites. Filters to the
    // cloud photos pinned for offline.
    Offline,
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
    val day: Int? = null,
)

enum class MediaType { All, PhotosOnly, VideosOnly }
enum class SyncStatusFilter { All, LocalOnly, BackedUp }

enum class TimelineGrouping { None, Day, Month, Year }

/**
 * One person on the People rail. Small on purpose (a cover reference, never an embedding) so the
 * gallery state stays cheap. [coverPhotoKey] is the cover face's photo ([GalleryItem.stableId]),
 * resolved to a thumbnail the same way a timeline cell is. [faceBox] is the cover face region as
 * fractions (0..1) of the cover image so a tile can crop to the face at any thumbnail resolution;
 * it is null when the cover photo's dimensions are unknown (a cloud-only photo not on the device),
 * in which case the tile shows the whole cover thumbnail.
 */
data class PersonUi(
    val personId: Long,
    val displayName: String?,
    val coverPhotoKey: String,
    val faceBox: FaceBox? = null,
    /** Cached number of faces assigned to the person; the album-style People card shows it as a count. */
    val faceCount: Int = 0,
)

/** A face region as fractions (0..1) of its image, left/top/right/bottom. */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Adapt a domain [eu.akoos.photos.domain.model.PersonSummary] to the UI [PersonUi], dropping a person
 * with no resolvable cover (so a tile never renders blank). Shared by every surface that lists people.
 */
fun eu.akoos.photos.domain.model.PersonSummary.toPersonUi(): PersonUi? {
    val cover = coverPhotoKey ?: return null
    return PersonUi(
        personId = personId,
        displayName = displayName,
        coverPhotoKey = cover,
        faceBox = faceBox?.let { FaceBox(it.left, it.top, it.right, it.bottom) },
        faceCount = faceCount,
    )
}
