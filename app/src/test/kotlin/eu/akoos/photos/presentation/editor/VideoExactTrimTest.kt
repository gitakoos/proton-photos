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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A trim saves from the in-point the editor showed: exact when a stream copy would start early. */
class VideoExactTrimTest {

    private val frame30 = frameDurationUs(30f)

    @Test
    fun `an in-point on its keyframe is copied losslessly`() {
        assertTrue(streamCopyStartsOnTime(listOf(0L, 8_000_000L), listOf(0L, 8_000_000L), frame30))
    }

    @Test
    fun `an in-point less than a frame after its keyframe is copied losslessly`() {
        assertTrue(streamCopyStartsOnTime(listOf(8_020_000L), listOf(8_000_000L), frame30))
    }

    @Test
    fun `an in-point a frame or more after its keyframe needs the exact cut`() {
        assertFalse(streamCopyStartsOnTime(listOf(8_000_000L + frame30), listOf(8_000_000L), frame30))
        // The reported case: a 15.9 s selection saved as 21.7 s, its start snapped 5.8 s back.
        assertFalse(streamCopyStartsOnTime(listOf(13_800_000L), listOf(8_000_000L), frame30))
    }

    @Test
    fun `one late window among several needs the exact cut`() {
        assertFalse(
            streamCopyStartsOnTime(listOf(0L, 12_500_000L), listOf(0L, 12_000_000L), frame30),
        )
    }

    @Test
    fun `the frame duration follows the frame rate and falls back to 30 fps`() {
        assertEquals(33_333L, frameDurationUs(30f))
        assertEquals(16_666L, frameDurationUs(60f))
        assertEquals(33_333L, frameDurationUs(null))
        assertEquals(33_333L, frameDurationUs(0f))
        assertEquals(33_333L, frameDurationUs(Float.NaN))
    }

    @Test
    fun `the exact clip starts at the in-point and not at a keyframe`() {
        val clip = exactTrimClipping(VideoSegment(13_800L, 29_700L))

        assertEquals(13_800L, clip.startPositionMs)
        assertEquals(29_700L, clip.endPositionMs)
        assertFalse(clip.startsAtKeyFrame)
    }

    @Test
    fun `the user's turn adds to the track rotation`() {
        assertEquals(90, addedRotation(0, 90))
        assertEquals(0, addedRotation(90, 270))
        assertEquals(180, addedRotation(270, -90))
        assertEquals(90, addedRotation(90, 0))
    }
}
