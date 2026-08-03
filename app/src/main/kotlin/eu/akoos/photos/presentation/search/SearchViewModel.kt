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

package eu.akoos.photos.presentation.search

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.presentation.common.GalleryItemSelectionController
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.map.MapPin
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.OfflineGeocoder
import eu.akoos.photos.util.retryOnDbTear
import javax.inject.Inject

/** Backs the Search screen. Filters the merged gallery by displayName substring + ContentFilter. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val photoLocationDao: PhotoLocationDao,
    private val selectionFactory: GalleryItemSelectionController.Factory,
    private val thumbnailUrlStore: ThumbnailUrlStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Hidden vault filter — same DataStore key the gallery uses. Without this the
     *  search page surfaces hidden photos via name / date / content-filter matches,
     *  defeating the point of the Hidden vault. */
    private val hiddenUrisFlow = context.settingsDataStore.data.map {
        it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
    }

    /** Cloud linkIds pinned for offline — the Offline category chip filters results to these.
     *  Same DataStore key the gallery and album-detail surfaces read. */
    private val offlinePinIdsFlow = context.settingsDataStore.data.map {
        it[SettingsKeys.OFFLINE_PIN_IDS] ?: emptySet()
    }

    /** MediaStore uris hearted on the device, the half of the Favourites chip Drive cannot answer,
     *  since a photo that was never backed up has no PhotoTag to carry one. Same DataStore key the
     *  gallery reads, so the chip narrows to the same photos on both surfaces. */
    private val favoriteIdsFlow = context.settingsDataStore.data.map {
        it[SettingsKeys.FAVORITE_IDS] ?: emptySet()
    }

    private fun List<GalleryItem>.dropHidden(hiddenUris: Set<String>): List<GalleryItem> =
        filter { item ->
            val uri = when (item) {
                is GalleryItem.LocalOnly -> item.local.uri
                is GalleryItem.Synced -> item.local.uri
                is GalleryItem.CloudOnly -> null
            }
            uri == null || uri !in hiddenUris
        }

    /** Overlay the store's freshly-decrypted thumbnail URL onto a cloud-only item so its map pin has
     *  an image source (the lite feed no longer carries the URL). A Local/Synced fix already paints
     *  from its local uri, so it is returned untouched. */
    private fun resolveThumbnail(item: GalleryItem, urls: Map<String, String>): GalleryItem =
        if (item is GalleryItem.CloudOnly) {
            val url = urls[item.cloud.linkId] ?: item.cloud.thumbnailUrl
            if (url == item.cloud.thumbnailUrl) item
            else GalleryItem.CloudOnly(item.cloud.copy(thumbnailUrl = url))
        } else {
            item
        }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _contentFilter = MutableStateFlow(ContentFilter())
    val contentFilter: StateFlow<ContentFilter> = _contentFilter.asStateFlow()

    /** Category chip selection — mirrors the gallery's [GalleryFilter] so the shared filter
     *  sheet drives both surfaces. [GalleryFilter.All] means no category constraint. */
    private val _selectedCategory = MutableStateFlow(GalleryFilter.All)
    val selectedCategory: StateFlow<GalleryFilter> = _selectedCategory.asStateFlow()

    /**
     * Unfiltered gallery source. The search page's empty state surfaces "On this day"
     * memories and a "Jump to month" grid that must reflect the user's entire library
     * — independent of any active query/contentFilter. Exposing the raw merged feed
     * here keeps that derived UI in sync with sync state changes without re-running
     * the filter pipeline.
     */
    val allItems: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(getGalleryItems.invoke(userId), hiddenUrisFlow) { all, hidden ->
                all.dropHidden(hidden)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Geotagged photos for the account — the Map entry card renders these as a live mini-map
     *  preview. Reads the persisted location table directly so it reflects the GPS backfill as
     *  rows land. */
    val geotaggedLocations: StateFlow<List<PhotoLocationEntity>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else photoLocationDao.observeForUser(userId.id)
        }
        .retryOnDbTear("SearchGeotagged")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The geotagged fixes enriched with the [GalleryItem] each id resolves to in the merged library,
     *  so the Map card's mini-pins have a thumbnail source (local uri or cloud thumbnail, both loaded
     *  through the gallery's Coil path). An id with no library match yet yields a null item, leaving
     *  that pin on the placeholder until the merge catches up. */
    val geotaggedPins: StateFlow<List<MapPin>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            // The mini-pin thumbnail is built imperatively (outside any Compose cell), so it can't
            // read the LocalThumbnailUrls CompositionLocal. Fold the store in and stamp each cloud
            // pin's freshly-decrypted URL onto the item so cloud-only fixes get a thumbnail again;
            // the set is bounded by the preview marker cap, so the extra re-emit is cheap.
            else combine(
                geotaggedLocations,
                getGalleryItems.invoke(userId),
                thumbnailUrlStore.urls,
            ) { locs, library, urls ->
                val itemByKey = HashMap<String, GalleryItem>(library.size * 2)
                for (item in library) {
                    when (item) {
                        is GalleryItem.LocalOnly -> itemByKey[item.local.uri] = item
                        is GalleryItem.Synced -> {
                            itemByKey[item.local.uri] = item
                            itemByKey[item.cloud.linkId] = item
                        }
                        is GalleryItem.CloudOnly -> itemByKey[item.cloud.linkId] = item
                    }
                }
                // A fix whose photo is gone from the library is dropped rather than kept with an
                // empty thumbnail. The map screen already resolves the same way, so keeping them
                // here put a marker with no picture on the card and counted a city the map does not
                // show: the card said seven while the map had one.
                locs.mapNotNull { loc ->
                    itemByKey[loc.id]?.let { item ->
                        MapPin(loc.id, loc.latitude, loc.longitude, resolveThumbnail(item, urls))
                    }
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of distinct cities the account's geotagged photos were taken in — the Map card's
     *  subtitle. Each fix is reverse-geocoded to a "City, Country" label off the main thread (the
     *  offline geocoder caches its dataset after the first lookup) and the distinct labels counted.
     *  Recomputes whenever [geotaggedLocations] changes; emits 0 until the first pass completes. */
    val distinctCityCount: StateFlow<Int> = geotaggedPins
        .mapLatest { resolved ->
            val labels = HashSet<String>()
            for (loc in resolved) {
                OfflineGeocoder.reverseGeocode(context, loc.latitude, loc.longitude)
                    ?.let { labels.add(it) }
            }
            labels.size
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The text field updates [_query] on every keystroke for instant echo, but the heavy
     *  per-item filter only needs to run once typing settles. Debouncing the query feed into
     *  [results] keeps the field responsive while sparing the library a full re-scan per key. */
    private val debouncedQuery = _query.debounce(250)

    val results: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(
                getGalleryItems.invoke(userId),
                debouncedQuery,
                _contentFilter,
                // Category + the two local id sets the chips read travel together so the later
                // sources stay within the typed combine arity; the Offline chip filters on the
                // pinned linkIds and the Favourites chip on the device hearts, both in applyAll.
                combine(_selectedCategory, offlinePinIdsFlow, favoriteIdsFlow) { category, pins, hearts ->
                    Triple(category, pins, hearts)
                },
                hiddenUrisFlow,
            ) { all, q, filter, chipSets, hidden ->
                val (category, pins, hearts) = chipSets
                applyAll(all.dropHidden(hidden), q, filter, category, pins, hearts)
            }
        }
        // Fold/normalize + per-item category checks over the whole library are heavy; run them off
        // the main thread so typing stays smooth on large libraries.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { _query.value = value }
    fun setContentFilter(filter: ContentFilter) { _contentFilter.value = filter }
    fun onCategorySelected(filter: GalleryFilter) { _selectedCategory.value = filter }
    fun clearAll() {
        _query.value = ""
        _contentFilter.value = ContentFilter()
        _selectedCategory.value = GalleryFilter.All
    }

    // ── Multi-select ──────────────────────────────────────────────────────────────────────────────
    //
    // Delegates to the shared [GalleryItemSelectionController] (the timeline uses the same one), so
    // every selection action, the delete + strip permission handshakes and the Undo offer live in
    // one place. Only Select-all needs local context — the current results list.
    val sel = selectionFactory.create(viewModelScope)

    val selectedItems: StateFlow<Set<GalleryItem>> get() = sel.selectedItems
    val albums: StateFlow<List<Album>> get() = sel.albums
    val shareIntent get() = sel.shareIntent
    val offlineBatchResult get() = sel.offlineBatchResult
    val actionFailure get() = sel.actionFailure
    val downloadStarted get() = sel.downloadStarted
    val isDeleting: StateFlow<Boolean> get() = sel.isDeleting
    val pendingDeleteIntent get() = sel.pendingDeleteIntent
    val pendingStripIntent get() = sel.pendingStripIntent
    val multiStripState get() = sel.multiStripState
    val favoriteIds get() = sel.favoriteIds
    val offlinePinIds get() = sel.offlinePinIds
    val favoriteState get() = sel.favoriteState

    fun toggleSelection(item: GalleryItem) = sel.toggleSelection(item)
    fun setSelection(items: Set<GalleryItem>) = sel.setSelection(items)
    fun clearSelection() = sel.clearSelection()
    fun selectAll() = sel.selectAll(results.value)
    fun shareSelected() = sel.shareSelected()
    fun addSelectedToAlbum(albumLinkId: String, onResult: (joined: Int, queued: Int) -> Unit) =
        sel.addSelectedToAlbum(albumLinkId, onResult)
    fun createAlbumThenAddSelected(
        name: String,
        onResult: (joined: Int, queued: Int, error: String?) -> Unit,
    ) = sel.createAlbumThenAddSelected(name, onResult)
    fun backUpSelected(onResult: (queued: Int) -> Unit) = sel.backUpSelected(onResult)
    fun downloadSelected(onResult: (succeeded: Int, failed: Int) -> Unit) = sel.downloadSelected(onResult)
    fun toggleSelectedOffline() = sel.toggleSelectedOffline()
    fun toggleSelectedFavorite() = sel.toggleSelectedFavorite()
    fun hideSelected() = sel.hideSelected()
    /** The two halves the selection's hide would act on, for the confirmation that fronts it. */
    fun hideSplitForSelection() = sel.hideSplitForSelection()
    fun deleteSelected(freeUpSpace: Boolean, deleteFromCloud: Boolean) =
        sel.deleteSelected(freeUpSpace, deleteFromCloud)
    fun onDeletePermissionGranted() = sel.onDeletePermissionGranted()
    fun clearPendingDeleteIntent() = sel.clearPendingDeleteIntent()
    fun stripMetadataSelected(config: MetadataStripConfig) = sel.stripMetadataSelected(config)
    fun onStripPermissionGranted() = sel.onStripPermissionGranted()
    fun clearPendingStripIntent() = sel.clearPendingStripIntent()
    fun resetMultiStripState() = sel.resetMultiStripState()

    /** The narrowing itself lives in [SearchFilter], which needs no Android; only the localized
     *  month and category names are resolved here and handed to it. */
    private fun applyAll(
        items: List<GalleryItem>,
        q: String,
        filter: ContentFilter,
        category: GalleryFilter,
        offlinePinIds: Set<String> = emptySet(),
        favoriteIds: Set<String> = emptySet(),
    ): List<GalleryItem> = SearchFilter.apply(
        items = items,
        q = q,
        filter = filter,
        category = category,
        offlinePinIds = offlinePinIds,
        favoriteIds = favoriteIds,
        foldedMonths = foldedMonths,
        foldedCategoryNames = foldedCategoryNames,
    )

    /** Localized month names, pre-folded once, so a "june" / "június" query matches by capture month. */
    private val foldedMonths: List<String> by lazy {
        SearchFilter.foldedMonthNames(java.util.Locale.getDefault())
    }

    /** PhotoTag id → pre-folded localized category name, so "screenshot" / "selfie" etc. match. */
    private val foldedCategoryNames: Map<Int, String> by lazy {
        mapOf(
            0 to R.string.gallery_filter_favorites,
            1 to R.string.gallery_filter_screenshots,
            2 to R.string.filter_type_videos,
            3 to R.string.gallery_filter_live_photos,
            4 to R.string.gallery_filter_motion_photos,
            5 to R.string.gallery_filter_selfies,
            6 to R.string.gallery_filter_portraits,
            7 to R.string.gallery_filter_bursts,
            8 to R.string.gallery_filter_panoramas,
            9 to R.string.gallery_filter_raw,
        ).mapValues { SearchFilter.fold(context.getString(it.value)) }
    }
}
