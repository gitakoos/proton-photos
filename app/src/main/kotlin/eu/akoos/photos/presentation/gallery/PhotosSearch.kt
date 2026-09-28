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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.akoos.photos.R
import eu.akoos.photos.presentation.search.SearchFilter
import eu.akoos.photos.presentation.theme.Accent
import eu.akoos.photos.presentation.theme.AppColors
import eu.akoos.photos.presentation.theme.FgDim
import eu.akoos.photos.presentation.theme.FgMute
import eu.akoos.photos.presentation.theme.FgPrimary
import eu.akoos.photos.presentation.theme.PillBg
import eu.akoos.photos.presentation.theme.PillBorder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Search terms ──────────────────────────────────────────────────────────────
//
// Search and filter are one thing on the Photos tab: a list of terms (typed text, a person, a time,
// a category, a storage state). The bar at the top of the timeline shows them, the search page adds
// and removes them, and the category chips under the bar are shortcuts for one kind of term. The
// timeline itself is the result.

/** One active term, as the search bar shows it: its label and how to take it off again. */
internal data class SearchTerm(val key: String, val label: String, val onRemove: () -> Unit)

/** Collapsed search entry: the same 38dp icon pill the Albums rail uses. */
@Composable
internal fun SearchIconButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(PillBg)
            .border(0.5.dp, PillBorder, RoundedCornerShape(19.dp))
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Search, contentDescription = label, tint = FgDim, modifier = Modifier.size(18.dp))
    }
}

/**
 * The Photos tab's search bar, shown only while terms are active: it lists them as chips,
 * each with its own ✕, plus a trailing ✕ that clears them all. A tap anywhere else opens the search
 * page, where terms are added.
 */
@Composable
internal fun SearchTermsBar(
    terms: List<SearchTerm>,
    placeholder: String,
    onOpen: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Accent.copy(alpha = 0.12f))
            .border(0.5.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(19.dp))
            .clickable(onClickLabel = placeholder) { onOpen() }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(terms, key = { it.key }) { term -> TermChip(term) }
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { onClearAll() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.filter_clear_all), tint = Accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** A term inside a search field: accent-tinted label with its own ✕. */
@Composable
private fun TermChip(term: SearchTerm) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Accent.copy(alpha = 0.22f))
            .clickable { term.onRemove() }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            term.label,
            color = Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Icon(Icons.Default.Close, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
    }
}

/** A tab's ⋮ menu: the settings pages for what the tab shows, reachable where they are needed as
 *  well as from Settings → Library. */
@Composable
internal fun TabOverflowMenu(entries: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.more_options), tint = FgDim, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = AppColors.current.sheetBg,
        ) {
            entries.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label, color = FgPrimary, fontSize = 14.sp) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

// ── Time labels ───────────────────────────────────────────────────────────────

/** Short label for a [DateRange] in the device locale's own order: "2024", "Sep 2026",
 *  "Jul – Sep 2026", "12 Mar 2024" or "12 Mar – 3 Apr 2024". */
@Composable
internal fun dateRangeLabel(range: DateRange): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(range, locale) { formatDateRange(range, locale) }
}

internal fun formatDateRange(range: DateRange, locale: Locale): String {
    fun fmt(date: LocalDate, skeleton: String): String = DateTimeFormatter
        .ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        .format(date)
    val start = range.start
    val end = range.endExclusive
    val wholeMonths = start.dayOfMonth == 1 && end.dayOfMonth == 1
    return when {
        start.dayOfYear == 1 && end == start.plusYears(1) -> start.year.toString()
        wholeMonths && end == start.plusMonths(1) -> fmt(start, "MMMyyyy")
        wholeMonths -> {
            val last = end.minusMonths(1)
            if (start.year == last.year) "${fmt(start, "MMM")} – ${fmt(last, "MMMyyyy")}"
            else "${fmt(start, "MMMyyyy")} – ${fmt(last, "MMMyyyy")}"
        }
        else -> {
            val last = end.minusDays(1)
            when {
                last == start -> fmt(start, "dMMMyyyy")
                start.year == last.year -> "${fmt(start, "dMMM")} – ${fmt(last, "dMMMyyyy")}"
                else -> "${fmt(start, "dMMMyyyy")} – ${fmt(last, "dMMMyyyy")}"
            }
        }
    }
}

