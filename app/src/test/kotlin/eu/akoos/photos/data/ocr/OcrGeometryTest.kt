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
 * Pins the detector's post-processing, which is everything that happens between the network's
 * probability grid and the outlines the viewer draws. All of it is plain arithmetic on that grid, so
 * it is checked here rather than on a device with a real model: a wrong scale factor or a dropped
 * rotation puts every highlight beside the words it is supposed to sit on, and that is not something
 * to discover by looking at a photo.
 */
class OcrGeometryTest {

    private val eps = 0.01f

    // ─── the network's input grid ───────────────────────────────────────────

    @Test
    fun `a small frame is rounded up to whole strides`() {
        assertEquals(128 to 64, detectorInputSize(100, 50))
    }

    @Test
    fun `a frame never goes below one stride`() {
        assertEquals(STRIDE to STRIDE, detectorInputSize(10, 4))
        assertEquals(STRIDE to STRIDE, detectorInputSize(1, 1))
    }

    @Test
    fun `a large frame is brought within the side limit`() {
        val (width, height) = detectorInputSize(4000, 3000)
        assertEquals(LIMIT_SIDE_MIN, width)
        assertEquals(736, height)
        assertTrue(width % STRIDE == 0 && height % STRIDE == 0)
    }

    @Test
    fun `the input grid keeps the frame's own proportions`() {
        val (width, height) = detectorInputSize(1920, 1080)
        assertEquals(960, width)
        assertEquals(544, height)
    }

    @Test
    fun `a raised limit carries the frame further into the grid`() {
        val (width, height) = detectorInputSize(4000, 3000, limitSide = 1600)
        assertEquals(1600, width)
        assertEquals(1216, height)
        assertTrue(width % STRIDE == 0 && height % STRIDE == 0)
    }

    // ─── how far the frame is carried ───────────────────────────────────────

    @Test
    fun `a device with room reads a large photo well past the old limit`() {
        // The one number that decides what can be found at all: a 12 megapixel photo on a phone
        // whose heap runs to half a gigabyte is worth carrying much further than 960 pixels.
        val limit = detectionLimitSide(4032, 3024, maxHeapBytes = 512L * 1024 * 1024)
        assertTrue("$limit", limit > LIMIT_SIDE_MIN)
        assertTrue("$limit", limit <= LIMIT_SIDE_MAX)
    }

    @Test
    fun `a constrained device reads exactly as it did before`() {
        // The floor is what keeps this from ever being a regression: too little heap to afford more
        // means the reference implementation's own default, not less than it.
        assertEquals(LIMIT_SIDE_MIN, detectionLimitSide(4032, 3024, 128L * 1024 * 1024))
        assertEquals(LIMIT_SIDE_MIN, detectionLimitSide(4032, 3024, 0L))
    }

    @Test
    fun `a small frame sets its own limit and is never enlarged`() {
        // Nothing is behind an upscale, so a frame that is already small costs no more than before.
        val limit = detectionLimitSide(1200, 900, 512L * 1024 * 1024)
        assertTrue("$limit", limit <= 1200)
    }

