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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins that a move takes the accepted photos out of the source album and an add leaves every one in place. */
class IdsToDropFromSourceTest {

    @Test
    fun a_move_drops_every_photo_the_target_accepted() {
        assertEquals(listOf("a", "b"), idsToDropFromSource(removeFromSource = true, addedIds = listOf("a", "b")))
    }

    @Test
    fun an_add_drops_nothing() {
        assertEquals(emptyList<String>(), idsToDropFromSource(removeFromSource = false, addedIds = listOf("a", "b")))
    }
}