// ── Search page ───────────────────────────────────────────────────────────────

/**
 * The Photos search page, opened from the search bar. Everything that narrows the timeline is here as
 * a suggestion that toggles a term: recent searches, people, times, categories and (signed in) where a
 * photo is stored. Typing narrows the suggestions to what matches and, at the same time, is itself a
 * term: the typed text matches names, dates, types and, with semantic search on, what is in a photo.
 * The timeline behind is the result; the bottom button closes the page onto it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotosSearchPage(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    terms: List<SearchTerm>,
    recentSearches: List<String>,
    people: List<PersonUi>,
    selectedPersonId: Long?,
    onPersonClick: (Long) -> Unit,
    dateRange: DateRange?,
    years: List<Int>,
    onDateRange: (DateRange?) -> Unit,
    categories: List<GalleryFilter>,
    selectedCategories: Set<GalleryFilter>,
    onCategoryClick: (GalleryFilter) -> Unit,
    showStorage: Boolean,
    syncStatus: SyncStatusFilter,
    onSyncClick: (SyncStatusFilter) -> Unit,
    resultCount: Int,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = AppColors.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler { onClose() }

    val needle = remember(query) { SearchFilter.fold(query.trim()) }
    val typing = needle.isNotEmpty()
    fun matches(label: String) = !typing || SearchFilter.fold(label).contains(needle)

    // ── suggestions, narrowed to the typed text ──────────────────────────────
    val today = remember { LocalDate.now() }
    val thisMonth = DateRange.month(today.year, today.monthValue)
    val lastMonth = today.minusMonths(1).let { DateRange.month(it.year, it.monthValue) }
    val last3 = DateRange.lastMonths(today, 3)
    val presetLabels = listOf(
        thisMonth to stringResource(R.string.search_time_this_month),
        lastMonth to stringResource(R.string.search_time_last_month),
        last3 to stringResource(R.string.search_time_last_3_months),
    )
    val monthNames = remember { SearchFilter.foldedMonthNames(Locale.getDefault()) }
    // Times only come from what is typed ("2023", "jun", "last month"): going to a time is the
    // timeline's job (the Years / Months / Days switcher), narrowing to one is a search.
    val timeSuggestions: List<Pair<DateRange, String>> = buildList {
        if (typing) {
            presetLabels.filter { (_, label) -> matches(label) }.forEach(::add)
            // Typed dates: a year ("2023"), a month name ("june", "jun") with or without a year.
            val words = needle.split(' ').filter { it.isNotBlank() }
            val typedYear = words.firstNotNullOfOrNull { w -> w.toIntOrNull()?.takeIf { it in 1900..2100 } }
            val typedMonth = words.firstNotNullOfOrNull { w ->
                if (w.length < 3) null else monthNames.indexOfFirst { it.startsWith(w) }.takeIf { it >= 0 }?.plus(1)
            }
            when {
                typedMonth != null -> {
                    // That month in the typed year, else in each year the library has photos in
                    // (the active year first), skipping months that have not happened yet.
                    val activeYear = dateRange?.takeIf { it == DateRange.year(it.start.year) }?.start?.year
                    val candidates = typedYear?.let { listOf(it) }
                        ?: (listOfNotNull(activeYear) + years).distinct()
                            .filter { y -> y < today.year || typedMonth <= today.monthValue }
                            .take(4)
                    candidates.forEach { y ->
                        add(DateRange.month(y, typedMonth).let { it to formatDateRange(it, Locale.getDefault()) })
                    }
                }
                typedYear != null -> add(DateRange.year(typedYear) to typedYear.toString())
            }
        }
        // The selected time always shows, selected, so it can be seen and taken off, including a
        // custom window none of the suggestions cover.
        if (dateRange != null && none { it.first == dateRange }) {
            add(0, dateRange to formatDateRange(dateRange, Locale.getDefault()))
        }
    }
    val peopleShown = people.filter { p -> p.displayName?.let { matches(it) } ?: !typing }
    val categoriesShown = categories.map { it to categoryLabel(it) }.filter { (_, label) -> matches(label) }
    val storageOptions = listOf(
        SyncStatusFilter.LocalOnly, SyncStatusFilter.BackedUp, SyncStatusFilter.CloudOnly, SyncStatusFilter.AvailableOffline,
    ).map { it to syncStatusLabel(it) }.filter { (_, label) -> matches(label) }
    val recentShown = if (typing) emptyList() else recentSearches

    Box(modifier = Modifier.fillMaxSize().background(colors.bg0)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // ── field: back, the active terms, then the text ──────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back), tint = FgPrimary)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.chipUnselectedBg)
                        .border(1.dp, if (terms.isNotEmpty()) Accent else colors.line, RoundedCornerShape(14.dp))
                        .clickable { focusRequester.requestFocus() }
                        .padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = FgDim, modifier = Modifier.size(20.dp))
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // The typed text is the field itself, so only the other terms show as chips.
                        terms.filter { it.key != SEARCH_TERM_TEXT }.forEach { TermChip(it) }
                        Box(modifier = Modifier.widthIn(min = 120.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(placeholder, color = FgMute, fontSize = 15.sp, maxLines = 1)
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                singleLine = true,
                                textStyle = TextStyle(color = FgPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(Accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { onClose() }),
                                modifier = Modifier.widthIn(min = 120.dp).focusRequester(focusRequester),
                            )
                        }
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onQueryChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.cd_clear_search), tint = FgDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (terms.isNotEmpty()) {
                Text(
                    text = "✕  ${stringResource(R.string.filter_reset)}",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 20.dp, bottom = 4.dp)
                        .clickable { onReset() },
                )
            }

            // ── suggestion sections ─────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (recentShown.isNotEmpty()) {
                    item(key = "recent") {
                        SearchSection(stringResource(R.string.search_section_recent)) {
                            recentShown.forEach { text ->
                                FilterChip(
                                    label = text,
                                    selected = false,
                                    onClick = { onQueryChange(text) },
                                    leadingIcon = { tint -> Icon(Icons.Default.History, null, tint = tint, modifier = Modifier.size(15.dp)) },
                                )
                            }
                        }
                    }
                }
                if (peopleShown.isNotEmpty()) {
                    item(key = "people") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(stringResource(R.string.gallery_category_people))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                peopleShown.forEach { person ->
                                    PersonTile(
                                        person = person,
                                        selected = person.personId == selectedPersonId,
                                        onClick = { onPersonClick(person.personId) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (timeSuggestions.isNotEmpty()) {
                    item(key = "times") {
                        SearchSection(stringResource(R.string.search_section_times)) {
                            timeSuggestions.forEach { (range, label) ->
                                FilterChip(
                                    label = label,
                                    selected = dateRange == range,
                                    accentWhenSelected = true,
                                    onClick = { onDateRange(if (dateRange == range) null else range) },
                                )
                            }
                        }
                    }
                }
                if (categoriesShown.isNotEmpty()) {
                    item(key = "categories") {
                        SearchSection(stringResource(R.string.gallery_filter_categories)) {
                            categoriesShown.forEach { (category, label) ->
                                FilterChip(
                                    label = label,
                                    selected = category in selectedCategories,
                                    accentWhenSelected = true,
                                    onClick = { onCategoryClick(category) },
                                    leadingIcon = { tint -> GalleryCategoryIcon(category, tint) },
                                )
                            }
                        }
                    }
                }
                if (showStorage && storageOptions.isNotEmpty()) {
                    item(key = "storage") {
                        SearchSection(stringResource(R.string.search_section_storage)) {
                            storageOptions.forEach { (status, label) ->
                                FilterChip(
                                    label = label,
                                    selected = syncStatus == status,
                                    accentWhenSelected = true,
                                    onClick = { onSyncClick(status) },
                                    leadingIcon = syncStatusIcon(status),
                                )
                            }
                        }
                    }
                }
            }

            // ── back to the timeline, which is the result ─────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Accent)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.filter_show_items, resultCount, formatCount(resultCount)),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Key of the typed-text term, which the page shows as the field's own text rather than a chip. */
internal const val SEARCH_TERM_TEXT = "text"

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = FgPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSection(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    }
}
