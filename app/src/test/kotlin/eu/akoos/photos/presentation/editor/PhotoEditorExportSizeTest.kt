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
 * Pins the export-size maths behind the Full / 2048 / 1024 save choice: the longest edge is capped at
 * the limit, the aspect ratio is preserved, and a photo already within the limit is never upscaled. A
 * wrong scale would export a stretched or needlessly re-encoded image.
 */
class PhotoEditorExportSizeTest {

    @Test
    fun landscapeCapsTheLongestEdge() {
        assertEquals(2048 to 1536, exportTargetSize(4000, 3000, 2048))
    }

    @Test
    fun portraitCapsTheLongestEdge() {
        assertEquals(1536 to 2048, exportTargetSize(3000, 4000, 2048))
    }

    @Test
    fun squareScalesBothEdgesEqually() {
        assertEquals(1024 to 1024, exportTargetSize(4000, 4000, 1024))
    }

    @Test
    fun aSmallerPhotoIsNeverUpscaled() {
        assertEquals(800 to 600, exportTargetSize(800, 600, 2048))
    }

    @Test
    fun anExactFitIsUnchanged() {
        assertEquals(2048 to 1024, exportTargetSize(2048, 1024, 2048))
    }

    @Test
    fun theResultNeverCollapsesToZero() {
        // An extreme panorama whose short edge would round to zero (4 * 1024 / 10000 = 0.41) is floored
        // at one pixel rather than collapsing.
        val (w, h) = exportTargetSize(10000, 4, 1024)
        assertEquals(1024, w)
        assertEquals(1, h)
    }
}
