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

package eu.akoos.photos.presentation.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the text-overlay angle magnet: a rotation within 5 degrees of a 45-degree detent draws at the
 * detent, everything else draws at its raw angle, and the input is normalised so negative and over-360
 * angles behave. Applied only at draw time, so a slow twist keeps accumulating and passes through a detent.
 */
class PhotoEditorTextAngleTest {

    @Test
    fun snapsWithinFiveDegreesOfADetent() {
        assertEquals(0f, snapTextAngle(3f), 1e-4f)
        assertEquals(45f, snapTextAngle(44f), 1e-4f)
        assertEquals(90f, snapTextAngle(92f), 1e-4f)
    }

    @Test
    fun leavesAnglesBetweenDetentsUntouched() {
        assertEquals(20f, snapTextAngle(20f), 1e-4f)
        // 7 degrees from 45: outside the magnet, so a continuing twist passes straight through.
        assertEquals(52f, snapTextAngle(52f), 1e-4f)
    }

    @Test
    fun normalisesNegativeAndOverflowAngles() {
        assertEquals(0f, snapTextAngle(-3f), 1e-4f)   // 357 -> snaps up to 360 -> 0
        assertEquals(10f, snapTextAngle(370f), 1e-4f) // 10, no detent nearby
        assertEquals(0f, snapTextAngle(358f), 1e-4f)  // snaps to 360 -> 0
    }

    @Test
    fun exactDetentsStayExact() {
        assertEquals(0f, snapTextAngle(0f), 1e-4f)
        assertEquals(135f, snapTextAngle(135f), 1e-4f)
        assertEquals(315f, snapTextAngle(315f), 1e-4f)
    }
}
