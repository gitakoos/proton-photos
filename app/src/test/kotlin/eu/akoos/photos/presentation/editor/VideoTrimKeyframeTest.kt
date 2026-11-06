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

/** A lossless trim begins at the keyframe before its in-point, never past it. */
class VideoTrimKeyframeTest {

    @Test
    fun `a trim between keyframes starts at the keyframe before it`() {
        assertEquals(0L, keyframeStartUs(inPointUs = 3_200_000L, keyframeUs = 0L))
        assertEquals(3_000_000L, keyframeStartUs(inPointUs = 3_200_000L, keyframeUs = 3_000_000L))
    }

    @Test
    fun `a trim on a keyframe starts right there`() {
        assertEquals(8_333_000L, keyframeStartUs(inPointUs = 8_333_000L, keyframeUs = 8_333_000L))
    }

    @Test
    fun `a seek that found no keyframe, or one past the in-point, keeps the in-point`() {
        assertEquals(3_200_000L, keyframeStartUs(inPointUs = 3_200_000L, keyframeUs = -1L))
        assertEquals(3_200_000L, keyframeStartUs(inPointUs = 3_200_000L, keyframeUs = 8_333_000L))
    }
}
