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

package eu.akoos.photos.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pixel-to-tensor step for the CLIP image encoder: each 8-bit channel is scaled to 0..1 and then
 * normalised by the per-channel mean and standard deviation, laid out channel-planes-first (NCHW). This
 * is the part a real duplicate of the model's preprocessing has to get right, and it is pure, so it runs
 * without a model or a bitmap.
 */
class ClipImageTensorTest {

    private val unit = 1f / 255f

    @Test
    fun `scales to unit then normalises each channel and orders planes R G B`() {
        val argb = (0x40 shl 24) or (0xFF shl 16) or (0x80 shl 8) or 0x00
        val mean = floatArrayOf(0.5f, 0.25f, 0.0f)
        val std = floatArrayOf(2f, 4f, 0.5f)

        val out = fillClipImageTensor(intArrayOf(argb), 1, 1, 3, mean, std)

        assertEquals(3, out.size)
        assertEquals((0xFF * unit - 0.5f) / 2f, out[0], 1e-6f)
        assertEquals((0x80 * unit - 0.25f) / 4f, out[1], 1e-6f)
        assertEquals((0x00 * unit - 0.0f) / 0.5f, out[2], 1e-6f)
    }

    @Test
    fun `fills a whole plane before the next across several pixels`() {
        val p0 = (0xFF shl 16) or (0x10 shl 8) or 0x20
        val p1 = (0x30 shl 16) or (0x40 shl 8) or 0x50
        val mean = floatArrayOf(0f, 0f, 0f)
        val std = floatArrayOf(1f, 1f, 1f)

        val out = fillClipImageTensor(intArrayOf(p0, p1), 2, 1, 3, mean, std)

        assertEquals(6, out.size)
        assertEquals(0xFF * unit, out[0], 1e-6f) // red, pixel 0
        assertEquals(0x30 * unit, out[1], 1e-6f) // red, pixel 1
        assertEquals(0x10 * unit, out[2], 1e-6f) // green, pixel 0
        assertEquals(0x40 * unit, out[3], 1e-6f) // green, pixel 1
        assertEquals(0x20 * unit, out[4], 1e-6f) // blue, pixel 0
        assertEquals(0x50 * unit, out[5], 1e-6f) // blue, pixel 1
    }

    @Test
    fun `adds a fourth alpha plane for an RGBA model`() {
        val argb = (0xC0 shl 24) or (0x10 shl 16) or (0x20 shl 8) or 0x30
        val mean = floatArrayOf(0f, 0f, 0f, 0f)
        val std = floatArrayOf(1f, 1f, 1f, 1f)

        val out = fillClipImageTensor(intArrayOf(argb), 1, 1, 4, mean, std)

        assertEquals(4, out.size)
        assertEquals(0xC0 * unit, out[3], 1e-6f)
    }
}
