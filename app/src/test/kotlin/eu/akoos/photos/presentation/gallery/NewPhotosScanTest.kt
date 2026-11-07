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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package eu.akoos.photos.presentation.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewPhotosScanTest {
    @Test
    fun the_first_look_and_an_empty_library_filling_up_are_not_new_photos() {
        assertFalse(libraryGained(null, LibraryMark(500, "a")))
        assertFalse(libraryGained(LibraryMark(0, null), LibraryMark(500, "a")))
    }

    @Test
    fun more_photos_or_a_new_newest_one_are_new_photos() {
        assertTrue(libraryGained(LibraryMark(500, "a"), LibraryMark(501, "b")))
        assertTrue(libraryGained(LibraryMark(500, "a"), LibraryMark(501, "a")))
        assertTrue(libraryGained(LibraryMark(500, "a"), LibraryMark(500, "b")))
    }

    @Test
    fun a_deletion_or_no_change_is_not_new_photos() {
        assertFalse(libraryGained(LibraryMark(500, "a"), LibraryMark(500, "a")))
        assertFalse(libraryGained(LibraryMark(500, "a"), LibraryMark(499, "a")))
        assertFalse(libraryGained(LibraryMark(500, "a"), LibraryMark(499, "b")))
    }
}
