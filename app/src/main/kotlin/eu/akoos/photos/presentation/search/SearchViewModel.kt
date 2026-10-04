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
import android.content.res.Configuration
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.domain.entity.Album
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.repository.DrivePhotoRepository
import eu.akoos.photos.domain.repository.LocalMediaRepository
import eu.akoos.photos.domain.usecase.AlbumSortMode
import eu.akoos.photos.domain.usecase.DeletePhotoUseCase
import eu.akoos.photos.domain.usecase.DownloadPhotosUseCase
import eu.akoos.photos.domain.usecase.ForceUploadLocalUrisUseCase
import eu.akoos.photos.domain.model.PersonSummary
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.domain.usecase.ObservePeopleUseCase
import eu.akoos.photos.domain.usecase.SemanticSearchUseCase
import eu.akoos.photos.domain.usecase.decodeAlbumOrder
import eu.akoos.photos.presentation.albums.DeviceFolder
import eu.akoos.photos.presentation.albums.DeviceFolderCards
import eu.akoos.photos.presentation.common.GalleryItemSelectionController
import eu.akoos.photos.presentation.common.MoveToFolderController
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.FaceBox
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.PersonUi
import eu.akoos.photos.presentation.gallery.categoryLabelRes
import eu.akoos.photos.presentation.gallery.visibleCategories
import eu.akoos.photos.util.FolderCoverMap
import eu.akoos.photos.util.MetadataStripConfig
import eu.akoos.photos.util.retryOnDbTear
import java.util.Locale
import javax.inject.Inject

