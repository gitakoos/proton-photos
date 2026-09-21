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

import eu.akoos.photos.domain.usecase.ReattachFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the shared box geometry: the pure [iou] overlap and the removed-face carry that re-applies
 * a model swap's exclusions to the re-detected faces by geometry. Plain JVM, no Android or coroutines.
 */
class FaceGeometryTest {

    private fun box(photo: String, l: Float, t: Float, r: Float, b: Float) = ReattachBox(photo, l, t, r, b)

    private fun face(id: String, photo: String, l: Float, t: Float, r: Float, b: Float) =
        ReattachFace(id, photo, l, t, r, b)

    @Test
    fun `iou is zero when boxes do not overlap`() {
        assertEquals(0f, iou(0f, 0f, 0.2f, 0.2f, 0.5f, 0.5f, 0.7f, 0.7f), 0f)
    }

    @Test
    fun `iou of identical boxes is one`() {
        assertEquals(1f, iou(0.1f, 0.1f, 0.4f, 0.4f, 0.1f, 0.1f, 0.4f, 0.4f), 1e-6f)
    }

    @Test
    fun `iou of a half-overlapping pair is the ratio of areas`() {
        // Intersection 0.01, each area 0.04, union 0.07.
        assertEquals(0.01f / 0.07f, iou(0f, 0f, 0.2f, 0.2f, 0.1f, 0.1f, 0.3f, 0.3f), 1e-6f)
    }

    @Test
    fun `a re-detected photo re-excludes the correct new face regardless of order`() {
        // One face was removed (top-left box); the other is kept (bottom-right). The new detector finds
        // them in the opposite order, so the ids no longer line up and only the box identifies the face.
        val removed = listOf(box("p1", 0.05f, 0.05f, 0.25f, 0.25f))
        val redetected = listOf(
            face("p1#0", "p1", 0.60f, 0.60f, 0.85f, 0.85f),
            face("p1#1", "p1", 0.06f, 0.06f, 0.26f, 0.26f),
        )
        val result = matchRejectedByGeometry(removed, redetected, 0.5f)
        assertEquals(listOf("p1#1"), result)
    }

    @Test
    fun `a weak overlap does not re-exclude`() {
        val removed = listOf(box("p1", 0f, 0f, 0.2f, 0.2f))
        val redetected = listOf(face("p1#0", "p1", 0.7f, 0.7f, 0.95f, 0.95f))
        assertTrue(matchRejectedByGeometry(removed, redetected, 0.5f).isEmpty())
    }

    @Test
    fun `a box never matches a face in another photo`() {
        val removed = listOf(box("p1", 0.1f, 0.1f, 0.3f, 0.3f))
        val redetected = listOf(face("p2#0", "p2", 0.1f, 0.1f, 0.3f, 0.3f))
        assertTrue(matchRejectedByGeometry(removed, redetected, 0.5f).isEmpty())
    }

    @Test
    fun `two removed boxes in one photo take one face each`() {
        val removed = listOf(
            box("p1", 0.05f, 0.05f, 0.25f, 0.25f),
            box("p1", 0.60f, 0.60f, 0.85f, 0.85f),
        )
        val redetected = listOf(
            face("p1#0", "p1", 0.61f, 0.61f, 0.86f, 0.86f),
            face("p1#1", "p1", 0.06f, 0.06f, 0.26f, 0.26f),
        )
        val result = matchRejectedByGeometry(removed, redetected, 0.5f).toSet()
        assertEquals(setOf("p1#0", "p1#1"), result)
    }

    @Test
    fun `empty inputs yield nothing`() {
        assertTrue(matchRejectedByGeometry(emptyList(), listOf(face("p1#0", "p1", 0f, 0f, 1f, 1f)), 0.5f).isEmpty())
        assertTrue(matchRejectedByGeometry(listOf(box("p1", 0f, 0f, 1f, 1f)), emptyList(), 0.5f).isEmpty())
    }
}
