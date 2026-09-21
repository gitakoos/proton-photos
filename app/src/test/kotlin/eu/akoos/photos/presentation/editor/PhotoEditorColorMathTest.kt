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

/**
 * Pins the pure colour maths behind the Curves and HSL tools: a tone curve interpolates linearly between
 * its control points and holds flat past the ends, and an HSL band's influence peaks at its centre hue
 * and blends to zero by 60 degrees away. A wrong curve or band weight would mis-grade every pixel.
 */
class PhotoEditorColorMathTest {

    @Test
    fun identityCurveMapsInputToItself() {
        assertEquals(0.5f, curveValueAt(IDENTITY_CURVE, 0.5f), 1e-4f)
        val lut = buildCurveLut(IDENTITY_CURVE)
        assertEquals(0, lut[0])
        assertEquals(128, lut[128])
        assertEquals(255, lut[255])
    }

    @Test
    fun curveInterpolatesLinearlyBetweenPoints() {
        val c = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.8f), CurvePoint(1f, 1f))
        assertEquals(0.4f, curveValueAt(c, 0.25f), 1e-4f)
        assertEquals(0.9f, curveValueAt(c, 0.75f), 1e-4f)
    }

    @Test
    fun curveHoldsFlatBeyondTheEnds() {
        val c = listOf(CurvePoint(0.2f, 0.3f), CurvePoint(0.8f, 0.9f))
        assertEquals(0.3f, curveValueAt(c, 0f), 1e-4f)
        assertEquals(0.9f, curveValueAt(c, 1f), 1e-4f)
    }

    @Test
    fun isIdentityCurveDetectsTheStraightLine() {
        assertTrue(isIdentityCurve(IDENTITY_CURVE))
        assertFalse(isIdentityCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 1f))))
    }

    @Test
    fun hueDistanceWrapsAroundTheWheel() {
        assertEquals(20f, hueDistance(350f, 10f), 1e-4f)
        assertEquals(180f, hueDistance(0f, 180f), 1e-4f)
        assertEquals(10f, hueDistance(50f, 60f), 1e-4f)
    }

    @Test
    fun hslBandWeightPeaksAtCentreAndFallsOff() {
        assertEquals(1f, hslBandWeight(60f, 60f), 1e-4f)
        assertEquals(0.5f, hslBandWeight(90f, 60f), 1e-4f)
        assertEquals(0f, hslBandWeight(130f, 60f), 1e-4f)
    }
}
