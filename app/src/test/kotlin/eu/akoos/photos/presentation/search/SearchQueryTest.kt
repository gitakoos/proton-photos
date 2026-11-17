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

package eu.akoos.photos.presentation.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun `straight, curly and angle marks are quotes, an apostrophe is not`() {
        for (q in listOf("\"a", "“a", "a”", "„a", "«a", "a»")) assertTrue(q, SearchQuery.hasQuote(q))
        assertFalse(SearchQuery.hasQuote("l'annee ’s beach, sunset"))
    }

    @Test
    fun `without quotes the terms split on commas then spaces as before`() {
        val q = "Beach  June, , Sunset "
        val before = SearchFilter.fold(q.trim()).split(',')
            .map { part -> part.split(' ').filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
        assertEquals(before, SearchQuery.alternatives(q))
        assertEquals(listOf(listOf("beach", "june"), listOf("sunset")), before)
    }

    @Test
    fun `a quoted span is one folded term, commas and spaces kept`() {
        assertEquals(
            listOf(listOf("new york, ny", "june"), listOf("cafe")),
            SearchQuery.alternatives("“New York, NY” june, Café"),
        )
        assertEquals(listOf(listOf("beach", "summer party")), SearchQuery.alternatives("beach \"Summer party"))
        assertTrue(SearchQuery.alternatives("\"\" , « »").isEmpty())
    }

    @Test
    fun `pills read only the words after a closed quote`() {
        assertEquals("beach june", SearchQuery.pillText("beach june"))
        assertEquals(" 2023", SearchQuery.pillText("\"beach june\" 2023"))
        assertEquals("", SearchQuery.pillText("\"beach june 2023"))
        assertEquals(" 2023", SearchQuery.pillText("\"beach\"june 2023"))
    }

    @Test
    fun `stripping removes only the quote marks`() {
        assertEquals("Anna's trip", SearchQuery.stripped("“Anna's trip”"))
    }
}
