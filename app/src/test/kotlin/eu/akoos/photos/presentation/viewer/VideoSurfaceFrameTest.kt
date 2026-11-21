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

package eu.akoos.photos.presentation.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins when the video surface asks for one more frame after a layout. */
class VideoSurfaceFrameTest {

    @Test
    fun `a surface laid out from zero to a real size asks for a frame`() {
        assertTrue(surfaceGainedSize(oldWidth = 0, oldHeight = 0, newWidth = 1080, newHeight = 2340))
    }

    @Test
    fun `a surface resized to another real size asks for a frame`() {
        assertTrue(surfaceGainedSize(oldWidth = 1080, oldHeight = 2340, newWidth = 2340, newHeight = 1080))
    }

    @Test
    fun `a layout that keeps the size asks for nothing`() {
        assertFalse(surfaceGainedSize(oldWidth = 1080, oldHeight = 2340, newWidth = 1080, newHeight = 2340))
    }

    @Test
    fun `an empty surface asks for nothing`() {
        assertFalse(surfaceGainedSize(oldWidth = 0, oldHeight = 0, newWidth = 0, newHeight = 0))
        assertFalse(surfaceGainedSize(oldWidth = 1080, oldHeight = 2340, newWidth = 1080, newHeight = 0))
        assertFalse(surfaceGainedSize(oldWidth = 0, oldHeight = 0, newWidth = 0, newHeight = 2340))
    }
}
