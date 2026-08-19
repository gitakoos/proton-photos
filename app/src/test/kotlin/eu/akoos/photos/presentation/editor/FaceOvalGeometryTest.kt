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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

/**
 * Pins the face-cover ellipse math: the oval sits on the box centre, follows the eye line's angle,
 * and grows past the box by the padding. The point a user feels is that a tilted face gets a tilted
 * mask, so the sign and size of the rotation and the radii are asserted structurally with a delta,
 * and the awkward inputs (coincident eyes, a degenerate box) stay finite and well formed.
 */
class FaceOvalGeometryTest {

    private val delta = 1e-3f

    private fun expectedDegrees(dy: Float, dx: Float): Float =
        Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()

    private fun assertFinite(name: String, value: Float) {
        assertTrue("$name must be finite, was $value", value.isFinite())
    }

    @Test
    fun `a level face is centred on the box with padded half-extent radii and no rotation`() {
        val left = 20f; val top = 40f; val right = 180f; val bottom = 260f
        val padding = 0.12f
        val oval = faceOval(
            left, top, right, bottom,
            leftEyeX = 60f, leftEyeY = 120f,
            rightEyeX = 140f, rightEyeY = 120f,
            padding = padding,
        )

        assertEquals(0f, oval.rotationDegrees, delta)
        assertEquals((left + right) / 2f, oval.centerX, delta)
        assertEquals((top + bottom) / 2f, oval.centerY, delta)
        assertEquals((right - left) / 2f * (1f + padding), oval.radiusX, delta)
        assertEquals((bottom - top) / 2f * (1f + padding), oval.radiusY, delta)
    }

    @Test
    fun `a right eye lower than the left tilts the oval clockwise`() {
        val oval = faceOval(
            50f, 80f, 150f, 220f,
            leftEyeX = 70f, leftEyeY = 120f,
            rightEyeX = 130f, rightEyeY = 150f,
        )
        // dy = +30, dx = +60: a positive (clockwise) angle in image space where y points down.
        assertTrue("expected a positive tilt, was ${oval.rotationDegrees}", oval.rotationDegrees > 0f)
        assertEquals(expectedDegrees(30f, 60f), oval.rotationDegrees, delta)
    }

    @Test
    fun `a right eye higher than the left tilts the oval the other way`() {
        val oval = faceOval(
            50f, 80f, 150f, 220f,
            leftEyeX = 70f, leftEyeY = 150f,
            rightEyeX = 130f, rightEyeY = 120f,
        )
        // dy = -30, dx = +60: the mirror of the previous case, so a negative angle of equal size.
        assertTrue("expected a negative tilt, was ${oval.rotationDegrees}", oval.rotationDegrees < 0f)
        assertEquals(expectedDegrees(-30f, 60f), oval.rotationDegrees, delta)
        assertEquals(-expectedDegrees(30f, 60f), oval.rotationDegrees, delta)
    }

    @Test
    fun `eyes stacked vertically give a quarter-turn rotation`() {
        val down = faceOval(
            0f, 0f, 100f, 100f,
            leftEyeX = 50f, leftEyeY = 30f,
            rightEyeX = 50f, rightEyeY = 90f,
        )
        assertEquals(90f, down.rotationDegrees, delta)

        val up = faceOval(
            0f, 0f, 100f, 100f,
            leftEyeX = 50f, leftEyeY = 90f,
            rightEyeX = 50f, rightEyeY = 30f,
        )
        assertEquals(-90f, up.rotationDegrees, delta)
    }

    @Test
    fun `radii scale linearly with the box size`() {
        val small = faceOval(
            0f, 0f, 100f, 100f,
            leftEyeX = 25f, leftEyeY = 50f, rightEyeX = 75f, rightEyeY = 50f,
        )
        val big = faceOval(
            0f, 0f, 200f, 200f,
            leftEyeX = 50f, leftEyeY = 100f, rightEyeX = 150f, rightEyeY = 100f,
        )
        assertEquals(2f * small.radiusX, big.radiusX, delta)
        assertEquals(2f * small.radiusY, big.radiusY, delta)
    }

    @Test
    fun `radii scale with padding`() {
        fun ovalWith(padding: Float) = faceOval(
            0f, 0f, 100f, 200f,
            leftEyeX = 25f, leftEyeY = 50f, rightEyeX = 75f, rightEyeY = 50f,
            padding = padding,
        )

        val bare = ovalWith(0f)
        // With no padding the radii are exactly the box half-extents.
        assertEquals(50f, bare.radiusX, delta)
        assertEquals(100f, bare.radiusY, delta)

        val padded = ovalWith(0.5f)
        assertEquals(bare.radiusX * 1.5f, padded.radiusX, delta)
        assertEquals(bare.radiusY * 1.5f, padded.radiusY, delta)
    }

    @Test
    fun `coincident eyes fall back to level with no NaN`() {
        val oval = faceOval(
            10f, 10f, 110f, 110f,
            leftEyeX = 60f, leftEyeY = 60f,
            rightEyeX = 60f, rightEyeY = 60f,
        )
        assertEquals(0f, oval.rotationDegrees, delta)
        assertFinite("rotationDegrees", oval.rotationDegrees)
    }

    @Test
    fun `a degenerate box yields non-negative finite radii`() {
        // right < left and bottom < top: the raw half-extents are negative.
        val inverted = faceOval(
            200f, 260f, 20f, 40f,
            leftEyeX = 60f, leftEyeY = 120f, rightEyeX = 140f, rightEyeY = 120f,
        )
        assertTrue("radiusX must not be negative, was ${inverted.radiusX}", inverted.radiusX >= 0f)
        assertTrue("radiusY must not be negative, was ${inverted.radiusY}", inverted.radiusY >= 0f)
        assertFinite("radiusX", inverted.radiusX)
        assertFinite("radiusY", inverted.radiusY)
        assertFinite("centerX", inverted.centerX)
        assertFinite("centerY", inverted.centerY)
        assertFinite("rotationDegrees", inverted.rotationDegrees)

        // A zero-area box collapses the radii to zero, still centred and finite.
        val point = faceOval(
            100f, 100f, 100f, 100f,
            leftEyeX = 100f, leftEyeY = 100f, rightEyeX = 100f, rightEyeY = 100f,
        )
        assertEquals(0f, point.radiusX, delta)
        assertEquals(0f, point.radiusY, delta)
        assertEquals(100f, point.centerX, delta)
        assertEquals(100f, point.centerY, delta)
    }
}
