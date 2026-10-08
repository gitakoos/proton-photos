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

package eu.akoos.photos.presentation.calendar

import eu.akoos.photos.presentation.search.SearchFilter

private val SPACES = Regex("\\s+")

/** The words of a calendar search [query], folded like the text they are matched against. */
internal fun calendarSearchTerms(query: String): List<String> =
    SearchFilter.fold(query).split(SPACES).filter { it.isNotBlank() }

/**
 * The text a day is matched against: its ISO date, month and day labels, year and the description
 * the user wrote for it, all folded with [SearchFilter.fold] so letter case and accents (including
 * the Turkish dotless "ı") never decide whether a day is found.
 */
internal fun calendarSearchHaystack(
    date: String,
    monthLabel: String,
    dayLabel: String,
    year: Int,
    description: String?,
): String = buildString {
    append(SearchFilter.fold(date))
    append(' ')
    append(SearchFilter.fold(monthLabel))
    append(' ')
    append(SearchFilter.fold(dayLabel))
    append(' ')
    append(year)
    description?.let { append(' '); append(SearchFilter.fold(it)) }
}
