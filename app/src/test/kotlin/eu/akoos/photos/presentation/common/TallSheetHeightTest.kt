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

package eu.akoos.photos.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TallSheetHeightTest {

    @Test
    fun `with the keyboard closed the content takes the fraction of the room`() {
        assertEquals(1840, tallSheetHeightPx(maxHeight = 2000, fraction = 0.92f, imeBottom = 0, navBottom = 100))
    }

    @Test
    fun `an open keyboard shrinks the content by what keeps the sheet top still`() {
        // Keyboard 1100 tall over a 100 nav bar: the room drops by 1000, the sheet total stays 1840 + 100.
        val height = tallSheetHeightPx(maxHeight = 1000, fraction = 0.92f, imeBottom = 1100, navBottom = 100)

        assertEquals(840, height)
    }

    @Test
    fun `a keyboard shorter than the navigation bar changes nothing`() {
        assertEquals(1840, tallSheetHeightPx(maxHeight = 2000, fraction = 0.92f, imeBottom = 50, navBottom = 100))
    }

    @Test
    fun `a keyboard taller than the room gives no negative height`() {
        assertEquals(0, tallSheetHeightPx(maxHeight = 10, fraction = 0.92f, imeBottom = 3000, navBottom = 100))
    }

    @Test
    fun `a full fraction fills the room with or without the keyboard`() {
        assertEquals(1000, tallSheetHeightPx(maxHeight = 1000, fraction = 1f, imeBottom = 1100, navBottom = 100))
    }
}
