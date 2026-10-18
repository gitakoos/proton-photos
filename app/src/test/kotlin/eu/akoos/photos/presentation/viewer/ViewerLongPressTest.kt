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
import org.junit.Test

/** Pins what a long press on the photo does under the Settings choice. */
class ViewerLongPressTest {

    private fun action(
        opensMenu: Boolean = false,
        trashMode: Boolean = false,
        copyTextEnabled: Boolean = true,
        textShowing: Boolean = false,
        metadataShown: Boolean = false,
        onControls: Boolean = false,
    ) = viewerLongPressAction(opensMenu, trashMode, copyTextEnabled, textShowing, metadataShown, onControls)

    @Test
    fun `by default the long press reads the text, as it always did`() {
        assertEquals(ViewerLongPressAction.ReadText, action())
    }

    @Test
    fun `with Copy text off the default long press does nothing`() {
        assertEquals(ViewerLongPressAction.None, action(copyTextEnabled = false))
    }

    @Test
    fun `with the choice on the long press opens the menu`() {
        assertEquals(ViewerLongPressAction.OpenMenu, action(opensMenu = true))
    }

    @Test
    fun `the menu opens whether or not Copy text is on`() {
        assertEquals(ViewerLongPressAction.OpenMenu, action(opensMenu = true, copyTextEnabled = false))
    }

    @Test
    fun `trash mode has no menu, so it keeps reading the text`() {
        assertEquals(ViewerLongPressAction.ReadText, action(opensMenu = true, trashMode = true))
        assertEquals(
            ViewerLongPressAction.None,
            action(opensMenu = true, trashMode = true, copyTextEnabled = false),
        )
        assertEquals(ViewerLongPressAction.ReadText, action(trashMode = true))
    }

    @Test
    fun `nothing happens while the recognised text is up`() {
        assertEquals(ViewerLongPressAction.None, action(textShowing = true))
        assertEquals(ViewerLongPressAction.None, action(opensMenu = true, textShowing = true))
    }

    @Test
    fun `nothing happens while the details sheet is open`() {
        assertEquals(ViewerLongPressAction.None, action(metadataShown = true))
        assertEquals(ViewerLongPressAction.None, action(opensMenu = true, metadataShown = true))
    }

    @Test
    fun `a press on a control never opens the menu`() {
        assertEquals(ViewerLongPressAction.None, action(opensMenu = true, onControls = true))
        assertEquals(ViewerLongPressAction.None, action(opensMenu = true, onControls = true, copyTextEnabled = false))
    }

    @Test
    fun `a press on a control leaves the text read as it was`() {
        assertEquals(ViewerLongPressAction.ReadText, action(onControls = true))
        assertEquals(ViewerLongPressAction.ReadText, action(opensMenu = true, trashMode = true, onControls = true))
    }

    @Test
    fun `reading text is turned away for a panorama, a motion clip and a blurred hidden photo`() {
        assertEquals(false, viewerTextReadBlocked(panorama = false, motionClip = false, hiddenBlurred = false))
        assertEquals(true, viewerTextReadBlocked(panorama = true, motionClip = false, hiddenBlurred = false))
        assertEquals(true, viewerTextReadBlocked(panorama = false, motionClip = true, hiddenBlurred = false))
        assertEquals(true, viewerTextReadBlocked(panorama = false, motionClip = false, hiddenBlurred = true))
    }
}
