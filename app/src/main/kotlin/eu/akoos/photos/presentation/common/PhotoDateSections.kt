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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.presentation.albums.AlbumPhotoGrouping
import eu.akoos.photos.presentation.util.dayMonthYearFormat
import eu.akoos.photos.presentation.util.monthYearFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [items] under the date headers [grouping] asks for: each header label mapped to its photos, every
 * photo paired with its position in [items], which is what the viewer opens on and the drag-select
 * keys line up with.
 *
 * [items] arrive already sorted. Headers come in the order their first photo appears, and one label
 * is one entry however its photos fall, so a header key is never repeated. Each label reads as the
 * timeline heads that span: Month is [monthYearFormat], the headers albums and folders have always
 * shown, Day is [dayMonthYearFormat], and Year is the plain year of the zoomed-out timeline.
 *
 * [locale] and [timeZone] default to the reader's, as every other date header does; a caller passes
 * them only to pin the labels.
 *
 * This runs on the main thread whenever a grid regroups, every pinch step included, so a photo costs
 * one calendar lookup for its [photoDateKey] and the formatter runs once per distinct key, not once
 * per photo.
 */
fun <T> photoDateSections(
    items: List<T>,
    grouping: AlbumPhotoGrouping,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
    captureTimeMs: (T) -> Long,
): Map<String, List<IndexedValue<T>>> {
    val fmt = when (grouping) {
        AlbumPhotoGrouping.Month -> monthYearFormat(locale)
        AlbumPhotoGrouping.Day -> dayMonthYearFormat(locale)
        AlbumPhotoGrouping.Year -> SimpleDateFormat("yyyy", locale)
    }
    fmt.timeZone = timeZone
    // The formatter's own calendar in the same zone, so a key names exactly the date its label prints
    // even for a locale whose calendar is not the Gregorian one.
    val calendar = fmt.calendar.clone() as Calendar
    val labelByKey = HashMap<Long, String>()
    val sections = LinkedHashMap<String, MutableList<IndexedValue<T>>>()
    // A sorted list keeps a section's photos together, so the photo after the first of a run reuses
    // its section without a lookup.
    var runKey = 0L
    var runSection: MutableList<IndexedValue<T>>? = null
    items.forEachIndexed { index, item ->
        val ms = captureTimeMs(item)
        calendar.timeInMillis = ms
        val key = photoDateKey(
            grouping,
            era = calendar.get(Calendar.ERA),
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH),
            day = calendar.get(Calendar.DAY_OF_MONTH),
        )
        val current = runSection
        val section = if (current != null && key == runKey) {
            current
        } else {
            val label = labelByKey.getOrPut(key) { fmt.format(Date(ms)) }
            sections.getOrPut(label) { ArrayList() }.also {
                runKey = key
                runSection = it
            }
        }
        section.add(IndexedValue(index, item))
    }
    return sections
}

/**
 * One number per span [grouping] gathers, from a photo's calendar era, year of that era, month
 * (0-based, as [Calendar.MONTH] counts) and day of month: every photo of one day, month or year gets
 * the same key, and photos whose labels differ never share one. A label prints no field the key does
 * not hold, so every photo under one key reads the same label. Two keys may still share a label (a
 * year printed without its era), which is why sections are gathered by label.
 */
internal fun photoDateKey(grouping: AlbumPhotoGrouping, era: Int, year: Int, month: Int, day: Int): Long {
    val span = (era * 1_000_000_000L + year) * 10_000L
    return when (grouping) {
        AlbumPhotoGrouping.Year -> span
        AlbumPhotoGrouping.Month -> span + month * 100L
        AlbumPhotoGrouping.Day -> span + month * 100L + day
    }
}
