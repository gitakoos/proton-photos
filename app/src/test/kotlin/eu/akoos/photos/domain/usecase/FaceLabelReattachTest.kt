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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLabelReattachTest {

    private fun label(id: String, photo: String, l: Float, t: Float, r: Float, b: Float, name: String) =
        ReattachLabel(id, photo, l, t, r, b, name)

    private fun face(id: String, photo: String, l: Float, t: Float, r: Float, b: Float) =
        ReattachFace(id, photo, l, t, r, b)

    @Test
    fun `same id rebinds with no geometry`() {
        // An embedder-only swap keeps the detector, so the id is reproduced and the box is irrelevant.
        val labels = listOf(label("p1#0", "p1", 0f, 0f, 0.2f, 0.2f, "Anna"))
        val faces = listOf(face("p1#0", "p1", 0.9f, 0.9f, 1f, 1f))
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["p1#0"])
    }

    @Test
    fun `shifted id rebinds by box overlap`() {
        // A detector change shifts the id but the face sits in the same place, so the box carries the name.
        val labels = listOf(label("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f, "Anna"))
        val faces = listOf(face("p1#7", "p1", 0.11f, 0.11f, 0.31f, 0.31f))
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["p1#7"])
    }

    @Test
    fun `low overlap leaves the face unnamed`() {
        val labels = listOf(label("p1#0", "p1", 0f, 0f, 0.2f, 0.2f, "Anna"))
        val faces = listOf(face("p1#3", "p1", 0.7f, 0.7f, 0.95f, 0.95f))
        val result = matchReattachLabels(labels, faces)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a different photo never matches`() {
        val labels = listOf(label("p1#0", "p1", 0.1f, 0.1f, 0.3f, 0.3f, "Anna"))
        val faces = listOf(face("p2#0", "p2", 0.1f, 0.1f, 0.3f, 0.3f))
        val result = matchReattachLabels(labels, faces)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `two people in one photo do not cross names`() {
        // Ids shifted, so both fall to the geometry pass; each name must land on its own face.
        val labels = listOf(
            label("p1#0", "p1", 0.05f, 0.05f, 0.25f, 0.25f, "Anna"),
            label("p1#1", "p1", 0.60f, 0.60f, 0.85f, 0.85f, "Bela"),
        )
        val faces = listOf(
            face("p1#4", "p1", 0.61f, 0.61f, 0.86f, 0.86f),
            face("p1#9", "p1", 0.06f, 0.06f, 0.26f, 0.26f),
        )
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["p1#9"])
        assertEquals("Bela", result["p1#4"])
    }

    @Test
    fun `id match wins over a competing overlap`() {
        // One label matches a face by id; another label overlaps that same face more tightly but must not
        // steal it, because the id match is the stronger signal.
        val labels = listOf(
            label("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f, "Anna"),
            label("p1#5", "p1", 0.11f, 0.11f, 0.31f, 0.31f, "Bela"),
        )
        val faces = listOf(face("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f))
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["p1#0"])
        assertEquals(1, result.size)
    }

    @Test
    fun `a contested face goes to the strongest overlap`() {
        // Two labels (ids shifted) want the one detected face; the closer box wins, the other is dropped.
        val labels = listOf(
            label("p1#0", "p1", 0.10f, 0.10f, 0.30f, 0.30f, "Anna"),
            label("p1#1", "p1", 0.12f, 0.12f, 0.34f, 0.34f, "Bela"),
        )
        val faces = listOf(face("p1#8", "p1", 0.10f, 0.10f, 0.30f, 0.30f))
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["p1#8"])
        assertEquals(1, result.size)
    }

    @Test
    fun `a detector reorder binds names to boxes not colliding ids`() {
        // A detector swap renumbers a two-face photo: the old ids still exist but now sit on the other
        // face, so a bare id match would name the wrong person. The names must follow the boxes.
        val labels = listOf(
            label("P#0", "P", 0.0f, 0.0f, 0.3f, 0.3f, "Anna"),
            label("P#1", "P", 0.6f, 0.6f, 0.9f, 0.9f, "Bela"),
        )
        val faces = listOf(
            face("P#0", "P", 0.6f, 0.6f, 0.9f, 0.9f),
            face("P#1", "P", 0.0f, 0.0f, 0.3f, 0.3f),
        )
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["P#1"])
        assertEquals("Bela", result["P#0"])
    }

    @Test
    fun `an embedder-only swap still binds identical ids by id`() {
        // Same detector, so ids and boxes are reproduced verbatim; both names rebind onto their own face
        // even though the photo holds two of them.
        val labels = listOf(
            label("P#0", "P", 0.0f, 0.0f, 0.3f, 0.3f, "Anna"),
            label("P#1", "P", 0.6f, 0.6f, 0.9f, 0.9f, "Bela"),
        )
        val faces = listOf(
            face("P#0", "P", 0.0f, 0.0f, 0.3f, 0.3f),
            face("P#1", "P", 0.6f, 0.6f, 0.9f, 0.9f),
        )
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["P#0"])
        assertEquals("Bela", result["P#1"])
    }

    @Test
    fun `a lone face keeps its name by id even when the box moved`() {
        // With one face in the photo there is nothing to confuse a name with, so the single-face branch
        // takes the id match though the box no longer overlaps.
        val labels = listOf(label("solo#0", "solo", 0.05f, 0.05f, 0.25f, 0.25f, "Anna"))
        val faces = listOf(face("solo#0", "solo", 0.60f, 0.60f, 0.90f, 0.90f))
        val result = matchReattachLabels(labels, faces)
        assertEquals("Anna", result["solo#0"])
    }

    @Test
    fun `empty inputs yield nothing`() {
        assertTrue(matchReattachLabels(emptyList(), listOf(face("p1#0", "p1", 0f, 0f, 1f, 1f))).isEmpty())
        assertTrue(matchReattachLabels(listOf(label("p1#0", "p1", 0f, 0f, 1f, 1f, "Anna")), emptyList()).isEmpty())
    }

    @Test
    fun `iou is zero when boxes do not overlap`() {
        assertEquals(0f, boxIou(0f, 0f, 0.2f, 0.2f, 0.5f, 0.5f, 0.7f, 0.7f), 0f)
    }

    @Test
    fun `iou of identical boxes is one`() {
        assertEquals(1f, boxIou(0.1f, 0.1f, 0.4f, 0.4f, 0.1f, 0.1f, 0.4f, 0.4f), 1e-6f)
    }
}
