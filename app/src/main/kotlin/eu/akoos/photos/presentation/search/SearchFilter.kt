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

import eu.akoos.photos.data.label.LabelKeyReconciler
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.usecase.CategorizeItem
import eu.akoos.photos.presentation.gallery.ContentFilter
import eu.akoos.photos.presentation.gallery.GalleryFilter
import eu.akoos.photos.presentation.gallery.MediaType
import eu.akoos.photos.presentation.gallery.SyncStatusFilter
import eu.akoos.photos.presentation.gallery.isItemFavorite
import eu.akoos.photos.presentation.gallery.isItemOfflinePinned
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
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
    private val SPACES = Regex("\\s+")

    private const val TIER_NAME = 0
    private const val TIER_DESCRIBED = 1
    private const val TIER_METADATA = 2
    private const val NO_MATCH = 3

    /** Lower-cases and strips diacritics so an ASCII query ("jose") matches accented names
     *  ("josé"). NFD decomposes each accented letter into base + combining mark, then the
     *  `\p{Mn}` (Mark, nonspacing) class removes the marks, leaving the bare letter. The Turkish
     *  dotless "ı" has no decomposition, so it is mapped to "i" by hand ("kizilay" finds "Kızılay"). */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARK_REGEX, "")
            .replace('\u0131', 'i')

    /** [q] without its last [count] words, the rest joined by single spaces: what a picked suggestion
     *  leaves of the text that spelled it. */
    fun dropLastWords(q: String, count: Int): String =
        q.trim().split(SPACES).filter { it.isNotEmpty() }.dropLast(count).joinToString(" ")

    /** The twelve month names of [locale], pre-folded once, so a "june" / "június" query matches by
     *  capture month. */
    fun foldedMonthNames(locale: Locale): List<String> {
        val fmt = java.text.SimpleDateFormat("LLLL", locale)
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        return (0..11).map { m -> cal.set(Calendar.MONTH, m); fold(fmt.format(cal.time)) }
    }

    /** The usual short forms of the twelve month names of [locale] ("Jan", "febr."), folded and without a
     *  closing dot, so a typed "febr" is the same word as the abbreviation. */
    fun foldedMonthAbbreviations(locale: Locale): List<String> {
        val fmt = java.text.SimpleDateFormat("LLL", locale)
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        return (0..11).map { m -> cal.set(Calendar.MONTH, m); fold(fmt.format(cal.time)).trimEnd('.') }
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
     * then the category chip. The query's matches lead with the photos whose name holds every word, then
     * those whose name, labels and place together do, then the ones only a date, type or category reaches. [foldedMonths] and [foldedCategoryNames] are pre-folded localized text
     * the query is matched against; [offlinePinIds] backs the Offline chip, which is a local pin set
     * rather than a server tag, and [favoriteIds] backs the Favourites chip for the photos whose
     * heart lives on the device rather than in Drive. [foldedLabels] holds each labelled photo's
     * labels, pre-folded by [foldLabels], so a typed word finds a photo by its label too, and
     * [foldedPlaces] its city and country by the key of its stored location ([placesOf]).
     */
    fun apply(
        items: List<GalleryItem>,
        q: String,
        filter: ContentFilter,
        category: GalleryFilter,
        offlinePinIds: Set<String> = emptySet(),
        favoriteIds: Set<String> = emptySet(),
        personPhotoKeys: Set<String>? = null,
        foldedMonths: List<String>,
        foldedCategoryNames: Map<Int, String>,
        foldedLabels: Map<String, String> = emptyMap(),
        foldedPlaces: Map<String, String> = emptyMap(),
        scoped: Boolean = false,
    ): List<GalleryItem> {
        val qTrimmed = q.trim()
        // Commas separate alternatives ("beach, sunset" finds a photo that matches either), and the words
        // of one alternative must all match ("beach june"). Commas and spaces alone are no query.
        val alternatives = fold(qTrimmed).split(',')
            .map { part -> part.split(' ').filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
        if (nothingAsked(if (alternatives.isEmpty()) "" else qTrimmed, filter, category, personSelected = personPhotoKeys != null, scoped = scoped)) {
            return emptyList()
        }
        var out = items
        if (alternatives.isNotEmpty()) {
            // Match every word of an alternative against the item's folded metadata haystack (name + date +
            // type + categories) so multi-word queries like "june 2024" or "beach video" work too.
            val cal = Calendar.getInstance()
            // Photos that name the query come ahead of the ones only its date, type or category
            // reaches, each group keeping the order the library gave. A photo ranks by the best
            // alternative it matches.
            val byName = ArrayList<GalleryItem>()
            val byLabel = ArrayList<GalleryItem>()
            val byMetadata = ArrayList<GalleryItem>()
            for (item in out) {
                // A word matches if it is in the file name OR the full date/type/tag haystack.
                // Test the cheap folded name first and only build the heavier haystack when the
                // name misses: for plain name queries the haystack is never built. Cache it per
                // item so several words and alternatives build it at most once.
                val foldedName = fold(displayNameOf(item))
                var haystack: String? = null
                var described: String? = null
                var best = NO_MATCH
                for (words in alternatives) {
                    val tier = if (words.all { foldedName.contains(it) }) {
                        TIER_NAME
                    } else {
                        val full = haystack
                            ?: searchHaystack(item, cal, foldedMonths, foldedCategoryNames, foldedLabels, foldedPlaces)
                                .also { haystack = it }
                        if (!words.all { foldedName.contains(it) || full.contains(it) }) {
                            NO_MATCH
                        } else {
                            // What the user attached or the photo's place says, as opposed to a date or a type.
                            val text = described
                                ?: listOfNotNull(labelsOf(item, foldedLabels), placesOf(item, foldedPlaces))
                                    .joinToString(" ").also { described = it }
                            if (text.isNotEmpty() && words.all { foldedName.contains(it) || text.contains(it) }) {
                                TIER_DESCRIBED
                            } else {
                                TIER_METADATA
                            }
                        }
                    }
                    if (tier < best) best = tier
                    if (best == TIER_NAME) break
                }
                when (best) {
                    TIER_NAME -> byName += item
                    TIER_DESCRIBED -> byLabel += item
                    TIER_METADATA -> byMetadata += item
                }
            }
            out = byName + byLabel + byMetadata
        }
        return applyFacets(out, filter, category, offlinePinIds, favoriteIds, personPhotoKeys)
    }

    /** True when the page asks for nothing: no query, no filter, no category chip and no person. A
     *  selected person is itself a filter, so it narrows an otherwise-untouched page to their photos
     *  rather than leaving it on the idle page, and so is an album or folder the search is narrowed to. */
    fun nothingAsked(
        q: String,
        filter: ContentFilter,
        category: GalleryFilter,
        personSelected: Boolean,
        scoped: Boolean = false,
    ): Boolean =
        q.isBlank() && filter == ContentFilter() && category == GalleryFilter.All && !personSelected && !scoped

    /**
     * Everything but the query: the content filter, the category chip and the People rail, in that
     * order and keeping the input order. The keyword matches pass through it inside [apply], and the
     * content-search matches pass through it on their own, so both answer to the same chips.
     */
    fun applyFacets(
        items: List<GalleryItem>,
        filter: ContentFilter,
        category: GalleryFilter,
        offlinePinIds: Set<String> = emptySet(),
        favoriteIds: Set<String> = emptySet(),
        personPhotoKeys: Set<String>? = null,
    ): List<GalleryItem> {
        var out = applyContentFilter(items, filter, offlinePinIds)
        if (category != GalleryFilter.All) {
            out = when (category) {
                // Live Photos (tag 3) and Motion Photos (tag 4) are the same concept on iOS/Android
                // and share one chip, so either tag matches both, mirroring the gallery filter.
                GalleryFilter.LivePhotos, GalleryFilter.MotionPhotos ->
                    out.filter { CategorizeItem.belongsTo(it, 3) || CategorizeItem.belongsTo(it, 4) }
                // Offline = the cloud photos pinned for offline; not a server tag, so it filters on
                // the pinned linkId set rather than CategorizeItem. Mirrors the gallery filter.
                GalleryFilter.Offline -> out.filter { isItemOfflinePinned(it, offlinePinIds) }
                // Favourite = the device-side set for a photo that lives only on the device, Drive
                // PhotoTag 0 for a backed-up one. Through the same helper the timeline, the grid
                // hearts and the viewer use, so the one chip cannot mean two things on two screens.
                GalleryFilter.Favorites -> out.filter { isItemFavorite(it, favoriteIds) }
                else -> category.tagId?.let { id -> out.filter { CategorizeItem.belongsTo(it, id) } } ?: out
            }
        }
        // People filter: narrow to the selected person's photos (their face-table keys), composing
        // with every other active filter exactly like the timeline's people rail.
        if (personPhotoKeys != null) {
            out = out.filter { it.stableId in personPhotoKeys }
        }
        return out
    }

    fun applyContentFilter(
        items: List<GalleryItem>,
        filter: ContentFilter,
        offlinePinIds: Set<String> = emptySet(),
    ): List<GalleryItem> {
        var out = items
        out = when (filter.mediaType) {
            MediaType.All        -> out
            MediaType.PhotosOnly -> out.filter { !mimeOf(it).startsWith("video/") }
            MediaType.VideosOnly -> out.filter { mimeOf(it).startsWith("video/") }
        }
        out = when (filter.syncStatus) {
            SyncStatusFilter.All       -> out
            SyncStatusFilter.LocalOnly -> out.filter { it is GalleryItem.LocalOnly }
            SyncStatusFilter.BackedUp  -> out.filter { it is GalleryItem.Synced }
            SyncStatusFilter.CloudOnly -> out.filter { it is GalleryItem.CloudOnly }
            SyncStatusFilter.Offline   -> out.filter { isItemOfflinePinned(it, offlinePinIds) }
        }
        // Every date part that is set narrows on its own, which is what the timeline's own filter
        // does. Hanging the whole branch off the year made a month or a day without one inert here
        // while it still narrowed there, and inert is worse than unreachable: the empty-state check
        // above counts any non-default filter as "something was asked for", so a month alone
        // answered with the entire library instead of with nothing.
        val year = filter.year
        val month = filter.month
        val day = filter.day
        val dayEnd = filter.dayEnd
        if (year != null || month != null || day != null) {
            val cal = Calendar.getInstance()
            out = out.filter {
                cal.timeInMillis = it.captureTimeMs
                val dom = cal.get(Calendar.DAY_OF_MONTH)
                (year == null || cal.get(Calendar.YEAR) == year) &&
                    (month == null || cal.get(Calendar.MONTH) + 1 == month) &&
                    (day == null || (if (dayEnd != null) dom in day..dayEnd else dom == day))
            }
        }
        val from = filter.dateFrom
        val to = filter.dateTo
        if (from != null && to != null) {
            val (start, end) = dayRangeBounds(from, to, ZoneId.systemDefault())
            out = out.filter { it.captureTimeMs in start until end }
        }
        return out
    }

    /** The instants [from] begins at and the day after [to] begins at, in [zone]: the half-open span of
     *  milliseconds that holds every moment of the days from [from] to [to]. */
    internal fun dayRangeBounds(from: LocalDate, to: LocalDate, zone: ZoneId): Pair<Long, Long> =
        from.atStartOfDay(zone).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Folded text a typed query matches against (file name + capture year + month name + media
     *  type + file extension + category names), so the search box finds photos by metadata, not just
     *  the file name. Month/category names arrive pre-folded; only the per-item name is folded here.
     *  The categories are the ones the category rail files the item under ([CategorizeItem.classify]),
     *  so a user-picked or device-detected category is findable by name as well as a server tag. The
     *  photo's own labels follow, read under every key they may sit under ([labelsOf]), and its city and
     *  country close it ([placesOf]). */
    fun searchHaystack(
        item: GalleryItem,
        cal: Calendar,
        foldedMonths: List<String>,
        foldedCategoryNames: Map<Int, String>,
        foldedLabels: Map<String, String> = emptyMap(),
        foldedPlaces: Map<String, String> = emptyMap(),
    ): String {
        cal.timeInMillis = item.captureTimeMs
        val mime = mimeOf(item)
        val ext = mime.substringAfterLast('/', "")
        val tags = CategorizeItem.classify(item)
        return buildString {
            append(fold(displayNameOf(item)))
            append(' ').append(cal.get(Calendar.YEAR))
            append(' ').append(foldedMonths[cal.get(Calendar.MONTH)])
            append(if (mime.startsWith("video/")) " video" else " photo")
            if (ext.isNotEmpty()) { append(' '); append(ext) }
            tags.forEach { id -> foldedCategoryNames[id]?.let { append(' '); append(it) } }
            labelsOf(item, foldedLabels)?.let { append(' '); append(it) }
            placesOf(item, foldedPlaces)?.let { append(' '); append(it) }
        }
    }

    /** [item]'s folded city and country from [foldedPlaces], read under the keys its stored location may
     *  sit under (a device uri, a cloud linkId); null when it has no stored location. */
    fun placesOf(item: GalleryItem, foldedPlaces: Map<String, String>): String? {
        if (foldedPlaces.isEmpty()) return null
        for (key in LabelKeyReconciler.keysFor(item)) foldedPlaces[key]?.let { return it }
        return null
    }

    /** Each photo key's labels folded and joined by single spaces, built once per label change rather
     *  than per item and keystroke. */
    fun foldLabels(byKey: Map<String, List<String>>): Map<String, String> =
        byKey.mapValues { (_, labels) -> labels.joinToString(" ") { fold(it) } }

    /** [item]'s folded labels from [foldedLabels]: those under its stableId and, for a backed-up photo,
     *  those still under its device uri from before the backup. Null when it carries none. */
    fun labelsOf(item: GalleryItem, foldedLabels: Map<String, String>): String? {
        if (foldedLabels.isEmpty()) return null
        val keys = LabelKeyReconciler.keysFor(item)
        val primary = foldedLabels[keys[0]]
        val secondary = keys.getOrNull(1)?.let { foldedLabels[it] } ?: return primary
        return if (primary == null) secondary else "$primary $secondary"
    }
}
