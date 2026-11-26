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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the square a face cover is cut to, so the same face in the same photo gives the same circle as on
 * the desktop. Plain JVM assertions, no Android.
 */
class FaceSquareTest {

    // region away from the edges

    @Test
    fun `a centred face gets the full margin, centred on it`() {
        assertEquals(FaceSquare(left = 380, top = 380, size = 140), faceSquare(FaceBox(0.4f, 0.4f, 0.5f, 0.5f), 1000, 1000))
    }

    @Test
    fun `a face near an edge with room for the full margin keeps it and stays centred`() {
        assertEquals(FaceSquare(left = 30, top = 380, size = 140), faceSquare(FaceBox(0.05f, 0.4f, 0.15f, 0.5f), 1000, 1000))
    }

    @Test
    fun `a face near an edge is zoomed in on so it stays centred`() {
        assertEquals(FaceSquare(left = 0, top = 385, size = 130), faceSquare(FaceBox(0.015f, 0.4f, 0.115f, 0.5f), 1000, 1000))
    }

    // endregion
    // region touching an edge

    @Test
    fun `a face on the left edge is zoomed in to the tightest margin, inside the picture`() {
        assertEquals(FaceSquare(left = 0, top = 440, size = 120), faceSquare(FaceBox(0f, 0.45f, 0.1f, 0.55f), 1000, 1000))
    }

    @Test
    fun `a face on the right edge is zoomed in to the tightest margin, inside the picture`() {
        assertEquals(FaceSquare(left = 880, top = 440, size = 120), faceSquare(FaceBox(0.9f, 0.45f, 1f, 0.55f), 1000, 1000))
    }

    @Test
    fun `a face on the top edge is zoomed in to the tightest margin, inside the picture`() {
        assertEquals(FaceSquare(left = 440, top = 0, size = 120), faceSquare(FaceBox(0.45f, 0f, 0.55f, 0.1f), 1000, 1000))
    }

    @Test
    fun `a face on the bottom edge is zoomed in to the tightest margin, inside the picture`() {
        assertEquals(FaceSquare(left = 440, top = 880, size = 120), faceSquare(FaceBox(0.45f, 0.9f, 0.55f, 1f), 1000, 1000))
    }

    // endregion
    // region in a corner

    @Test
    fun `a face in the top left corner is cut from the corner`() {
        assertEquals(FaceSquare(left = 0, top = 0, size = 120), faceSquare(FaceBox(0f, 0f, 0.1f, 0.1f), 1000, 500))
    }

    @Test
    fun `a face in the top right corner is cut from the corner`() {
        assertEquals(FaceSquare(left = 880, top = 0, size = 120), faceSquare(FaceBox(0.9f, 0f, 1f, 0.1f), 1000, 500))
    }

    @Test
    fun `a face in the bottom left corner is cut from the corner`() {
        assertEquals(FaceSquare(left = 0, top = 380, size = 120), faceSquare(FaceBox(0f, 0.9f, 0.1f, 1f), 1000, 500))
    }

    @Test
    fun `a face in the bottom right corner is cut from the corner`() {
        assertEquals(FaceSquare(left = 940, top = 440, size = 60), faceSquare(FaceBox(0.95f, 0.9f, 1f, 1f), 1000, 500))
    }

    // endregion
    // region larger than the zoom range

    @Test
    fun `a face filling the picture is cut to the picture's shorter side, centred`() {
        assertEquals(FaceSquare(left = 200, top = 0, size = 800), faceSquare(FaceBox(0f, 0f, 1f, 1f), 1200, 800))
    }

    @Test
    fun `a face whose tightest margin overflows the picture is cut to the whole picture`() {
        assertEquals(FaceSquare(left = 0, top = 0, size = 1000), faceSquare(FaceBox(0.05f, 0.05f, 0.95f, 0.95f), 1000, 1000))
    }

    @Test
    fun `a face box running past the picture's edge is cut inside the picture`() {
        assertEquals(FaceSquare(left = 760, top = 380, size = 240), faceSquare(FaceBox(0.9f, 0.4f, 1.1f, 0.6f), 1000, 1000))
    }

    // endregion
    // region tiny photos

    @Test
    fun `a few-pixel photo still gets a whole-pixel square inside it`() {
        assertEquals(FaceSquare(left = 1, top = 0, size = 3), faceSquare(FaceBox(0.25f, 0.125f, 0.5f, 0.625f), 6, 4))
    }

    @Test
    fun `a one-pixel photo gets a one-pixel square`() {
        assertEquals(FaceSquare(left = 0, top = 0, size = 1), faceSquare(FaceBox(0.4f, 0.4f, 0.6f, 0.6f), 1, 1))
    }

    // endregion
}
