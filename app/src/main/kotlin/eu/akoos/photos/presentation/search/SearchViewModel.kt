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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Normalizer
import me.proton.core.accountmanager.domain.AccountManager
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.R
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.domain.usecase.GetGalleryItemsUseCase
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import java.util.Calendar
import javax.inject.Inject

/** Backs the Search screen. Filters the merged gallery by displayName substring + ContentFilter. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getGalleryItems: GetGalleryItemsUseCase,
    private val accountManager: AccountManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Hidden vault filter — same DataStore key the gallery uses. Without this the
     *  search page surfaces hidden photos via name / date / content-filter matches,
     *  defeating the point of the Hidden vault. */
    private val hiddenUrisFlow = context.settingsDataStore.data.map {
        it[SettingsKeys.HIDDEN_PHOTO_URIS] ?: emptySet()
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
                _selectedCategory,
                hiddenUrisFlow,
            ) { all, q, filter, category, hidden ->
                applyAll(all.dropHidden(hidden), q, filter, category)
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

    private fun applyAll(
        items: List<GalleryItem>,
        q: String,
        filter: ContentFilter,
        category: GalleryFilter,
    ): List<GalleryItem> {
        val qTrimmed = q.trim()
        if (qTrimmed.isEmpty() && filter == ContentFilter() && category == GalleryFilter.All) {
            return emptyList()
        }
        var out = items
        if (qTrimmed.isNotEmpty()) {
            // Match every query word against the item's folded metadata haystack (name + date +
            // type + categories) so multi-word queries like "june 2024" or "beach video" work too.
            val needleWords = fold(qTrimmed).split(' ').filter { it.isNotBlank() }
            val cal = Calendar.getInstance()
            out = out.filter { item ->
                // A word matches if it is in the file name OR the full date/type/tag haystack.
                // Test the cheap folded name first and only build the heavier haystack when the
                // name misses — for plain name queries the haystack is never built. Cache it per
                // item so multi-word queries build it at most once.
                val foldedName = fold(displayNameOf(item))
                var haystack: String? = null
                needleWords.all { word ->
                    foldedName.contains(word) || run {
                        val full = haystack ?: searchHaystack(item, cal).also { haystack = it }
                        full.contains(word)
                    }
                }
            }
        }
        out = applyContentFilter(out, filter)
        val tagId = category.tagId
        if (category != GalleryFilter.All && tagId != null) {
            out = out.filter { CategorizeItem.belongsTo(it, tagId) }
        }
        return out
    }

    /** Lower-cases and strips diacritics so an ASCII query ("jose") matches accented names
     *  ("josé"). NFD decomposes each accented letter into base + combining mark, then the
     *  `\p{Mn}` (Mark, nonspacing) class removes the marks, leaving the bare letter. */
    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARK_REGEX, "")

    private fun displayNameOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.displayName
        is GalleryItem.Synced    -> item.local.displayName
        is GalleryItem.CloudOnly -> item.cloud.displayName
    }

    private fun applyContentFilter(items: List<GalleryItem>, filter: ContentFilter): List<GalleryItem> {
        var out = items
        out = when (filter.mediaType) {
            MediaType.All        -> out
            MediaType.PhotosOnly -> out.filter { !mimeOf(it).startsWith("video/") }
            MediaType.VideosOnly -> out.filter { mimeOf(it).startsWith("video/") }
        }
        out = when (filter.syncStatus) {
            SyncStatusFilter.All       -> out
            SyncStatusFilter.LocalOnly -> out.filter { it is GalleryItem.LocalOnly }
            SyncStatusFilter.BackedUp  -> out.filter { it is GalleryItem.Synced || it is GalleryItem.CloudOnly }
        }
        val year = filter.year
        if (year != null) {
            val month = filter.month
            val day = filter.day
            val cal = Calendar.getInstance()
            out = out.filter {
                cal.timeInMillis = it.captureTimeMs
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                y == year && (month == null || m == month) && (day == null || d == day)
            }
        }
        return out
    }

    private fun mimeOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }

    /** Localized month names, pre-folded once, so a "june" / "június" query matches by capture month. */
    private val foldedMonths: List<String> by lazy {
        val fmt = java.text.SimpleDateFormat("LLLL", java.util.Locale.getDefault())
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        (0..11).map { m -> cal.set(Calendar.MONTH, m); fold(fmt.format(cal.time)) }
    }

    /** PhotoTag id → pre-folded localized category name, so "screenshot" / "selfie" etc. match. */
    private val foldedCategoryNames: Map<Int, String> by lazy {
        mapOf(
            0 to R.string.gallery_filter_favorites,
            1 to R.string.gallery_filter_screenshots,
            2 to R.string.filter_type_videos,
            3 to R.string.gallery_filter_live_photos,
            5 to R.string.gallery_filter_selfies,
            6 to R.string.gallery_filter_portraits,
            7 to R.string.gallery_filter_bursts,
            8 to R.string.gallery_filter_panoramas,
            9 to R.string.gallery_filter_raw,
        ).mapValues { fold(context.getString(it.value)) }
    }

    /** Folded text a typed query matches against — file name + capture year + month name + media
     *  type + file extension + category names — so the search box finds photos by metadata, not just
     *  the file name. Month/category names are pre-folded; only the per-item name is folded here. */
    private fun searchHaystack(item: GalleryItem, cal: Calendar): String {
        cal.timeInMillis = item.captureTimeMs
        val mime = mimeOf(item)
        val ext = mime.substringAfterLast('/', "")
        val tags = when (item) {
            is GalleryItem.Synced    -> item.cloud.tags
            is GalleryItem.CloudOnly -> item.cloud.tags
            is GalleryItem.LocalOnly -> emptySet()
        }
        return buildString {
            append(fold(displayNameOf(item)))
            append(' ').append(cal.get(Calendar.YEAR))
            append(' ').append(foldedMonths[cal.get(Calendar.MONTH)])
            append(if (mime.startsWith("video/")) " video" else " photo")
            if (ext.isNotEmpty()) { append(' '); append(ext) }
            tags.forEach { id -> foldedCategoryNames[id]?.let { append(' '); append(it) } }
        }
    }

    private companion object {
        /** Combining (nonspacing) marks left behind by NFD decomposition. */
        val MARK_REGEX = Regex("\\p{Mn}+")
    }
}
