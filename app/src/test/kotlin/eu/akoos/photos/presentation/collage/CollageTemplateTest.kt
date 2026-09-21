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

package eu.akoos.photos.presentation.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the collage layout catalogue: a template must always hold exactly as many cells as photos,
 * keep every cell inside the canvas, and tile it edge-to-edge with no gaps and no overlaps. A broken
 * layout would draw a photo off-canvas or leave a hole, so these invariants gate every builder.
 */
class CollageTemplateTest {

    private val eps = 1e-4f

    private fun allTemplates(): List<CollageTemplate> =
        (COLLAGE_MIN_PHOTOS..COLLAGE_MAX_PHOTOS).flatMap { collageTemplatesFor(it) }

    /** Interiors overlap (a shared edge is not an overlap), with a small epsilon for float slop. */
    private fun overlaps(a: CollageCell, b: CollageCell): Boolean =
        a.left < b.right - eps && b.left < a.right - eps &&
            a.top < b.bottom - eps && b.top < a.bottom - eps

    @Test
    fun everyTemplateHasOneCellPerPhoto() {
        for (count in COLLAGE_MIN_PHOTOS..COLLAGE_MAX_PHOTOS) {
            val templates = collageTemplatesFor(count)
            assertTrue("count $count should have at least one template", templates.isNotEmpty())
            for (t in templates) {
                assertEquals("template ${t.id} cell count", count, t.cellCount)
            }
        }
    }

    @Test
    fun everyCellStaysWithinTheCanvas() {
        for (t in allTemplates()) {
            for (c in t.cells) {
                assertTrue("${t.id}: left in range", c.left >= -eps && c.left <= 1f + eps)
                assertTrue("${t.id}: top in range", c.top >= -eps && c.top <= 1f + eps)
                assertTrue("${t.id}: right past left", c.right > c.left && c.right <= 1f + eps)
                assertTrue("${t.id}: bottom past top", c.bottom > c.top && c.bottom <= 1f + eps)
            }
        }
    }

    @Test
    fun everyTemplateTilesTheWholeCanvas() {
        for (t in allTemplates()) {
            val area = t.cells.sumOf { it.area.toDouble() }
            assertEquals("${t.id}: cells should cover the canvas", 1.0, area, 1e-3)
        }
    }

    @Test
    fun cellsNeverOverlap() {
        for (t in allTemplates()) {
            for (i in t.cells.indices) {
                for (j in i + 1 until t.cells.size) {
                    assertFalse("${t.id}: cells $i and $j overlap", overlaps(t.cells[i], t.cells[j]))
                }
            }
        }
    }

    @Test
    fun defaultTemplateIsTheFirstCandidate() {
        for (count in COLLAGE_MIN_PHOTOS..COLLAGE_MAX_PHOTOS) {
            assertEquals(collageTemplatesFor(count).first(), defaultCollageTemplate(count))
        }
    }

    @Test
    fun countsOutsideTheRangeHaveNoTemplates() {
        assertTrue(collageTemplatesFor(0).isEmpty())
        assertTrue(collageTemplatesFor(1).isEmpty())
        assertTrue(collageTemplatesFor(COLLAGE_MAX_PHOTOS + 1).isEmpty())
        assertNull(defaultCollageTemplate(1))
        assertNull(defaultCollageTemplate(COLLAGE_MAX_PHOTOS + 1))
    }

    @Test
    fun aspectRatiosAreWidthOverHeight() {
        assertEquals(1f, CollageAspect.SQUARE.ratio, 1e-6f)
        assertEquals(0.8f, CollageAspect.PORTRAIT_4_5.ratio, 1e-6f)
        assertEquals(1.5f, CollageAspect.LANDSCAPE_3_2.ratio, 1e-6f)
        assertTrue("portrait is taller than wide", CollageAspect.PORTRAIT_9_16.ratio < 1f)
        assertTrue("landscape is wider than tall", CollageAspect.LANDSCAPE_16_9.ratio > 1f)
    }

    @Test
    fun freeformTemplateIsFlaggedAndHasNoCells() {
        val ff = freeformCollageTemplate()
        assertTrue("freeform flag", ff.freeform)
        assertTrue("freeform has no cells", ff.cells.isEmpty())
        assertEquals("freeform id", "freeform", ff.id)
    }

    @Test
    fun gridTemplatesAreNeverFreeform() {
        for (t in allTemplates()) {
            assertFalse("${t.id} should not be freeform", t.freeform)
        }
    }

    @Test
    fun freeformHoldsMorePhotosThanAGrid() {
        assertTrue("freeform cap exceeds the grid cap", COLLAGE_FREEFORM_MAX > COLLAGE_MAX_PHOTOS)
    }
}
