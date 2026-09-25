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

import eu.akoos.photos.data.db.dao.CuratedFace
import eu.akoos.photos.data.db.entity.NotPersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules of folding a photo's device-keyed faces into its cloud-keyed faces when both copies were
 * scanned: pairing by box, free ids for the faces the cloud copy lacks, the cloud side's curation winning,
 * and the marks, covers, marker flag and people that follow.
 */
class FaceRekeyMergePlanTest {

    private val dev = "content://media/external/images/media/9"
    private val link = "linkP"

    private val boxA = floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f)
    private val boxA2 = floatArrayOf(0.11f, 0.1f, 0.31f, 0.3f)
    private val boxB = floatArrayOf(0.6f, 0.1f, 0.8f, 0.3f)
    private val boxB2 = floatArrayOf(0.61f, 0.11f, 0.81f, 0.31f)
    private val boxX = floatArrayOf(0.4f, 0.6f, 0.6f, 0.8f)
    private val boxY = floatArrayOf(0.1f, 0.6f, 0.3f, 0.8f)

    private fun face(
        id: String,
        box: FloatArray,
        personId: Long? = null,
        confirmed: String? = null,
        rejected: Boolean = false,
    ) = CuratedFace(id, personId, box[0], box[1], box[2], box[3], rejected, confirmed)

    private fun d(index: Int) = "$dev#$index"
    private fun c(index: Int) = "$link#$index"
    private fun mark(name: String, faceId: String) = NotPersonEntity("acct", name, faceId)

    private fun plan(
        device: List<CuratedFace>,
        cloud: List<CuratedFace>,
        deviceMarks: List<NotPersonEntity> = emptyList(),
        cloudMarks: List<NotPersonEntity> = emptyList(),
        namedPeople: Map<Long, String> = emptyMap(),
        deviceManualNames: Set<String> = emptySet(),
        deviceCovers: Map<Long, String> = emptyMap(),
        deviceHiRes: Boolean = false,
    ) = planFaceRekeyMerge(
        oldKey = dev,
        newKey = link,
        device = device,
        cloud = cloud,
        deviceMarks = deviceMarks,
        cloudMarks = cloudMarks,
        namedPeople = namedPeople,
        deviceManualNames = deviceManualNames,
        deviceCovers = deviceCovers,
        deviceHiRes = deviceHiRes,
    )

    @Test
    fun `pairs by box rather than by index and gives the unpaired face a free id`() {
        // The device detected the faces in another order and found one more.
        val result = plan(
            device = listOf(face(d(0), boxB2), face(d(1), boxX), face(d(2), boxA2)),
            cloud = listOf(face(c(0), boxA), face(c(1), boxB)),
        )

        assertEquals(mapOf(d(0) to c(1), d(2) to c(0)), result.merged)
        // Index 1 is the cloud copy's, so the extra face takes the lowest free one.
        assertEquals(mapOf(d(1) to c(2)), result.moved)
    }

    @Test
    fun `an unpaired face keeps its own index while that is free`() {
        val result = plan(
            device = listOf(face(d(1), boxX), face(d(2), boxY)),
            cloud = listOf(face(c(0), boxA), face(c(1), boxB)),
        )

        // Index 2 is free and stays; index 1 is taken, so that face takes the lowest id still free.
        assertEquals(mapOf(d(2) to c(2), d(1) to c(3)), result.moved)
        assertTrue(result.merged.isEmpty())
    }

    @Test
    fun `the cloud side's confirmation and removal win`() {
        val result = plan(
            device = listOf(face(d(0), boxA2, confirmed = "Alicia"), face(d(1), boxB2, confirmed = "Bob")),
            cloud = listOf(face(c(0), boxA, confirmed = "Alice"), face(c(1), boxB, rejected = true)),
        )

        assertEquals(mapOf(d(0) to c(0), d(1) to c(1)), result.merged)
        assertTrue(result.confirm.isEmpty())
        assertTrue(result.remove.isEmpty())
    }

    @Test
    fun `the device side only fills the gaps`() {
        val named = mapOf(1L to "Ann", 2L to "Bea", 3L to "Cid")
        val result = plan(
            device = listOf(
                // Confirmed here, unconfirmed and ungrouped on the cloud side: the name lands.
                face(d(0), boxA2, personId = 2L, confirmed = "Bea"),
                // Both only grouped under a named person: the cloud grouping stays.
                face(d(1), boxB2, personId = 3L),
                // Removed here, unconfirmed on the cloud side: the removal lands.
                face(d(2), boxY, rejected = true),
            ),
            cloud = listOf(face(c(0), boxA), face(c(1), boxB, personId = 1L), face(c(2), boxY, personId = 1L)),
            namedPeople = named,
        )

        assertEquals(mapOf("Bea" to listOf(c(0))), result.confirm)
        assertEquals(listOf(c(2)), result.remove)
        // The device faces' people lose a face, and so does the person of the removed cloud face.
        assertEquals(setOf(2L, 3L, 1L), result.people)
    }

    @Test
    fun `a removed cloud face absorbs its device twin`() {
        val result = plan(
            device = listOf(face(d(0), boxA2, personId = 5L)),
            cloud = listOf(face(c(0), boxA, rejected = true)),
        )

        assertEquals(mapOf(d(0) to c(0)), result.merged)
        assertTrue("no second, kept face comes back", result.moved.isEmpty())
        assertTrue(result.confirm.isEmpty())
        assertTrue(result.remove.isEmpty())
    }

    @Test
    fun `marks follow their face once each and never against the confirmed name`() {
        val result = plan(
            device = listOf(face(d(0), boxA2), face(d(1), boxX)),
            cloud = listOf(face(c(0), boxA, confirmed = "Alice")),
            deviceMarks = listOf(
                mark("Carol", d(0)),
                mark("Alice", d(0)),
                mark("Frank", d(0)),
                mark("Dave", d(1)),
                mark("Eve", d(7)),
            ),
            cloudMarks = listOf(mark("Carol", c(0))),
        )

        // Carol is already on the cloud face, Alice is its confirmed name, and d(7) is no face of the photo.
        assertEquals(listOf(mark("Frank", c(0)), mark("Dave", c(1))), result.marks)
    }

    @Test
    fun `a cloud mark keeps a device confirmation of that name off the face`() {
        val result = plan(
            device = listOf(face(d(0), boxA2, confirmed = "Bob")),
            cloud = listOf(face(c(0), boxA)),
            cloudMarks = listOf(mark("Bob", c(0))),
        )

        // The cloud side marked its face "not Bob", so the device confirmation stays out.
        assertTrue(result.confirm.isEmpty())
    }

    @Test
    fun `covers move to the face their device face lands on`() {
        val result = plan(
            device = listOf(face(d(0), boxA2, personId = 10L), face(d(1), boxX, personId = 11L)),
            cloud = listOf(face(c(0), boxA, personId = 10L)),
            deviceCovers = mapOf(10L to d(0), 11L to d(1)),
        )

        assertEquals(mapOf(10L to c(0), 11L to c(1)), result.covers)
        assertTrue(result.people.containsAll(listOf(10L, 11L)))
    }

    @Test
    fun `the cloud marker takes the device marker's hi-res flag`() {
        assertTrue(plan(device = emptyList(), cloud = emptyList(), deviceHiRes = true).raiseHiRes)
        assertFalse(plan(device = emptyList(), cloud = emptyList(), deviceHiRes = false).raiseHiRes)
    }

    @Test
    fun `people attached to the photo by hand are refreshed`() {
        val result = plan(
            device = emptyList(),
            cloud = listOf(face(c(0), boxA, personId = 1L)),
            namedPeople = mapOf(1L to "Ann", 3L to "Cid"),
            deviceManualNames = setOf("Cid"),
        )

        assertEquals(setOf(3L), result.people)
    }
}
