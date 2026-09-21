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
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the shared colour-adjustment math that both the photo and video editors compose. The math must
 * match what android.graphics.ColorMatrix would produce for the same operations, so the golden test
 * multiplies the operand matrices with an INDEPENDENT 5x5 affine multiply (not the production
 * postConcat4x5) and expects the same result.
 */
class ColorAdjustmentMatrixTest {

    private val eps = 1e-3f

    // --- independent affine multiply, used only to cross-check the production code ---

    private fun to5x5(m: FloatArray): Array<FloatArray> {
        val r = Array(5) { FloatArray(5) }
        for (row in 0 until 4) for (col in 0 until 5) r[row][col] = m[row * 5 + col]
        r[4][4] = 1f // implicit affine bottom row
        return r
    }

    /** outer x inner (apply inner first, outer after), computed as a plain 5x5 matrix product. */
    private fun affineMul(outer: FloatArray, inner: FloatArray): FloatArray {
        val a = to5x5(outer)
        val b = to5x5(inner)
        val p = Array(5) { FloatArray(5) }
        for (i in 0 until 5) for (j in 0 until 5) {
            var s = 0f
            for (k in 0 until 5) s += a[i][k] * b[k][j]
            p[i][j] = s
        }
        val out = FloatArray(20)
        for (row in 0 until 4) for (col in 0 until 5) out[row * 5 + col] = p[row][col]
        return out
    }

    private fun assertMatrixEquals(expected: FloatArray, actual: FloatArray?) {
        requireNotNull(actual) { "matrix was null" }
        assertEquals("length", 20, actual.size)
        for (i in 0 until 20) assertEquals("index $i", expected[i], actual[i], eps)
    }

    // --- tests ---

    @Test
    fun all_zero_returns_null() {
        assertNull(colorAdjustmentMatrix(0, 0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun brightness_sets_the_offset_column() {
        // brightness 10 -> 10 * 1.5 = 15 additive on each channel (indices 4, 9, 14); diagonal stays 1.
        val m = colorAdjustmentMatrix(brightness = 10, exposure = 0, contrast = 0, highlights = 0,
            shadows = 0, saturation = 0, temperature = 0, tone = 0, fade = 0)
        requireNotNull(m)
        assertEquals(15f, m[4], eps)
        assertEquals(15f, m[9], eps)
        assertEquals(15f, m[14], eps)
        assertEquals(1f, m[0], eps)
        assertEquals(1f, m[6], eps)
        assertEquals(1f, m[12], eps)
    }

    @Test
    fun saturation_minus_100_is_luminance_greyscale() {
        // sat multiplier 0 -> every RGB row is the luminance weights 0.213 / 0.715 / 0.072.
        val m = colorAdjustmentMatrix(brightness = 0, exposure = 0, contrast = 0, highlights = 0,
            shadows = 0, saturation = -100, temperature = 0, tone = 0, fade = 0)
        requireNotNull(m)
        for (row in 0 until 3) {
            assertEquals("row $row R", 0.213f, m[row * 5 + 0], eps)
            assertEquals("row $row G", 0.715f, m[row * 5 + 1], eps)
            assertEquals("row $row B", 0.072f, m[row * 5 + 2], eps)
        }
    }

    @Test
    fun contrast_scales_the_diagonal_and_shifts_the_offset() {
        // contrast 50 -> 1.5x diagonal, translate = (1 - 1.5) * 128 = -64 on each channel.
        val m = colorAdjustmentMatrix(brightness = 0, exposure = 0, contrast = 50, highlights = 0,
            shadows = 0, saturation = 0, temperature = 0, tone = 0, fade = 0)
        requireNotNull(m)
        assertEquals(1.5f, m[0], eps)
        assertEquals(1.5f, m[6], eps)
        assertEquals(1.5f, m[12], eps)
        assertEquals(-64f, m[4], eps)
        assertEquals(-64f, m[9], eps)
        assertEquals(-64f, m[14], eps)
    }

    @Test
    fun combine_returns_the_non_null_operand_when_one_is_null() {
        val x = floatArrayOf(
            1.2f, 0f, 0f, 0f, 3f,
            0f, 1.2f, 0f, 0f, 3f,
            0f, 0f, 1.2f, 0f, 3f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertSame(x, combineColorMatrices4x5(null, x))
        assertSame(x, combineColorMatrices4x5(x, null))
        assertNull(combineColorMatrices4x5(null, null))
    }

    @Test
    fun combine_postconcats_adjustments_after_the_filter() {
        // a = a filter-like matrix, b = an adjustment-like matrix; combine applies a first, b after.
        val a = floatArrayOf(
            0.9f, 0.1f, 0f, 0f, 5f,
            0f, 1.0f, 0f, 0f, 0f,
            0f, 0f, 1.1f, 0f, -4f,
            0f, 0f, 0f, 1f, 0f,
        )
        val b = colorAdjustmentMatrix(brightness = 8, exposure = 0, contrast = 30, highlights = 0,
            shadows = 0, saturation = 0, temperature = 0, tone = 0, fade = 0)!!
        assertMatrixEquals(affineMul(b, a), combineColorMatrices4x5(a, b))
    }

    @Test
    fun golden_contrast_plus_exposure_matches_hand_computed_colormatrix() {
        // Reproduce, by hand, the ops android.graphics.ColorMatrix would run for contrast=50, exposure=20:
        //   combined = mExposure x (mAdjust x (mSaturation x identity)),  mSaturation = identity (sat 1).
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val contrastF = 1f + 50 / 100f
        val translate = (1f - contrastF) * 128f
        val mAdjust = floatArrayOf(
            contrastF, 0f, 0f, 0f, translate,
            0f, contrastF, 0f, 0f, translate,
            0f, 0f, contrastF, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        )
        val expScale = 1f + 20 / 100f
        val mExposure = floatArrayOf(
            expScale, 0f, 0f, 0f, 0f,
            0f, expScale, 0f, 0f, 0f,
            0f, 0f, expScale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val expected = affineMul(mExposure, affineMul(mAdjust, identity))

        val actual = colorAdjustmentMatrix(brightness = 0, exposure = 20, contrast = 50, highlights = 0,
            shadows = 0, saturation = 0, temperature = 0, tone = 0, fade = 0)
        assertMatrixEquals(expected, actual)

        // Closed-form anchors: diagonal 1.5 * 1.2 = 1.8, offset -64 * 1.2 = -76.8.
        requireNotNull(actual)
        assertEquals(1.8f, actual[0], eps)
        assertEquals(-76.8f, actual[4], eps)
    }

    @Test
    fun to_4x4_column_major_matches_the_filter_conversion() {
        // 3x3 RGB block into columns 0-2, offsets (4, 9, 14) / 255 into column 3, last row 0,0,0,1.
        val m4x5 = floatArrayOf(
            0.9f, 0.1f, 0.0f, 0f, 25.5f,
            0.2f, 0.8f, 0.0f, 0f, 51.0f,
            0.0f, 0.0f, 1.0f, 0f, -25.5f,
            0f, 0f, 0f, 1f, 0f,
        )
        val c = colorMatrix4x5To4x4ColumnMajor(m4x5)
        val expected = floatArrayOf(
            0.9f, 0.2f, 0.0f, 0f, // col 0 (input R)
            0.1f, 0.8f, 0.0f, 0f, // col 1 (input G)
            0.0f, 0.0f, 1.0f, 0f, // col 2 (input B)
            25.5f / 255f, 51.0f / 255f, -25.5f / 255f, 1f, // col 3 (offsets)
        )
        for (i in 0 until 16) assertEquals("index $i", expected[i], c[i], 1e-6f)
    }
}
