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
 */

package eu.akoos.photos.presentation.search

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A date the typed query could mean: a whole [year], or one [month] (1 to 12) of it. [consumedTokens]
 * is how many trailing words of the query spell it, so choosing it can take them out of the text.
 */
data class DateSuggestion(val year: Int, val month: Int?, val consumedTokens: Int)

/** Turns the end of a typed query into dates to pick: "june" offers that month of each year the library
 *  has, "june 2023" and "2023" offer exactly that, and a short run of year digits offers the years that
 *  start with it. */
internal object DateSuggestions {
    private const val MIN_MONTH_CHARS = 3
    private const val MAX_SUGGESTIONS = 6
    private const val FIRST_YEAR = 1900
    private const val LAST_YEAR = 2100

    /** [foldedMonths] are the 12 month names folded like the query, [years] the library's years newest first. */
    fun suggest(query: String, foldedMonths: List<String>, years: List<Int>): List<DateSuggestion> {
        val tokens = SearchFilter.fold(query.trim()).split(' ').filter { it.isNotBlank() }
        val last = tokens.lastOrNull() ?: return emptyList()
        if (last.all { it.isDigit() }) {
            val matching = when (last.length) {
                4 -> listOfNotNull(last.toInt().takeIf { it in FIRST_YEAR..LAST_YEAR })
                2, 3 -> years.filter { it.toString().startsWith(last) }
                else -> return emptyList()
            }
            val month = tokens.getOrNull(tokens.size - 2)?.let { monthOf(it, foldedMonths) }
            return matching.take(MAX_SUGGESTIONS).map { DateSuggestion(it, month, if (month != null) 2 else 1) }
        }
        val month = monthOf(last, foldedMonths) ?: return emptyList()
        return years.take(MAX_SUGGESTIONS).map { DateSuggestion(it, month, 1) }
    }

    /** The month a word names, by its first letters: only a word long enough to be unambiguous counts. */
    private fun monthOf(token: String, foldedMonths: List<String>): Int? {
        if (token.length < MIN_MONTH_CHARS) return null
        return foldedMonths.indices.filter { foldedMonths[it].startsWith(token) }.singleOrNull()?.plus(1)
    }
}

/** The text of a date filter: "2024", "June 2024", "June 3, 2024", or a day range, in [locale]. Null
 *  when no date is set. */
internal fun dateTermLabel(year: Int?, month: Int?, day: Int?, dayEnd: Int?, locale: Locale): String? {
    if (year == null && month == null && day == null) return null
    fun format(skeleton: String, y: Int, m: Int, d: Int): String {
        val calendar = Calendar.getInstance(locale).apply { clear(); set(y, m - 1, d) }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(calendar.time)
    }
    return when {
        year != null && month == null -> year.toString()
        year != null && day == null -> format("yMMMM", year, month!!, 1)
        year != null && dayEnd != null -> "${format("yMMMMd", year, month!!, day!!)} - ${format("yMMMMd", year, month, dayEnd)}"
        year != null -> format("yMMMMd", year, month!!, day!!)
        day == null -> month?.let { format("LLLL", 2000, it, 1) }
        month != null -> format("MMMMd", 2000, month, day)
        else -> null
    }
}
