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

package eu.akoos.photos.presentation.settings

import eu.akoos.photos.data.db.entity.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-value coverage for [namedPeopleCount], the figure the AI panel shows above the face tiles. It
 * must equal the set of tiles rendered next to it: named clusters only, never the Unsorted bucket and
 * never an unnamed cluster, so the header and the row cannot disagree.
 */
class PeopleFoundCountTest {

    private fun person(
        name: String? = null,
        faceCount: Int = 0,
        isOther: Boolean = false,
    ) = PersonEntity(userId = "u", displayName = name, faceCount = faceCount, isOther = isOther)

    @Test
    fun counts_only_named_clusters() {
        val people = listOf(
            person(name = "Alice", faceCount = 12),
            person(name = "Bob", faceCount = 3),
            person(name = null, faceCount = 8),
        )
        assertEquals(2, namedPeopleCount(people))
    }

    @Test
    fun excludes_the_unsorted_bucket_even_though_it_carries_faces() {
        // The Unsorted bucket is unnamed and marked isOther; the old header wrongly counted it because
        // it holds two or more faces. Both the null name and the isOther flag keep it out.
        val people = listOf(
            person(name = "Alice", faceCount = 5),
            person(name = null, faceCount = 40, isOther = true),
        )
        assertEquals(1, namedPeopleCount(people))
    }

    @Test
    fun an_isOther_bucket_is_excluded_even_if_it_somehow_carries_a_name() {
        // The flag alone disqualifies it, independent of the name.
        val people = listOf(
            person(name = "Alice", faceCount = 5),
            person(name = "Unsorted", faceCount = 40, isOther = true),
        )
        assertEquals(1, namedPeopleCount(people))
    }

    @Test
    fun unnamed_clusters_do_not_count_however_many_faces_they_hold() {
        val people = listOf(
            person(name = null, faceCount = 2),
            person(name = null, faceCount = 99),
        )
        assertEquals(0, namedPeopleCount(people))
    }

    @Test
    fun a_blank_name_is_not_a_named_person() {
        val people = listOf(
            person(name = "   ", faceCount = 6),
            person(name = "Cara", faceCount = 6),
        )
        assertEquals(1, namedPeopleCount(people))
    }

    @Test
    fun no_people_is_zero() {
        assertEquals(0, namedPeopleCount(emptyList()))
    }
}
