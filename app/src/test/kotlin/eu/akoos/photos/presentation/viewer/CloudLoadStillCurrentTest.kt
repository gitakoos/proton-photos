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

import eu.akoos.photos.presentation.viewer.PhotoViewerViewModel.ViewerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cloud photo with no thumbnail on Drive waits in Loading until its download lands. That download
 * has to replace the spinner, or the viewer spins forever; a download for a photo swiped past must not.
 */
class CloudLoadStillCurrentTest {

    @Test
    fun `a photo without a thumbnail shows its download`() {
        assertTrue(cloudLoadStillCurrent(ViewerState.Loading("link-a"), "link-a"))
    }

    @Test
    fun `a photo shown through its thumbnail takes its full resolution`() {
        assertTrue(cloudLoadStillCurrent(ViewerState.ShowImage("file://thumb", itemKey = "link-a"), "link-a"))
    }

    @Test
    fun `a download for a photo swiped past is dropped`() {
        assertFalse(cloudLoadStillCurrent(ViewerState.Loading("link-b"), "link-a"))
        assertFalse(cloudLoadStillCurrent(ViewerState.ShowImage("file://thumb", itemKey = "link-b"), "link-a"))
    }

    @Test
    fun `a state with no photo key drops it`() {
        assertFalse(cloudLoadStillCurrent(ViewerState.Loading(), "link-a"))
        assertFalse(cloudLoadStillCurrent(ViewerState.Error("failed"), "link-a"))
    }
}
