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

/** Pins what a double tap on a video does and where its seek lands. */
class VideoDoubleTapTest {

    @Test
    fun `the left third seeks back, the right third forward, the middle zooms`() {
        assertEquals(VideoDoubleTap.Back, videoDoubleTap(x = 50f, width = 900f, scale = 1f))
        assertEquals(VideoDoubleTap.Zoom, videoDoubleTap(x = 450f, width = 900f, scale = 1f))
        assertEquals(VideoDoubleTap.Forward, videoDoubleTap(x = 850f, width = 900f, scale = 1f))
    }

    @Test
    fun `zoomed in, a double tap anywhere zooms back out`() {
        assertEquals(VideoDoubleTap.Zoom, videoDoubleTap(x = 50f, width = 900f, scale = 2.5f))
        assertEquals(VideoDoubleTap.Zoom, videoDoubleTap(x = 850f, width = 900f, scale = 2.5f))
    }

    @Test
    fun `a viewer not measured yet only zooms`() {
        assertEquals(VideoDoubleTap.Zoom, videoDoubleTap(x = 0f, width = 0f, scale = 1f))
    }

    @Test
    fun `a seek stays inside the clip`() {
        assertEquals(15_000L, videoSeekTarget(positionMs = 5_000L, deltaMs = 10_000L, durationMs = 60_000L))
        assertEquals(0L, videoSeekTarget(positionMs = 4_000L, deltaMs = -10_000L, durationMs = 60_000L))
        assertEquals(60_000L, videoSeekTarget(positionMs = 55_000L, deltaMs = 10_000L, durationMs = 60_000L))
    }

    @Test
    fun `a clip whose length is not known yet is only kept above zero`() {
        assertEquals(30_000L, videoSeekTarget(positionMs = 20_000L, deltaMs = 10_000L, durationMs = -1L))
    }

    @Test
    fun `taps on the side whose label shows add up, the other side starts over`() {
        val first = nextVideoSeekFlash(null, forward = true)
        assertEquals(10, first.seconds)
        val second = nextVideoSeekFlash(first, forward = true)
        assertEquals(20, second.seconds)
        assertEquals(first.tick + 1, second.tick)
        val back = nextVideoSeekFlash(second, forward = false)
        assertEquals(10, back.seconds)
        assertEquals(false, back.forward)
    }
}
