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
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.hidden.HiddenStorageManager
import eu.akoos.photos.data.offline.OfflineStorageManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.data.transfer.TransferCenter
import eu.akoos.photos.R
import eu.akoos.photos.data.db.dao.AlbumPhotoMembershipDao
import eu.akoos.photos.data.db.dao.CloudAlbumDao
import eu.akoos.photos.data.db.dao.FaceDao
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.label.PhotoLabelRepository
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
import eu.akoos.photos.presentation.albums.AlbumBrowseSource
import eu.akoos.photos.presentation.albums.DeviceFolderCards
import eu.akoos.photos.presentation.albums.LibraryTile
import eu.akoos.photos.presentation.albums.deviceBucketOf
import eu.akoos.photos.presentation.albums.resolveTileOrder
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
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * An album or device folder a search is narrowed to, shown as a pill in the field. [key] is `c:<linkId>`
 * for one of the user's cloud albums or `f:<bucket>` for a device folder.
 */
data class SearchScope(val key: String, val name: String) {
    val cloudLinkId: String? get() = key.takeIf { it.startsWith("c:") }?.drop(2)
    val folder: String? get() = key.takeIf { it.startsWith("f:") }?.drop(2)
}

/** What an active [SearchScope] lets through: a cloud album's member photos, or a device folder's. */
private class ScopeFilter(private val linkIds: Set<String>?, private val folder: String?) {
    fun accepts(item: GalleryItem): Boolean {
        if (linkIds != null) {
            val linkId = when (item) {
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.Synced -> item.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
            return linkId != null && linkId in linkIds
        }
        return deviceBucketOf(item) == folder
    }
}

/**
 * One step of the content search: whether it is running, and the ranked keys when it has them. [keys] is
 * null while a new query is still being embedded, which leaves the previous query's ranking in place.
 */
internal data class RankStep(val loading: Boolean, val keys: List<String>?)

/** The (still-running, ranked keys) pair after [step]: its keys when it has them, otherwise those held. */
internal fun Pair<Boolean, List<String>>.after(step: RankStep): Pair<Boolean, List<String>> =
    step.loading to (step.keys ?: second)

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
    private val albumBrowseSource: AlbumBrowseSource,
    private val albumMembershipDao: AlbumPhotoMembershipDao,
    private val labelRepo: PhotoLabelRepository,
    private val placeIndex: PhotoPlaceIndex,
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

    private val _scope = MutableStateFlow<SearchScope?>(null)

    /** The album or device folder the results are narrowed to, or null for the whole library. */
    val scope: StateFlow<SearchScope?> = _scope.asStateFlow()

    fun setScope(value: SearchScope?) { _scope.value = value }

