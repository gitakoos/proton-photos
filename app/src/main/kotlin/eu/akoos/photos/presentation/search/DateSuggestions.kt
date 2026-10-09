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

/**
 * The date a typed query ends with, enough to become a filter on its own: a [year], a [month] (1 to 12),
 * or both, either of them null when the words do not name it. [consumedTokens] is how many trailing words
 * spell it. [complete] is false when the month is spelled by its abbreviation ("jan") rather than its whole
 * name, since the abbreviation may still be going to become the whole name.
 */
data class DateTerm(
    val year: Int?,
    val month: Int?,
    val consumedTokens: Int,
    val complete: Boolean = true,
    /** The day of [month], for a phrase that names one ("yesterday"). */
    val day: Int? = null,
)

/** A date a phrase names relative to today: "today", "yesterday", "this month", "last month", "this year",
 *  "last year". A week is left out, since the date filter holds a day range only within one month. */
enum class RelativeDate { Today, Yesterday, ThisMonth, LastMonth, ThisYear, LastYear }

/** Reads the end of a typed query as a date. [term] takes a month name or a whole year, or the two
 *  together in either order, to be turned into the date pill at once. [suggest] offers the years a short
 *  run of digits could still become, since there is a choice to make. */
internal object DateSuggestions {
    private const val MIN_MONTH_CHARS = 3

    /** A name or abbreviation from another language than the app's must be this long, so a short word of
     *  one language ("ago", "set", "ene") is not taken for a month of another. */
    private const val MIN_OTHER_LANGUAGE_CHARS = 4

    /** The languages the app is offered in, whose month names and abbreviations a typed word may be
     *  (`res/xml/locale_config.xml`, pinned by `DateSuggestionsTest`). */
    val MONTH_LANGUAGES = listOf("en", "de", "es", "fr", "hu", "it", "nl", "pl", "sk", "sl", "cs", "tr")
    private const val MAX_SUGGESTIONS = 6
    private const val FIRST_YEAR = 1900
    private const val LAST_YEAR = 2100

    private const val MAX_PHRASE_WORDS = 4
    private val QUOTES = Regex("[\u2019'`\u00b4]")
    private val SPACES = Regex("\\s+")

    /** A word or phrase as it is compared: folded like the rest of the search, apostrophes dropped and
     *  hyphens read as spaces, so "l'annee", "l’annee" and "ce mois-ci" are the same however typed. */
    fun phraseKey(text: String): String =
        SearchFilter.fold(text).replace(QUOTES, "").replace('-', ' ').trim().split(' ').filter { it.isNotBlank() }
            .joinToString(" ")

    /**
     * The relative date the end of [query] spells in full, with how many of its words spell it, or null.
     * [phrases] holds each date's phrases already through [phraseKey]. The longest ending wins, and a
     * phrase must be matched whole: "last" or "month" alone is only text.
     */
    fun relative(query: String, phrases: Map<RelativeDate, Set<String>>): Pair<RelativeDate, Int>? {
        val words = query.trim().split(SPACES).filter { it.isNotBlank() }
        val keys = words.map { phraseKey(it) }
        for (count in minOf(MAX_PHRASE_WORDS, keys.size) downTo 1) {
            val tail = keys.takeLast(count).joinToString(" ")
            phrases.entries.firstOrNull { tail in it.value }?.let { return it.key to count }
        }
        return null
    }

    /** The date filter [date] means on the day [today] falls on: a day, a month or a whole year. */
    fun resolveRelative(date: RelativeDate, today: Calendar, consumedTokens: Int): DateTerm {
        val at = today.clone() as Calendar
        when (date) {
            RelativeDate.Yesterday -> at.add(Calendar.DAY_OF_MONTH, -1)
            RelativeDate.LastMonth -> { at.set(Calendar.DAY_OF_MONTH, 1); at.add(Calendar.MONTH, -1) }
            RelativeDate.LastYear -> at.add(Calendar.YEAR, -1)
            else -> Unit
        }
        val year = at.get(Calendar.YEAR)
        val month = at.get(Calendar.MONTH) + 1
        return when (date) {
            RelativeDate.Today, RelativeDate.Yesterday ->
                DateTerm(year, month, consumedTokens, day = at.get(Calendar.DAY_OF_MONTH))
            RelativeDate.ThisMonth, RelativeDate.LastMonth -> DateTerm(year, month, consumedTokens)
            RelativeDate.ThisYear, RelativeDate.LastYear -> DateTerm(year, null, consumedTokens)
        }
    }

