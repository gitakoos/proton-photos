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
    val scratch = Date()
    return items.withIndex().groupBy { scratch.time = captureTimeMs(it.value); fmt.format(scratch) }
}
