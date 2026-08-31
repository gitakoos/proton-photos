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

package eu.akoos.photos.presentation.person

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-value coverage for [personPhotoCount], the shared tally every path that refreshes a person's
 * cached count runs. The count is the de-duplicated union of the photos the person's faces appear in
 * and any manually attached photos, so a manual add on a face's photo does not double-count and a
 * person with manual adds is not under-counted.
 */
class PersonPhotoCountTest {

    @Test
    fun no_manual_photos_counts_the_face_photos() {
        assertEquals(3, personPhotoCount(listOf("a", "b", "c"), emptyList()))
    }

    @Test
    fun disjoint_manual_photos_add_to_the_total() {
        // A person whose manual adds are photos no face was found on: both sets contribute in full.
        assertEquals(5, personPhotoCount(listOf("a", "b", "c"), listOf("d", "e")))
    }

    @Test
    fun a_manual_add_on_a_face_photo_is_not_double_counted() {
        // "b" appears in both lists; the union counts it once.
        assertEquals(3, personPhotoCount(listOf("a", "b"), listOf("b", "c")))
    }

    @Test
    fun repeats_within_either_list_collapse() {
        assertEquals(2, personPhotoCount(listOf("a", "a", "b"), listOf("b", "b")))
    }

    @Test
    fun no_photos_at_all_is_zero() {
        assertEquals(0, personPhotoCount(emptyList(), emptyList()))
    }

    @Test
    fun only_manual_photos_are_counted_when_there_are_no_faces() {
        // A named person built purely from manual adds still reports its photos.
        assertEquals(2, personPhotoCount(emptyList(), listOf("d", "e")))
    }
}
