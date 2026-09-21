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

package eu.akoos.photos.domain.ocr

import eu.akoos.photos.util.FitPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape a detector reports through. The corners carry the text's own angle, so the derived
 * upright extent has to be the hull of all four and not the first and third: a slanted sign read off
 * a wide box would otherwise claim the lines above and below it.
 */
class TextRecognitionTest {

    private val eps = 0.001f

    @Test
    fun `an upright quad lists its corners clockwise from the top left`() {
        val quad = TextQuad.upright(10f, 20f, 110f, 60f)
        assertEquals(FitPoint(10f, 20f), quad.topLeft)
        assertEquals(FitPoint(110f, 20f), quad.topRight)
        assertEquals(FitPoint(110f, 60f), quad.bottomRight)
        assertEquals(FitPoint(10f, 60f), quad.bottomLeft)
        assertEquals(listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft), quad.corners)
    }

    @Test
    fun `an upright quad is its own extent`() {
        val bounds = TextQuad.upright(10f, 20f, 110f, 60f).bounds
        assertEquals(10f, bounds.left, eps)
        assertEquals(20f, bounds.top, eps)
        assertEquals(110f, bounds.right, eps)
        assertEquals(60f, bounds.bottom, eps)
    }

    @Test
    fun `a slanted quad takes its extent from every corner`() {
        val quad = TextQuad(
            topLeft = FitPoint(20f, 40f),
            topRight = FitPoint(120f, 10f),
            bottomRight = FitPoint(130f, 50f),
            bottomLeft = FitPoint(30f, 80f),
        )
        assertEquals(20f, quad.bounds.left, eps)
        assertEquals(10f, quad.bounds.top, eps)
        assertEquals(130f, quad.bounds.right, eps)
        assertEquals(80f, quad.bounds.bottom, eps)
    }

    @Test
    fun `a block reports the extent of its own quad`() {
        val quad = TextQuad.upright(5f, 5f, 25f, 15f)
        assertEquals(quad.bounds, RecognizedTextBlock("hello", 0.8f, quad).bounds)
    }

    @Test
    fun `the derived corners stay out of equality`() {
        // corners and bounds are computed per instance, so they would break equality if they were
        // constructor properties. Recomposition leans on this comparing equal across reads.
        assertEquals(TextQuad.upright(0f, 0f, 10f, 10f), TextQuad.upright(0f, 0f, 10f, 10f))
        assertEquals(
            RecognizedTextBlock("a", 0.5f, TextQuad.upright(0f, 0f, 10f, 10f)),
            RecognizedTextBlock("a", 0.5f, TextQuad.upright(0f, 0f, 10f, 10f)),
        )
    }

    @Test
    fun `a block with no text still carries its outline`() {
        // Detection reports where the words are before anything reads them, so a block with an empty
        // string is a normal result and everything derived from the quad has to keep working.
        val block = RecognizedTextBlock("", 0.71f, TextQuad.upright(4f, 6f, 40f, 18f))
        assertEquals(4f, block.bounds.left, eps)
        assertEquals(18f, block.bounds.bottom, eps)
        assertTrue(block.confidence > 0f)
    }
}
