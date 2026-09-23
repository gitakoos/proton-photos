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

package eu.akoos.photos.presentation.gallery

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the two rules each multi-select surface wires into the shared selection drawer (#125): what
 * the header's Select all would select, [selectAllCountFor], and how far a grid pads its end so its
 * last row scrolls clear of the resting drawer, [gridBottomPadding]. Plain JVM assertions.
 */
class SelectionSurfaceRulesTest {

    @Test
    fun `Select all offers the surface's whole selectable total`() {
        assertEquals(40, selectAllCountFor(total = 40, selected = 1))
        assertEquals(40, selectAllCountFor(total = 40, selected = 39))
        assertEquals(12_000, selectAllCountFor(total = 12_000, selected = 3))
    }

    @Test
    fun `a selection that already holds everything offers no Select all`() {
        assertNull(selectAllCountFor(total = 1, selected = 1))
        assertNull(selectAllCountFor(total = 40, selected = 40))
    }

    @Test
    fun `a surface with nothing to select offers no Select all`() {
        assertNull(selectAllCountFor(total = 0, selected = 0))
        assertNull(selectAllCountFor(total = 0, selected = 2))
    }

    @Test
    fun `a selection larger than the surface can still be set to the whole surface`() {
        // Photos the surface no longer lists can linger in the selection; Select all trims it to the
        // surface's own photos.
        assertEquals(40, selectAllCountFor(total = 40, selected = 41))
    }

    @Test
    fun `outside selection mode a grid keeps its own bottom padding`() {
        assertEquals(120.dp, gridBottomPadding(120.dp, selecting = false, drawerHeight = 250.dp))
        assertEquals(24.dp, gridBottomPadding(24.dp, selecting = false, drawerHeight = 250.dp))
        assertEquals(12.dp, gridBottomPadding(12.dp, selecting = false, drawerHeight = 250.dp, gridInset = 24.dp))
    }

    @Test
    fun `while selecting a grid pads its end by at least the resting drawer`() {
        // The timeline's dock padding and a detail page's plain end both give way to the drawer.
        assertEquals(250.dp, gridBottomPadding(120.dp, selecting = true, drawerHeight = 250.dp))
        assertEquals(250.dp, gridBottomPadding(24.dp, selecting = true, drawerHeight = 250.dp))
    }

    @Test
    fun `a padding already taller than the drawer stays as it is`() {
        assertEquals(300.dp, gridBottomPadding(300.dp, selecting = true, drawerHeight = 250.dp))
        assertEquals(250.dp, gridBottomPadding(250.dp, selecting = true, drawerHeight = 250.dp))
    }

    @Test
    fun `a grid that already clears the navigation bar counts that inset once`() {
        // A 205dp resting drawer over a 48dp navigation bar, which the drawer's height includes,
        // beside a grid that its own modifier already lifts above that bar.
        assertEquals(
            205.dp,
            gridBottomPadding(12.dp, selecting = true, drawerHeight = 253.dp, gridInset = 48.dp),
        )
    }
}
