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

package eu.akoos.photos.util

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the fingerprint itself — the 64 bits [PerceptualHash.dHash] reads out of a photo — as opposed
 * to the clustering built on top of it, which is covered elsewhere.
 *
 * This is the duplicate finder's foundation, and the finder offers to DELETE what it matches, so the
 * bits have to mean what the clustering assumes. Three things are pinned: the grid and bit order (a
 * silent change to either re-fingerprints the whole library, and old and new hashes then compare as
 * unrelated photos), the luminance weights, and the fact that the scaled copy is recycled while the
 * caller's bitmap never is.
 *
 * The uniform-image blind spot is pinned as DOCUMENTED behaviour, not as a defect: a flat frame has
 * no left-to-right differences to read, so every flat frame fingerprints identically and the finder
 * groups them. That is the known cost of the 12/64 threshold.
 */
class PerceptualHashDHashTest {

    @Before
    fun setUp() = mockkStatic(Bitmap::class)

    @After
    fun tearDown() = unmockkStatic(Bitmap::class)

    /** Opaque grey whose luminance is exactly [value]. */
    private fun grey(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private fun rowsOf(vararg rows: List<Int>): List<List<Int>> = rows.toList()

    private fun flat(value: Int): List<List<Int>> = List(8) { List(9) { grey(value) } }

    /** dHash over a 9x8 grid of ARGB pixels, with the scaling step standing in for the real one. */
    private fun hashOf(rows: List<List<Int>>): Long = hashOf(rows, mockk(), mockk(relaxed = true))

    private fun hashOf(rows: List<List<Int>>, source: Bitmap, scaled: Bitmap): Long {
        every { Bitmap.createScaledBitmap(source, 9, 8, true) } returns scaled
        every { scaled.getPixel(any(), any()) } answers { rows[secondArg<Int>()][firstArg<Int>()] }
        return PerceptualHash.dHash(source)
    }

    // ── the grid and the bit order ──────────────────────────────────────────────────────────────

    @Test
    fun `a row that darkens to the right sets every one of its bits`() {
        val ramp = (0 until 9).map { grey(200 - it * 20) }
        assertEquals(-1L, hashOf(List(8) { ramp }))
    }

    @Test
    fun `a row that brightens to the right sets none of its bits`() {
        val ramp = (0 until 9).map { grey(20 + it * 20) }
        assertEquals(0L, hashOf(List(8) { ramp }))
    }

    @Test
    fun `the first comparison of the first row is the lowest bit`() {
        val step = listOf(grey(200)) + List(8) { grey(100) }
        assertEquals(1L, hashOf(rowsOf(step, *Array(7) { List(9) { grey(100) } })))
    }

    @Test
    fun `each row occupies the next eight bits, in top-to-bottom order`() {
        // Eight comparisons per row across nine pixels, so row n starts at bit 8n. A change here
        // renumbers every stored fingerprint in the library.
        val step = listOf(grey(200)) + List(8) { grey(100) }
        val flatRow = List(9) { grey(100) }
        for (row in 0 until 8) {
            val rows = (0 until 8).map { if (it == row) step else flatRow }
            assertEquals("row $row starts at bit ${row * 8}", 1L shl (row * 8), hashOf(rows))
        }
    }

    @Test
    fun `the comparison is strict, so equal neighbours leave the bit clear`() {
        assertEquals(0L, hashOf(flat(128)))
    }

    // ── luminance ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `green reads as brighter than red and red as brighter than blue`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val flatRow = List(9) { grey(0) }
        fun firstBitOf(left: Int, right: Int): Long {
            val row = listOf(left, right) + List(7) { grey(0) }
            return hashOf(rowsOf(row, *Array(7) { flatRow })) and 1L
        }
        assertEquals("green over red sets the bit", 1L, firstBitOf(green, red))
        assertEquals("red under green leaves it clear", 0L, firstBitOf(red, green))
        assertEquals("red over blue sets the bit", 1L, firstBitOf(red, blue))
        assertEquals("blue under red leaves it clear", 0L, firstBitOf(blue, red))
    }