/** Backs the Search screen. Filters the merged gallery by displayName substring + ContentFilter. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val selectionFactory: GalleryItemSelectionController.Factory,
    private val observePeopleUseCase: ObservePeopleUseCase,
    private val semanticSearchUseCase: SemanticSearchUseCase,
    private val faceDao: FaceDao,
    private val moveController: MoveToFolderController,
    private val driveRepo: DrivePhotoRepository,
    private val cloudAlbumDao: CloudAlbumDao,
    private val localMediaRepo: LocalMediaRepository,
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

    /** Whether a Proton account is signed in. A null-userId local-only session leaves the cloud
     *  selection actions without a destination, so the screen hides them. Defaults to signed-in so
     *  nothing flickers before the first emit. */
    val isSignedIn: StateFlow<Boolean> = accountManager.getPrimaryUserId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The AI master + face toggles both on, the one gate [faceEnabled], [people] and
     *  [peopleSuggestions] share. */
    private val faceSwitchesOn: Flow<Boolean> = context.settingsDataStore.data
        .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.FACE_ENABLED] == true }
        .distinctUntilChanged()

    /** Whether on-device face grouping is switched on (the AI master + face toggles). Drives the
     *  People entry card and the selection's Add to person, both independent of sign-in, so a
     *  local-only guest can reach, name and add to their people too. Defaults off so neither flashes
     *  before the switches resolve. */
    val faceEnabled: StateFlow<Boolean> = faceSwitchesOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    private val chipState: Flow<ChipState> = combine(
        _selectedCategory,
        offlinePinIdsFlow,
        favoriteIdsFlow,
        _personSelection.map { if (it.id != null) it.keys else null },
    ) { category, pins, hearts, personKeys ->
        ChipState(category, pins, hearts, personKeys)
    }

    /** The app's language, as the screen resolves it with appLocale(), so typed month and category
     *  names match in the language the page is drawn in, a per-app language included. Seeded from the
     *  application resources until the screen hands it over. */
    private val _locale = MutableStateFlow<Locale>(context.resources.configuration.locales[0] ?: Locale.getDefault())

    /** The localized text a typed query is matched against, pre-folded once per language: the category
     *  names both per chip ([filterNames]) and per PhotoTag id ([categoryNames]). */
    private data class LocalizedText(
        val months: List<String>,
        val filterNames: Map<GalleryFilter, String>,
    ) {
        val categoryNames: Map<Int, String> =
            filterNames.entries.mapNotNull { (filter, name) -> filter.tagId?.let { it to name } }.toMap()
    }

    private val localizedText: Flow<LocalizedText> = _locale
        .map { LocalizedText(SearchFilter.foldedMonthNames(it), foldedFilterNames(it)) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** The keyword + metadata matches, the search path that has always been here: the merged library
     *  narrowed by the typed query, the content filter, the category chip and the People rail. Folded
     *  into [results] first and unchanged, so a semantic-off session sees exactly this. */
    private val keywordResults: Flow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
                else getGalleryItems.invoke(userId)
            combine(
                libraryFlow,
                combine(debouncedQuery, localizedText) { q, text -> q to text },
                _contentFilter,
                // Category, the two local id sets the chips read, and the selected person's photo keys
                // travel together so the later sources stay within the typed combine arity; the Offline
                // chip filters on the pinned linkIds, the Favourites chip on the device hearts, and the
                // People rail on the person keys, all in applyAll.
                chipState,
                hiddenUrisFlow,
            ) { all, (q, text), filter, chip, hidden ->
                applyAll(
                    all.dropHidden(hidden), q, text, filter,
                    chip.category, chip.pins, chip.hearts, chip.personKeys,
                )
            }
        }
        // Fold/normalize + per-item category checks over the whole library are heavy; run them off
        // the main thread so typing stays smooth on large libraries.
        .flowOn(Dispatchers.Default)

    /** The AI master switch and semantic search both on. The whole semantic feed hangs off this, the
     *  same gate shape the [people] rail uses, so a semantic-off session adds nothing to [results]. */
    private val semanticEnabled = context.settingsDataStore.data
        .map { it[SettingsKeys.AI_FEATURES_ENABLED] == true && it[SettingsKeys.SEMANTIC_ENABLED] == true }
        .distinctUntilChanged()

    /** Exposed for the search bar placeholder: with semantic search on the field matches any
     *  description, not just filenames, so the hint text switches accordingly. */
    val semanticSearchEnabled: StateFlow<Boolean> = semanticEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The content-search feed: whether a search is still running for the current query, and the
     *  resolved matches so far. Held as one value so the loading flag and the matches never disagree. */
    private data class SemanticFeed(val loading: Boolean, val items: List<GalleryItem>)

    /**
     * The image-content search as (still-running, ranked photo keys). Gated on the AI + semantic flags
     * (never runs, empty when off) and guest-safe (a null account keys the local partition).
     *
     * A fresh non-blank query emits loading true at once, so the screen's placeholder shows through the
     * settle delay AND the model run rather than only after; then it waits [SEMANTIC_DEBOUNCE_MS] for
     * typing to settle (flatMapLatest cancels a superseded query mid-wait, exactly as a debounce would),
     * embeds and ranks off the main thread, and emits the ranked keys with loading false. Keys, not
     * items, cross this stage so [semanticFeed] can re-resolve them against the library on a sync change
     * without re-running the model. The CLIP embed is far dearer than the keyword filter, so it settles a
     * touch longer than the keyword feed.
     */
    private val rankedKeys: Flow<Pair<Boolean, List<String>>> = combine(
        semanticEnabled,
        accountManager.getPrimaryUserId(),
    ) { on, userId -> on to userId }
        .flatMapLatest { (on, userId) ->
            if (!on) flowOf(false to emptyList<String>())
            else _query.flatMapLatest { q ->
                if (q.isBlank()) flowOf(false to emptyList<String>())
                else flow {
                    emit(true to emptyList<String>())
                    delay(SEMANTIC_DEBOUNCE_MS)
                    emit(false to semanticSearchUseCase.searchKeys(q, userId, SEMANTIC_LIMIT))
                }
            }
        }
        .flowOn(Dispatchers.Default)

    /**
     * The resolved content matches plus the live loading flag. Each ranked key is resolved to the
     * [GalleryItem] it names in the merged library, in rank order, dropping any key no longer in the
     * feed, then narrowed by the same content filter, category chip and person the keyword matches
     * answer to ([SearchFilter.applyFacets]). Held so both [results] and [semanticSearching] read one
     * consistent value.
     */
    private val semanticFeed: StateFlow<SemanticFeed> =
        combine(rankedKeys, allItems, _contentFilter, chipState) { (loading, keys), library, filter, chip ->
            if (keys.isEmpty()) {
                SemanticFeed(loading, emptyList())
            } else {
                val byKey = library.associateBy { it.stableId }
                val hits = keys.mapNotNull { byKey[it] }
                SemanticFeed(
                    loading,
                    SearchFilter.applyFacets(hits, filter, chip.category, chip.pins, chip.hearts, chip.personKeys),
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SemanticFeed(false, emptyList()))

    /** True while a content search for the current query is still running, so the grid can show a
     *  placeholder instead of an empty "no results" flash the matches then pop into. */
    val semanticSearching: StateFlow<Boolean> = semanticFeed
        .map { it.loading }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The grid's photos: the keyword + metadata matches first, in their existing order, then any
     * semantic-only match after them in rank order, deduped by [GalleryItem.stableId]. Semantic off (or
     * no hits) leaves this exactly equal to [keywordResults], so the non-semantic path never changes;
     * with it on the same grid gains the content-matched photos.
     */
    val results: StateFlow<List<GalleryItem>> = combine(keywordResults, semanticFeed) { keyword, feed ->
        val semantic = feed.items
        if (semantic.isEmpty()) {
            keyword
        } else {
            val seen = HashSet<String>(keyword.size + semantic.size)
            for (item in keyword) seen.add(item.stableId)
            val tail = semantic.filter { seen.add(it.stableId) }
            if (tail.isEmpty()) keyword else keyword + tail
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Named people whose name matches the typed query, so a name search reaches a person and their
     * photos even when no filename matches the text. Diacritics are folded both ways, so "akos" finds
     * "Ákos". Empty while the query is blank or nothing matches, and while the AI or face switch is
     * off, like [people].
     */
    val peopleSuggestions: StateFlow<List<PersonUi>> = combine(
        faceSwitchesOn,
        accountManager.getPrimaryUserId(),
    ) { faceOn, userId -> faceOn to userId }
        .flatMapLatest { (faceOn, userId) ->
            if (!faceOn) flowOf(emptyList())
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
     *  independent of the query. Gated on the AI + face flags, so an ML-off session shows no chip; a
     *  local-only session surfaces its own named people. Mirrors the timeline's people rail. */
    val people: StateFlow<List<PersonUi>> = combine(
        faceSwitchesOn,
        accountManager.getPrimaryUserId(),
    ) { aiOn, userId -> aiOn to userId }
        .flatMapLatest { (aiOn, userId) ->
            if (!aiOn) flowOf(emptyList())
            else observePeopleUseCase(userId, allItems).map { summaries ->
                summaries.filter { !it.displayName.isNullOrBlank() }.mapNotNull { it.toPersonUi() }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The user's own cloud albums as the album cache holds them. Cache only: the Albums tab keeps it
     * fresh, so Search adds no network call. Read again whenever that cache changes, as when a refresh
     * the Albums tab started lands, and whenever the hidden cloud photos change, which re-resolves a
     * cover that just became hidden, as the Albums tab does. A guest has no cloud albums, and a later
     * sign-in re-subscribes this, so the rows gain them in place.
     */
    private val cachedCloudAlbums: Flow<List<Album>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else combine(cloudAlbumDao.observeOwned(), driveRepo.observeHiddenAlbumMemberLinkIds()) { _, _ -> }
                .mapLatest { driveRepo.loadAlbumsCached() }
        }
        .retryOnDbTear("SearchAlbums")

    /** The device folders as the Albums tab builds them, from the shared device listing rather than a
     *  scan of their own, with the vaulted photos left out and each folder's pinned cover. A guest has
     *  these too. */
    private val deviceFolders: Flow<List<DeviceFolder>> = combine(
        localMediaRepo.observeLocalMedia(),
        context.settingsDataStore.data
            .map { prefs ->
                (prefs[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()) to
                    FolderCoverMap.parse(prefs[SettingsKeys.FOLDER_COVER_URI_MAP])
            }
            .distinctUntilChanged(),
    ) { items, (hiddenUris, pinnedCovers) ->
        DeviceFolderCards.build(items, hiddenUris, pinnedCovers)
    }
        // Grouping the whole device library into folders is CPU work, kept off the main thread.
        .flowOn(Dispatchers.Default)
        // No folders until the device listing lands, so the cloud albums never wait on a scan.
        .onStart { emit(emptyList()) }
        .catch { emit(emptyList()) }

    /** What both album surfaces are drawn from, read once and shared between them. The album settings
     *  travel as one snapshot, so writes to unrelated settings re-emit nothing. */
    private val albumSource: StateFlow<SearchAlbums.Source> = combine(
        cachedCloudAlbums,
        deviceFolders,
        context.settingsDataStore.data
            .map { prefs ->
                SearchAlbums.Source(
                    hiddenAlbumIds = prefs[SettingsKeys.HIDDEN_ALBUM_IDS] ?: emptySet(),
                    excluded = prefs[SettingsKeys.ALBUMS_GRID_EXCLUDED] ?: emptySet(),
                    sortMode = AlbumSortMode.fromOrdinal(prefs[SettingsKeys.ALBUMS_SORT_MODE]),
                    customOrder = decodeAlbumOrder(prefs[SettingsKeys.ALBUMS_CUSTOM_ORDER]),
                )
            }
            .distinctUntilChanged(),
    ) { cloudAlbums, folders, settings ->
        settings.copy(cloudAlbums = cloudAlbums, deviceFolders = folders)
    }
        .catch { emit(SearchAlbums.Source()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchAlbums.Source())

    /** The idle page's Albums row: the Albums tab's cards in its order, by [SearchAlbums.row]. */
    val albumRow: StateFlow<List<SearchAlbum>> = albumSource
        .map { SearchAlbums.row(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The albums and device folders whose name matches the typed query, by [SearchAlbums.matches].
     *  Empty while the query is blank. */
    val albumSuggestions: StateFlow<List<SearchAlbum>> =
        combine(albumSource, debouncedQuery) { source, q -> SearchAlbums.matches(source, q) }
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
            val account = accountManager.getPrimaryUserId().first()?.id ?: PhotoLocationEntity.LOCAL_USER
            val keys = try {
                faceDao.photoKeysForPerson(account, personId).first().toSet()
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
        _personSelection.value = PersonSelection()
    }

    /** The app's language as the screen resolves it, so the typed month and category names follow it. */
    fun setLocale(locale: Locale) { _locale.value = locale }

    /** The years the library has photos from, newest first: what a typed month is offered with. */
    private val availableYears: StateFlow<List<Int>> = allItems
        .map { items ->
            val calendar = java.util.Calendar.getInstance()
            val seen = HashSet<Int>()
            for (item in items) {
                if (item.captureTimeMs <= 0L) continue
                calendar.timeInMillis = item.captureTimeMs
                seen.add(calendar.get(java.util.Calendar.YEAR))
            }
            seen.sortedDescending()
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dates the end of the typed query could mean, to pick as a filter instead of matching as text. */
    val dateSuggestions: StateFlow<List<DateSuggestion>> =
        combine(_query, availableYears, localizedText) { q, years, text ->
            DateSuggestions.suggest(q, text.months, years)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Takes a suggested date: the words that spelled it leave the text and it becomes the date filter. */
    fun applyDateSuggestion(suggestion: DateSuggestion) {
        _query.value = SearchFilter.dropLastWords(_query.value, suggestion.consumedTokens)
        _contentFilter.value = _contentFilter.value.copy(
            year = suggestion.year, month = suggestion.month, day = null, dayEnd = null,
        )
    }

    /** The chips the category rail shows, in its order, so only a chip the page offers is selected. */
    private val railCategories: Flow<List<GalleryFilter>> = context.settingsDataStore.data
        .map { visibleCategories(it[SettingsKeys.CATEGORY_RAIL_ORDER], it[SettingsKeys.CATEGORY_RAIL_HIDDEN] ?: emptySet()) }
        .distinctUntilChanged()

    init {
        // A query that settles on a category's whole name selects that chip, exactly as a tap on the
        // rail would, and the words that spelled it leave the text. Only while no chip is picked, and
        // only for the text still in the field, so clearing the pill never brings it back.
        viewModelScope.launch {
            combine(_query.debounce(CATEGORY_SETTLE_MS), localizedText, railCategories) { q, text, rail ->
                val names = rail.mapNotNull { filter -> text.filterNames[filter]?.let { filter to it } }.toMap()
                q to CategorySuggestions.match(q, names, _selectedCategory.value)
            }.collect { (q, match) ->
                if (match == null || q != _query.value || _selectedCategory.value != GalleryFilter.All) return@collect
                _query.value = SearchFilter.dropLastWords(q, match.consumedTokens)
                onCategorySelected(match.filter)
            }
        }
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
        text: LocalizedText,
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
        foldedMonths = text.months,
        foldedCategoryNames = text.categoryNames,
    )

    /** Category chip → pre-folded name in [locale], so "screenshot" / "selfie" etc. match. The names
     *  are the rail's own labels, from a context localized the way the screen's own is. */
    private fun foldedFilterNames(locale: Locale): Map<GalleryFilter, String> {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        return GalleryFilter.entries
            .mapNotNull { filter -> categoryLabelRes(filter)?.let { filter to SearchFilter.fold(localized.getString(it)) } }
            .toMap()
    }

    private companion object {
        /** The semantic top-K a search folds in, matching the use case's own cap: more than the grid
         *  shows at once, cheap to rank and merge. */
        const val SEMANTIC_LIMIT = 200

        /** Settle delay before a content search embeds the query, the debounce the keyword feed uses plus
         *  a touch, since the CLIP embed is far dearer than the per-item filter. A superseded query cancels
         *  the wait through flatMapLatest, so only the query typing settles on is ever embedded. */
        const val SEMANTIC_DEBOUNCE_MS = 300L

        /** Settle delay before a typed category name selects its chip, long enough that the plural 's'
         *  typed right after "screenshot" lands first. */
        const val CATEGORY_SETTLE_MS = 500L
    }
}
