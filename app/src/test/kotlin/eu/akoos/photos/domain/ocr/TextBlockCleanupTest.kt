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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pins what a page of readings is allowed to look like by the time it reaches the screen. All three
 * rules here are answers to what the models actually do: a rectangle fitted to a ragged blob comes
 * back at an angle, one run can be found twice with each finding holding some of the characters, and
 * a row of status icons reads as a character or two that no one can copy.
 */
class TextBlockCleanupTest {

    private val eps = 0.01f

    private fun block(text: String, confidence: Float, quad: TextQuad) =
        RecognizedTextBlock(text, confidence, quad)

    /** A [width] by [height] run centred on ([centreX], [centreY]) and turned by [degrees]. */
    private fun run(
        centreX: Float,
        centreY: Float,
        width: Float,
        height: Float,
        degrees: Float,
    ): TextQuad {
        val radians = Math.toRadians(degrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        fun corner(dx: Float, dy: Float) = FitPoint(
            centreX + dx * cosine - dy * sine,
            centreY + dx * sine + dy * cosine,
        )
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        return TextQuad(
            topLeft = corner(-halfWidth, -halfHeight),
            topRight = corner(halfWidth, -halfHeight),
            bottomRight = corner(halfWidth, halfHeight),
            bottomLeft = corner(-halfWidth, halfHeight),
        )
    }

    // ─── the tilt a fitted rectangle invents ────────────────────────────────

    @Test
    fun `the tilt of a level run is nothing`() {
        assertEquals(0f, quadTiltDegrees(TextQuad.upright(100f, 100f, 500f, 140f)), eps)
    }

    @Test
    fun `the tilt is measured whichever way the run leans`() {
        assertEquals(7f, quadTiltDegrees(run(300f, 200f, 400f, 40f, 7f)), eps)
        assertEquals(7f, quadTiltDegrees(run(300f, 200f, 400f, 40f, -7f)), eps)
    }

    @Test
    fun `the allowance loosens as a run gets shorter`() {
        // The same end-to-end wobble, over a shorter run, is a larger angle.
        assertEquals(3.43f, deskewAllowanceDegrees(10f), eps)
        assertEquals(5.71f, deskewAllowanceDegrees(6f), eps)
        assertTrue(deskewAllowanceDegrees(4f) > deskewAllowanceDegrees(8f))
        // A four-character label is about twice as long as it is tall, and hits the ceiling.
        assertEquals(MAX_DESKEW_DEGREES, deskewAllowanceDegrees(2f), eps)
        assertEquals(MAX_DESKEW_DEGREES, deskewAllowanceDegrees(0.5f), eps)
    }

    @Test
    fun `a run tilted under the threshold is laid flat and keeps its size`() {
        val tilted = run(300f, 200f, 400f, 40f, 2.5f)
        val flat = deskewed(tilted)
        assertEquals(flat.topLeft.y, flat.topRight.y, eps)
        assertEquals(flat.bottomLeft.y, flat.bottomRight.y, eps)
        // Its own length and height, not the upright extent of the tilted quad, which for a run this
        // long would be nearly half as tall again.
        assertEquals(400f, flat.topRight.x - flat.topLeft.x, eps)
        assertEquals(40f, flat.bottomLeft.y - flat.topLeft.y, eps)
        assertEquals(300f, (flat.topLeft.x + flat.topRight.x) / 2f, eps)
        assertEquals(200f, (flat.topLeft.y + flat.bottomLeft.y) / 2f, eps)
    }

    @Test
    fun `a run tilted past the threshold keeps every degree of it`() {
        val tilted = run(300f, 200f, 400f, 40f, 12f)
        assertSame(tilted, deskewed(tilted))
        assertEquals(12f, quadTiltDegrees(deskewed(tilted)), eps)
    }

    @Test
    fun `a short run at a button's angle is laid flat`() {
        // A four-character label on a level button, fitted seven degrees off.
        val label = run(200f, 100f, 60f, 30f, 7f)
        assertEquals(0f, quadTiltDegrees(deskewed(label)), eps)
    }

    @Test
    fun `a long run at the same angle keeps it`() {
        val line = run(300f, 200f, 400f, 40f, 7f)
        assertSame(line, deskewed(line))
    }

    @Test
    fun `a short run at an angle of its own keeps it`() {
        // One word on a sign held at a slant, which is the photograph's angle and not a fitting error.
        val sign = run(200f, 100f, 60f, 30f, 25f)
        assertSame(sign, deskewed(sign))
    }

    @Test
    fun `a vertical caption is not laid flat`() {
        val vertical = run(300f, 200f, 400f, 40f, 90f)
        assertSame(vertical, deskewed(vertical))
    }

    @Test
    fun `a run already square to the frame comes back untouched`() {
        val square = TextQuad.upright(100f, 100f, 500f, 140f)
        val flat = deskewed(square)
        assertEquals(square.topLeft.x, flat.topLeft.x, eps)
        assertEquals(square.topLeft.y, flat.topLeft.y, eps)
        assertEquals(square.bottomRight.x, flat.bottomRight.x, eps)
        assertEquals(square.bottomRight.y, flat.bottomRight.y, eps)
    }

    // ─── one run, one highlight ─────────────────────────────────────────────

    @Test
    fun `two quads over the same run leave one, the surer reading`() {
        val straight = block("Heading", 0.71f, TextQuad.upright(100f, 100f, 500f, 160f))
        val leaning = block("Heading", 0.94f, run(302f, 132f, 402f, 62f, 2f))
        val kept = fusedRuns(listOf(straight, leaning))
        assertEquals(1, kept.size)
        assertEquals("Heading", kept.single().text)
        assertEquals(0.94f, kept.single().confidence, eps)
    }

    @Test
    fun `a reading wholly inside another is folded into it`() {
        val whole = block("HEADING", 0.80f, TextQuad.upright(100f, 100f, 500f, 160f))
        val part = block("HEAD", 0.90f, TextQuad.upright(150f, 110f, 300f, 150f))
        val kept = fusedRuns(listOf(whole, part)).single()
        // Nothing the pair read is lost, and the characters both of them read take the better score.
        assertEquals("HEADING", kept.text)
        assertEquals(0.90f, kept.confidence, eps)
    }

    @Test
    fun `two pieces of one line fuse and keep both readings`() {
        val start = block("Frozen", 0.90f, TextQuad.upright(100f, 100f, 400f, 160f))
        val end = block("No More", 0.80f, TextQuad.upright(260f, 100f, 520f, 160f))
        val fused = fusedRuns(listOf(start, end)).single()
        assertEquals("Frozen No More", fused.text)
        // Six characters read at nine tenths and seven at eight, over the thirteen of them.
        assertEquals(0.846f, fused.confidence, eps)
    }

    @Test
    fun `a narrow sliver standing across a run is fused into it`() {
        val heading = block("Frozen No More", 0.88f, TextQuad.upright(100f, 100f, 500f, 160f))
        val sliver = block("No", 0.92f, TextQuad.upright(280f, 80f, 330f, 200f))
        // Sticking out above and below the heading is what holds the area ratio down: the sliver
        // covers every character in its own width and is still only half inside by area.
        assertTrue(quadOverlapRatio(heading.quad, sliver.quad) < MERGE_OVERLAP_RATIO)

        val fused = fusedRuns(listOf(heading, sliver)).single()
        assertEquals("Frozen No More", fused.text)
        assertEquals(0.92f, fused.confidence, eps)
    }

    @Test
    fun `the fused quad covers every piece`() {
        val heading = block("Frozen No More", 0.88f, TextQuad.upright(100f, 100f, 500f, 160f))
        val sliver = block("No", 0.92f, TextQuad.upright(280f, 80f, 330f, 200f))
        val bounds = fusedRuns(listOf(heading, sliver)).single().bounds
        assertEquals(100f, bounds.left, eps)
        assertEquals(80f, bounds.top, eps)
        assertEquals(500f, bounds.right, eps)
        assertEquals(200f, bounds.bottom, eps)
    }

    @Test
    fun `a chain of overlapping pieces comes out as one run`() {
        // The first and last touch only at their margins; they arrive together through the middle one.
        val chain = chainOfThree()
        assertTrue(quadOverlapRatio(chain[0].quad, chain[2].quad) < MERGE_OVERLAP_RATIO)

        val fused = fusedRuns(chain).single()
        assertEquals("one two three", fused.text)
        assertEquals(100f, fused.bounds.left, eps)
        assertEquals(500f, fused.bounds.right, eps)
    }

    @Test
    fun `the fused run does not depend on the order the page arrived in`() {
        val chain = chainOfThree()
        val expected = fusedRuns(chain).single()
        for (order in permutationsOfThree()) {
            val fused = fusedRuns(order.map { chain[it] }).single()
            assertEquals(expected.text, fused.text)
            assertEquals(expected.confidence, fused.confidence, eps)
            assertEquals(expected.quad, fused.quad)
        }
    }

    @Test
    fun `two lines that merely touch both survive`() {
        // Neighbouring lines of a paragraph share only the margin each was grown by.
        val first = block("first line", 0.90f, TextQuad.upright(100f, 100f, 500f, 140f))
        val second = block("second line", 0.60f, TextQuad.upright(100f, 135f, 500f, 175f))
        assertEquals(2, fusedRuns(listOf(first, second)).size)
    }

    @Test
    fun `two columns running into each other both survive`() {
        // The same band across the page, and no span along it worth speaking of.
        val left = block("left column", 0.90f, TextQuad.upright(100f, 100f, 310f, 160f))
        val right = block("right column", 0.90f, TextQuad.upright(300f, 100f, 520f, 160f))
        assertEquals(2, fusedRuns(listOf(left, right)).size)
    }

    @Test
    fun `equally sure readings hand the run to the longer one`() {
        val short = block("Head", 0.90f, TextQuad.upright(100f, 100f, 500f, 160f))
        val long = block("Heading", 0.90f, TextQuad.upright(102f, 101f, 498f, 159f))
        val fused = fusedRuns(listOf(short, long)).single()
        assertEquals("Heading", fused.text)
        assertEquals(0.90f, fused.confidence, eps)
    }

    @Test
    fun `separate runs are never fused`() {
        val left = block("left", 0.90f, TextQuad.upright(100f, 100f, 300f, 160f))
        val right = block("right", 0.90f, TextQuad.upright(400f, 100f, 600f, 160f))
        assertEquals(2, fusedRuns(listOf(left, right)).size)
    }

    @Test
    fun `overlap is measured against the smaller of the two`() {
        val big = TextQuad.upright(0f, 0f, 100f, 100f)
        assertEquals(1f, quadOverlapRatio(big, big), eps)
        assertEquals(1f, quadOverlapRatio(big, TextQuad.upright(20f, 20f, 60f, 60f)), eps)
        assertEquals(0f, quadOverlapRatio(big, TextQuad.upright(200f, 200f, 300f, 300f)), eps)
        // A quarter of the smaller square's area, whichever way round the pair is given.
        val quarter = TextQuad.upright(50f, 50f, 150f, 150f)
        assertEquals(0.25f, quadOverlapRatio(big, quarter), eps)
        assertEquals(0.25f, quadOverlapRatio(quarter, big), eps)
    }

    /** Three pieces of one line, each running into the next and only into the next. */
    private fun chainOfThree() = listOf(
        block("one", 0.90f, TextQuad.upright(100f, 100f, 300f, 160f)),
        block("two", 0.80f, TextQuad.upright(190f, 100f, 400f, 160f)),
        block("three", 0.70f, TextQuad.upright(290f, 100f, 500f, 160f)),
    )

    private fun permutationsOfThree() = listOf(
        listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
        listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
    )

    // ─── what counts as text at all ─────────────────────────────────────────

    @Test
    fun `nothing readable is not text`() {
        assertFalse(looksReadable("", 0.99f))
        assertFalse(looksReadable("   ", 0.99f))
    }

    @Test
    fun `a reading the model is unsure of is dropped`() {
        assertFalse(looksReadable("uncertain", 0.42f))
    }

    @Test
    fun `a run of marks with no letter or digit in it is not text`() {
        // What a row of status icons comes back as: glyph-shaped, confidently, and unreadable.
        assertFalse(looksReadable("|", 0.95f))
        assertFalse(looksReadable(".-.", 0.88f))
        assertFalse(looksReadable("«»", 0.91f))
    }

    @Test
    fun `one or two characters have to clear the higher bar`() {
        assertFalse(looksReadable("O", 0.62f))
        assertFalse(looksReadable("l1", 0.70f))
        assertTrue(looksReadable("7", 0.95f))
        assertTrue(looksReadable("OK", 0.86f))
    }

    @Test
    fun `a whole word is kept on the ordinary bar`() {
        assertTrue(looksReadable("Fahrenheit", 0.55f))
        assertTrue(looksReadable("221b", 0.55f))
    }

    // ─── the whole pass ─────────────────────────────────────────────────────

    @Test
    fun `a page is levelled and fused in one pass`() {
        val heading = block("Heading", 0.70f, run(300f, 130f, 400f, 60f, 1f))
        val duplicate = block("Heading", 0.92f, TextQuad.upright(100f, 100f, 500f, 160f))
        val slanted = block("on a sign", 0.88f, run(300f, 400f, 300f, 50f, 22f))
        val cleaned = cleanTextBlocks(listOf(heading, duplicate, slanted))

        assertEquals(2, cleaned.size)
        assertEquals("Heading", cleaned[0].text)
        assertEquals(0.92f, cleaned[0].confidence, eps)
        assertEquals(0f, quadTiltDegrees(cleaned[0].quad), eps)
        // The photograph's own angle is not something to correct.
        assertEquals(22f, quadTiltDegrees(cleaned[1].quad), eps)
    }

    @Test
    fun `a single reading passes straight through`() {
        val only = listOf(block("alone", 0.90f, TextQuad.upright(10f, 10f, 90f, 30f)))
        assertEquals(only, cleanTextBlocks(only))
    }

    @Test
    fun `an empty page stays empty`() {
        assertEquals(emptyList<RecognizedTextBlock>(), cleanTextBlocks(emptyList()))
    }
}
