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

package eu.akoos.photos.data.ocr

import eu.akoos.photos.domain.ocr.TextQuad
import eu.akoos.photos.util.FitPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins everything between a detected outline and the strip the reading network is handed.
 *
 * All of it is arithmetic on four corners, so it is checked here rather than on a device: a crop cut
 * at the wrong size reads the neighbouring line as well, a missed quarter turn hands a column of
 * text to a network that only reads rows, and a run sized by count rather than by width is how a
 * single banner photo turns into a hundred megabyte allocation.
 */
class OcrReadGeometryTest {

    private fun quad(width: Float, height: Float): TextQuad = TextQuad.upright(0f, 0f, width, height)

    // ─── the crop's own size ────────────────────────────────────────────────

    @Test
    fun `a level line is cut at its own size`() {
        val size = cropSizeFor(quad(200f, 40f))
        assertEquals(200, size.width)
        assertEquals(40, size.height)
        assertFalse(size.turnsUpright)
        assertEquals(200, size.readWidth)
        assertEquals(40, size.readHeight)
    }

    @Test
    fun `a slanted line is cut as the straightened line, not the box around it`() {
        // The same words photographed at an angle: the upright box around them is far taller than
        // the line is, and cutting that in would carry half of the line above along with it.
        val slanted = TextQuad(
            topLeft = FitPoint(0f, 30f),
            topRight = FitPoint(40f, 0f),
            bottomRight = FitPoint(46f, 8f),
            bottomLeft = FitPoint(6f, 38f),
        )
        val size = cropSizeFor(slanted)
        assertEquals(50, size.width)
        assertEquals(10, size.height)
        assertEquals(38f, slanted.bounds.bottom - slanted.bounds.top, 0.01f)
    }

    @Test
    fun `the longer of two opposing edges wins`() {
        // A quad whose corners drifted still has to cover every glyph it was drawn around.
        val uneven = TextQuad(
            topLeft = FitPoint(0f, 0f),
            topRight = FitPoint(100f, 0f),
            bottomRight = FitPoint(120f, 40f),
            bottomLeft = FitPoint(0f, 40f),
        )
        assertEquals(120, cropSizeFor(uneven).width)
    }

    @Test
    fun `a column of text is turned upright`() {
        val size = cropSizeFor(quad(30f, 200f))
        assertTrue(size.turnsUpright)
        assertEquals(200, size.readWidth)
        assertEquals(30, size.readHeight)
        assertTrue(size.readRatio > 1f)
    }

    @Test
    fun `the turn starts exactly at the stated ratio`() {
        assertFalse(cropSizeFor(quad(100f, 149f)).turnsUpright)
        assertTrue(cropSizeFor(quad(100f, 150f)).turnsUpright)
    }

    @Test
    fun `a crop is never cut taller than the reader can use`() {
        // The reader squeezes to 48 pixels regardless, so a line spanning a 4000 pixel photo would
        // otherwise allocate megabytes to be thrown away one run later.
        val size = cropSizeFor(quad(4000f, 400f))
        assertEquals(CROP_HEIGHT_LIMIT, size.readHeight)
        assertEquals(960, size.readWidth)
        assertEquals(10f, size.readRatio, 0.05f)
    }

    @Test
    fun `a turned crop is bounded on the side that becomes its height`() {
        val size = cropSizeFor(quad(400f, 4000f))
        assertTrue(size.turnsUpright)
        assertEquals(CROP_HEIGHT_LIMIT, size.readHeight)
        assertEquals(960, size.readWidth)
    }

    @Test
    fun `a crop smaller than the limit is left alone`() {
        val size = cropSizeFor(quad(120f, 20f))
        assertEquals(120, size.width)
        assertEquals(20, size.height)
    }

    @Test
    fun `a degenerate quad still yields something a bitmap can be made of`() {
        val size = cropSizeFor(quad(0f, 0f))
        assertTrue(size.width >= 1)
        assertTrue(size.height >= 1)
    }

    // ─── the run's shared width ─────────────────────────────────────────────

