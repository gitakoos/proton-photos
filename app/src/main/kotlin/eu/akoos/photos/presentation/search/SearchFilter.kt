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

package eu.akoos.photos.presentation.search

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import eu.akoos.photos.presentation.gallery.isItemFavorite
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * What the Search screen actually narrows the library down to: list in, list out, no Android and no
 * coroutine scope. [SearchViewModel] supplies the localized month and category names (the only part
 * that needs a resources handle) and does nothing else to the result.
 */
internal object SearchFilter {

    /** Combining (nonspacing) marks left behind by NFD decomposition. */
    private val MARK_REGEX = Regex("\\p{Mn}+")

    /** Lower-cases and strips diacritics so an ASCII query ("jose") matches accented names
     *  ("josé"). NFD decomposes each accented letter into base + combining mark, then the
     *  `\p{Mn}` (Mark, nonspacing) class removes the marks, leaving the bare letter. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARK_REGEX, "")

    /** The twelve month names of [locale], pre-folded once, so a "june" / "június" query matches by
     *  capture month. */
    fun foldedMonthNames(locale: Locale): List<String> {
        val fmt = java.text.SimpleDateFormat("LLLL", locale)
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        return (0..11).map { m -> cal.set(Calendar.MONTH, m); fold(fmt.format(cal.time)) }
    }

    fun displayNameOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.displayName
        is GalleryItem.Synced    -> item.local.displayName
        is GalleryItem.CloudOnly -> item.cloud.displayName
    }

    fun mimeOf(item: GalleryItem): String = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced    -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }

    /**
     * The whole narrowing, in the order the screen applies it: query words, then the content filter,
     * then the category chip. [foldedMonths] and [foldedCategoryNames] are pre-folded localized text
     * the query is matched against; [offlinePinIds] backs the Offline chip, which is a local pin set
     * rather than a server tag, and [favoriteIds] backs the Favourites chip for the photos whose
     * heart lives on the device rather than in Drive.
     */
    fun apply(
        items: List<GalleryItem>,
        q: String,
        filter: ContentFilter,
        category: GalleryFilter,
        offlinePinIds: Set<String> = emptySet(),
        favoriteIds: Set<String> = emptySet(),
        foldedMonths: List<String>,
        foldedCategoryNames: Map<Int, String>,
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
                        val full = haystack
                            ?: searchHaystack(item, cal, foldedMonths, foldedCategoryNames)
                                .also { haystack = it }
                        full.contains(word)
                    }
                }
            }
        }
        out = applyContentFilter(out, filter)
        if (category != GalleryFilter.All) {
            out = when (category) {
                // Live Photos (tag 3) and Motion Photos (tag 4) are the same concept on iOS/Android
                // and share one chip, so either tag matches both — mirror the gallery filter.
                GalleryFilter.LivePhotos, GalleryFilter.MotionPhotos ->
                    out.filter { CategorizeItem.belongsTo(it, 3) || CategorizeItem.belongsTo(it, 4) }
                // Offline = the cloud photos pinned for offline; not a server tag, so it filters on
                // the pinned linkId set rather than CategorizeItem. Mirrors the gallery filter.
                GalleryFilter.Offline ->
                    out.filter { (it as? GalleryItem.CloudOnly)?.cloud?.linkId?.let { id -> id in offlinePinIds } == true }
                // Favourite = the device-side set for a photo that lives only on the device, Drive
                // PhotoTag 0 for a backed-up one. Through the same helper the timeline, the grid
                // hearts and the viewer use, so the one chip cannot mean two things on two screens.
                GalleryFilter.Favorites -> out.filter { isItemFavorite(it, favoriteIds) }
                else -> category.tagId?.let { id -> out.filter { CategorizeItem.belongsTo(it, id) } } ?: out
            }
        }
        return out
    }

    fun applyContentFilter(items: List<GalleryItem>, filter: ContentFilter): List<GalleryItem> {
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
        // Every date part that is set narrows on its own, which is what the timeline's own filter
        // does. Hanging the whole branch off the year made a month or a day without one inert here
        // while it still narrowed there, and inert is worse than unreachable: the empty-state check
        // above counts any non-default filter as "something was asked for", so a month alone
        // answered with the entire library instead of with nothing.
        val year = filter.year
        val month = filter.month
        val day = filter.day
        if (year != null || month != null || day != null) {
            val cal = Calendar.getInstance()
            out = out.filter {
                cal.timeInMillis = it.captureTimeMs
                (year == null || cal.get(Calendar.YEAR) == year) &&
                    (month == null || cal.get(Calendar.MONTH) + 1 == month) &&
                    (day == null || cal.get(Calendar.DAY_OF_MONTH) == day)
            }
        }
        return out
    }

    /** Folded text a typed query matches against — file name + capture year + month name + media
     *  type + file extension + category names — so the search box finds photos by metadata, not just
     *  the file name. Month/category names arrive pre-folded; only the per-item name is folded here. */
    fun searchHaystack(
        item: GalleryItem,
        cal: Calendar,
        foldedMonths: List<String>,
        foldedCategoryNames: Map<Int, String>,
    ): String {
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
}