    @Test
    fun `the ceiling holds however much heap there is`() {
        assertEquals(LIMIT_SIDE_MAX, detectionLimitSide(6000, 4000, 8L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a long thin frame is carried further than a square one`() {
        // The budget is pixels, and a panorama spends far fewer of them per pixel of its long side.
        val heap = 512L * 1024 * 1024
        assertTrue(detectionLimitSide(4000, 1000, heap) > detectionLimitSide(4000, 4000, heap))
    }

    @Test
    fun `more heap is never fewer pixels`() {
        var previous = 0
        for (megabytes in listOf(64L, 128L, 256L, 512L, 1024L)) {
            val limit = detectionLimitSide(4032, 3024, megabytes * 1024 * 1024)
            assertTrue("$megabytes MB gave $limit", limit >= previous)
            previous = limit
        }
    }

    // ─── geometry ───────────────────────────────────────────────────────────

    @Test
    fun `a hull drops interior and collinear points`() {
        val filled = buildList {
            for (x in 0..4) for (y in 0..3) add(FitPoint(x.toFloat(), y.toFloat()))
        }
        val hull = convexHull(filled)
        assertEquals(4, hull.size)
        assertEquals(0f, hull.minOf { it.x }, eps)
        assertEquals(4f, hull.maxOf { it.x }, eps)
        assertEquals(0f, hull.minOf { it.y }, eps)
        assertEquals(3f, hull.maxOf { it.y }, eps)
    }

    @Test
    fun `the smallest rectangle around a diamond is the turned one`() {
        // A square standing on its corner: the axis-aligned box has twice the area of the rectangle
        // that follows the shape, which is exactly the difference that keeps slanted text tight.
        val diamond = listOf(
            FitPoint(10f, 0f), FitPoint(20f, 10f), FitPoint(10f, 20f), FitPoint(0f, 10f),
        )
        val rect = minimumAreaRectangle(diamond)
        assertEquals(4, rect.size)
        assertEquals(200f, quadArea(rect), 0.5f)
    }

    @Test
    fun `an axis-aligned block keeps its own corners`() {
        val rect = minimumAreaRectangle(
            listOf(FitPoint(5f, 5f), FitPoint(15f, 5f), FitPoint(15f, 9f), FitPoint(5f, 9f)),
        )
        assertEquals(40f, quadArea(rect), 0.5f)
    }

    @Test
    fun `corners come back clockwise from the top left`() {
        val turned = listOf(
            FitPoint(20f, 10f), FitPoint(10f, 20f), FitPoint(0f, 10f), FitPoint(10f, 0f),
        )
        val ordered = orderClockwise(turned)
        assertEquals(FitPoint(10f, 0f), ordered[0])
        assertEquals(FitPoint(20f, 10f), ordered[1])
        assertEquals(FitPoint(10f, 20f), ordered[2])
        assertEquals(FitPoint(0f, 10f), ordered[3])
    }

    @Test
    fun `a point is inside a polygon whichever way round its corners are given`() {
        val clockwise = listOf(
            FitPoint(0f, 0f), FitPoint(10f, 0f), FitPoint(10f, 10f), FitPoint(0f, 10f),
        )
        assertTrue(polygonContainsPoint(clockwise, 5f, 5f))
        assertTrue(polygonContainsPoint(clockwise.reversed(), 5f, 5f))
        assertFalse(polygonContainsPoint(clockwise, 10.5f, 5f))
        // An edge counts as covered, so a region's own boundary cells feed its confidence.
        assertTrue(polygonContainsPoint(clockwise, 0f, 5f))
    }

    @Test
    fun `growing a region pushes every edge outwards`() {
        val rect = listOf(
            FitPoint(5f, 5f), FitPoint(15f, 5f), FitPoint(15f, 9f), FitPoint(5f, 9f),
        )
        val grown = unclip(rect, 1.5f)
        assertEquals(4, grown.size)
        assertTrue(grown.minOf { it.x } < 5f)
        assertTrue(grown.minOf { it.y } < 5f)
        assertTrue(grown.maxOf { it.x } > 15f)
        assertTrue(grown.maxOf { it.y } > 9f)
        assertTrue(quadArea(grown) > quadArea(rect))
    }

    @Test
    fun `a region is held inside the grid it was found in`() {
        val clipped = clipToBounds(
            listOf(FitPoint(-4f, -2f), FitPoint(60f, -2f), FitPoint(60f, 40f), FitPoint(-4f, 40f)),
            width = 40, height = 20,
        )
        assertEquals(0f, clipped.minOf { it.x }, eps)
        assertEquals(0f, clipped.minOf { it.y }, eps)
        assertEquals(39f, clipped.maxOf { it.x }, eps)
        assertEquals(19f, clipped.maxOf { it.y }, eps)
    }

    @Test
    fun `the shortest side of a region is the one that decides it is too thin`() {
        assertEquals(
            4f,
            shortestSide(listOf(FitPoint(0f, 0f), FitPoint(10f, 0f), FitPoint(10f, 4f), FitPoint(0f, 4f))),
            eps,
        )
    }

    @Test
    fun `confidence is the mean probability over the region's own cells`() {
        val map = mapOf(width = 6, height = 4) { x, _ -> if (x < 3) 1f else 0f }
        val leftHalf = listOf(FitPoint(0f, 0f), FitPoint(3f, 0f), FitPoint(3f, 4f), FitPoint(0f, 4f))
        assertEquals(1f, regionScore(map, leftHalf), eps)
        val whole = listOf(FitPoint(0f, 0f), FitPoint(6f, 0f), FitPoint(6f, 4f), FitPoint(0f, 4f))
        assertEquals(0.5f, regionScore(map, whole), eps)
    }

    // ─── which shape the confidence is measured over ────────────────────────

    @Test
    fun `a blob is scored over its own outline and not the rectangle around it`() {
        // A run whose blob does not fill the rectangle fitted to it: the corners of that rectangle
        // are background, and averaging them in is what drops a perfectly good region.
        val map = wedgeMap()
        val blob = connectedComponents(map).maxBy { it.size }
        val hull = convexHull(hullCandidates(blob, map.width))
        val rectangle = minimumAreaRectangle(hull, alreadyConvex = true)

        assertEquals(1f, regionScore(map, hull), eps)
        assertTrue(regionScore(map, rectangle) < 0.6f)
    }

    @Test
    fun `a region the rectangle would have thrown away survives`() {
        val found = detectQuads(wedgeMap(), imageWidth = 50, imageHeight = 50)
        assertEquals(1, found.size)
        assertEquals(1f, found.single().score, eps)
    }

    // ─── the points the outline is built from ───────────────────────────────

    @Test
    fun `an outline is built from the ends of each row and loses nothing`() {
        val map = wedgeMap()
        val blob = connectedComponents(map).maxBy { it.size }
        val everyCell = blob.map { FitPoint((it % map.width).toFloat(), (it / map.width).toFloat()) }

        assertEquals(convexHull(everyCell), convexHull(hullCandidates(blob, map.width)))
        // And the point that gets there is a fraction of the blob's own area, which is what keeps
        // the hull affordable once the frame is read at any real resolution.
        assertTrue(hullCandidates(blob, map.width).size < blob.size / 4)
    }

    @Test
    fun `a single cell has an outline of its own`() {
        assertEquals(listOf(FitPoint(3f, 2f)), hullCandidates(intArrayOf(2 * 10 + 3), width = 10))
        assertTrue(hullCandidates(IntArray(0), width = 10).isEmpty())
    }

    // ─── the whole chain ────────────────────────────────────────────────────

    @Test
    fun `one solid block of probability becomes one region`() {
        val found = detectQuads(blockMap(), imageWidth = 40, imageHeight = 20)
        assertEquals(1, found.size)
        assertEquals(1f, found[0].score, eps)
    }

    @Test
    fun `a region is reported in image pixels and not in grid cells`() {
        // The same grid read against a frame ten times its size has to land ten times further out.
        // This is the one conversion the overlay cannot recover from if it is wrong.
        val onGrid = detectQuads(blockMap(), imageWidth = 40, imageHeight = 20).single().quad.bounds
        val onPhoto = detectQuads(blockMap(), imageWidth = 400, imageHeight = 200).single().quad.bounds
        assertEquals(onGrid.left * 10f, onPhoto.left, eps)
        assertEquals(onGrid.top * 10f, onPhoto.top, eps)
        assertEquals(onGrid.right * 10f, onPhoto.right, eps)
        assertEquals(onGrid.bottom * 10f, onPhoto.bottom, eps)
    }

    @Test
    fun `a region covers the block it was found on`() {
        val bounds = detectQuads(blockMap(), imageWidth = 40, imageHeight = 20).single().quad.bounds
        // Grown outwards from the thresholded core, which is what puts the outline around the whole
        // glyph instead of through the middle of it.
        assertTrue(bounds.left < 5f && bounds.top < 5f)
        assertTrue(bounds.right > 14f && bounds.bottom > 9f)
        assertTrue(bounds.left >= 0f && bounds.top >= 0f)
    }

    @Test
    fun `a weak region is left out`() {
        // Above the threshold that makes it a blob at all, below the one that makes it worth showing.
        val map = mapOf(width = 40, height = 20) { x, y ->
            if (x in 5..14 && y in 5..9) 0.4f else 0f
        }
        assertTrue(detectQuads(map, imageWidth = 40, imageHeight = 20).isEmpty())
    }

    @Test
    fun `an empty grid finds nothing`() {
        assertTrue(detectQuads(mapOf(width = 20, height = 10) { _, _ -> 0f }, 200, 100).isEmpty())
    }

    @Test
    fun `two runs on one line of a large photo stay on one line`() {
        // The allowance follows the runs' own height, so a page read at four thousand pixels and the
        // same page read at four hundred come back in the same order. A fixed allowance suits only
        // one of those and splits the other's lines across rows.
        val left = TextQuad.upright(100f, 1000f, 900f, 1080f)
        val right = TextQuad.upright(1000f, 1030f, 1800f, 1110f)
        assertTrue(onSameRow(left, right))
        assertFalse(onSameRow(left, TextQuad.upright(100f, 1120f, 900f, 1200f)))
    }

    @Test
    fun `a line's runs come back left to right and the line below comes after`() {
        val ordered = sortReadingOrder(
            listOf(
                DetectedQuad(TextQuad.upright(1000f, 1030f, 1800f, 1110f), 0.9f),
                DetectedQuad(TextQuad.upright(100f, 1000f, 900f, 1080f), 0.9f),
                DetectedQuad(TextQuad.upright(100f, 1300f, 900f, 1380f), 0.9f),
            ),
        )
        assertEquals(100f, ordered[0].quad.bounds.left, eps)
        assertEquals(1000f, ordered[1].quad.bounds.left, eps)
        assertEquals(1300f, ordered[2].quad.bounds.top, eps)
    }

    @Test
    fun `regions come back in reading order`() {
        val map = mapOf(width = 60, height = 40) { x, y ->
            val lowerLeft = x in 4..16 && y in 24..30
            val upperRight = x in 30..46 && y in 4..10
            val upperLeft = x in 4..16 && y in 4..10
            if (lowerLeft || upperRight || upperLeft) 1f else 0f
        }
        val found = detectQuads(map, imageWidth = 60, imageHeight = 40)
        assertEquals(3, found.size)
        // Top line first, and within it the left block before the right one.
        assertTrue(found[0].quad.bounds.top < found[2].quad.bounds.top)
        assertTrue(found[0].quad.bounds.left < found[1].quad.bounds.left)
        assertEquals(found[0].quad.bounds.top, found[1].quad.bounds.top, 1f)
    }

    @Test
    fun `cells that meet only at a corner belong to one blob`() {
        // The walk is eight-connected, so a stroke that steps across a corner is not split in two,
        // which is what an italic or a thinly-rendered glyph looks like on the grid.
        val map = mapOf(width = 6, height = 6) { x, y ->
            if ((x == 1 && y == 1) || (x == 2 && y == 2)) 1f else 0f
        }
        val blobs = connectedComponents(map)
        assertEquals(1, blobs.size)
        assertEquals(2, blobs.single().size)
    }

    @Test
    fun `cells with a gap between them are separate blobs`() {
        val map = mapOf(width = 6, height = 6) { x, y ->
            if ((x == 0 && y == 0) || (x == 4 && y == 4)) 1f else 0f
        }
        assertEquals(2, connectedComponents(map).size)
    }

    @Test
    fun `only cells above the threshold join a blob`() {
        val map = mapOf(width = 4, height = 4) { x, _ -> if (x == 0) 0.31f else 0.29f }
        assertEquals(1, connectedComponents(map).size)
        assertEquals(4, connectedComponents(map).single().size)
    }

    private fun blockMap(): ProbabilityMap = mapOf(width = 40, height = 20) { x, y ->
        if (x in 5..14 && y in 5..9) 1f else 0f
    }

    /**
     * A blob that half fills the rectangle fitted to it. Its own outline scores a flat one, while the
     * rectangle around it averages in two corners of background and lands under the bar a region has
     * to clear, which is the whole difference between the two ways a region can be scored.
     */
    private fun wedgeMap(): ProbabilityMap = mapOf(width = 50, height = 50) { x, y ->
        if (x >= 10 && y >= 10 && (x - 10) + (y - 10) <= 19) 1f else 0f
    }

    private fun mapOf(width: Int, height: Int, value: (Int, Int) -> Float): ProbabilityMap =
        ProbabilityMap(width, height, FloatArray(width * height) { value(it % width, it / width) })

    /** Shoelace area, used to compare rectangles without depending on which corner comes first. */
    private fun quadArea(points: List<FitPoint>): Float {
        var area = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return kotlin.math.abs(area) / 2f
    }
}
