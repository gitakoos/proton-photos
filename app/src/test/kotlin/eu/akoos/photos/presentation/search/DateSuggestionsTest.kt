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
    fun `every language the app is offered in names all six relative dates, and no phrase names two`() {
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

    @Test
    fun `a short, ambiguous or unknown word, or a year out of range, is no term`() {
        for (query in listOf("ju", "ma", "hello", "   ", "", "1234", "20231", "202", "7", "beach")) {
            assertNull("'$query'", DateSuggestions.term(query, english))
        }
    }
}
