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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The placement maths behind putting the background music at an arbitrary point on the edited timeline:
 * [audioBlockRangeMs] turns an offset + the music-file trim into the [start, end] the music actually
 * occupies, capped to the edited length. This one function feeds both the preview gate and the two export
 * paths, so its edge cases (offset 0, offset past the end, a slice longer than the video) are pinned here.
 */
class AudioPlacementTest {

    @Test
    fun `offset 0 places the block flush with the start`() {
        assertEquals(0L..5_000L, audioBlockRangeMs(0L, 0L, 5_000L, 20_000L))
    }

    @Test
    fun `a mid-timeline offset shifts the whole block`() {
        assertEquals(4_000L..9_000L, audioBlockRangeMs(4_000L, 0L, 5_000L, 20_000L))
    }

    @Test
    fun `the block end is capped to the edited total`() {
        // Music slice is 30 s but the video is only 20 s; starting at 4 s it must be cut at the last frame.
        assertEquals(4_000L..20_000L, audioBlockRangeMs(4_000L, 0L, 30_000L, 20_000L))
    }

    @Test
    fun `a trimmed music slice uses its own length`() {
        // Slice = [1 s, 4 s] = 3 s long, placed at 2 s, so it runs 2 s..5 s.
        assertEquals(2_000L..5_000L, audioBlockRangeMs(2_000L, 1_000L, 4_000L, 20_000L))
    }

    @Test
    fun `an offset at the end yields no block`() {
        assertNull(audioBlockRangeMs(20_000L, 0L, 5_000L, 20_000L))
    }

    @Test
    fun `an offset past the end yields no block`() {
        assertNull(audioBlockRangeMs(25_000L, 0L, 5_000L, 20_000L))
    }

    @Test
    fun `a zero-length music slice yields no block`() {
        assertNull(audioBlockRangeMs(3_000L, 5_000L, 5_000L, 20_000L))
    }

    @Test
    fun `a negative offset is treated as the start`() {
        assertEquals(0L..5_000L, audioBlockRangeMs(-500L, 0L, 5_000L, 20_000L))
    }
}
