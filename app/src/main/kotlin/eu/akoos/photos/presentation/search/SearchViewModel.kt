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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.dao.PhotoLocationDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.drive.ThumbnailUrlStore
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.presentation.common.GalleryItemSelectionController
import eu.akoos.photos.presentation.common.MoveToFolderController
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.PersonUi
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
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val faceDao: FaceDao,
    private val moveController: MoveToFolderController,
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

    /** Whether a Proton account is signed in. A null-userId local-only session leaves the cloud
     *  selection actions without a destination, so the screen hides them. Defaults to signed-in so
     *  nothing flickers before the first emit. */
    val isSignedIn: StateFlow<Boolean> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _contentFilter = MutableStateFlow(ContentFilter())
    val contentFilter: StateFlow<ContentFilter> = _contentFilter.asStateFlow()

    /** Category chip selection — mirrors the gallery's [GalleryFilter] so the shared filter
     *  sheet drives both surfaces. [GalleryFilter.All] means no category constraint. */
    private val _selectedCategory = MutableStateFlow(GalleryFilter.All)
    val selectedCategory: StateFlow<GalleryFilter> = _selectedCategory.asStateFlow()

    /** The person the People rail filters results to (id + their photo keys, loaded once on tap), or
     *  empty when no person is selected. Held as one value so the id and its key set update atomically,
     *  never leaving the results combine to see a new id against a stale key set. */
    private data class PersonSelection(val id: Long? = null, val keys: Set<String> = emptySet())
    private val _personSelection = MutableStateFlow(PersonSelection())
    val selectedPersonId: StateFlow<Long?> =
        _personSelection.map { it.id }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Unfiltered gallery source. The search page's empty state surfaces "On this day"
     * memories and a "Jump to month" grid that must reflect the user's entire library
     * — independent of any active query/contentFilter. Exposing the raw merged feed
     * here keeps that derived UI in sync with sync state changes without re-running
     * the filter pipeline.
     */
    val allItems: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
                else getGalleryItems.invoke(userId)
            combine(libraryFlow, hiddenUrisFlow) { all, hidden ->
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
            // The mini-pin thumbnail is built imperatively (outside any Compose cell), so it can't
            // read the LocalThumbnailUrls CompositionLocal. Fold the store in and stamp each cloud
            // pin's freshly-decrypted URL onto the item so cloud-only fixes get a thumbnail again;
            // the set is bounded by the preview marker cap, so the extra re-emit is cheap.
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
                else getGalleryItems.invoke(userId)
            combine(
                geotaggedLocations,
                libraryFlow,
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
    private object CityCountCache {
        @Volatile var last: Int = 0
    }

    val distinctCityCount: StateFlow<Int> = geotaggedPins
        .mapLatest { resolved ->
            // The count is stable across a session, but this view-model is recreated on every Search
            // entry, so the last count is cached and used as the initial value. While the pins are still
            // loading they read empty; keep the cached count then instead of recomputing from zero, so
            // the card shows the number at once instead of only after the geocode finishes.
            if (resolved.isEmpty()) return@mapLatest CityCountCache.last
            val labels = HashSet<String>()
            for (loc in resolved) {
                OfflineGeocoder.reverseGeocode(context, loc.latitude, loc.longitude)
                    ?.let { labels.add(it) }
            }
            labels.size.also { CityCountCache.last = it }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CityCountCache.last)

    /** The text field updates [_query] on every keystroke for instant echo, but the heavy
     *  per-item filter only needs to run once typing settles. Debouncing the query feed into
     *  [results] keeps the field responsive while sparing the library a full re-scan per key. */
    private val debouncedQuery = _query.debounce(250)

    /** The chip-driven inputs folded into one value so the [results] combine stays within the typed
     *  5-flow arity: the category chip, the Offline pin set, the Favourites device-heart set, and the
     *  People rail's selected-person keys (null when no person is selected). */
    private data class ChipState(
        val category: GalleryFilter,
        val pins: Set<String>,
        val hearts: Set<String>,
        val personKeys: Set<String>?,
    )

    val results: StateFlow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
                else getGalleryItems.invoke(userId)
            combine(
                libraryFlow,
                debouncedQuery,
                _contentFilter,
                // Category, the two local id sets the chips read, and the selected person's photo keys
                // travel together so the later sources stay within the typed combine arity; the Offline
                // chip filters on the pinned linkIds, the Favourites chip on the device hearts, and the
                // People rail on the person keys, all in applyAll.
                combine(
                    _selectedCategory,
                    offlinePinIdsFlow,
                    favoriteIdsFlow,
                    _personSelection.map { if (it.id != null) it.keys else null },
                ) { category, pins, hearts, personKeys ->
                    ChipState(category, pins, hearts, personKeys)
                },
                hiddenUrisFlow,
            ) { all, q, filter, chip, hidden ->
                applyAll(
                    all.dropHidden(hidden), q, filter,
                    chip.category, chip.pins, chip.hearts, chip.personKeys,
                )
            }
        }
        // Fold/normalize + per-item category checks over the whole library are heavy; run them off
        // the main thread so typing stays smooth on large libraries.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Named people whose name matches the typed query, so a name search reaches a person and their
     * photos even when no filename matches the text. Diacritics are folded both ways, so "akos" finds
     * "Ákos". Empty while the query is blank or nothing matches.
     */
    val peopleSuggestions: StateFlow<List<PersonUi>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(observePeopleUseCase(userId, allItems), debouncedQuery) { people, q ->
                val needle = foldForMatch(q)
                if (needle.isBlank()) emptyList()
                else people
                    .filter { !it.displayName.isNullOrBlank() && foldForMatch(it.displayName!!).contains(needle) }
                    .mapNotNull { it.toPersonUi() }
                    .take(12)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All NAMED people for the People rail (the filter chip + face bar in the shared category rail),
     *  independent of the query. Gated on the AI + face flags and a signed-in account, so a logged-out
     *  or ML-off session shows no chip. Mirrors the timeline's people rail. */
    val people: StateFlow<List<PersonUi>> = combine(
        context.settingsDataStore.data
            .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.FACE_ENABLED] == true }
            .distinctUntilChanged(),
        accountManager.getPrimaryUserId(),
    ) { aiOn, userId -> aiOn to userId }
        .flatMapLatest { (aiOn, userId) ->
            if (!aiOn || userId == null) flowOf(emptyList())
            else observePeopleUseCase(userId, allItems).map { summaries ->
                summaries.filter { !it.displayName.isNullOrBlank() }.mapNotNull { it.toPersonUi() }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // If the people list empties while a person filter is active (AI turned off, sign-out), drop
        // the filter so results do not stay narrowed with no way back, mirroring the timeline.
        viewModelScope.launch {
            people.collectLatest { p ->
                if (p.isEmpty() && _personSelection.value.id != null) {
                    _personSelection.value = PersonSelection()
                }
            }
        }
    }

    /** Select a person to filter results to (loads their photo keys once), or clear with null. The
     *  shared rail provider passes null when the active person is tapped again. Mirrors the timeline. */
    fun onPersonSelected(personId: Long?) {
        viewModelScope.launch {
            if (personId == null) {
                _personSelection.value = PersonSelection()
                return@launch
            }
            val userId = accountManager.getPrimaryUserId().first() ?: return@launch
            val keys = try {
                faceDao.photoKeysForPerson(userId.id, personId).first().toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet<String>()
            }
            _personSelection.value = PersonSelection(personId, keys)
        }
    }

    /** Diacritic-folded, lowercased form so a name search like "akos" matches "Ákos". */
    private fun foldForMatch(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Adapt a domain [PersonSummary] to [PersonUi], dropping a person with no resolvable cover. */
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

    // ── Move to a device folder (logged-out, device data only) ──────────────────────────────────
    // Delegated to the shared [MoveToFolderController], the same relocation the timeline offers, so a
    // search selection can send its device photos into a DCIM folder.

    /** Existing device folders offered as move targets, kept warm for the picker. */
    val moveTargetFolders = moveController.targetFolders(viewModelScope)

    /** One-shot system write-consent request a foreign-file move needs; the screen's host drives it. */
    val pendingMoveIntent = moveController.pendingMoveIntent

    /** Destination folder of a completed move, for the host's snackbar. */
    val moveConfirmation = moveController.moveConfirmation

    /** The selected photos that carry a device file, mapped to their uris; a cloud-only one has none. */
    private fun selectedDeviceUris(): List<String> = selectedItems.value.mapNotNull { item ->
        when (item) {
            is GalleryItem.LocalOnly -> item.local.uri
            is GalleryItem.Synced -> item.local.uri
            is GalleryItem.CloudOnly -> null
        }
    }

    /** Move every selected device photo into [folderName] under DCIM/, then drop the selection. */
    fun moveSelectedToFolder(folderName: String) {
        val uris = selectedDeviceUris()
        moveController.move(viewModelScope, uris, folderName)
        clearSelection()
    }

    /** Move the selection into a freshly named device folder, born with the photos the move lands there. */
    fun createFolderWithPhotos(name: String) {
        val uris = selectedDeviceUris()
        moveController.createFolder(viewModelScope, name, uris)
        clearSelection()
    }

    fun onMovePermissionGranted() = moveController.onPermissionGranted(viewModelScope)

    fun clearPendingMove() = moveController.clearPending()

    /** The narrowing itself lives in [SearchFilter], which needs no Android; only the localized
     *  month and category names are resolved here and handed to it. */
    private fun applyAll(
        items: List<GalleryItem>,
        q: String,
        filter: ContentFilter,
        category: GalleryFilter,
        offlinePinIds: Set<String> = emptySet(),
        favoriteIds: Set<String> = emptySet(),
        personPhotoKeys: Set<String>? = null,
    ): List<GalleryItem> = SearchFilter.apply(
        items = items,
        q = q,
        filter = filter,
        category = category,
        offlinePinIds = offlinePinIds,
        favoriteIds = favoriteIds,
        personPhotoKeys = personPhotoKeys,
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