    @Test
    fun `the alpha channel is not part of the fingerprint`() {
        // A transparent white and an opaque white weigh the same, so a PNG's alpha never moves a bit.
        val opaqueWhite = 0xFFFFFFFF.toInt()
        val transparentWhite = 0x00FFFFFF
        val black = 0xFF000000.toInt()
        val flatRow = List(9) { grey(0) }
        val opaqueRow = listOf(opaqueWhite, black) + List(7) { grey(0) }
        val transparentRow = listOf(transparentWhite, black) + List(7) { grey(0) }
        assertEquals(
            hashOf(rowsOf(opaqueRow, *Array(7) { flatRow })),
            hashOf(rowsOf(transparentRow, *Array(7) { flatRow })),
        )
    }

    // ── what the fingerprint cannot see ─────────────────────────────────────────────────────────

    @Test
    fun `a photo with no left-to-right change fingerprints as zero whatever its brightness`() {
        // The documented blind spot: black, white and every flat grey between them are one and the
        // same fingerprint, so the finder groups them. Deliberate, and the reason a threshold of 12
        // is safe for real photos.
        assertEquals(0L, hashOf(flat(0)))
        assertEquals(0L, hashOf(flat(255)))
        assertEquals(0L, hashOf(flat(128)))
    }

    @Test
    fun `a black frame and a white frame are indistinguishable to the duplicate finder`() {
        val black = hashOf(flat(0))
        val white = hashOf(flat(255))
        assertEquals(0, PerceptualHash.distance(black, white))
        assertTrue(PerceptualHash.distance(black, white) <= PerceptualHash.SIMILARITY_THRESHOLD)
        // And the clustering agrees, which is what the finder actually acts on.
        val roots = PerceptualHash.clusterSimilar(longArrayOf(black, white), PerceptualHash.SIMILARITY_THRESHOLD)
        assertEquals(roots[0], roots[1])
    }

    @Test
    fun `a purely vertical gradient is invisible to the fingerprint`() {
        // Every comparison is horizontal, so rows that differ only from each other read as flat.
        val rows = (0 until 8).map { y -> List(9) { grey(y * 30) } }
        assertEquals(0L, hashOf(rows))
    }

    // ── the fingerprint as a whole ──────────────────────────────────────────────────────────────

    @Test
    fun `inverting every comparison inverts every bit`() {
        val darkening = (0 until 9).map { grey(200 - it * 20) }
        val brightening = darkening.reversed()
        assertEquals(64, PerceptualHash.distance(hashOf(List(8) { darkening }), hashOf(List(8) { brightening })))
    }

    @Test
    fun `the same picture read twice gives the same fingerprint`() {
        val rows = (0 until 8).map { y -> (0 until 9).map { x -> grey((x * 17 + y * 43) % 256) } }
        assertEquals(hashOf(rows), hashOf(rows))
    }

    @Test
    fun `a photo that differs in one comparison sits one bit away`() {
        val flatRow = List(9) { grey(100) }
        val nudged = listOf(grey(200)) + List(8) { grey(100) }
        val a = hashOf(List(8) { flatRow })
        val b = hashOf(rowsOf(nudged, *Array(7) { flatRow }))
        assertEquals(1, PerceptualHash.distance(a, b))
        assertTrue(PerceptualHash.distance(a, b) <= PerceptualHash.SIMILARITY_THRESHOLD)
    }

    // ── the caller's bitmap ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the scaled copy is released and the caller's bitmap is left alone`() {
        val source = mockk<Bitmap>(relaxed = true)
        val scaled = mockk<Bitmap>(relaxed = true)
        hashOf(flat(100), source, scaled)
        verify(exactly = 1) { scaled.recycle() }
        verify(exactly = 0) { source.recycle() }
    }

    @Test
    fun `a bitmap already at the fingerprint size is never recycled underneath its owner`() {
        // The platform hands the source straight back when no scaling is needed; recycling it there
        // would destroy a bitmap the caller still owns.
        val source = mockk<Bitmap>(relaxed = true)
        hashOf(flat(100), source, source)
        verify(exactly = 0) { source.recycle() }
    }

    @Test
    fun `the fingerprint is read off a nine by eight grid with filtering on`() {
        val source = mockk<Bitmap>()
        val scaled = mockk<Bitmap>(relaxed = true)
        hashOf(flat(100), source, scaled)
        verify(exactly = 1) { Bitmap.createScaledBitmap(source, 9, 8, true) }
    }

    // ── the version that governs the stored fingerprints ────────────────────────────────────────

    @Test
    fun `the algorithm version is the one every stored fingerprint was computed under`() {
        assertEquals(1, PerceptualHash.DHASH_ALGO_VERSION)
        assertEquals(12, PerceptualHash.SIMILARITY_THRESHOLD)
    }
}
