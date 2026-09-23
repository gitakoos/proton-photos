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

package eu.akoos.photos.presentation.memories

import eu.akoos.photos.presentation.gallery.PersonUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins who the Collection's people row shows: the named people, or before anyone is named the groups
 * worth naming, and never the Unsorted bucket, which is a pile of faces to sort rather than a person.
 */
class CollectionPeopleTest {

    private fun person(id: Long, name: String?, faceCount: Int = 5, isOther: Boolean = false) =
        PersonUi(personId = id, displayName = name, coverPhotoKey = "k$id", faceCount = faceCount, isOther = isOther)

    private fun shown(vararg people: PersonUi): List<Long> = collectionPeople(people.toList()).map { it.personId }

    @Test
    fun `the named people are shown in the given order`() {
        assertEquals(listOf(2L, 1L), shown(person(2, "Béla"), person(3, null), person(1, "Anna")))
    }

    @Test
    fun `before anyone is named the groups worth naming are shown`() {
        assertEquals(
            listOf(4L, 5L),
            shown(person(4, null, faceCount = 9), person(5, null, faceCount = 2), person(6, null, faceCount = 1)),
        )
    }

    @Test
    fun `the Unsorted bucket is never shown as a person`() {
        // It has no name and often holds the most faces, so it would otherwise lead the row of groups.
        assertEquals(listOf(4L), shown(person(9, null, faceCount = 40, isOther = true), person(4, null, faceCount = 3)))
        assertEquals(listOf(1L), shown(person(9, null, faceCount = 40, isOther = true), person(1, "Anna")))
        assertEquals(emptyList<Long>(), shown(person(9, null, faceCount = 40, isOther = true)))
    }

    @Test
    fun `an Unsorted bucket carrying a name is still not a person`() {
        assertEquals(listOf(1L), shown(person(9, "Unsorted", isOther = true), person(1, "Anna")))
        assertEquals(emptyList<Long>(), shown(person(9, "Unsorted", isOther = true)))
    }

    @Test
    fun `a blank name is not a named person`() {
        assertEquals(listOf(7L), shown(person(8, "  ", faceCount = 1), person(7, null, faceCount = 3)))
    }

    @Test
    fun `no people shows none`() {
        assertEquals(emptyList<Long>(), shown())
    }
}
