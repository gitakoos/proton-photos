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

class SnackbarClearanceTest {

    @Test
    fun `no bar on screen keeps the resting spot`() {
        assertEquals(120, snackbarBottomPaddingPx(restingPx = 120, obstaclePx = 0, gapPx = 20))
    }

    @Test
    fun `a bar reaching above the resting spot lifts the snackbar over it with the gap`() {
        // The viewer's controls reach 400 px up; the undo bar must clear them, not sit on Delete.
        assertEquals(420, snackbarBottomPaddingPx(restingPx = 120, obstaclePx = 400, gapPx = 20))
    }

    @Test
    fun `a bar lower than the resting spot leaves the snackbar where it was`() {
        assertEquals(300, snackbarBottomPaddingPx(restingPx = 300, obstaclePx = 200, gapPx = 20))
    }

    @Test
    fun `a bar whose gap would just reach the resting spot does not move it`() {
        assertEquals(300, snackbarBottomPaddingPx(restingPx = 300, obstaclePx = 280, gapPx = 20))
    }

    @Test
    fun `bar height is measured from the window bottom up to its top edge`() {
        assertEquals(400, barHeightFromBottom(rootHeightPx = 2400, barTopPx = 2000f))
    }

    @Test
    fun `a bar laid out below the window reaches nothing`() {
        assertEquals(0, barHeightFromBottom(rootHeightPx = 2400, barTopPx = 2600f))
    }

    @Test
    fun `a bar is never taller than the window`() {
        assertEquals(2400, barHeightFromBottom(rootHeightPx = 2400, barTopPx = -50f))
    }

    @Test
    fun `the tallest reported bar wins and a cleared bar stops counting`() {
        val clearance = SnackbarClearance()
        assertEquals(0, clearance.obstaclePx)
        val dock = Any()
        val drawer = Any()
        clearance.report(dock, 200)
        clearance.report(drawer, 500)
        assertEquals(500, clearance.obstaclePx)
        clearance.clear(drawer)
        assertEquals(200, clearance.obstaclePx)
        clearance.clear(dock)
        assertEquals(0, clearance.obstaclePx)
    }
}
