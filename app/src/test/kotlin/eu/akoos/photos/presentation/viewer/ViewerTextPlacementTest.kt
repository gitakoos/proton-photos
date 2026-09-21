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

import eu.akoos.photos.domain.ocr.RecognizedTextBlock
import eu.akoos.photos.domain.ocr.TextQuad
import eu.akoos.photos.util.FitPoint
import eu.akoos.photos.util.fitImageInBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the placement the text highlights and the selectable text nodes both ride on. The blocks are
 * stated in the photo's own pixels while the user pinches and pans the drawn image, so the overlay
 * has to reproduce the letterbox fit and the viewer's centre-anchored zoom exactly. A wrong pivot or
 * a dropped pan puts every outline beside the words it is supposed to sit on, and the same
 * arithmetic decides where a run's invisible text goes, how big it is set and which way it faces.
 */
class ViewerTextPlacementTest {

    private val eps = 0.001f

    /** A 1000x500 photo in a 400x400 page: scale 0.4, with 100pt letterbox bars top and bottom. */
    private val fit = fitImageInBox(1000f, 500f, 400f, 400f)
    private val unzoomed = ViewerTransform.untransformed(400f, 400f)

    private fun block(text: String, quad: TextQuad) = RecognizedTextBlock(text, 0.9f, quad)

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: FitPoint) {
        assertEquals("x", expectedX, actual.x, eps)
        assertEquals("y", expectedY, actual.y, eps)
    }

    // ─── image space to screen space ────────────────────────────────────────

    @Test
    fun `at fit to screen a point lands where the letterbox puts it`() {
        assertPoint(0f, 100f, imagePointToScreen(FitPoint(0f, 0f), fit, unzoomed))
        assertPoint(400f, 300f, imagePointToScreen(FitPoint(1000f, 500f), fit, unzoomed))
        assertPoint(200f, 200f, imagePointToScreen(FitPoint(500f, 250f), fit, unzoomed))
    }

    @Test
    fun `zooming leaves the point at the centre of the page alone`() {
        val zoomed = ViewerTransform(400f, 400f, scale = 3f, offsetX = 0f, offsetY = 0f)
        assertPoint(200f, 200f, imagePointToScreen(FitPoint(500f, 250f), fit, zoomed))
    }

    @Test
    fun `zooming pushes everything else away from the centre by the scale`() {
        val zoomed = ViewerTransform(400f, 400f, scale = 2f, offsetX = 0f, offsetY = 0f)
        // (0,0) sits at (0,100) unzoomed, which is (-200,-100) from the centre; doubling that
        // distance puts it at (-200,0).
        assertPoint(-200f, 0f, imagePointToScreen(FitPoint(0f, 0f), fit, zoomed))
    }

    @Test
    fun `a pan shifts every point by the same screen distance`() {
        val panned = ViewerTransform(400f, 400f, scale = 2f, offsetX = 35f, offsetY = -60f)
        val still = ViewerTransform(400f, 400f, scale = 2f, offsetX = 0f, offsetY = 0f)
        listOf(FitPoint(0f, 0f), FitPoint(640f, 310f), FitPoint(1000f, 500f)).forEach { point ->
            val before = imagePointToScreen(point, fit, still)
            val after = imagePointToScreen(point, fit, panned)
            assertEquals(35f, after.x - before.x, eps)
            assertEquals(-60f, after.y - before.y, eps)
        }
    }

    @Test
    fun `the point under a double tap stays under the finger`() {
        // The viewer anchors its double-tap zoom with offset = (page centre - tap) * (scale - 1).
        // A highlight over whatever the user tapped has to come back out at that same screen point,
        // or every outline slides off its word the moment the photo is zoomed.
        val tapX = 320f
        val tapY = 140f
        val newScale = 2.5f
        val transform = ViewerTransform(
            containerW = 400f, containerH = 400f, scale = newScale,
            offsetX = (200f - tapX) * (newScale - 1f),
            offsetY = (200f - tapY) * (newScale - 1f),
        )
        val underFinger = FitPoint(fit.toImageX(tapX), fit.toImageY(tapY))
        assertPoint(tapX, tapY, imagePointToScreen(underFinger, fit, transform))
    }

    @Test
    fun `a quad keeps its corner order on the way to the screen`() {
        val quad = TextQuad.upright(100f, 100f, 300f, 200f)
        val corners = blockCornersOnScreen(block("t", quad), fit, unzoomed)
        assertEquals(4, corners.size)
        assertPoint(40f, 140f, corners[0])
        assertPoint(120f, 140f, corners[1])
        assertPoint(120f, 180f, corners[2])
        assertPoint(40f, 180f, corners[3])
    }

    // ─── the photo's own rectangle ──────────────────────────────────────────

    @Test
    fun `the photo rectangle is the drawn image, not the page`() {
        val photo = photoRectOnScreen(1000, 500, fit, unzoomed)
        assertEquals(0f, photo.left, eps)
        assertEquals(100f, photo.top, eps)
        assertEquals(400f, photo.right, eps)
        assertEquals(300f, photo.bottom, eps)
    }

    @Test
    fun `the photo rectangle follows the zoom off the edges of the page`() {
        val zoomed = ViewerTransform(400f, 400f, scale = 2f, offsetX = 0f, offsetY = 0f)
        val photo = photoRectOnScreen(1000, 500, fit, zoomed)
        assertEquals(-200f, photo.left, eps)
        assertEquals(0f, photo.top, eps)
        assertEquals(600f, photo.right, eps)
        assertEquals(400f, photo.bottom, eps)
    }

    // ─── squeezing the photo out from behind the system bars ────────────────

    /**
     * Where the photo's own top and bottom edges land once [zoom] is applied to a [imageH] tall
     * photo drawn at [drawnH] inside a 400pt page. The zoom pivots on the page's centre, so this is
     * what the user actually sees clear of the bars.
     */
    private fun verticalEdges(zoom: ViewerZoom, drawnH: Float): Pair<Float, Float> {
        val half = drawnH * zoom.scale / 2f
        return (200f - half + zoom.offsetY) to (200f + half + zoom.offsetY)
    }

    @Test
    fun `a photo wider than the page is squeezed by the bars down its sides`() {
        // 1000x500 in a 400x400 page draws 400x200, so its own sides touch the page's.
        val zoom = fitPhotoInsideInsets(1000, 500, 400f, 400f, 20f, 0f, 20f, 0f)
        assertEquals(0.9f, zoom.scale, eps)
        assertEquals(0f, zoom.offsetX, eps)
        assertEquals(0f, zoom.offsetY, eps)
    }

    @Test
    fun `a photo taller than the page is squeezed by the bars above and below it`() {
        // 500x1000 in a 400x400 page draws 200x400, so its own top and bottom touch the page's.
        val zoom = fitPhotoInsideInsets(500, 1000, 400f, 400f, 0f, 40f, 0f, 40f)
        assertEquals(0.8f, zoom.scale, eps)
        assertEquals(0f, zoom.offsetX, eps)
        assertEquals(0f, zoom.offsetY, eps)
        val (top, bottom) = verticalEdges(zoom, 400f)
        assertEquals(40f, top, eps)
        assertEquals(360f, bottom, eps)
    }

    @Test
    fun `an uneven pair of bars leaves the photo centred on what is left`() {
        val zoom = fitPhotoInsideInsets(500, 1000, 400f, 400f, 30f, 60f, 10f, 20f)
        assertEquals(0.8f, zoom.scale, eps)
        assertEquals(10f, zoom.offsetX, eps)
        assertEquals(20f, zoom.offsetY, eps)
        val (top, bottom) = verticalEdges(zoom, 400f)
        assertEquals(60f, top, eps)
        assertEquals(380f, bottom, eps)
    }

    @Test
    fun `a photo the bars already clear is left exactly where it was`() {
        // The same 400x200 drawing, with 100pt letterbox bars the 40pt system bars sit inside.
        val zoom = fitPhotoInsideInsets(1000, 500, 400f, 400f, 0f, 40f, 0f, 40f)
        assertEquals(ViewerZoom.Fit, zoom)
    }

    @Test
    fun `a page with nothing measurable yet stays at fit to screen`() {
        assertEquals(ViewerZoom.Fit, fitPhotoInsideInsets(0, 0, 400f, 400f, 0f, 40f, 0f, 40f))
        assertEquals(ViewerZoom.Fit, fitPhotoInsideInsets(1000, 500, 0f, 0f, 0f, 40f, 0f, 40f))
    }

    @Test
    fun `bars deeper than the page itself leave the photo alone`() {
        assertEquals(ViewerZoom.Fit, fitPhotoInsideInsets(1000, 500, 400f, 400f, 0f, 260f, 0f, 260f))
    }

    // ─── the air around a highlight ─────────────────────────────────────────

    @Test
    fun `padding moves every edge of an upright box outwards by itself`() {
        val padded = expandPolygon(
            listOf(
                FitPoint(100f, 100f), FitPoint(300f, 100f),
                FitPoint(300f, 200f), FitPoint(100f, 200f),
            ),
            5f,
        )
        assertPoint(95f, 95f, padded[0])
        assertPoint(305f, 95f, padded[1])
        assertPoint(305f, 205f, padded[2])
        assertPoint(95f, 205f, padded[3])
    }

    @Test
    fun `padding a turned box moves its corners along their own diagonals`() {
        // A square on its point: each corner's outward direction is the diagonal it sits on, and a
        // corner moving root two times the padding grows both edges meeting there by the padding.
        val diamond = listOf(
            FitPoint(0f, -50f), FitPoint(50f, 0f), FitPoint(0f, 50f), FitPoint(-50f, 0f),
        )
        val padded = expandPolygon(diamond, 10f)
        assertPoint(0f, -64.142f, padded[0])
        assertPoint(64.142f, 0f, padded[1])
        assertPoint(0f, 64.142f, padded[2])
        assertPoint(-64.142f, 0f, padded[3])
    }

    @Test
    fun `no padding leaves the corners exactly where they were`() {
        val corners = listOf(FitPoint(0f, 0f), FitPoint(10f, 0f), FitPoint(10f, 10f))
        assertSame(corners, expandPolygon(corners, 0f))
    }

    // ─── where a run's own text node goes ───────────────────────────────────

    /**
     * A run turned by a 3-4-5 triangle: 250 image pixels of baseline running down and to the right,
     * 50 across. Every length here comes out whole once the 0.4 fit is applied, so a wrong pivot or
     * a swapped axis shows up as a round number in the wrong place rather than as rounding noise.
     */
    private val slanted = TextQuad(
        topLeft = FitPoint(100f, 100f),
        topRight = FitPoint(300f, 250f),
        bottomRight = FitPoint(270f, 290f),
        bottomLeft = FitPoint(70f, 140f),
    )

    @Test
    fun `an upright run starts at its own corner and does not turn`() {
        val placed = blockPlacement(block("t", TextQuad.upright(100f, 100f, 300f, 200f)), fit, unzoomed)
        assertEquals(40f, placed.originX, eps)
        assertEquals(140f, placed.originY, eps)
        assertEquals(0f, placed.angleDegrees, eps)
        assertEquals(80f, placed.widthPx, eps)
        assertEquals(40f, placed.heightPx, eps)
    }

    @Test
    fun `a turned run comes back at its own angle with its own extent`() {
        val placed = blockPlacement(block("t", slanted), fit, unzoomed)
        assertEquals(40f, placed.originX, eps)
        assertEquals(140f, placed.originY, eps)
        assertEquals(36.8699f, placed.angleDegrees, 0.01f)
        // Along the baseline, not across the upright box the run happens to fill.
        assertEquals(100f, placed.widthPx, eps)
        assertEquals(20f, placed.heightPx, eps)
    }

    @Test
    fun `a caption reading straight down comes back at a right angle`() {
        val vertical = TextQuad(
            topLeft = FitPoint(100f, 100f),
            topRight = FitPoint(100f, 300f),
            bottomRight = FitPoint(50f, 300f),
            bottomLeft = FitPoint(50f, 100f),
        )
        val placed = blockPlacement(block("t", vertical), fit, unzoomed)
        assertEquals(90f, placed.angleDegrees, eps)
        assertEquals(80f, placed.widthPx, eps)
        assertEquals(20f, placed.heightPx, eps)
    }

    @Test
    fun `a quad the detector left slightly out of square is averaged rather than picked from`() {
        // The bottom edge is 40 image pixels longer than the top one; the run is set at the mean of
        // the two rather than at whichever corner pair happened to be read first.
        val skewed = TextQuad(
            topLeft = FitPoint(100f, 100f),
            topRight = FitPoint(300f, 100f),
            bottomRight = FitPoint(340f, 200f),
            bottomLeft = FitPoint(100f, 200f),
        )
        val placed = blockPlacement(block("t", skewed), fit, unzoomed)
        assertEquals(88f, placed.widthPx, eps)
    }

    @Test
    fun `zooming and panning moves a run and scales it without turning it`() {
        // The pinch is a uniform scale about the page's centre, which is exactly why the layer can
        // carry it: every length doubles, the origin lands where the transform puts it, and the
        // angle is untouched. A rotation appearing here would mean the runs had to be re-laid out
        // on every pointer event instead.
        val panned = ViewerTransform(400f, 400f, scale = 2f, offsetX = 35f, offsetY = -60f)
        val rest = blockPlacement(block("t", slanted), fit, unzoomed)
        val moved = blockPlacement(block("t", slanted), fit, panned)
        assertEquals(-85f, moved.originX, eps)
        assertEquals(20f, moved.originY, eps)
        assertEquals(rest.angleDegrees, moved.angleDegrees, eps)
        assertEquals(rest.widthPx * 2f, moved.widthPx, eps)
        assertEquals(rest.heightPx * 2f, moved.heightPx, eps)
    }

    // ─── the type a run is set in ───────────────────────────────────────────

    @Test
    fun `the type size follows the height of the words and nothing else`() {
        assertEquals(32f, blockFontSizePx(40f), eps)
        assertEquals(64f, blockFontSizePx(80f), eps)
        assertEquals(0f, blockFontSizePx(0f), eps)
    }

    @Test
    fun `the type is smaller than the box it is set in`() {
        // The quad is drawn around the ink, and the em that produced that ink is larger than it.
        assertTrue(blockFontSizePx(40f) < 40f)
    }

    @Test
    fun `the type size is derived from the same height the placement reports`() {
        val placed = blockPlacement(block("t", slanted), fit, unzoomed)
        assertEquals(16f, blockFontSizePx(placed.heightPx), eps)
    }

    // ─── making the words end where the photo's words end ───────────────────

    @Test
    fun `a run the device sets narrower than the photo did is stretched onto it`() {
        assertEquals(2f, blockHorizontalScale(200f, 100f), eps)
    }

    @Test
    fun `a run the device sets wider than the photo did is squeezed onto it`() {
        assertEquals(0.5f, blockHorizontalScale(100f, 200f), eps)
    }

    @Test
    fun `a run that already fits is left alone`() {
        assertEquals(1f, blockHorizontalScale(120f, 120f), eps)
    }

    @Test
    fun `a run with no measurement to go on is left alone rather than collapsed`() {
        assertEquals(1f, blockHorizontalScale(120f, 0f), eps)
        assertEquals(1f, blockHorizontalScale(0f, 120f), eps)
    }

    @Test
    fun `an absurd measurement cannot stretch a run across the screen`() {
        assertEquals(5f, blockHorizontalScale(4000f, 1f), eps)
        assertEquals(0.2f, blockHorizontalScale(1f, 4000f), eps)
    }

    // ─── the staggered reveal ───────────────────────────────────────────────

    @Test
    fun `a lone block reveals across the whole timeline`() {
        assertEquals(0f, blockReveal(0f, 0, 1), eps)
        assertEquals(0.5f, blockReveal(0.5f, 0, 1), eps)
        assertEquals(1f, blockReveal(1f, 0, 1), eps)
    }

    @Test
    fun `a later block starts later and still finishes`() {
        assertEquals(0f, blockReveal(0f, 2, 3), eps)
        assertTrue(blockReveal(0.3f, 0, 3) > blockReveal(0.3f, 2, 3))
        assertEquals(1f, blockReveal(1f, 0, 3), eps)
        assertEquals(1f, blockReveal(1f, 2, 3), eps)
    }

    @Test
    fun `the reveal is clamped at both ends`() {
        assertEquals(0f, blockReveal(-1f, 0, 2), eps)
        assertEquals(1f, blockReveal(2f, 1, 2), eps)
    }
}
