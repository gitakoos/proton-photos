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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins what a tap on a photo means while the recognised words are up.
 *
 * Two intents ride on one gesture, and the whole point of splitting them is that the split is made
 * on what is actually picked out rather than on how many taps have landed. A rule that counted taps
 * would read the same on the happy path and would still leave the first tap dead on a photo the user
 * selected nothing in, which is the failure these cases exist to keep out.
 */
class ViewerTextTapTest {

    @Test
    fun `a tap with something picked out puts the pick down`() {
        assertEquals(ViewerTextTap.ClearSelection, viewerTextTap(selectionActive = true))
    }

    @Test
    fun `a tap with nothing picked out leaves text mode`() {
        assertEquals(ViewerTextTap.LeaveTextMode, viewerTextTap(selectionActive = false))
    }

    @Test
    fun `the first tap on a photo nothing was picked out of still leaves`() {
        // The tap that follows a photo being read, with no long press in between: the reader never
        // picked anything, so the gesture is not a clear and must not be spent on one.
        val selection = ViewerTextSelection()
        assertFalse(selection.active)
        assertEquals(ViewerTextTap.LeaveTextMode, viewerTextTap(selection.active))
    }

    @Test
    fun `a pick and then a drop takes two taps, in that order`() {
        val selection = ViewerTextSelection()
        // The platform puts its selection up, and the layer reports it.
        selection.active = true
        assertEquals(ViewerTextTap.ClearSelection, viewerTextTap(selection.active))
        // Dropping it is what the first tap asks for, so the second tap is the one that leaves.
        selection.active = false
        assertEquals(ViewerTextTap.LeaveTextMode, viewerTextTap(selection.active))
    }
}
