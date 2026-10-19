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

package eu.akoos.photos.presentation.map

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DarkTileMatrixTest {

    private fun apply(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val m = darkTileMatrix().array
        fun row(i: Int) = m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]
        return Triple(row(0), row(1), row(2))
    }

    @Test
    fun `water stays blue and goes dark`() {
        val (r, g, b) = apply(170, 211, 223)
        assertTrue("blue leads the red of the water", b > r + 20f)
        assertTrue("the water is dark", b < 110f)
    }

    @Test
    fun `vegetation stays green`() {
        val (r, g, b) = apply(173, 209, 158)
        assertTrue("green leads the vegetation", g > r && g > b)
    }

    @Test
    fun `the white of the land becomes dark and the black of the labels light`() {
        val (wr, wg, wb) = apply(255, 255, 255)
        assertTrue(wr < 30f && wg < 30f && wb < 30f)
        val (br, bg, bb) = apply(0, 0, 0)
        assertTrue(br > 225f && bg > 225f && bb > 225f)
    }
}
