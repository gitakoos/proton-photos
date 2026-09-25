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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins when the chrome over a playing video steps aside on its own.
 *
 * Exactly one situation hides it: a video playing under visible chrome with nothing holding it open.
 * Every other case leaves the chrome where the last tap put it, which is what keeps photos, the
 * slideshow and a screen reader's controls exactly as they were.
 */
class ViewerChromeAutoHideTest {

    private fun delayFor(
        chromeVisible: Boolean = true,
        videoPlaying: Boolean = true,
        slideshowRunning: Boolean = false,
        heldOpen: Boolean = false,
        touchExploration: Boolean = false,
    ): Long? = videoChromeAutoHideDelayMs(
        chromeVisible = chromeVisible,
        videoPlaying = videoPlaying,
        slideshowRunning = slideshowRunning,
        heldOpen = heldOpen,
        touchExploration = touchExploration,
    )

    @Test
    fun `a playing video under visible chrome hides it after two and a half seconds`() {
        assertEquals(2_500L, delayFor())
    }

    @Test
    fun `a paused or still loading video leaves the chrome up`() {
        assertNull(delayFor(videoPlaying = false))
    }

    @Test
    fun `the slideshow keeps its own hide`() {
        assertNull(delayFor(slideshowRunning = true))
    }

    @Test
    fun `a finger down or an open menu, sheet or dialog holds the chrome`() {
        assertNull(delayFor(heldOpen = true))
    }

    @Test
    fun `explore by touch never loses the controls it finds by feel`() {
        assertNull(delayFor(touchExploration = true))
    }

    @Test
    fun `chrome that is already down has nothing to hide`() {
        assertNull(delayFor(chromeVisible = false))
    }
}
