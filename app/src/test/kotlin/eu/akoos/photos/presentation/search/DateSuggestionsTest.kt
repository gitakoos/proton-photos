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

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateSuggestionsTest {

    private val english = SearchFilter.foldedMonthNames(Locale.ENGLISH)
    private val hungarian = SearchFilter.foldedMonthNames(Locale("hu"))
    private val years = listOf(2024, 2023, 2021)

    @Test
    fun `a month name offers that month of every year the library has`() {
        val result = DateSuggestions.suggest("june", english, years)
        assertEquals(years.map { DateSuggestion(it, 6, 1) }, result)
    }

    @Test
    fun `a month and a year offer exactly that month and take both words`() {
        val result = DateSuggestions.suggest("beach jun 2023", english, years)
        assertEquals(listOf(DateSuggestion(2023, 6, 2)), result)
    }

    @Test
    fun `a lone year offers the whole year`() {
        assertEquals(listOf(DateSuggestion(2023, null, 1)), DateSuggestions.suggest("2023", english, years))
    }

    @Test
    fun `a few year digits offer the years that start with them`() {
        assertEquals(
            listOf(DateSuggestion(2024, null, 1), DateSuggestion(2023, null, 1), DateSuggestion(2021, null, 1)),
            DateSuggestions.suggest("20", english, years),
        )
        assertEquals(listOf(DateSuggestion(2021, null, 1)), DateSuggestions.suggest("202", english, listOf(2021, 2015)).filter { it.year == 2021 })
    }

    @Test
    fun `a short or unknown word offers nothing`() {
        assertTrue(DateSuggestions.suggest("ju", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("hello", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("   ", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("", english, years).isEmpty())
    }

    @Test
    fun `a year out of range or of odd length offers nothing`() {
        assertTrue(DateSuggestions.suggest("1234", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("20231", english, years).isEmpty())
        assertTrue(DateSuggestions.suggest("7", english, years).isEmpty())
    }

    @Test
    fun `a month with no years in the library offers nothing`() {
        assertTrue(DateSuggestions.suggest("june", english, emptyList()).isEmpty())
    }

    @Test
    fun `month names are read in the given language and without accents`() {
        assertEquals(listOf(DateSuggestion(2024, 6, 1)), DateSuggestions.suggest("jun", hungarian, listOf(2024)))
        assertEquals(listOf(DateSuggestion(2024, 6, 1)), DateSuggestions.suggest("j\u00fan", hungarian, listOf(2024)))
    }

    @Test
    fun `at most six years are offered`() {
        val many = (2010..2024).toList().reversed()
        assertEquals(6, DateSuggestions.suggest("may", english, many).size)
    }
}
