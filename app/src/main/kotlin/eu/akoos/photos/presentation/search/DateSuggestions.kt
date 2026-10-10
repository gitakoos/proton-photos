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

import eu.akoos.photos.presentation.gallery.ContentFilter
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
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
    /** The first and last day of a run that may cross a month ("last week"), instead of the parts above. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/** [current] with this term as its date: a run of days replaces the year, month and day, and a day, month or
 *  year fills in whichever of them the term names, keeping the others [current] holds. */
internal fun DateTerm.applyTo(current: ContentFilter): ContentFilter =
    if (from != null && to != null) {
        current.copy(year = null, month = null, day = null, dayEnd = null, dateFrom = from, dateTo = to)
    } else {
        current.copy(
            year = year ?: current.year, month = month ?: current.month, day = day,
            dayEnd = null, dateFrom = null, dateTo = null,
        )
    }

/** A date a phrase names relative to today: "today", "yesterday", "this week", "last week", "this month",
 *  "last month", "this year", "last year". */
enum class RelativeDate { Today, Yesterday, ThisWeek, LastWeek, ThisMonth, LastMonth, ThisYear, LastYear }

/** What a counted phrase counts back in: "3 days ago", "2 weeks ago", "2 months ago", "2 years ago". */
enum class RelativeUnit { Day, Week, Month, Year }

/** One way of phrasing a counted date: [regex] matches a whole folded phrase, with the count as its first
 *  group, or no group for a phrase that means one ("a week ago"). */
class CountedPattern(val unit: RelativeUnit, val regex: Regex)

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
    private const val MAX_COUNTED_WORDS = 5
    private const val MAX_COUNT = 999
    private const val COUNT_MARK = "#"
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

    /** The patterns for [templates], each a folded phrase whose [COUNT_MARK] stands for the number. A
     *  template without the mark means one. */
    fun countedPatterns(templates: Map<RelativeUnit, Set<String>>): List<CountedPattern> =
        templates.flatMap { (unit, phrases) ->
            phrases.map { phrase ->
                val parts = phrase.split(COUNT_MARK)
                CountedPattern(unit, Regex(parts.joinToString("(\\d{1,3})") { Regex.escape(it) }))
            }
        }

    /**
     * The counted date the end of [query] spells in full: its unit, the count and how many of its words
     * spell it, or null. The longest ending wins, and a count of zero is no date.
     */
    fun counted(query: String, patterns: List<CountedPattern>): Triple<RelativeUnit, Int, Int>? {
        val keys = query.trim().split(SPACES).filter { it.isNotBlank() }.map { phraseKey(it) }
        for (count in minOf(MAX_COUNTED_WORDS, keys.size) downTo 1) {
            val tail = keys.takeLast(count).joinToString(" ")
            for (pattern in patterns) {
                val match = pattern.regex.matchEntire(tail) ?: continue
                val number = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                if (number in 1..MAX_COUNT) return Triple(pattern.unit, number, count)
            }
        }
        return null
    }

    /** The date filter [number] [unit]s before the day [today] falls on: that day, the week, month or year
     *  it falls in. A week begins on [today]'s first day of the week. */
    fun resolveCounted(unit: RelativeUnit, number: Int, today: Calendar, consumedTokens: Int): DateTerm {
        val day = LocalDate.of(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
        return when (unit) {
            RelativeUnit.Day -> day.minusDays(number.toLong()).let {
                DateTerm(it.year, it.monthValue, consumedTokens, day = it.dayOfMonth)
            }
            RelativeUnit.Week -> weekOf(day.minusWeeks(number.toLong()), today.firstDayOfWeek).let {
                DateTerm(null, null, consumedTokens, from = it.first, to = it.second)
            }
            RelativeUnit.Month -> day.withDayOfMonth(1).minusMonths(number.toLong()).let {
                DateTerm(it.year, it.monthValue, consumedTokens)
            }
            RelativeUnit.Year -> DateTerm(day.year - number, null, consumedTokens)
        }
    }

    /** The first and last day of the week [day] falls in, a week beginning on [firstDayOfWeek] (a
     *  [Calendar] day constant). */
    private fun weekOf(day: LocalDate, firstDayOfWeek: Int): Pair<LocalDate, LocalDate> {
        val first = DayOfWeek.of((firstDayOfWeek + 5) % 7 + 1)
        val start = day.with(TemporalAdjusters.previousOrSame(first))
        return start to start.plusDays(6)
    }

    /** The date filter [date] means on the day [today] falls on: a day, a week, a month or a whole year. */
    fun resolveRelative(date: RelativeDate, today: Calendar, consumedTokens: Int): DateTerm {
        when (date) {
            RelativeDate.ThisWeek -> return resolveCounted(RelativeUnit.Week, 0, today, consumedTokens)
            RelativeDate.LastWeek -> return resolveCounted(RelativeUnit.Week, 1, today, consumedTokens)
            else -> Unit
        }
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
            RelativeDate.ThisWeek, RelativeDate.LastWeek -> DateTerm(year, month, consumedTokens)
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

/** The text of a date filter: "2024", "June 2024", "June 3, 2024", or a day range, in [locale]. A run of
 *  days that may cross a month, [from] to [to], reads as its two ends. Null when no date is set. */
internal fun dateTermLabel(
    year: Int?,
    month: Int?,
    day: Int?,
    dayEnd: Int?,
    locale: Locale,
    from: LocalDate? = null,
    to: LocalDate? = null,
): String? {
    fun format(skeleton: String, y: Int, m: Int, d: Int): String {
        val calendar = Calendar.getInstance(locale).apply { clear(); set(y, m - 1, d) }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(calendar.time)
    }
    if (from != null && to != null) {
        val start = format("yMMMd", from.year, from.monthValue, from.dayOfMonth)
        val end = format("yMMMd", to.year, to.monthValue, to.dayOfMonth)
        return if (from == to) start else "$start - $end"
    }
    if (year == null && month == null && day == null) return null
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
