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

package eu.akoos.photos.presentation.viewer

import eu.akoos.photos.R
import eu.akoos.photos.presentation.gallery.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules behind the People row in the details panel: what each pill says, where the end fade
 * ramps in either layout direction, how large a decode each crop asks for, how a stored face box is held
 * inside the frame, and that every entry gets a key of its own. Plain JVM assertions, no Android.
 */
class DetailsPeopleBoxTest {

    private val delta = 1e-3f

    private val unnamed = DetailsPeopleRow.PillLabel.Placeholder(R.string.person_detail_unnamed)

    private fun person(personId: Long, faceId: String, isOther: Boolean = false, name: String? = null) =
        PhotoViewerViewModel.ViewerPerson(
            personId = personId,
            name = name,
            faceBox = FaceBox(0.25f, 0.25f, 0.5f, 0.5f),
            faceId = faceId,
            isOther = isOther,
        )

    // region what each pill says

    @Test
    fun `a named person's pill shows the name`() {
        assertEquals(
            DetailsPeopleRow.PillLabel.Name("Alice"),
            DetailsPeopleRow.pillLabel(person(1L, "face-a", name = "Alice")),
        )
    }

    @Test
    fun `an unnamed group reads as Unnamed`() {
        assertEquals(unnamed, DetailsPeopleRow.pillLabel(person(1L, "face-a")))
    }

    @Test
    fun `a single Unsorted face reads as Unnamed as well`() {
        assertEquals(unnamed, DetailsPeopleRow.pillLabel(person(9L, "face-c", isOther = true)))
    }

    @Test
    fun `a blank name counts as none`() {
        for (blank in listOf("", " ", "\t")) {
            assertEquals(unnamed, DetailsPeopleRow.pillLabel(person(1L, "face-a", name = blank)))
        }
    }

    // endregion
    // region where the end fade ramps

    @Test
    fun `left to right the ramp finishes one edge inset in from the right`() {
        val span = DetailsPeopleRow.endFadeSpan(widthPx = 1000f, edgePx = 40f, rampPx = 100f, ltr = true)

        assertEquals(960f, span.clearX, delta)
        assertEquals(860f, span.solidX, delta)
    }

    @Test
    fun `right to left the ramp sits on the left edge`() {
        val span = DetailsPeopleRow.endFadeSpan(widthPx = 1000f, edgePx = 40f, rampPx = 100f, ltr = false)

        assertEquals(40f, span.clearX, delta)
        assertEquals(140f, span.solidX, delta)
    }

    @Test
    fun `the ramp spans its width and mirrors between directions`() {
        for (width in listOf(320f, 840f, 1600f)) {
            val ltr = DetailsPeopleRow.endFadeSpan(width, edgePx = 42f, rampPx = 100f, ltr = true)
            val rtl = DetailsPeopleRow.endFadeSpan(width, edgePx = 42f, rampPx = 100f, ltr = false)

            // Solid on the side toward the row's start, clear on the trailing side.
            assertEquals(100f, ltr.clearX - ltr.solidX, delta)
            assertEquals(100f, rtl.solidX - rtl.clearX, delta)
            assertEquals(width - ltr.clearX, rtl.clearX, delta)
            assertEquals(width - ltr.solidX, rtl.solidX, delta)
        }
    }

    // endregion
    // region the decode each crop asks for

    @Test
    fun `the decode scales with the face's share of the frame`() {
        assertEquals(480, DetailsPeopleRow.decodePx(FaceBox(0.25f, 0.25f, 0.5f, 0.5f), avatarPx = 120f))
        assertEquals(960, DetailsPeopleRow.decodePx(FaceBox(0.25f, 0.25f, 0.375f, 0.375f), avatarPx = 120f))
    }

    @Test
    fun `the face's longer side sets its share`() {
        // A quarter of the frame tall and an eighth wide: the height decides.
        assertEquals(480, DetailsPeopleRow.decodePx(FaceBox(0.5f, 0.25f, 0.625f, 0.5f), avatarPx = 120f))
    }

    @Test
    fun `a face filling the frame decodes at the floor`() {
        assertEquals(DetailsPeopleRow.DECODE_MIN_PX, DetailsPeopleRow.decodePx(FaceBox(0f, 0f, 1f, 1f), avatarPx = 120f))
    }

    @Test
    fun `a tiny face decodes at the ceiling`() {
        assertEquals(
            DetailsPeopleRow.DECODE_MAX_PX,
            DetailsPeopleRow.decodePx(FaceBox(0.5f, 0.5f, 0.5625f, 0.5625f), avatarPx = 120f),
        )
    }