    /** What the active scope lets through, following the album's membership as it changes. */
    private val scopeFilter: Flow<ScopeFilter?> = _scope.flatMapLatest { scope ->
        when {
            scope == null -> flowOf(null)
            scope.cloudLinkId != null ->
                albumMembershipDao.observeAssociatedPhotoLinkIdsForAlbums(setOf(scope.cloudLinkId!!))
                    .map<List<String>, ScopeFilter?> { ScopeFilter(it.toSet(), null) }
                    .catch { emit(ScopeFilter(emptySet(), null)) }
            else -> flowOf(ScopeFilter(null, scope.folder))
        }
    }

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
    private val debouncedQuery = _query.debounce(QUERY_SETTLE_MS)

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
        val tileNames: Map<LibraryTile, String>,
        /** The phrases that name a day, week, month or year relative to today, in the app's language and in
         *  English, each through [DateSuggestions.phraseKey]. */
        val relativePhrases: Map<RelativeDate, Set<String>>,
        /** The phrasings of a counted date ("2 weeks ago"), in the app's language and in English. */
        val countedPatterns: List<CountedPattern>,
        /** The usual short forms of the month names ("jan", "febr"). */
        val monthAbbreviations: List<String>,
        /** The month names and their short forms in every language the app is offered in, for a typed month
         *  of another one. */
        val otherMonths: List<List<String>>,
    ) {
        val categoryNames: Map<Int, String> =
            filterNames.entries.mapNotNull { (filter, name) -> filter.tagId?.let { it to name } }.toMap()
    }

    private val localizedText: Flow<LocalizedText> = _locale
        .map {
            LocalizedText(
                SearchFilter.foldedMonthNames(it),
                foldedFilterNames(it),
                foldedTileNames(it),
                relativePhrases(it),
                countedPatterns(it),
                SearchFilter.foldedMonthAbbreviations(it),
                DateSuggestions.MONTH_LANGUAGES.flatMap { tag ->
                    val language = Locale.forLanguageTag(tag)
                    listOf(SearchFilter.foldedMonthNames(language), SearchFilter.foldedMonthAbbreviations(language))
                },
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Every labelled photo's labels for the current account (a guest's own when signed out), folded
     *  once per change so the per-item match only appends them. A vaulted photo's labels are dropped
     *  when it goes into the vault, and a hidden one never reaches the match, so neither is found. */
    private val foldedLabels: Flow<Map<String, String>> = labelRepo.observeLabelsByPhoto()
        .map { SearchFilter.foldLabels(it) }
        .distinctUntilChanged()
        .catch { emit(emptyMap()) }
        .flowOn(Dispatchers.Default)

    /** Every located photo's city and country, folded, by its location key. Empty until the index has
     *  geocoded the library's spots, so a first search never waits on it and the place matches join in
     *  when it lands. */
    private val foldedPlaces: Flow<Map<String, String>> = _locale
        .flatMapLatest { placeIndex.observe(it) }
        .catch { emit(emptyMap()) }
        .onStart { emit(emptyMap()) }
        .flowOn(Dispatchers.Default)

    /** The typed side of the keyword match: the settled query, and the localized names, folded labels
     *  and folded places it is matched against, travelling as one value to keep the combine within its
     *  arity. */
    private data class TypedInput(
        val q: String,
        val text: LocalizedText,
        val labels: Map<String, String>,
        val places: Map<String, String>,
    )

    /** The keyword + metadata matches, the search path that has always been here: the merged library
     *  narrowed by the typed query, the content filter, the category chip and the People rail. Folded
     *  into [results] first and unchanged, so a semantic-off session sees exactly this. */
    private val keywordResults: Flow<List<GalleryItem>> = accountManager.getPrimaryUserId()
        .flatMapLatest { userId ->
            val libraryFlow = if (userId == null) getGalleryItems.invokeLocalOnly()
                else getGalleryItems.invoke(userId)
            combine(
                libraryFlow,
                combine(debouncedQuery, localizedText, foldedLabels, foldedPlaces) { q, text, labels, places ->
                    TypedInput(q, text, labels, places)
                },
                _contentFilter,
                // Category, the two local id sets the chips read, and the selected person's photo keys
                // travel together so the later sources stay within the typed combine arity; the Offline
                // chip filters on the pinned linkIds, the Favourites chip on the device hearts, and the
                // People rail on the person keys, all in applyAll.
                chipState,
                combine(hiddenUrisFlow, _scope) { hidden, scope -> hidden to (scope != null) },
            ) { all, typed, filter, chip, (hidden, scoped) ->
                applyAll(
                    all.dropHidden(hidden), typed.q, typed.text, filter,
                    chip.category, chip.pins, chip.hearts, chip.personKeys, typed.labels, typed.places, scoped,
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

    /** The content-search feed: whether a search is still running for the current query, and the
     *  resolved matches so far. Held as one value so the loading flag and the matches never disagree. */
    private data class SemanticFeed(val loading: Boolean, val items: List<GalleryItem>)

    /**
     * The image-content search as (still-running, ranked photo keys). Gated on the AI + semantic flags
     * (never runs, empty when off) and guest-safe (a null account keys the local partition).
     *
     * A fresh query of [SEMANTIC_MIN_CHARS] or more emits loading true at once, so the screen's placeholder
     * shows through the settle delay AND the model run rather than only after; the ranking of the query
     * before it stays on screen until this one's arrives, so the content matches are replaced in one step
     * rather than vanishing and coming back. Then it waits [QUERY_SETTLE_MS], the same settle the keyword
     * feed uses, for typing to settle (flatMapLatest cancels a superseded query mid-wait, exactly as a
     * debounce would), embeds and ranks off the main thread, and emits the ranked keys with loading false.
     * A shorter or blank query, or switching the search off, clears the ranking. Keys, not items, cross
     * this stage so [semanticFeed] can re-resolve them against the library on a sync change without
     * re-running the model.
     */
    private val rankedKeys: Flow<Pair<Boolean, List<String>>> = combine(
        semanticEnabled,
        accountManager.getPrimaryUserId(),
    ) { on, userId -> on to userId }
        .flatMapLatest { (on, userId) ->
            if (!on) flowOf(RankStep(false, emptyList()))
            else _query.map { it.trim() }.distinctUntilChanged().flatMapLatest { q ->
                if (q.length < SEMANTIC_MIN_CHARS) flowOf(RankStep(false, emptyList()))
                else flow {
                    emit(RankStep(true, null))
                    delay(QUERY_SETTLE_MS)
                    emit(RankStep(false, semanticSearchUseCase.searchKeys(q, userId, SEMANTIC_LIMIT)))
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .scan(false to emptyList<String>()) { held, step -> held.after(step) }

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
    val results: StateFlow<List<GalleryItem>> = combine(keywordResults, semanticFeed, scopeFilter) { keyword, feed, scoped ->
        val semantic = feed.items
        val merged = if (semantic.isEmpty()) {
            keyword
        } else {
            val seen = HashSet<String>(keyword.size + semantic.size)
            for (item in keyword) seen.add(item.stableId)
            val tail = semantic.filter { seen.add(it.stableId) }
            if (tail.isEmpty()) keyword else keyword + tail
        }
        if (scoped == null) merged else merged.filter(scoped::accepts)
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
            else combine(observePeopleUseCase(userId, allItems, namedOnly = true), debouncedQuery) { people, q ->
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
            else observePeopleUseCase(userId, allItems, namedOnly = true).map { summaries ->
                summaries.filter { !it.displayName.isNullOrBlank() }.mapNotNull { it.toPersonUi() }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What both album surfaces are drawn from, read once and shared between them. */
    private val albumSource: StateFlow<SearchAlbums.Source> = albumBrowseSource.source
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

    /** What decides which shortcut tiles a name can reach: the Albums tab's own order and hidden set,
     *  and whether face grouping is on, the gate the People tile has there. */
    private data class TileSettings(val order: String?, val hidden: Set<String>, val faceOn: Boolean)

    private val tileSettings: Flow<TileSettings> = context.settingsDataStore.data
        .map {
            TileSettings(
                order = it[SettingsKeys.ALBUMS_TILE_ORDER],
                hidden = it[SettingsKeys.ALBUMS_TILE_HIDDEN] ?: emptySet(),
                faceOn = it[SettingsKeys.FACE_ENABLED] ?: false,
            )
        }
        .distinctUntilChanged()

    /** The shortcut tiles the typed query names, offered under the same rules the Albums tab shows them
     *  by: its order, a tile the user hid stays out, People needs face grouping, and Shared and Offline
     *  need an account. Empty while the query is blank. */
    val destinations: StateFlow<List<LibraryTile>> =
        combine(debouncedQuery, localizedText, tileSettings, isSignedIn) { q, text, settings, signedIn ->
            val offered = resolveTileOrder(settings.order).filter { tile ->
                tile.name !in settings.hidden && when (tile) {
                    LibraryTile.People -> settings.faceOn
                    LibraryTile.Shared, LibraryTile.Offline -> signedIn
                    else -> true
                }
            }
            SearchDestinations.match(q, offered.mapNotNull { tile -> text.tileNames[tile]?.let { tile to it } })
        }
            .distinctUntilChanged()
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
        _scope.value = null
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
            DateSuggestions.suggest(q, text.months, years, text.monthAbbreviations)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Takes a suggested date: the words that spelled it leave the text and it becomes the date filter. */
    fun applyDateSuggestion(suggestion: DateSuggestion) {
        _query.value = SearchFilter.dropLastWords(_query.value, suggestion.consumedTokens)
        _contentFilter.value = _contentFilter.value.copy(
            year = suggestion.year, month = suggestion.month, day = null, dayEnd = null, dateFrom = null, dateTo = null,
        )
    }

    /** The chips the category rail shows, in its order, so only a chip the page offers is selected. */
    private val railCategories: Flow<List<GalleryFilter>> = context.settingsDataStore.data
        .map { visibleCategories(it[SettingsKeys.CATEGORY_RAIL_ORDER], it[SettingsKeys.CATEGORY_RAIL_HIDDEN] ?: emptySet()) }
        .distinctUntilChanged()

    init {
        // A month name or a whole year at the end of the settled query becomes the date filter, shown as a
        // pill, and the words leave the text; a month and a year typed one after the other join into one
        // date. A relative or counted phrase ("last week", "2 weeks ago") does the same, the longer of the
        // two winning. Only for the text still in the field.
        viewModelScope.launch {
            combine(_query, localizedText) { q, text ->
                val today = Calendar.getInstance(_locale.value)
                val relative = DateSuggestions.relative(q, text.relativePhrases)
                    ?.let { (date, words) -> DateSuggestions.resolveRelative(date, today, words) }
                val counted = DateSuggestions.counted(q, text.countedPatterns)
                    ?.let { (unit, number, words) -> DateSuggestions.resolveCounted(unit, number, today, words) }
                val phrase = listOfNotNull(relative, counted).maxByOrNull { it.consumedTokens }
                q to (phrase ?: DateSuggestions.term(q, text.months, text.monthAbbreviations, text.otherMonths))
            }
                .debounce { (_, term) ->
                    when {
                        term == null -> 0L
                        term.complete -> DATE_SETTLE_MS
                        else -> DATE_PARTIAL_SETTLE_MS
                    }
                }
                .collect { (q, term) ->
                    if (term == null || q != _query.value) return@collect
                    val current = _contentFilter.value
                    _query.value = SearchFilter.dropLastWords(q, term.consumedTokens)
                    _contentFilter.value = term.applyTo(current)
                }
        }
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
        foldedLabels: Map<String, String> = emptyMap(),
        foldedPlaces: Map<String, String> = emptyMap(),
        scoped: Boolean = false,
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
        foldedLabels = foldedLabels,
        foldedPlaces = foldedPlaces,
        scoped = scoped,
    )

    private fun localizedContext(locale: Locale): Context {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }

    /** The phrases for today, yesterday, this and last week, month and year, in [locale] and in
     *  English, from the `search_rel_*` strings, whose variants are separated by a bar. */
    private fun relativePhrases(locale: Locale): Map<RelativeDate, Set<String>> {
        val contexts = listOf(localizedContext(locale), localizedContext(Locale.ENGLISH))
        return RelativeDate.entries.associateWith { date ->
            contexts.flatMap { it.getString(relativePhraseRes(date)).split('|') }
                .map { DateSuggestions.phraseKey(it) }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }

    /** The counted phrasings in [locale] and in English, from the `search_ago_*` strings, whose variants are
     *  separated by a bar and whose `#` stands for the number. */
    private fun countedPatterns(locale: Locale): List<CountedPattern> {
        val contexts = listOf(localizedContext(locale), localizedContext(Locale.ENGLISH))
        return DateSuggestions.countedPatterns(
            RelativeUnit.entries.associateWith { unit ->
                contexts.flatMap { it.getString(countedPhraseRes(unit)).split('|') }
                    .map { DateSuggestions.phraseKey(it) }
                    .filter { it.isNotEmpty() }
                    .toSet()
            },
        )
    }

    private fun countedPhraseRes(unit: RelativeUnit): Int = when (unit) {
        RelativeUnit.Day -> R.string.search_ago_days
        RelativeUnit.Week -> R.string.search_ago_weeks
        RelativeUnit.Month -> R.string.search_ago_months
        RelativeUnit.Year -> R.string.search_ago_years
    }

    private fun relativePhraseRes(date: RelativeDate): Int = when (date) {
        RelativeDate.Today -> R.string.search_rel_today
        RelativeDate.Yesterday -> R.string.search_rel_yesterday
        RelativeDate.ThisWeek -> R.string.search_rel_this_week
        RelativeDate.LastWeek -> R.string.search_rel_last_week
        RelativeDate.ThisMonth -> R.string.search_rel_this_month
        RelativeDate.LastMonth -> R.string.search_rel_last_month
        RelativeDate.ThisYear -> R.string.search_rel_this_year
        RelativeDate.LastYear -> R.string.search_rel_last_year
    }

    /** Library tile → pre-folded label in [locale], the Albums tab's own labels, so "shared" or "map"
     *  names the tile the way it is drawn. */
    private fun foldedTileNames(locale: Locale): Map<LibraryTile, String> {
        val localized = localizedContext(locale)
        return LibraryTile.entries.associateWith { SearchFilter.fold(localized.getString(it.label)) }
    }

    /** Category chip → pre-folded name in [locale], so "screenshot" / "selfie" etc. match. The names
     *  are the rail's own labels, from a context localized the way the screen's own is. */
    private fun foldedFilterNames(locale: Locale): Map<GalleryFilter, String> {
        val localized = localizedContext(locale)
        return GalleryFilter.entries
            .mapNotNull { filter -> categoryLabelRes(filter)?.let { filter to SearchFilter.fold(localized.getString(it)) } }
            .toMap()
    }

    private companion object {
        /** The semantic top-K a search folds in, matching the use case's own cap: more than the grid
         *  shows at once, cheap to rank and merge. */
        const val SEMANTIC_LIMIT = 200

        /** How long typing must pause before the keyword feed filters and a content search embeds the query,
         *  one value for both so their results land together. A superseded query cancels the wait through
         *  flatMapLatest, so only the query typing settles on is ever embedded. */
        const val QUERY_SETTLE_MS = 300L

        /** The shortest query a content search embeds: one letter ranks noise. */
        const val SEMANTIC_MIN_CHARS = 2

        /** Settle delay before a typed whole month name or year becomes the date pill. It rewrites what was
         *  typed, so it waits well past the pause between two keystrokes, where the results only wait
         *  [QUERY_SETTLE_MS]. */
        const val DATE_SETTLE_MS = 700L

        /** The same for a month that is only the start of its name ("jan"), which may be going to become
         *  another word, so it waits longer. */
        const val DATE_PARTIAL_SETTLE_MS = 1_000L

        /** Settle delay before a typed category name selects its chip, long enough that the plural 's'
         *  typed right after "screenshot" lands first. */
        const val CATEGORY_SETTLE_MS = 700L
    }
}