    /**
     * [foldedMonths] are the 12 month names folded like the query, in the app's language, [abbreviations]
     * their usual short forms, and [otherLanguages] the whole names and the short forms in the other
     * languages the app is offered in, each a list of twelve. A month is a whole name or an abbreviation
     * (or the first three letters of the name), never just a word that starts like one. A month, or a whole year, at the end, with the month or year beside
     * it when there is one, or null when the end names neither.
     */
    fun term(
        query: String,
        foldedMonths: List<String>,
        abbreviations: List<String> = emptyList(),
        otherLanguages: List<List<String>> = emptyList(),
    ): DateTerm? {
        val tokens = SearchFilter.fold(query.trim()).split(' ').filter { it.isNotBlank() }
        val last = tokens.lastOrNull() ?: return null
        val before = tokens.getOrNull(tokens.size - 2)
        val lastYear = yearOf(last)
        if (lastYear != null) {
            val month = before?.let { monthMatch(it, foldedMonths, abbreviations, otherLanguages) }
            return DateTerm(lastYear, month?.first, if (month != null) 2 else 1, complete = month?.second ?: true)
        }
        val month = monthMatch(last, foldedMonths, abbreviations, otherLanguages) ?: return null
        val year = before?.let { yearOf(it) }
        return DateTerm(year, month.first, if (year != null) 2 else 1, complete = month.second)
    }

    /** [foldedMonths] are the 12 month names folded like the query, [years] the library's years newest first.
     *  Only two or three year digits offer anything: a whole year or a month is taken by [term]. */
    fun suggest(
        query: String,
        foldedMonths: List<String>,
        years: List<Int>,
        abbreviations: List<String> = emptyList(),
    ): List<DateSuggestion> {
        val tokens = SearchFilter.fold(query.trim()).split(' ').filter { it.isNotBlank() }
        val last = tokens.lastOrNull() ?: return emptyList()
        if (!last.all { it.isDigit() } || last.length !in 2..3) return emptyList()
        val matching = years.filter { it.toString().startsWith(last) }
        val month = tokens.getOrNull(tokens.size - 2)?.let { monthOf(it, foldedMonths, abbreviations) }
        return matching.take(MAX_SUGGESTIONS).map { DateSuggestion(it, month, if (month != null) 2 else 1) }
    }

    /** The year a word spells: exactly four digits within a plausible range. */
    private fun yearOf(token: String): Int? =
        if (token.length == 4 && token.all { it.isDigit() }) token.toInt().takeIf { it in FIRST_YEAR..LAST_YEAR } else null

    private fun monthOf(token: String, foldedMonths: List<String>, abbreviations: List<String>): Int? =
        monthMatch(token, foldedMonths, abbreviations)?.first

    /**
     * The month a word names and whether the word is its whole name, or null when it names none. In the
     * app's language the word must be a month's whole name, its usual abbreviation or the first three
     * letters of its name, and name one month alone ("mar" fits one month, a Czech "cer" fits two and so
     * none). A word that is only the start of a longer one ("janu") is no month. Failing that, a whole name
     * or an abbreviation in another language counts, from four letters on.
     */
    private fun monthMatch(
        token: String,
        foldedMonths: List<String>,
        abbreviations: List<String>,
        otherLanguages: List<List<String>> = emptyList(),
    ): Pair<Int, Boolean>? {
        if (token.length < MIN_MONTH_CHARS) return null
        val own = foldedMonths.indices.filter { month ->
            token == foldedMonths[month] || token == abbreviations.getOrNull(month) ||
                token == foldedMonths[month].take(MIN_MONTH_CHARS)
        }
        if (own.size == 1) return (own[0] + 1) to (token == foldedMonths[own[0]])
        if (own.isNotEmpty() || token.length < MIN_OTHER_LANGUAGE_CHARS) return null
        val other = foldedMonths.indices.filter { month -> otherLanguages.any { it[month] == token } }
            .singleOrNull() ?: return null
        return (other + 1) to true
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
