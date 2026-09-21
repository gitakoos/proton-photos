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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-to-end check of the fingerprint on image-like inputs and the transforms a real duplicate survives,
 * run through the actual [PdqHash] pipeline (Android's ImageIO/AWT is not on the unit-test classpath, so
 * the transforms are done here on the pixel buffer): a photo-like scene stays a match after a re-encode
 * proxy (blur + noise, the low-frequency-preserving softening JPEG does), a resize, and an exposure lift,
 * while a different scene and a heavy same-scene recolour are told apart, and a flat frame is dropped.
 */
class PdqHashImageTest {

    private val W = 400
    private val H = 300

    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun fp(px: IntArray, w: Int = W, h: Int = H): PdqHash.Fingerprint =
        PdqHash.fingerprintFromPixels(px, w, h)

    /** Two deterministic photo-like scenes that are genuinely different photos, each with real
     *  low-frequency structure plus a fine texture so both clear the quality gate. */
    private fun scene(variant: Int): IntArray {
        val out = IntArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                var r: Int; var g: Int; var b: Int
                if (variant == 0) {
                    val sky = y < H / 2
                    r = if (sky) 60 + y / 3 else 40 + (H - y) / 6
                    g = if (sky) 110 + y / 4 else 120 - (y - H / 2) / 8
                    b = if (sky) 200 - y / 5 else 70 + (H - y) / 10
                    if (x in 120 until 210 && y in 90 until 210) { r = 220; g = 200; b = 150 }
                    val t = (sin(x * 0.20) * sin(y * 0.18) * 22).toInt()
                    r += t; g += t; b += t
                } else {
                    r = 150 + x / 4
                    g = 40 + x / 8
                    b = 90 - x / 12
                    if ((x - 280) * (x - 280) + (y - 120) * (y - 120) < 70 * 70) { r = 30; g = 30; b = 40 }
                    val t = (sin(x * 0.08) * sin(y * 0.33) * 26).toInt()
                    r += t; g += t; b += t
                }
                out[y * W + x] = packRgb(r, g, b)
            }
        }
        return out
    }

    /** A 3x3 box blur then small per-pixel noise: a low-frequency-preserving softening that stands in for
     *  a JPEG re-encode, which is exactly what a DCT fingerprint is meant to see through. */
    private fun reencode(src: IntArray, seed: Long): IntArray {
        val blurred = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until W && yy in 0 until H) {
                    val p = src[yy * W + xx]
                    r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF; n++
                }
            }
            blurred[y * W + x] = packRgb(r / n, g / n, b / n)
        }
        val rng = Random(seed)
        return IntArray(W * H) {
            val p = blurred[it]
            packRgb(((p ushr 16) and 0xFF) + rng.nextInt(9) - 4,
                ((p ushr 8) and 0xFF) + rng.nextInt(9) - 4,
                (p and 0xFF) + rng.nextInt(9) - 4)
        }
    }

    /** Box-average resample to a new size. */
    private fun resample(src: IntArray, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (dy in 0 until dh) for (dx in 0 until dw) {
            val x0 = dx * W / dw; val x1 = maxOf(x0 + 1, (dx + 1) * W / dw)
            val y0 = dy * H / dh; val y1 = maxOf(y0 + 1, (dy + 1) * H / dh)
            var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in y0 until minOf(y1, H)) for (xx in x0 until minOf(x1, W)) {
                val p = src[yy * W + xx]
                r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF; n++
            }
            out[dy * dw + dx] = packRgb(r / n, g / n, b / n)
        }
        return out
    }

    private fun map(src: IntArray, f: (r: Int, g: Int, b: Int) -> Int): IntArray =
        IntArray(src.size) { val p = src[it]; f((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF) }

    private val base = scene(0)

    @Test
    fun the_base_scene_carries_enough_detail_to_be_used() {
        assertTrue("a textured photo-like scene clears the quality gate", PdqHash.isUsable(fp(base)))
    }

    @Test
    fun a_re_encode_still_matches_the_original() {
        assertTrue("a light re-encode is the same photo", PdqHash.matches(fp(base), fp(reencode(base, 1))))
        assertTrue("a heavier re-encode is still the same photo", PdqHash.matches(fp(base), fp(reencode(reencode(base, 2), 3))))
    }

    @Test
    fun a_resized_copy_still_matches() {
        assertTrue("a 70% resize is the same photo", PdqHash.matches(fp(base), fp(resample(base, 280, 210), 280, 210)))
        assertTrue("a 150% resize is the same photo", PdqHash.matches(fp(base), fp(resample(base, 600, 450), 600, 450)))
    }

    @Test
    fun a_mild_exposure_shift_still_matches() {
        // A uniform brightness lift: the dropped DC term makes structure blind to it, and a +18 lift is
        // within the colour tolerance, so it stays the same photo.
        assertTrue(PdqHash.matches(fp(base), fp(map(base) { r, g, b -> packRgb(r + 18, g + 18, b + 18) })))
    }

    @Test
    fun a_different_scene_does_not_match() {
        val other = fp(scene(1))
        assertFalse("a different photo is not a duplicate", PdqHash.matches(fp(base), other))
        assertTrue("and its structure is far away", PdqHash.distance(fp(base).bits, other.bits) > PdqHash.MATCH_THRESHOLD)
    }

    @Test
    fun a_heavy_recolour_of_the_same_scene_is_rejected_by_the_colour_gate() {
        // Swap the warm/cool balance while leaving the layout in place: the colour signature moves well
        // past the gate, so it is not offered as a duplicate of the original.
        val recoloured = map(base) { r, g, b -> packRgb(b, g / 2, r) }
        assertTrue(
            "the recolour is far in colour space",
            PdqHash.colorDistance(fp(base).color, fp(recoloured).color) > PdqHash.MAX_COLOR_DISTANCE,
        )
        assertFalse("same layout, very different palette: not a duplicate", PdqHash.matches(fp(base), fp(recoloured)))
    }

    @Test
    fun a_flat_frame_is_dropped() {
        assertFalse("an evenly grey frame is too flat to fingerprint", PdqHash.isUsable(fp(map(base) { _, _, _ -> packRgb(128, 128, 128) })))
    }
}
