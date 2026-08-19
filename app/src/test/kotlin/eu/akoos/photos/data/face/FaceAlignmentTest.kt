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

package eu.akoos.photos.data.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pins the pure face-alignment math. The thing that matters is that the returned 2x3 affine lands the
 * source landmarks on the ArcFace template: exactly so when the source already is the template, and
 * still so when the source is a rotated, scaled and shifted copy of it. Only [similarityTransform] is
 * exercised here, so the whole test is android-free.
 */
class FaceAlignmentTest {

    /** The ArcFace template, pinned locally so a change to the production constant is caught here. */
    private val templateX = floatArrayOf(38.2946f, 73.5318f, 56.0252f, 41.5493f, 70.7299f)
    private val templateY = floatArrayOf(51.6963f, 51.5014f, 71.7366f, 92.3655f, 92.2041f)

    /** Applies the 2x3 affine [a, -b, tx, b, a, ty] to one point. */
    private fun apply(affine: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val mapX = affine[0] * x + affine[1] * y + affine[2]
        val mapY = affine[3] * x + affine[4] * y + affine[5]
        return mapX to mapY
    }

    @Test
    fun the_template_itself_maps_back_onto_itself() {
        val affine = FaceAlignment.similarityTransform(templateX.copyOf(), templateY.copyOf())
        for (i in templateX.indices) {
            val (mapX, mapY) = apply(affine, templateX[i], templateY[i])
            assertEquals("x$i", templateX[i], mapX, DELTA)
            assertEquals("y$i", templateY[i], mapY, DELTA)
        }
    }

    @Test
    fun a_known_similarity_of_the_template_is_inverted_back_onto_it() {
        // Build the source as a known rotate + uniform-scale + translate of the template, then check
        // the returned transform sends it back onto the template.
        val scale = 1.7f
        val theta = 0.42f
        val cos = cos(theta.toDouble()).toFloat()
        val sin = sin(theta.toDouble()).toFloat()
        val shiftX = 14f
        val shiftY = -23f

        val srcX = FloatArray(templateX.size)
        val srcY = FloatArray(templateY.size)
        for (i in templateX.indices) {
            srcX[i] = scale * (cos * templateX[i] - sin * templateY[i]) + shiftX
            srcY[i] = scale * (sin * templateX[i] + cos * templateY[i]) + shiftY
        }

        val affine = FaceAlignment.similarityTransform(srcX, srcY)
        for (i in templateX.indices) {
            val (mapX, mapY) = apply(affine, srcX[i], srcY[i])
            assertEquals("x$i", templateX[i], mapX, DELTA)
            assertEquals("y$i", templateY[i], mapY, DELTA)
        }
    }

    @Test
    fun an_all_equal_source_yields_a_finite_transform() {
        val srcX = floatArrayOf(20f, 20f, 20f, 20f, 20f)
        val srcY = floatArrayOf(35f, 35f, 35f, 35f, 35f)

        val affine = FaceAlignment.similarityTransform(srcX, srcY)

        assertEquals(6, affine.size)
        for (value in affine) {
            assertTrue("finite: $value", value.isFinite())
        }
    }

    private companion object {
        /** Float accumulation over five points keeps the residual well under this. */
        const val DELTA = 1e-2f
    }
}
