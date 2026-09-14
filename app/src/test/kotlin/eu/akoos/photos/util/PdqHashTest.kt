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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DCT fingerprint the duplicate finder is moving to: identical photos match, a uniform
 * brightness shift leaves the structure untouched (the DC term is dropped), two structurally different
 * photos stay apart, a near-flat frame is dropped instead of matching every other flat frame (the old
 * difference hash's blind spot), and two shots that share a layout but not a palette are rejected by the
 * colour gate. All off-device, over hand-built pixel arrays.
 */
class PdqHashTest {

    private val W = 128
    private val H = 128

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun grey(v: Int): Int = argb(v, v, v)

    private fun build(px: (x: Int, y: Int) -> Int): IntArray {
        val out = IntArray(W * H)
        var i = 0
        for (y in 0 until H) for (x in 0 until W) out[i++] = px(x, y)
        return out
    }

    private fun solid(color: Int) = build { _, _ -> color }
    private fun leftRight(dark: Int, bright: Int) = build { x, _ -> if (x < W / 2) dark else bright }
    private fun topBottom(dark: Int, bright: Int) = build { _, y -> if (y < H / 2) dark else bright }

    // A varied, detailed field kept in a mid-range [15, 175] so a small brightness shift never clamps:
    // a ramp for low-frequency structure plus a checkerboard for edges the quality score can read.
    private fun textured(tint: (v: Int) -> Int) = build { x, y ->
        val ramp = (x * 80 / W) + (y * 40 / H)
        val checker = if ((x / 12 + y / 12) % 2 == 0) 40 else 0
        tint((15 + ramp + checker).coerceIn(0, 255))
    }

    private fun fp(px: IntArray) = PdqHash.fingerprintFromPixels(px, W, H)

    @Test
    fun the_same_image_fingerprints_identically() {
        val a = fp(textured { grey(it) })
        val b = fp(textured { grey(it) })
        assertEquals("identical structure", 0, PdqHash.distance(a.bits, b.bits))
        assertEquals("identical colour", 0, PdqHash.colorDistance(a.color, b.color))
        assertTrue(PdqHash.matches(a, b))
    }

    @Test
    fun a_uniform_brightness_shift_leaves_the_structure_untouched() {
        // Dropping the DC coefficient makes the hash blind to overall brightness, so a re-exposed copy
        // still matches its original.
        val a = fp(textured { grey(it) })
        val b = fp(textured { grey((it + 30).coerceAtMost(255)) })
        assertTrue("a flat brightness shift barely moves the hash", PdqHash.distance(a.bits, b.bits) <= 4)
    }

    @Test
    fun a_near_flat_frame_is_dropped_as_too_low_quality() {
        // The difference hash grouped every flat frame together (black == white == grey). Here each is
        // simply discarded, so the finder never offers them as duplicates of one another.
        assertFalse("black is unusable", PdqHash.isUsable(fp(solid(grey(0)))))
        assertFalse("white is unusable", PdqHash.isUsable(fp(solid(grey(255)))))
        assertFalse("mid grey is unusable", PdqHash.isUsable(fp(solid(grey(128)))))
    }

    @Test
    fun a_detailed_frame_is_usable() {
        assertTrue("a textured photo carries plenty of detail", PdqHash.isUsable(fp(textured { grey(it) })))
    }

    @Test
    fun two_structurally_different_photos_are_not_matched() {
        // A vertical edge and a horizontal edge, same palette: only the structure separates them.
        val a = fp(leftRight(grey(40), grey(210)))
        val b = fp(topBottom(grey(40), grey(210)))
        assertTrue("orthogonal structure is far apart", PdqHash.distance(a.bits, b.bits) > PdqHash.MATCH_THRESHOLD)
        assertFalse(PdqHash.matches(a, b))
    }

    @Test
    fun the_colour_grid_captures_palette() {
        // Same textured structure, one tinted red and one blue: the colour signatures are far apart.
        val red = fp(textured { argb(it, 0, 0) })
        val blue = fp(textured { argb(0, 0, it) })
        assertTrue(
            "a red and a blue version have very different colour grids",
            PdqHash.colorDistance(red.color, blue.color) > PdqHash.MAX_COLOR_DISTANCE,
        )
    }

    @Test
    fun the_colour_gate_rejects_a_structural_match_with_a_different_palette() {
        val bits = longArrayOf(0x0123L, 0x4567L, 0x89ABL, 0xCDEFL)
        val redGrid = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 0) 255.toByte() else 0 }
        val blueGrid = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 2) 255.toByte() else 0 }
        val closeToRed = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 0) 245.toByte() else 10 }

        val a = PdqHash.Fingerprint(bits, quality = 80, color = redGrid)
        val differentPalette = PdqHash.Fingerprint(bits.copyOf(), quality = 80, color = blueGrid)
        val samePalette = PdqHash.Fingerprint(bits.copyOf(), quality = 80, color = closeToRed)

        assertEquals("structure is identical", 0, PdqHash.distance(a.bits, differentPalette.bits))
        assertFalse("but a different palette is rejected", PdqHash.matches(a, differentPalette))
        assertTrue("the same palette passes", PdqHash.matches(a, samePalette))
    }

    @Test
    fun color_distance_is_zero_for_identical_grids_and_large_for_opposite_ones() {
        val red = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 0) 255.toByte() else 0 }
        val blue = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 2) 255.toByte() else 0 }
        assertEquals(0, PdqHash.colorDistance(red, red.copyOf()))
        assertTrue(PdqHash.colorDistance(red, blue) > PdqHash.MAX_COLOR_DISTANCE)
    }
}
