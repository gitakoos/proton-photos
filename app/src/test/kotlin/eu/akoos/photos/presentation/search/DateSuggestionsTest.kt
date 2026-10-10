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
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DateSuggestionsTest {

    private val english = SearchFilter.foldedMonthNames(Locale.ENGLISH)
    private val hungarian = SearchFilter.foldedMonthNames(Locale("hu"))
    private val years = listOf(2024, 2023, 2021)

    @Test
    fun `a month name or a whole year is taken by term, not offered as a choice`() {
        assertTrue(DateSuggestions.suggest("june", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("2023", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("beach jun 2023", english, years).isEmpty())
    }

    @Test
    fun `a month followed by a few year digits offers the years that start with them`() {
        assertEquals(
            listOf(DateSuggestion(2024, 6, 2), DateSuggestion(2023, 6, 2), DateSuggestion(2021, 6, 2)),
            DateSuggestions.suggest("june 20", english, years),
        )
    }

    @Test
    fun `a few year digits offer the years that start with them`() {
        assertEquals(
            listOf(DateSuggestion(2024, null, 1), DateSuggestion(2023, null, 1), DateSuggestion(2021, null, 1)),
            DateSuggestions.suggest("20", english, years),
        )
        assertEquals(
            listOf(DateSuggestion(2021, null, 1)),
            DateSuggestions.suggest("202", english, listOf(2021, 2015)),
        )
    }

    @Test
    fun `at most six years are offered`() {
        val many = (2010..2024).toList().reversed()
        assertEquals(6, DateSuggestions.suggest("20", english, many).size)
    }

    @Test
    fun `nothing is offered for a word, a single digit or a long run of digits`() {
        for (query in listOf("ju", "hello", "   ", "", "7", "20231", "2023")) {
            assertTrue("'$query'", DateSuggestions.suggest(query, english, years).isEmpty())
        }
    }

    @Test
    fun `a lone month name is a month term`() {
        assertEquals(DateTerm(null, 6, 1), DateSuggestions.term("june", english))
        assertEquals(DateTerm(null, 6, 1, complete = false), DateSuggestions.term("beach jun", english))
    }

    @Test
    fun `a lone whole year is a year term`() {
        assertEquals(DateTerm(2023, null, 1), DateSuggestions.term("2023", english))
        assertEquals(DateTerm(2023, null, 1), DateSuggestions.term("beach 2023", english))
    }

    @Test
    fun `a month and a year in either order are one term that takes both words`() {
        assertEquals(DateTerm(2023, 6, 2, complete = false), DateSuggestions.term("beach jun 2023", english))
        assertEquals(DateTerm(2023, 6, 2), DateSuggestions.term("2023 june", english))
    }

    @Test
    fun `months are read in the given language and without accents`() {
        assertEquals(DateTerm(null, 6, 1, complete = false), DateSuggestions.term("jun", hungarian))
        assertEquals(DateTerm(null, 6, 1, complete = false), DateSuggestions.term("j\u00fan", hungarian))
        assertEquals(DateTerm(null, 6, 1), DateSuggestions.term("j\u00fanius", hungarian))
        assertEquals(DateTerm(2022, 2, 2), DateSuggestions.term("februar 2022", hungarian))
    }

    @Test
    fun `a whole month name or its abbreviation is the month, a word that only starts like it is not`() {
        for (query in listOf("jan", "january")) {
            assertEquals(query, 1, DateSuggestions.term(query, english)?.month)
        }
        for (query in listOf("janu", "janua", "januar", "janet", "january2", "marble", "decor", "septic", "novel")) {
            assertNull(query, DateSuggestions.term(query, english))
        }
        assertEquals(false, DateSuggestions.term("jan", english)?.complete)
        assertEquals(true, DateSuggestions.term("january", english)?.complete)
    }

    private val hungarianAbbreviations = SearchFilter.foldedMonthAbbreviations(Locale("hu"))

    @Test
    fun `every month is read by its whole name and its usual abbreviation`() {
        for (month in 1..12) {
            val name = hungarian[month - 1]
            assertEquals(name, month, DateSuggestions.term(name, hungarian, hungarianAbbreviations)?.month)
            val short = hungarianAbbreviations[month - 1]
            assertEquals(short, month, DateSuggestions.term(short, hungarian, hungarianAbbreviations)?.month)
        }
        assertNull(DateSuggestions.term("janu", hungarian, hungarianAbbreviations))
        assertNull(DateSuggestions.term("februa", hungarian, hungarianAbbreviations))
        assertEquals(9, DateSuggestions.term("szept", hungarian, hungarianAbbreviations)?.month)
    }

    private val others = DateSuggestions.MONTH_LANGUAGES.flatMap {
        val language = Locale.forLanguageTag(it)
        listOf(SearchFilter.foldedMonthNames(language), SearchFilter.foldedMonthAbbreviations(language))
    }

    @Test
    fun `in every language the app is offered in each month is read by its whole name`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val language = Locale.forLanguageTag(tag)
            val names = SearchFilter.foldedMonthNames(language)
            val short = SearchFilter.foldedMonthAbbreviations(language)
            for (month in 1..12) {
                val term = DateSuggestions.term(names[month - 1], names, short)
                assertEquals("$tag ${names[month - 1]}", month, term?.month)
                assertEquals("$tag ${names[month - 1]}", true, term?.complete)
            }
        }
    }

    @Test
    fun `in every language the app is offered in a month's abbreviation is that month`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val language = Locale.forLanguageTag(tag)
            val names = SearchFilter.foldedMonthNames(language)
            val short = SearchFilter.foldedMonthAbbreviations(language)
            for (month in 1..12) {
                val abbreviation = short[month - 1]
                // A form under three letters is too short to take, and one that two months share names none.
                if (abbreviation.length < 3 || short.count { it == abbreviation } > 1) continue
                assertEquals("$tag $abbreviation", month, DateSuggestions.term(abbreviation, names, short)?.month)
            }
        }
    }

    @Test
    fun `in every language the start of a month name that is no abbreviation is no month`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val language = Locale.forLanguageTag(tag)
            val names = SearchFilter.foldedMonthNames(language)
            val short = SearchFilter.foldedMonthAbbreviations(language)
            for (month in 1..12) {
                val name = names[month - 1]
                val partial = name.take(name.length - 1)
                if (partial.length < 3 || partial == short[month - 1] || partial == name.take(3)) continue
                if (names.any { it == partial } || short.any { it == partial }) continue
                assertEquals("$tag $partial", null, DateSuggestions.term(partial, names, short))
            }
        }
    }

    @Test
    fun `a whole month name of another language is read when the app's own has none by that name`() {
        assertEquals(DateTerm(null, 3, 1), DateSuggestions.term("marcius", english, otherLanguages = others))
        assertEquals(DateTerm(null, 7, 1), DateSuggestions.term("juillet", english, otherLanguages = others))
        assertEquals(DateTerm(null, 7, 1, complete = true), DateSuggestions.term("juil", english, otherLanguages = others))
    }

    @Test
    fun `only the start of a month name of another language, or a short word of one, is no month`() {
        assertNull(DateSuggestions.term("marci", english, otherLanguages = others))
        assertNull(DateSuggestions.term("ago", english, otherLanguages = others))
        assertNull(DateSuggestions.term("set", english, otherLanguages = others))
    }

    @Test
    fun `the languages month names are read in are the ones the app is offered in`() {
        val config = java.io.File("src/main/res/xml/locale_config.xml").readText()
        val offered = Regex("android:name=\"([a-z]+)\"").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(offered, DateSuggestions.MONTH_LANGUAGES.toSet())
    }

    private val phrases: Map<RelativeDate, Set<String>> = mapOf(
        RelativeDate.Today to setOf("today"),
        RelativeDate.Yesterday to setOf("yesterday"),
        RelativeDate.ThisWeek to setOf("this week"),
        RelativeDate.LastWeek to setOf("last week"),
        RelativeDate.ThisMonth to setOf("this month", "ce mois ci"),
        RelativeDate.LastMonth to setOf("last month", "previous month"),
        RelativeDate.ThisYear to setOf("this year"),
        RelativeDate.LastYear to setOf("last year", "lannee derniere"),
    )

    @Test
    fun `a relative phrase at the end of the query is read whole and takes its words`() {
        assertEquals(RelativeDate.LastMonth to 2, DateSuggestions.relative("beach last month", phrases))
        assertEquals(RelativeDate.LastYear to 2, DateSuggestions.relative("Last  Year", phrases))
        assertEquals(RelativeDate.Today to 1, DateSuggestions.relative("dogs today", phrases))
        assertEquals(RelativeDate.ThisMonth to 2, DateSuggestions.relative("ce mois-ci", phrases))
    }

    @Test
    fun `a phrase is read however its apostrophe is typed`() {
        assertEquals(RelativeDate.LastYear to 2, DateSuggestions.relative("l'ann\u00e9e derni\u00e8re", phrases))
        assertEquals(RelativeDate.LastYear to 2, DateSuggestions.relative("l\u2019annee derniere", phrases))
    }

    @Test
    fun `half a phrase or a phrase not at the end is only text`() {
        for (query in listOf("last", "month", "last mont", "last month beach", "lastmonth", "", "  ")) {
            assertNull("'$query'", DateSuggestions.relative(query, phrases))
        }
    }

    private fun day(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, 12, 0, 0) }

    @Test
    fun `today and yesterday are a day, this and last month a month, this and last year a year`() {
        val today = day(2026, 3, 1)
        assertEquals(DateTerm(2026, 3, 1, day = 1), DateSuggestions.resolveRelative(RelativeDate.Today, today, 1))
        assertEquals(DateTerm(2026, 2, 2, day = 28), DateSuggestions.resolveRelative(RelativeDate.Yesterday, today, 2))
        assertEquals(DateTerm(2026, 3, 1), DateSuggestions.resolveRelative(RelativeDate.ThisMonth, today, 1))
        assertEquals(DateTerm(2026, 2, 2), DateSuggestions.resolveRelative(RelativeDate.LastMonth, today, 2))
        assertEquals(DateTerm(2026, null, 2), DateSuggestions.resolveRelative(RelativeDate.ThisYear, today, 2))
        assertEquals(DateTerm(2025, null, 2), DateSuggestions.resolveRelative(RelativeDate.LastYear, today, 2))
    }

    @Test
    fun `last month and yesterday cross the year and a short month without slipping`() {
        assertEquals(DateTerm(2025, 12, 2), DateSuggestions.resolveRelative(RelativeDate.LastMonth, day(2026, 1, 31), 2))
        assertEquals(DateTerm(2026, 2, 2), DateSuggestions.resolveRelative(RelativeDate.LastMonth, day(2026, 3, 31), 2))
        assertEquals(DateTerm(2025, 12, 1, day = 31), DateSuggestions.resolveRelative(RelativeDate.Yesterday, day(2026, 1, 1), 1))
        assertEquals(DateTerm(2023, null, 2), DateSuggestions.resolveRelative(RelativeDate.LastYear, day(2024, 2, 29), 2))
    }

    /** The `search_rel_*` strings of one locale folder, read off the file, variants split on the bar. */
    private fun relativeStrings(folder: String): Map<RelativeDate, Set<String>> {
        val text = java.io.File("src/main/res/$folder/strings.xml").readText()
        val names = mapOf(
            RelativeDate.Today to "search_rel_today",
            RelativeDate.Yesterday to "search_rel_yesterday",
            RelativeDate.ThisWeek to "search_rel_this_week",
            RelativeDate.LastWeek to "search_rel_last_week",
            RelativeDate.ThisMonth to "search_rel_this_month",
            RelativeDate.LastMonth to "search_rel_last_month",
            RelativeDate.ThisYear to "search_rel_this_year",
            RelativeDate.LastYear to "search_rel_last_year",
        )
        return names.mapValues { (_, name) ->
            val value = Regex("<string name=\"$name\">([^<]*)</string>").find(text)?.groupValues?.get(1)
            assertTrue("$folder is missing $name", !value.isNullOrBlank())
            value!!.replace("\\'", "'").split('|').map { DateSuggestions.phraseKey(it) }.filter { it.isNotEmpty() }.toSet()
        }
    }

    @Test
    fun `every language the app is offered in names all eight relative dates, and no phrase names two`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val folder = if (tag == "en") "values" else "values-$tag"
            val byDate = relativeStrings(folder)
            val seen = HashMap<String, RelativeDate>()
            for ((date, set) in byDate) {
                assertTrue("$tag $date has no phrase", set.isNotEmpty())
                for (phrase in set) {
                    val other = seen.put(phrase, date)
                    assertTrue("$tag: '$phrase' names both $other and $date", other == null)
                }
            }
        }
    }

    @Test
    fun `every language's own phrases are read from the end of a query`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val byDate = relativeStrings(if (tag == "en") "values" else "values-$tag")
            for ((date, set) in byDate) {
                for (phrase in set) {
                    assertEquals("$tag '$phrase'", date to phrase.split(' ').size, DateSuggestions.relative("beach $phrase", byDate))
                }
            }
        }
    }

    private fun dayIn(locale: Locale, year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(locale).apply { clear(); set(year, month - 1, day, 12, 0, 0) }

    private fun run(from: LocalDate, to: LocalDate, words: Int) = DateTerm(null, null, words, from = from, to = to)

    @Test
    fun `this and last week are the calendar week, from the first day of the week the language uses`() {
        val monday = dayIn(Locale.GERMANY, 2026, 10, 7)
        assertEquals(
            run(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), 2),
            DateSuggestions.resolveRelative(RelativeDate.ThisWeek, monday, 2),
        )
        assertEquals(
            run(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4), 2),
            DateSuggestions.resolveRelative(RelativeDate.LastWeek, monday, 2),
        )
        val sunday = dayIn(Locale.US, 2026, 10, 7)
        assertEquals(
            run(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10), 2),
            DateSuggestions.resolveRelative(RelativeDate.ThisWeek, sunday, 2),
        )
        assertEquals(
            run(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 3), 2),
            DateSuggestions.resolveRelative(RelativeDate.LastWeek, sunday, 2),
        )
    }

    @Test
    fun `a week that crosses the new year is one run, and the first day of the week starts its own`() {
        val friday = dayIn(Locale.GERMANY, 2026, 1, 2)
        assertEquals(
            run(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4), 2),
            DateSuggestions.resolveRelative(RelativeDate.ThisWeek, friday, 2),
        )
        assertEquals(
            run(LocalDate.of(2025, 12, 22), LocalDate.of(2025, 12, 28), 2),
            DateSuggestions.resolveRelative(RelativeDate.LastWeek, friday, 2),
        )
        val firstDay = dayIn(Locale.GERMANY, 2026, 10, 5)
        assertEquals(
            run(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), 1),
            DateSuggestions.resolveRelative(RelativeDate.ThisWeek, firstDay, 1),
        )
    }

    private val englishCounted = DateSuggestions.countedPatterns(
        mapOf(
            RelativeUnit.Day to setOf("# days ago", "# day ago", "a day ago"),
            RelativeUnit.Week to setOf("# weeks ago", "# week ago", "a week ago"),
            RelativeUnit.Month to setOf("# months ago"),
            RelativeUnit.Year to setOf("# years ago"),
        ),
    )

    @Test
    fun `a counted phrase at the end of the query gives its unit, its number and its words`() {
        assertEquals(Triple(RelativeUnit.Week, 2, 3), DateSuggestions.counted("beach 2 weeks ago", englishCounted))
        assertEquals(Triple(RelativeUnit.Day, 15, 3), DateSuggestions.counted("15 Days  Ago", englishCounted))
        assertEquals(Triple(RelativeUnit.Week, 1, 3), DateSuggestions.counted("a week ago", englishCounted))
        assertEquals(Triple(RelativeUnit.Year, 999, 3), DateSuggestions.counted("999 years ago", englishCounted))
        val french = DateSuggestions.countedPatterns(mapOf(RelativeUnit.Week to setOf("il y a # semaines")))
        assertEquals(Triple(RelativeUnit.Week, 3, 5), DateSuggestions.counted("chien il y a 3 semaines", french))
    }

    @Test
    fun `half a counted phrase, zero, a number too long or a phrase not at the end is only text`() {
        for (query in listOf("2 weeks", "weeks ago", "0 weeks ago", "1000 weeks ago", "2 weeks ago beach", "2weeks ago", "", "ago")) {
            assertNull("'$query'", DateSuggestions.counted(query, englishCounted))
        }
    }

    @Test
    fun `a counted date is that day, or the week, month or year it falls in`() {
        val march = dayIn(Locale.GERMANY, 2026, 3, 1)
        assertEquals(DateTerm(2026, 2, 3, day = 26), DateSuggestions.resolveCounted(RelativeUnit.Day, 3, march, 3))
        assertEquals(DateTerm(2025, 11, 3), DateSuggestions.resolveCounted(RelativeUnit.Month, 4, march, 3))
        assertEquals(DateTerm(2024, null, 3), DateSuggestions.resolveCounted(RelativeUnit.Year, 2, march, 3))
        val wednesday = dayIn(Locale.GERMANY, 2026, 10, 7)
        assertEquals(
            run(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27), 3),
            DateSuggestions.resolveCounted(RelativeUnit.Week, 2, wednesday, 3),
        )
        assertEquals(
            DateSuggestions.resolveRelative(RelativeDate.LastWeek, wednesday, 3),
            DateSuggestions.resolveCounted(RelativeUnit.Week, 1, wednesday, 3),
        )
        assertEquals(
            DateTerm(2025, 12, 3),
            DateSuggestions.resolveCounted(RelativeUnit.Month, 1, dayIn(Locale.GERMANY, 2026, 1, 31), 3),
        )
    }

    @Test
    fun `a term becomes the filter's date, a run replacing the parts and the parts keeping what the filter holds`() {
        val held = ContentFilter(year = 2024, month = 5, day = 3, dateFrom = null)
        val week = run(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27), 2).applyTo(held)
        assertEquals(ContentFilter(dateFrom = LocalDate.of(2026, 9, 21), dateTo = LocalDate.of(2026, 9, 27)), week)
        assertEquals(ContentFilter(year = 2026, month = 3), DateTerm(null, 3, 1).applyTo(ContentFilter(year = 2026, day = 9)))
        assertEquals(ContentFilter(year = 2025), DateTerm(2025, null, 1).applyTo(ContentFilter()))
        assertEquals(ContentFilter(year = 2026, month = 10, day = 7), DateTerm(2026, 10, 1, day = 7).applyTo(week))
    }

    /** The `search_ago_*` strings of one locale folder, read off the file, variants split on the bar. */
    private fun countedStrings(folder: String): Map<RelativeUnit, Set<String>> {
        val text = java.io.File("src/main/res/$folder/strings.xml").readText()
        val names = mapOf(
            RelativeUnit.Day to "search_ago_days",
            RelativeUnit.Week to "search_ago_weeks",
            RelativeUnit.Month to "search_ago_months",
            RelativeUnit.Year to "search_ago_years",
        )
        return names.mapValues { (_, name) ->
            val value = Regex("<string name=\"$name\">([^<]*)</string>").find(text)?.groupValues?.get(1)
            assertTrue("$folder is missing $name", !value.isNullOrBlank())
            value!!.replace("\\'", "'").split('|').map { DateSuggestions.phraseKey(it) }.filter { it.isNotEmpty() }.toSet()
        }
    }

    @Test
    fun `every language names all four counted units, and no phrase names two`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val byUnit = countedStrings(if (tag == "en") "values" else "values-$tag")
            val seen = HashMap<String, RelativeUnit>()
            for ((unit, set) in byUnit) {
                assertTrue("$tag $unit has no counted phrase", set.any { "#" in it })
                for (phrase in set) {
                    val other = seen.put(phrase, unit)
                    assertTrue("$tag: '$phrase' names both $other and $unit", other == null)
                }
            }
        }
    }

    @Test
    fun `every language's own counted phrases are read from the end of a query`() {
        for (tag in DateSuggestions.MONTH_LANGUAGES) {
            val byUnit = countedStrings(if (tag == "en") "values" else "values-$tag")
            val patterns = DateSuggestions.countedPatterns(byUnit)
            for ((unit, set) in byUnit) {
                for (phrase in set) {
                    val typed = phrase.replace("#", "7")
                    assertEquals(
                        "$tag '$typed'",
                        Triple(unit, if ('#' in phrase) 7 else 1, typed.split(' ').size),
                        DateSuggestions.counted("beach $typed", patterns),
                    )
                }
            }
        }
    }

    @Test
    fun `a short, ambiguous or unknown word, or a year out of range, is no term`() {
        for (query in listOf("ju", "ma", "hello", "   ", "", "1234", "20231", "202", "7", "beach")) {
            assertNull("'$query'", DateSuggestions.term(query, english))
        }
    }
}
