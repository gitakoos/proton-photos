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

package eu.akoos.photos.presentation.people

import eu.akoos.photos.presentation.gallery.PersonUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the person picker's name matching: the grid narrows by name ignoring case and accents, and typed
 * text that already names someone commits to that person instead of creating a second one of the name.
 */
class PersonPickerSearchTest {

    private fun person(id: Long, name: String?, photos: Int = 1, isOther: Boolean = false) =
        PersonUi(personId = id, displayName = name, coverPhotoKey = "k$id", faceCount = photos, isOther = isOther)

    private val people = listOf(person(1, "Ákos"), person(2, "Anna"), person(3, "José"), person(4, null))

    private fun filtered(query: String) = PersonPickerSearch.filter(people, query).map { it.personId }

    private fun named(typed: String, list: List<PersonUi> = people) =
        PersonPickerSearch.personNamed(list, typed)?.personId

    // region narrowing the grid

    @Test
    fun `a blank query keeps everyone in order`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), filtered(""))
        assertEquals(listOf(1L, 2L, 3L, 4L), filtered("   "))
    }

    @Test
    fun `an unaccented query finds an accented name`() {
        assertEquals(listOf(1L), filtered("akos"))
        assertEquals(listOf(3L), filtered("jose"))
    }

    @Test
    fun `an accented query finds the name too`() {
        assertEquals(listOf(1L), filtered("ÁKOS"))
        assertEquals(listOf(3L), filtered("josé"))
    }

    @Test
    fun `case does not matter and a part of the name is enough`() {
        assertEquals(listOf(2L), filtered("ANN"))
        assertEquals(listOf(1L), filtered("kO"))
    }

    @Test
    fun `surrounding spaces in the query are ignored`() {
        assertEquals(listOf(2L), filtered("  anna "))
    }

    @Test
    fun `a query matching no one keeps no one`() {
        assertEquals(emptyList<Long>(), filtered("zoltan"))
    }

    // endregion
    // region typed text naming someone already

    @Test
    fun `the exact name is that person`() {
        assertEquals(2L, named("Anna"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(2L, named("anna"))
        assertEquals(2L, named("ANNA"))
    }

    @Test
    fun `accents do not matter either way`() {
        assertEquals(1L, named("akos"))
        assertEquals(1L, named("AKOS"))
        assertEquals(3L, named("jose"))
        val plain = listOf(person(5, "Jose"))
        assertEquals(5L, named("José", plain))
    }

    @Test
    fun `surrounding spaces are trimmed on both sides`() {
        assertEquals(2L, named("  Anna  "))
        val padded = listOf(person(6, " Béla "))
        assertEquals(6L, named("Béla", padded))
        assertEquals(6L, named("bela", padded))
    }

    @Test
    fun `a name nobody has is no one`() {
        assertNull(named("Zoltán"))
    }

    @Test
    fun `a part of a name is not a match`() {
        assertNull(named("Ann"))
        assertNull(named("ako"))
    }

    @Test
    fun `blank text is no one`() {
        assertNull(named(""))
        assertNull(named("   "))
    }

    @Test
    fun `the most photographed wins when several share the exact name`() {
        val list = listOf(person(10, "Anna", photos = 3), person(11, "Anna", photos = 40), person(12, "Anna", photos = 7))
        assertEquals(11L, named("Anna", list))
    }

    @Test
    fun `an equal photo count keeps the first in the list`() {
        val list = listOf(person(13, "Anna", photos = 4), person(14, "Anna", photos = 4))
        assertEquals(13L, named("Anna", list))
    }

    @Test
    fun `the exact spelling wins over a name that only matches without accents`() {
        val list = listOf(person(20, "Ákos", photos = 90), person(21, "Akos", photos = 2))
        assertEquals(21L, named("Akos", list))
        assertEquals(20L, named("Ákos", list))
    }

    @Test
    fun `among names that match only without case and accents the most photographed wins`() {
        val list = listOf(person(30, "Ákos", photos = 5), person(31, "ÁKOS", photos = 12))
        assertEquals(31L, named("akos", list))
    }

    @Test
    fun `the Unsorted bucket and unnamed people never match`() {
        val list = listOf(person(40, null, isOther = true), person(41, " "), person(42, "Unsorted", isOther = true))
        assertNull(named("Unsorted", list))
        assertNull(named(" ", list))
    }

    // endregion
}