    @Test
    fun `an ordinary line lands on the narrowest input`() {
        assertEquals(READ_MIN_WIDTH, readBatchWidth(1f))
        assertEquals(READ_MIN_WIDTH, readBatchWidth(READ_MIN_WIDTH.toFloat() / READ_HEIGHT))
    }

    @Test
    fun `a wide line widens the run up to the ceiling`() {
        assertEquals(480, readBatchWidth(10f))
        assertEquals(READ_MAX_WIDTH, readBatchWidth(60f))
    }

    @Test
    fun `a full width line is read wide enough to stay legible`() {
        // A line spanning a large screenshot runs many times as long as it is tall. Held to a narrow
        // ceiling it is squeezed until the reader gives nothing back, so the ceiling has to leave a
        // long line's glyphs near their own width and sit well above a single ordinary run.
        assertTrue("a long line must not be crushed", READ_MAX_WIDTH >= 2000)
        assertEquals(READ_MAX_WIDTH, readBatchWidth(80f))
    }

    @Test
    fun `a nonsense ratio falls back to the narrowest input`() {
        assertEquals(READ_MIN_WIDTH, readBatchWidth(0f))
        assertEquals(READ_MIN_WIDTH, readBatchWidth(Float.NaN))
    }

    @Test
    fun `the content stops where the picture stops`() {
        assertEquals(320, readContentWidth(20f, 320))
        assertEquals(96, readContentWidth(2f, 320))
        assertEquals(1, readContentWidth(0f, 320))
    }

    // ─── how runs are sized ─────────────────────────────────────────────────

    @Test
    fun `ordinary lines fill a run to the batch ceiling`() {
        assertEquals(listOf(READ_MAX_BATCH, 2), planReadBatches(List(8) { 2f }))
    }

    @Test
    fun `nothing to read is no runs at all`() {
        assertTrue(planReadBatches(emptyList()).isEmpty())
    }

    @Test
    fun `wide crops make shorter runs so the output stays the same size`() {
        // Six banner-shaped crops in one run would ask for six times the columns of an ordinary run,
        // and each column carries a score for every one of eighteen thousand characters.
        val sizes = planReadBatches(List(6) { 40f })
        assertTrue(sizes.toString(), sizes.all { it <= 2 })
        assertEquals(6, sizes.sum())
    }

    @Test
    fun `every crop lands in exactly one run`() {
        val ratios = listOf(1f, 2f, 3f, 6f, 9f, 12f, 18f, 25f, 40f).sorted()
        assertEquals(ratios.size, planReadBatches(ratios).sum())
    }

    @Test
    fun `no run goes past the column budget unless one crop is that wide on its own`() {
        val ratios = listOf(1f, 2f, 5f, 8f, 14f, 20f, 30f, 45f)
        var start = 0
        for (size in planReadBatches(ratios)) {
            val width = readBatchWidth(ratios[start + size - 1])
            assertTrue("$size at $start", size == 1 || width * size <= READ_WIDTH_BUDGET)
            assertTrue(size <= READ_MAX_BATCH)
            start += size
        }
        assertEquals(ratios.size, start)
    }

    @Test
    fun `a single crop always forms a run however wide it is`() {
        assertEquals(listOf(1), planReadBatches(listOf(400f)))
    }

    // ─── which way up the line runs ─────────────────────────────────────────

    @Test
    fun `an upright line is left alone`() {
        assertFalse(classifierTurnsCrop(upright = 0.99f, upsideDown = 0.01f))
    }

    @Test
    fun `a confidently inverted line is turned`() {
        assertTrue(classifierTurnsCrop(upright = 0.02f, upsideDown = 0.98f))
    }

    @Test
    fun `winning is not enough on its own`() {
        // Turning a line the wrong way round costs the whole line, so a near-tie is left as it is.
        assertFalse(classifierTurnsCrop(upright = 0.45f, upsideDown = 0.55f))
        assertFalse(classifierTurnsCrop(upright = 0.11f, upsideDown = UPSIDE_DOWN_THRESHOLD))
        assertTrue(classifierTurnsCrop(upright = 0.09f, upsideDown = 0.91f))
    }

    @Test
    fun `clearing the bar is not enough either`() {
        assertFalse(classifierTurnsCrop(upright = 0.97f, upsideDown = 0.95f))
    }
}
