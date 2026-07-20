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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [edgeScrollVelocity], the curve that turns "how near an edge is the finger" into how
 * fast the albums grid pulls itself along under a held card.
 *
 * It runs once a frame off a drag, so the values it is asked for are not tidy ones. A finger can be
 * dragged clean off the top of the window, giving a negative depth; a short window can be shallower
 * than twice the band, leaving the two sides to argue over the middle; a filtered grid can leave no
 * window at all. None of those may produce a pull that reverses, overshoots, or never stops, because
 * the caller feeds the result straight into a scroll with nothing else standing between them.
 *
 * No Android, no Compose: plain JVM assertions on floats.
 */
class ReorderAutoScrollTest {

    // A roomy window with the band well clear of the middle, which is the ordinary case.
    private val top = 0f
    private val bottom = 1000f
    private val band = 150f
    private val max = 500f

    private fun velocityAt(
        pointerY: Float,
        bandDepth: Float = band,
        windowBottom: Float = bottom,
    ) = edgeScrollVelocity(
        pointerY = pointerY,
        contentTop = top,
        contentBottom = windowBottom,
        band = bandDepth,
        maxVelocity = max,
    )

    @Test
    fun `a card parked between the bands does not pull at all`() {
        assertEquals(0f, velocityAt(500f), 0.0001f)
        assertEquals(0f, velocityAt(200f), 0.0001f)
        assertEquals(0f, velocityAt(800f), 0.0001f)
    }

    @Test
    fun `the band edge itself is the last still point`() {
        // Exactly on the boundary is outside the band, so the grid holds. One pixel in and it moves,
        // which is what makes the pull start from a crawl instead of a jump.
        assertEquals(0f, velocityAt(150f), 0.0001f)
        assertEquals(0f, velocityAt(850f), 0.0001f)
        assertTrue(velocityAt(149f) < 0f)
        assertTrue(velocityAt(851f) > 0f)
    }

    @Test
    fun `the top band pulls towards the start and the bottom band towards the end`() {
        // Sign is the whole direction signal the caller gets, so it has to be this way round: the
        // finger near the top wants earlier albums on screen, which is a scroll back.
        assertTrue(velocityAt(20f) < 0f)
        assertTrue(velocityAt(980f) > 0f)
    }

    @Test
    fun `the ramp is linear and reaches full speed at the edge`() {
        assertEquals(-max, velocityAt(0f), 0.0001f)
        assertEquals(-max / 2f, velocityAt(75f), 0.0001f)
        assertEquals(max, velocityAt(1000f), 0.0001f)
        assertEquals(max / 2f, velocityAt(925f), 0.0001f)
    }

    @Test
    fun `speed only ever rises on the way to an edge`() {
        // A pull that dipped anywhere on the run in would read as the grid stumbling.
        var previous = 0f
        for (step in 0..150) {
            val speed = -velocityAt(150f - step)
            assertTrue("depth $step went backwards", speed >= previous - 0.0001f)
            previous = speed
        }
        assertEquals(max, previous, 0.0001f)
    }

    @Test
    fun `a finger dragged past an edge holds full speed rather than reversing`() {
        // The card can be dragged clean out of the window, and past the edge the ramp's own
        // arithmetic would carry on past 1 — so it is capped, not extrapolated.
        assertEquals(-max, velocityAt(-1f), 0.0001f)
        assertEquals(-max, velocityAt(-400f), 0.0001f)
        assertEquals(-max, velocityAt(-100_000f), 0.0001f)
        assertEquals(max, velocityAt(1001f), 0.0001f)
        assertEquals(max, velocityAt(100_000f), 0.0001f)
    }

    @Test
    fun `bands too deep for the window meet in the middle instead of overlapping`() {
        // 300 either side of a 200-tall window would have both sides claiming every point. Clamped
        // to half, the midpoint belongs to neither and each side still reaches full speed at its own
        // edge.
        val short = 200f
        assertEquals(0f, velocityAt(100f, bandDepth = 300f, windowBottom = short), 0.0001f)
        assertEquals(-max, velocityAt(0f, bandDepth = 300f, windowBottom = short), 0.0001f)
        assertEquals(max, velocityAt(short, bandDepth = 300f, windowBottom = short), 0.0001f)
        assertEquals(-max / 2f, velocityAt(50f, bandDepth = 300f, windowBottom = short), 0.0001f)
        assertEquals(max / 2f, velocityAt(150f, bandDepth = 300f, windowBottom = short), 0.0001f)
    }

    @Test
    fun `a window with no room to scroll in yields no pull`() {
        // Both edges on the same line, and an inverted pair, which is what a viewport padded past
        // its own height reports.
        assertEquals(0f, velocityAt(500f, windowBottom = top), 0.0001f)
        assertEquals(0f, velocityAt(500f, windowBottom = top - 200f), 0.0001f)
    }

    @Test
    fun `a band with no depth yields no pull`() {
        // Nothing sets this today, but a zero band is the natural way to switch the pull off and it
        // must not divide the ramp by it.
        assertEquals(0f, velocityAt(0f, bandDepth = 0f), 0.0001f)
        assertEquals(0f, velocityAt(1000f, bandDepth = 0f), 0.0001f)
        assertEquals(0f, velocityAt(0f, bandDepth = -50f), 0.0001f)
    }

    @Test
    fun `no reachable point asks for more than full speed`() {
        // The result is scaled by the frame's own elapsed time and handed to scrollBy, so an
        // out-of-range magnitude here would show up as the grid lurching.
        for (y in -2000..3000 step 7) {
            val speed = velocityAt(y.toFloat())
            assertTrue("y=$y ran to $speed", speed >= -max - 0.0001f && speed <= max + 0.0001f)
        }
    }
}
