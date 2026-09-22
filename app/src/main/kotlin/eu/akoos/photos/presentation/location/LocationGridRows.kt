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

package eu.akoos.photos.presentation.location

import eu.akoos.photos.domain.entity.GalleryItem
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** One slot of the place page's photo grid under its hero: a full-width month header, or a photo. */
internal sealed interface LocationGridRow {
    /** The grid key. A photo's is its [locationCellKey], the key the drag-select resolves; a header's
     *  is built from its month and never matches a photo's. */
    val key: String

    /** Heads a run of photos from one calendar month; [label] is the text it shows. */
    data class Header(override val key: String, val label: String) : LocationGridRow

    /** A photo, [index] being its position in the list the rows were built from. That is the position
     *  the viewer opens on, so a header never shifts which photo a tap opens. */
    data class Photo(override val key: String, val index: Int, val item: GalleryItem) : LocationGridRow
}

/**
 * Lays [items] out the way the place grid shows them, in list order: a header wherever the calendar
 * month (read in [zone]) differs from the photo before, then the photo. [labelOf] names a header from
 * the capture time of the first photo under it. A header's key is its year and month, so it holds
 * across a language change; a month the list comes back to later, which only an unsorted list does,
 * gets a numbered key so no two headers share one. Pure.
 */
internal fun locationGridRows(
    items: List<GalleryItem>,
    zone: TimeZone,
    labelOf: (Long) -> String,
): List<LocationGridRow> {
    val calendar = GregorianCalendar(zone)
    val headersPerMonth = HashMap<Int, Int>()
    val rows = ArrayList<LocationGridRow>(items.size + 16)
    var previousMonth: Int? = null
    items.forEachIndexed { index, item ->
        calendar.timeInMillis = item.captureTimeMs
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val monthId = year * 12 + month
        if (monthId != previousMonth) {
            val earlier = headersPerMonth[monthId] ?: 0
            headersPerMonth[monthId] = earlier + 1
            val base = "hdr_$year-${month + 1}"
            rows += LocationGridRow.Header(
                key = if (earlier == 0) base else base + "_" + (earlier + 1),
                label = labelOf(item.captureTimeMs),
            )
            previousMonth = monthId
        }
        rows += LocationGridRow.Photo(key = locationCellKey(item), index = index, item = item)
    }
    return rows
}

/** Cell / drag-select key: local uri where a device copy exists, else the cloud linkId, prefixed so
 *  the two id spaces never collide. Shared by the grid cells, the drag sweep and the index map. */
internal fun locationCellKey(item: GalleryItem): String = when (item) {
    is GalleryItem.LocalOnly -> "L:" + item.local.uri
    is GalleryItem.Synced    -> "S:" + item.local.uri
    is GalleryItem.CloudOnly -> "C:" + item.cloud.linkId
}
