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

package eu.akoos.photos.util

import eu.akoos.photos.domain.entity.GalleryItem
import java.util.Calendar

/** Milestone anniversaries, in years, a memory is allowed to mark. Years in between (3, 4, 6 …)
 *  are skipped so the carousel highlights meaningful jumps instead of echoing every single year. */
private val MEMORY_MILESTONE_YEARS = listOf(1, 2, 5, 10)

/** Half-width, in days, of the window around each anniversary. A few days either side (≈ a week)
 *  keeps a milestone from vanishing just because nothing happened to be shot on the exact day. */
private const val MEMORY_WINDOW_DAYS = 3

/**
 * Curated "memories": photos taken around this date a milestone number of years ago
 * ([MEMORY_MILESTONE_YEARS] — 1, 2, 5, 10 years), matched within [MEMORY_WINDOW_DAYS] days either
 * side so a milestone still surfaces when nothing was shot on the exact calendar day. Returns
 * (year, items) pairs most-recent-first; an empty list means no milestone has photos, so callers
 * hide the carousel/section entirely. The carousel derives its "N years ago" label from the year.
 *
 * Backs both the gallery's carousel and the search page's memories row from one place.
 */
fun computeOnThisDay(items: List<GalleryItem>): List<Pair<Int, List<GalleryItem>>> {
    if (items.isEmpty()) return emptyList()
    val todayYear = Calendar.getInstance().get(Calendar.YEAR)
    val result = ArrayList<Pair<Int, List<GalleryItem>>>(MEMORY_MILESTONE_YEARS.size)
    for (yearsAgo in MEMORY_MILESTONE_YEARS) {
        val anchor = Calendar.getInstance().apply { add(Calendar.YEAR, -yearsAgo) }
        val lo = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -MEMORY_WINDOW_DAYS) }.timeInMillis
        val hi = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, MEMORY_WINDOW_DAYS) }.timeInMillis
        val window = items.filter { it.captureTimeMs in lo..hi }
        if (window.isNotEmpty()) result += (todayYear - yearsAgo) to window
    }
    return result
}
