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

import android.media.MediaMuxer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route and the location of an edit that re-encodes: an overlay or crop save, not a plain trim. */
class VideoEditMetadataTest {

    @Test
    fun `an overlay edit starting on a keyframe keeps the lossless video copy`() {
        assertFalse(reencodeRendersFrames(needsGl = false, multiWindow = false) { true })
    }

    @Test
    fun `an overlay edit starting between keyframes renders its frames for the exact start`() {
        assertTrue(reencodeRendersFrames(needsGl = false, multiWindow = false) { false })
    }

    @Test
    fun `a crop, colour or cut edit renders its frames without probing the source`() {
        var probed = false
        assertTrue(reencodeRendersFrames(needsGl = true, multiWindow = false) { probed = true; true })
        assertTrue(reencodeRendersFrames(needsGl = false, multiWindow = true) { probed = true; true })
        assertFalse(probed)
    }

    @Test
    fun `a re-encoded edit is written with the source location`() {
        val muxer = mockk<MediaMuxer>(relaxed = true)

        writeMuxerLocation(muxer, 47.4979 to 19.0402)

        verify(exactly = 1) { muxer.setLocation(47.4979f, 19.0402f) }
    }

    @Test
    fun `a source without a location writes none`() {
        val muxer = mockk<MediaMuxer>(relaxed = true)

        writeMuxerLocation(muxer, null)

        verify(exactly = 0) { muxer.setLocation(any(), any()) }
    }

    @Test
    fun `a location the muxer refuses does not fail the save`() {
        val muxer = mockk<MediaMuxer>(relaxed = true)
        every { muxer.setLocation(any(), any()) } throws IllegalArgumentException("out of range")

        writeMuxerLocation(muxer, 95.0 to 19.0)
    }
}
