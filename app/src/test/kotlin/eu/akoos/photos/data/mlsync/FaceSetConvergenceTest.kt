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

package eu.akoos.photos.data.mlsync

import eu.akoos.photos.domain.usecase.FACE_EMBEDDING_DIM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FaceSetConvergenceTest {

    /** A vector along [axis], tipped towards [lean] by [amount], so two vectors' agreement is set exactly. */
    private fun vector(axis: Int, lean: Int = axis, amount: Float = 0f): ByteArray {
        val buffer = ByteBuffer.allocate(FACE_EMBEDDING_DIM * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until FACE_EMBEDDING_DIM) {
            buffer.putFloat(
                when (i) {
                    axis -> 1f
                    lean -> amount
                    else -> 0f
                },
            )
        }
        return buffer.array()
    }

    private fun face(id: String, left: Float, right: Float, embedding: ByteArray = vector(0)) =
        SetFace(id, left, 0.1f, right, 0.4f, embedding)

    @Test
    fun `the copy of the device whose id sorts first wins, never an unknown or this device's own`() {
        assertTrue(incomingSetWins("0000", "ffff"))
        assertFalse(incomingSetWins("ffff", "0000"))
        assertFalse(incomingSetWins("abcd", "abcd"))
        assertFalse(incomingSetWins(null, "ffff"))
        assertFalse(incomingSetWins("0000", null))
        assertFalse(incomingSetWins("", "ffff"))
    }

    @Test
    fun `two faces are the same by half their boxes, or by loose boxes and agreeing vectors`() {
        val here = face("p#0", 0.10f, 0.30f)
        // Overlap 0.67.
        assertTrue(sameFace(here, face("p#0", 0.12f, 0.32f, vector(9))))
        // Overlap 0.4: the vectors decide.
        assertTrue(sameFace(here, face("p#0", 0.185f, 0.385f, vector(0))))
        assertFalse(sameFace(here, face("p#0", 0.185f, 0.385f, vector(9))))
        // Apart: agreeing vectors are not enough.
        assertFalse(sameFace(here, face("p#0", 0.60f, 0.80f, vector(0))))
    }

    @Test
    fun `loose boxes pair by vector, each face once, the closest vectors first`() {
        val incoming = listOf(face("p#0", 0.10f, 0.30f, vector(0)))
        val local = listOf(
            face("p#5", 0.185f, 0.385f, vector(0, lean = 3, amount = 0.6f)),
            face("p#6", 0.015f, 0.215f, vector(0)),
        )
        assertEquals(mapOf("p#0" to "p#6"), pairSameFaces("p", incoming, local))
        assertEquals(emptyMap<String, String>(), pairSameFaces("p", incoming, listOf(face("p#5", 0.185f, 0.385f, vector(4)))))
    }

    @Test
    fun `a set already taken in is held as it is, faces of its own beside it allowed`() {
        val incoming = listOf(face("p#0", 0.10f, 0.30f), face("p#1", 0.60f, 0.80f))
        assertTrue(holdsIncomingSet(incoming, incoming + face("p#2", 0.40f, 0.50f)))
        assertFalse(holdsIncomingSet(incoming, listOf(face("p#0", 0.10f, 0.30f), face("p#1", 0.61f, 0.80f))))
        assertFalse(holdsIncomingSet(incoming, listOf(face("p#0", 0.10f, 0.30f), face("p#2", 0.60f, 0.80f))))
    }

    @Test
    fun `a moving face keeps its own index when free, else takes the lowest free one`() {
        assertEquals(
            mapOf("p#4" to "p#4", "p#1" to "p#2"),
            freeFaceIds("p", listOf("p#1", "p#4"), setOf("p#0", "p#1")),
        )
    }

    @Test
    fun `taking a set in carries each twin and keeps every face that has none`() {
        val incoming = listOf(face("p#0", 0.10f, 0.30f), face("p#1", 0.60f, 0.80f, vector(1)))
        val local = listOf(
            face("p#0", 0.61f, 0.81f, vector(2)),
            face("p#1", 0.185f, 0.385f, vector(0)),
            face("p#2", 0.40f, 0.50f, vector(5)),
        )
        val plan = planFaceSetAdoption("p", incoming, local)
        assertEquals(mapOf("p#0" to "p#1", "p#1" to "p#0"), plan.carried)
        assertEquals(mapOf("p#2" to "p#2"), plan.kept)
    }

    @Test
    fun `only a face that is none of the faces here is taken in`() {
        val incoming = listOf(
            face("p#0", 0.10f, 0.30f),
            face("p#1", 0.12f, 0.32f),
            face("p#2", 0.60f, 0.80f),
        )
        val local = listOf(face("p#0", 0.10f, 0.30f), face("p#5", 0.40f, 0.45f))
        // p#1 sits on the face here that p#0 paired with: a twin, not a face of its own.
        assertEquals(mapOf("p#2" to "p#2"), planIncomingAdds("p", incoming, local, mapOf("p#0" to "p#0")))
    }

    @Test
    fun `a face taken in keeps what was decided by hand here and takes what is open from there`() {
        val confirmedThere = SnapshotFaceCuration(personName = "Ann", confirmedName = "Ann", rejected = false)
        assertEquals(
            CarriedCuration(rejected = false, confirmedName = "Bea", groupedName = null),
            carriedCuration(LocalFaceCuration(confirmedName = "Bea"), confirmedThere),
        )
        assertEquals(
            CarriedCuration(rejected = false, confirmedName = "Ann", groupedName = "Ann"),
            carriedCuration(LocalFaceCuration(), confirmedThere),
        )
        assertEquals(
            CarriedCuration(rejected = false, confirmedName = null, groupedName = null),
            carriedCuration(LocalFaceCuration(blockedNames = setOf("Ann")), confirmedThere),
        )
        assertEquals(
            CarriedCuration(rejected = true, confirmedName = null, groupedName = null),
            carriedCuration(LocalFaceCuration(rejected = true), confirmedThere),
        )
        assertEquals(
            CarriedCuration(rejected = true, confirmedName = null, groupedName = null),
            carriedCuration(LocalFaceCuration(), SnapshotFaceCuration(personName = null, confirmedName = null, rejected = true)),
        )
        assertEquals(
            CarriedCuration(rejected = false, confirmedName = "Ann", groupedName = "Ann"),
            carriedCuration(null, confirmedThere),
        )
    }
}
