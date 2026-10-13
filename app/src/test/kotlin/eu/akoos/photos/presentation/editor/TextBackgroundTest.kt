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

/** The geometry and colour of a text's background, shared by the live overlay and the full-size save. */
class TextBackgroundTest {

    private fun item(opacity: Float = 0f, background: Int = 0xFF3366CC.toInt(), corner: Float = 0.3f) =
        TextItem(1, "Hi", 0.5f, 0.5f, 0xFFFFFFFF.toInt(), 0.08f, 0f, background, opacity, corner)

    @Test
    fun a_text_has_no_background_until_it_has_opacity() {
        assertFalse(item(opacity = 0f).hasBackground())
        assertTrue(item(opacity = 0.4f).hasBackground())
    }

    @Test
    fun the_opacity_is_the_alpha_of_the_background_colour() {
        assertEquals(0xFF3366CC.toInt(), textBackgroundArgb(item(opacity = 1f)))
        assertEquals(0x803366CC.toInt(), textBackgroundArgb(item(opacity = 0.5f)))
        assertEquals(0x003366CC, textBackgroundArgb(item(opacity = 0f)))
    }

    @Test
    fun an_opacity_out_of_range_or_not_a_number_is_held_to_it() {
        assertEquals(0xFF3366CC.toInt(), textBackgroundArgb(item(opacity = 4f)))
        assertEquals(0x003366CC, textBackgroundArgb(item(opacity = -1f)))
        assertEquals(0x003366CC, textBackgroundArgb(item(opacity = Float.NaN)))
    }

    @Test
    fun the_box_keeps_its_room_round_the_lines() {
        val b = textBackdrop(fontPx = 100f, widestLinePx = 300f, lineCount = 2, cornerRadius = 0f)
        assertEquals(150f + 100f * TEXT_BACKDROP_PAD_X, b.halfWidth, 1e-4f)
        assertEquals(120f + 100f * TEXT_BACKDROP_PAD_Y, b.halfHeight, 1e-4f)
        assertEquals(0f, b.radius, 0f)
    }

    @Test
    fun a_corner_runs_from_square_to_fully_rounded_ends() {
        val square = textBackdrop(100f, 300f, 1, 0f)
        val round = textBackdrop(100f, 300f, 1, 1f)
        assertEquals(0f, square.radius, 0f)
        assertEquals(round.halfHeight, round.radius, 1e-4f)
        assertEquals(round.halfHeight / 2f, textBackdrop(100f, 300f, 1, 0.5f).radius, 1e-4f)
    }

    @Test
    fun a_corner_out_of_range_or_not_a_number_is_held_to_it() {
        val round = textBackdrop(100f, 300f, 1, 1f)
        assertEquals(round.radius, textBackdrop(100f, 300f, 1, 7f).radius, 1e-4f)
        assertEquals(0f, textBackdrop(100f, 300f, 1, -3f).radius, 0f)
        assertEquals(0f, textBackdrop(100f, 300f, 1, Float.NaN).radius, 0f)
    }

    @Test
    fun a_text_with_no_lines_or_no_width_still_has_a_box() {
        val b = textBackdrop(100f, -5f, 0, 0f)
        assertEquals(100f * TEXT_BACKDROP_PAD_X, b.halfWidth, 1e-4f)
        assertEquals(60f + 100f * TEXT_BACKDROP_PAD_Y, b.halfHeight, 1e-4f)
    }
}
