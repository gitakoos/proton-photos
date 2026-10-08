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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What the calendar search matches a typed query against. A day's description is free text the user
 * wrote, so the match must not depend on letter case, accents or the device language: on a Turkish
 * device a plain lower-case would turn the "I" of "Instagram" into a dotless "ı" and the query
 * "instagram" would never find it.
 */
class CalendarSearchTextTest {

    private fun matches(query: String, haystack: String): Boolean =
        calendarSearchTerms(query).let { terms -> terms.isNotEmpty() && terms.all { haystack.contains(it) } }

    private fun haystack(description: String?, monthLabel: String = "June 2024", dayLabel: String = "June 15") =
        calendarSearchHaystack(
            date = "2024-06-15", monthLabel = monthLabel, dayLabel = dayLabel, year = 2024, description = description,
        )

    @Test
    fun `the query is split into folded words and blanks are dropped`() {
        assertEquals(listOf("kizilay", "2024"), calendarSearchTerms("  Kızılay   2024 "))
        assertTrue(calendarSearchTerms("   ").isEmpty())
    }

    @Test
    fun `a description is found whatever the letter case`() {
        assertTrue(matches("instagram", haystack("Instagram story")))
        assertTrue(matches("INSTAGRAM", haystack("instagram story")))
    }

    @Test
    fun `a description is found on a Turkish device too`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertTrue(matches("instagram", haystack("Instagram story")))
            assertTrue(matches("Instagram", haystack("INSTAGRAM story")))
            assertTrue(matches("ışık", haystack("Işık festivali")))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `accents and the dotless i do not decide a match`() {
        assertTrue(matches("kizilay", haystack("Kızılay buluşması")))
        assertTrue(matches("Kızılay", haystack("kizilay meeting")))
        assertTrue(matches("junius", haystack(null, monthLabel = "június 2024")))
    }

    @Test
    fun `a Turkish month label is found by its plain spelling`() {
        assertTrue(matches("kasim 2024", haystack(null, monthLabel = "Kasım 2024", dayLabel = "Kasım 15")))
    }

    @Test
    fun `every word has to match and the ISO date and year are searchable`() {
        assertTrue(matches("2024-06 istanbul", haystack("İstanbul trip")))
        assertFalse(matches("istanbul ankara", haystack("İstanbul trip")))
        assertTrue(matches("2024", haystack(null)))
    }
}
