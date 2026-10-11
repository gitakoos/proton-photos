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

package eu.akoos.photos.util

import android.content.Context
import eu.akoos.photos.data.preferences.SettingsKeys
import eu.akoos.photos.data.preferences.settingsDataStore
import eu.akoos.photos.domain.entity.GalleryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Calendar

/** Milestone anniversaries, in years, a memory is allowed to mark. Years in between (3, 4, 6 ...)
 *  are skipped so the carousel highlights meaningful jumps instead of echoing every single year. */
private val MEMORY_MILESTONE_YEARS = listOf(1, 2, 5, 10)

/** The most whole days either side of the date the user can widen "On this day" to. */
const val ON_THIS_DAY_MAX_WINDOW_DAYS = 3

/** The user's "days either side" choice for "On this day", 0 (the day itself) to [ON_THIS_DAY_MAX_WINDOW_DAYS]. */
fun Context.onThisDayWindowDays(): Flow<Int> = settingsDataStore.data
    .map { (it[SettingsKeys.ON_THIS_DAY_WINDOW_DAYS] ?: 0).coerceIn(0, ON_THIS_DAY_MAX_WINDOW_DAYS) }
    .distinctUntilChanged()

/**
 * Curated "memories": photos taken on this calendar date a milestone number of years ago
 * ([MEMORY_MILESTONE_YEARS]: 1, 2, 5, 10 years). The match is by whole local days: the day itself, and
 * with [windowDays] above 0 that many whole days before and after it, so no photo from a neighbouring
 * day shows unless the user asked for it. Returns (year, items) pairs most-recent-first; an empty list
 * means no milestone has photos, so callers hide the carousel/section entirely. The carousel derives its
 * "N years ago" label from the year. [now] is the moment "today" is read from.
 *
 * Backs the gallery's carousel, the search page's memories row and the Memories screen from one place.
 */
fun computeOnThisDay(
    items: List<GalleryItem>,
    windowDays: Int = 0,
    now: Calendar = Calendar.getInstance(),
): List<Pair<Int, List<GalleryItem>>> {
    if (items.isEmpty()) return emptyList()
    val days = windowDays.coerceIn(0, ON_THIS_DAY_MAX_WINDOW_DAYS)
    val todayYear = now.get(Calendar.YEAR)
    val result = ArrayList<Pair<Int, List<GalleryItem>>>(MEMORY_MILESTONE_YEARS.size)
    for (yearsAgo in MEMORY_MILESTONE_YEARS) {
        // From the first moment of the first day to the first moment after the last day.
        val from = dayStartMs(now, yearsAgo, -days)
        val until = dayStartMs(now, yearsAgo, days + 1)
        val window = items.filter { it.captureTimeMs >= from && it.captureTimeMs < until }
        if (window.isNotEmpty()) result += (todayYear - yearsAgo) to window
    }
    return result
}

/** The first moment of the local day [dayOffset] days from [yearsAgo] years before [now]. */
private fun dayStartMs(now: Calendar, yearsAgo: Int, dayOffset: Int): Long =
    (now.clone() as Calendar).apply {
        add(Calendar.YEAR, -yearsAgo)
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