    @Test
    fun `the decode stays within its bounds`() {
        val box = FaceBox(0.25f, 0.25f, 0.5f, 0.5f)
        for (avatarPx in listOf(1f, 60f, 120f, 240f, 480f, 10_000f)) {
            val px = DetailsPeopleRow.decodePx(box, avatarPx)
            assertTrue(px >= DetailsPeopleRow.DECODE_MIN_PX)
            assertTrue(px <= DetailsPeopleRow.DECODE_MAX_PX)
        }
        assertEquals(320, DetailsPeopleRow.DECODE_MIN_PX)
        assertEquals(1024, DetailsPeopleRow.DECODE_MAX_PX)
    }

    @Test
    fun `an unusable avatar size takes the floor`() {
        val box = FaceBox(0.25f, 0.25f, 0.5f, 0.5f)
        assertEquals(DetailsPeopleRow.DECODE_MIN_PX, DetailsPeopleRow.decodePx(box, avatarPx = 0f))
        assertEquals(DetailsPeopleRow.DECODE_MIN_PX, DetailsPeopleRow.decodePx(box, avatarPx = -40f))
        assertEquals(DetailsPeopleRow.DECODE_MIN_PX, DetailsPeopleRow.decodePx(box, avatarPx = Float.NaN))
    }

    // endregion
    // region the face box is held inside the frame

    @Test
    fun `a box inside the frame passes through unchanged`() {
        val box = FaceBox(0.25f, 0.25f, 0.5f, 0.75f)
        assertEquals(box, DetailsPeopleRow.clampedFaceBox(box))
    }

    @Test
    fun `fractions past the frame are clamped to it`() {
        // A face cut by the frame's edge, which the detector reports past the edge.
        assertEquals(
            FaceBox(0f, 0.5f, 0.25f, 1f),
            DetailsPeopleRow.clampedFaceBox(FaceBox(-0.125f, 0.5f, 0.25f, 1.25f)),
        )
    }

    @Test
    fun `swapped edges are put back in order`() {
        assertEquals(
            FaceBox(0.25f, 0.25f, 0.5f, 0.75f),
            DetailsPeopleRow.clampedFaceBox(FaceBox(0.5f, 0.75f, 0.25f, 0.25f)),
        )
    }

    @Test
    fun `a box with no area left is dropped`() {
        // Entirely past the right edge: both sides clamp onto it.
        assertNull(DetailsPeopleRow.clampedFaceBox(FaceBox(1.25f, 0.25f, 1.5f, 0.5f)))
        assertNull(DetailsPeopleRow.clampedFaceBox(FaceBox(0.5f, 0.25f, 0.5f, 0.5f)))
        assertNull(DetailsPeopleRow.clampedFaceBox(FaceBox(0.25f, 0.5f, 0.5f, 0.5f)))
    }

    @Test
    fun `an unreadable edge is dropped`() {
        assertNull(DetailsPeopleRow.clampedFaceBox(FaceBox(Float.NaN, 0.25f, 0.5f, 0.5f)))
        assertNull(DetailsPeopleRow.clampedFaceBox(FaceBox(0.25f, 0.25f, 0.5f, Float.NaN)))
    }

    // endregion
    // region every entry keys apart

    @Test
    fun `each face keys by its own id`() {
        val people = listOf(person(1L, "face-a"), person(2L, "face-b"), person(9L, "face-c", isOther = true))
        assertEquals(listOf("face-a", "face-b", "face-c"), DetailsPeopleRow.keys(people))
    }

    @Test
    fun `a face with no id keys by its person`() {
        val keys = DetailsPeopleRow.keys(listOf(person(1L, ""), person(2L, "")))

        assertEquals(2, keys.toSet().size)
        assertTrue(keys.none { it.isEmpty() })
    }

    @Test
    fun `entries that would share a key still get their own`() {
        // Two leftover faces of the one Unsorted bucket with no face id, and a face listed twice.
        val people = listOf(
            person(9L, "", isOther = true),
            person(9L, "", isOther = true),
            person(1L, "face-a"),
            person(2L, "face-a"),
        )
        val keys = DetailsPeopleRow.keys(people)

        assertEquals(people.size, keys.toSet().size)
    }

    @Test
    fun `the same list keys the same way every time`() {
        val people = listOf(person(9L, "", isOther = true), person(9L, "", isOther = true), person(1L, "face-a"))
        assertEquals(DetailsPeopleRow.keys(people), DetailsPeopleRow.keys(people))
    }

    // endregion
}
