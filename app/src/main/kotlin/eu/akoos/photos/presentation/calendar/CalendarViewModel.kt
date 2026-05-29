@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package eu.akoos.photos.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.db.dao.DayMetaDao
import eu.akoos.photos.data.db.entity.DayMetaEntity
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Buckets [GalleryItem]s by ISO date and overlays user-authored [DayMetaEntity] so the
 * Calendar grid knows for each day:
 *  - the auto-picked or user-picked representative thumbnail,
 *  - the day's total photo + video count.
 *
 * The view model owns no editing surface — that lives in [DayDetailViewModel]. The two
 * share the underlying [DayMetaDao] so an edit in Day Detail is immediately visible
 * when the user returns to the Calendar.
 *
 * It also drives three transient UI affordances that don't persist past app restart:
 *  - [viewMode] toggle between the stacked-list and one-month-per-page pager,
 *  - [searchQuery] + [searchResults] for free-text day-meta search (location/description/
 *    date string),
 *  - last-known [primaryUserId] so the search query can hit the DAO directly off the UI
 *    thread without re-resolving the account on every keystroke.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    private val dayMetaDao: DayMetaDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    // Tracks the resolved primary userId so search queries don't have to re-await it.
    @Volatile private var primaryUserId: String? = null

    init {
        observe()
        observeSearch()
    }

    private fun observe() {
        viewModelScope.launch {
            accountManager.getPrimaryUserId()
                .flatMapLatest { userId ->
                    primaryUserId = userId?.id
                    if (userId == null) {
                        flowOf(emptyList<GalleryItem>() to emptyList<DayMetaEntity>())
                    } else {
                        combine(
                            getGalleryItems.invoke(userId),
                            dayMetaDao.observeAll(userId.id),
                        ) { items, metas -> items to metas }
                    }
                }
                .collect { (items, metas) ->
                    val months = buildMonths(items, metas)
                    _uiState.update { it.copy(
                        isLoading = false,
                        months = months,
                    ) }
                }
        }
    }

    /**
     * Re-runs the search whenever the query changes (debounced) OR when the underlying
     * data changes. We snapshot the matching DAY tiles by flattening all of [months]
     * into a single flat list and then keeping those whose day-meta text matches OR
     * whose ISO/locale-formatted date string matches.
     */
    private fun observeSearch() {
        viewModelScope.launch {
            // distinctUntilChanged on each input dodges a feedback loop: writing back to
            // _uiState (to set searchResults / isSearchLoading) would otherwise re-trigger
            // combine even though months and query haven't actually changed.
            combine(
                _uiState.map { it.months }.distinctUntilChanged(),
                _uiState.map { it.searchQuery }.distinctUntilChanged().debounce(180L),
            ) { months, query -> months to query }
                .collect { (months, query) ->
                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        _uiState.update { it.copy(searchResults = emptyList(), isSearchLoading = false) }
                        return@collect
                    }
                    _uiState.update { it.copy(isSearchLoading = true) }
                    val results = computeSearchResults(months, trimmed)
                    _uiState.update { it.copy(searchResults = results, isSearchLoading = false) }
                }
        }
    }

    fun setViewMode(mode: CalendarViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun toggleViewMode() {
        _uiState.update {
            val next = if (it.viewMode == CalendarViewMode.Stacked) {
                CalendarViewMode.PerMonth
            } else {
                CalendarViewMode.Stacked
            }
            it.copy(viewMode = next)
        }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update {
            if (active) it.copy(isSearchActive = true)
            // Closing the bar clears the query so the user lands back on the full month grid.
            else it.copy(isSearchActive = false, searchQuery = "", searchResults = emptyList())
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /** Jump-to-month picker visibility — toggled from the in-bar header chip. */
    fun setJumpPickerVisible(visible: Boolean) {
        _uiState.update { it.copy(isJumpPickerVisible = visible) }
    }

    /**
     * Computes the flat list of [DayBucket]s matching [query] across:
     *  - day-meta location text (case-insensitive contains),
     *  - day-meta description text (case-insensitive contains),
     *  - the day's ISO date (e.g. "2024-05" or "2024-05-03"),
     *  - the day's localized month + year string (e.g. "may 2024", "march 5").
     *
     * Multi-word queries are AND-matched against the same haystack so the user can type
     * "budapest 2024" and only see hits that match BOTH terms.
     */
    private suspend fun computeSearchResults(
        months: List<MonthBucket>,
        query: String,
    ): List<DayBucket> {
        val needle = query.lowercase(Locale.getDefault())
        val terms = needle.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        // Pull all DayMeta rows for cross-checking the location/description against the
        // user-authored fields stored in the DB. We bias the DAO call against the FIRST
        // term — Room narrows the row set cheaply by ISO LIKE, then we intersect the
        // remaining terms in-memory across the day-bucket-derived strings.
        val uid = primaryUserId
        val metaByDate: Map<String, DayMetaEntity> = if (uid != null) {
            runCatching {
                dayMetaDao.searchByText(uid, "%${terms.first()}%")
            }.getOrDefault(emptyList()).associateBy { it.date }
        } else {
            emptyMap()
        }

        val monthLabelFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val dayLabelFmt = SimpleDateFormat("MMMM d", Locale.getDefault())
        val cal = Calendar.getInstance()
        val results = mutableListOf<DayBucket>()

        for (month in months) {
            // Cheap per-month label that all of its day buckets can reuse.
            cal.set(month.year, month.month - 1, 1, 0, 0, 0)
            val monthLabel = monthLabelFmt.format(cal.time).lowercase(Locale.getDefault())

            for ((dayNum, dayBucket) in month.days) {
                cal.set(month.year, month.month - 1, dayNum, 0, 0, 0)
                val dayLabel = dayLabelFmt.format(cal.time).lowercase(Locale.getDefault())
                val meta = metaByDate[dayBucket.date]
                val haystack = buildString {
                    append(dayBucket.date.lowercase(Locale.getDefault()))
                    append(' ')
                    append(monthLabel)
                    append(' ')
                    append(dayLabel)
                    append(' ')
                    append(month.year.toString())
                    meta?.locationText?.let { append(' '); append(it.lowercase(Locale.getDefault())) }
                    dayBucket.locationText?.let { append(' '); append(it.lowercase(Locale.getDefault())) }
                    meta?.description?.let { append(' '); append(it.lowercase(Locale.getDefault())) }
                }
                if (terms.all { haystack.contains(it) }) {
                    results += dayBucket
                }
            }
        }
        // Newest-first so a "2024" query bubbles late-2024 dates above early-2024.
        return results.sortedByDescending { it.date }
    }

    private fun buildMonths(
        items: List<GalleryItem>,
        metas: List<DayMetaEntity>,
    ): List<MonthBucket> {
        if (items.isEmpty()) {
            // With no photos at all we still want SOMETHING to show — render the current
            // month so the user sees the empty grid rather than a blank page.
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            return listOf(MonthBucket(year, month, emptyMap()))
        }

        val metaByDate = metas.associateBy { it.date }
        val cal = Calendar.getInstance()

        // Group items by yyyy-MM-dd while ALSO tracking each (year, month) pair, so we can
        // render every month in the user's library — including months that fall between
        // two with photos but contain none themselves.
        val itemsByDate = mutableMapOf<String, MutableList<GalleryItem>>()
        var minMillis = Long.MAX_VALUE
        var maxMillis = Long.MIN_VALUE
        for (item in items) {
            val ms = item.captureTimeMs
            if (ms <= 0L) continue
            if (ms < minMillis) minMillis = ms
            if (ms > maxMillis) maxMillis = ms
            val date = ISO_DATE.get().format(Date(ms))
            itemsByDate.getOrPut(date) { mutableListOf() }.add(item)
        }

        if (minMillis == Long.MAX_VALUE) {
            val curYear = Calendar.getInstance().get(Calendar.YEAR)
            val curMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
            return listOf(MonthBucket(curYear, curMonth, emptyMap()))
        }

        // Walk every month between min and max — descending so the most recent shows up
        // at the TOP of the Calendar. The upper bound is the CURRENT month so the user's
        // most-recent-photo month is the first entry they see, not the December of that
        // year filled with empty grids. The lower bound rounds to January of the oldest
        // year so months without photos still appear in the listing in between (a year
        // with one photo in March still renders Jan/Feb/Apr…/Dec as empty grids). This
        // keeps the calendar continuous rather than a jagged "only months with content"
        // list.
        val months = mutableListOf<MonthBucket>()
        cal.timeInMillis = System.currentTimeMillis()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val minCal = Calendar.getInstance().apply {
            timeInMillis = minMillis
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Hard cap on months (~10 years) so a single misdated photo from 1970 doesn't
        // make us render 700 empty grids and OOM the screen.
        val MAX_MONTHS = 240
        var safety = 0
        while (cal.timeInMillis >= minCal.timeInMillis && safety++ < MAX_MONTHS) {
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            val days = mutableMapOf<Int, DayBucket>()
            for (day in 1..daysInMonth) {
                val date = formatDate(year, month, day)
                val dayItems = itemsByDate[date]
                if (dayItems.isNullOrEmpty()) continue
                val sorted = dayItems.sortedBy { it.captureTimeMs }
                val meta = metaByDate[date]
                val coverItem = resolveCover(sorted, meta?.coverPhotoUri)
                days[day] = DayBucket(
                    date = date,
                    items = sorted,
                    coverItem = coverItem,
                    locationText = meta?.locationText,
                )
            }
            months += MonthBucket(year = year, month = month, days = days)

            // Step one calendar month back.
            cal.add(Calendar.MONTH, -1)
        }

        return months
    }

    private fun resolveCover(
        dayItems: List<GalleryItem>,
        coverPhotoUri: String?,
    ): GalleryItem {
        if (coverPhotoUri.isNullOrBlank()) return dayItems.first()
        val match = dayItems.firstOrNull { item ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.uri == coverPhotoUri
                is GalleryItem.Synced    -> item.local.uri == coverPhotoUri || item.cloud.linkId == coverPhotoUri
                is GalleryItem.CloudOnly -> item.cloud.linkId == coverPhotoUri
            }
        }
        return match ?: dayItems.first()
    }

    companion object {
        // SimpleDateFormat isn't thread-safe but the calls here are confined to one
        // coroutine — stash it in a ThreadLocal to dodge the per-call allocation while
        // still being conservative about reuse if buildMonths ever gets parallelised.
        private val ISO_DATE = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
        }

        fun formatDate(year: Int, month: Int, day: Int): String =
            "%04d-%02d-%02d".format(year, month, day)
    }
}

/**
 * Calendar's two layout strategies. [Stacked] is the legacy LazyColumn of months;
 * [PerMonth] is a HorizontalPager where each page is one month full-width. State is
 * intentionally NOT persisted to DataStore — it resets per app launch by design.
 */
enum class CalendarViewMode { Stacked, PerMonth }

data class CalendarUiState(
    val isLoading: Boolean = true,
    val months: List<MonthBucket> = emptyList(),
    /** Current view-mode toggle. Stacked by default — matches what users had before. */
    val viewMode: CalendarViewMode = CalendarViewMode.Stacked,
    /** Whether the in-bar search input is visible. */
    val isSearchActive: Boolean = false,
    /** Live raw query — debounced before being matched against [months]. */
    val searchQuery: String = "",
    /** Most recent search hits — flat list of day tiles, newest-first. */
    val searchResults: List<DayBucket> = emptyList(),
    /** True while we're recomputing [searchResults] for a freshly changed query. */
    val isSearchLoading: Boolean = false,
    /** Whether the jump-to-month picker dropdown is currently shown. */
    val isJumpPickerVisible: Boolean = false,
)

data class MonthBucket(
    val year: Int,
    /** 1-12. */
    val month: Int,
    /** day-of-month → contents; days with no photos are absent from the map. */
    val days: Map<Int, DayBucket>,
)

data class DayBucket(
    /** ISO yyyy-MM-dd. */
    val date: String,
    val items: List<GalleryItem>,
    val coverItem: GalleryItem,
    val locationText: String?,
)
