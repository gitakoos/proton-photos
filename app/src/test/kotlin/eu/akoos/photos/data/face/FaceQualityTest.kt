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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves the pure face-quality grading: sharpness separates a flat crop from a busy one, the turn
 *  measure separates a frontal face from a side-on one, and the confidence gate honours its floors
 *  while treating an unknown metric as no objection. */
class FaceQualityTest {

    @Test
    fun a_flat_crop_has_zero_laplacian_variance_and_a_busy_one_high() {
        val flat = IntArray(6 * 6) { 128 }
        assertEquals(0.0, laplacianVariance(flat, 6, 6), 1e-9)

        val checker = IntArray(6 * 6) { i -> if (((i % 6) + (i / 6)) % 2 == 0) 0 else 255 }
        assertTrue("a checkerboard is sharp", laplacianVariance(checker, 6, 6) > 1000.0)
    }

    @Test
    fun a_frontal_face_measures_near_zero_and_a_turned_one_higher() {
        val frontal = sidewaysMeasure(
            leftEye = Landmark(-10f, 0f),
            rightEye = Landmark(10f, 0f),
            nose = Landmark(0f, 10f),
            leftMouth = Landmark(-8f, 20f),
            rightMouth = Landmark(8f, 20f),
        )
        assertTrue("a straight-on face is near frontal, was $frontal", frontal < 0.05f)

        val turned = sidewaysMeasure(
            leftEye = Landmark(-10f, 0f),
            rightEye = Landmark(10f, 0f),
            nose = Landmark(8f, 10f),
            leftMouth = Landmark(-8f, 20f),
            rightMouth = Landmark(8f, 20f),
        )
        assertTrue("a nose shifted to one eye reads as turned, was $turned", turned > 0.2f)
    }

    @Test
    fun encoded_landmarks_parse_or_reject() {
        val ok = sidewaysFromEncoded("-10,0;10,0;8,10;-8,20;8,20")
        assertTrue(ok != null && ok > 0.2f)
        assertNull("four points is not enough", sidewaysFromEncoded("-10,0;10,0;0,10;-8,20"))
        assertNull("garbage yields null", sidewaysFromEncoded("not;a;point"))
    }

    @Test
    fun the_confidence_gate_honours_its_floors_and_ignores_unknowns() {
        assertTrue(isConfidentFace(0.80f, blur = 100.0, sideways = 0.05f))
        assertFalse("a low score is not confident", isConfidentFace(0.40f, 100.0, 0.05f))
        assertFalse("a blurred crop is not confident", isConfidentFace(0.80f, 2.0, 0.05f))
        assertFalse("a turned face is not confident", isConfidentFace(0.80f, 100.0, 0.40f))
        assertTrue("unknown blur and turn are no objection", isConfidentFace(0.80f, null, null))
    }
}
