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

import eu.akoos.photos.presentation.gallery.PersonUi
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleMatchingTest {

    private fun person(id: Long, name: String?) = PersonUi(personId = id, displayName = name, coverPhotoKey = "k$id")

    private val named = listOf(
        person(1, "Ákos Kovács"),
        person(2, "Anna"),
        person(3, "Zsófi"),
        person(4, null),
        person(5, "akos junior"),
    )

    @Test
    fun `a name matches with diacritics folded both ways, in list order`() {
        assertEquals(listOf(1L, 5L), peopleMatching(named, "akos").map { it.personId })
        assertEquals(listOf(1L, 5L), peopleMatching(named, "  ÁKOS ").map { it.personId })
        assertEquals(listOf(3L), peopleMatching(named, "zsofi").map { it.personId })
    }

    @Test
    fun `a blank query or no match gives nobody`() {
        assertEquals(emptyList<PersonUi>(), peopleMatching(named, ""))
        assertEquals(emptyList<PersonUi>(), peopleMatching(named, "   "))
        assertEquals(emptyList<PersonUi>(), peopleMatching(named, "bela"))
    }

    @Test
    fun `at most the limit is returned`() {
        val many = (1L..30L).map { person(it, "Anna $it") }

        assertEquals(12, peopleMatching(many, "anna").size)
        assertEquals((1L..12L).toList(), peopleMatching(many, "anna").map { it.personId })
        assertEquals(3, peopleMatching(many, "anna", limit = 3).size)
    }
}
