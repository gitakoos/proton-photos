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

/** Coming back to an album keeps a name renamed on its page; another album shows the name it was opened with. */
class AlbumNameOnLoadTest {

    @Test
    fun `the same album keeps the name renamed on its page`() {
        assertEquals("Trip 2", albumNameOnLoad("a1", "Trip 2", "a1", "Trip"))
    }

    @Test
    fun `a first load or another album takes the name it was opened with`() {
        assertEquals("Trip", albumNameOnLoad("", "", "a1", "Trip"))
        assertEquals("Beach", albumNameOnLoad("a1", "Trip 2", "a2", "Beach"))
    }

    @Test
    fun `a blank shown name never hides the opened one`() {
        assertEquals("Trip", albumNameOnLoad("a1", "", "a1", "Trip"))
    }
}
