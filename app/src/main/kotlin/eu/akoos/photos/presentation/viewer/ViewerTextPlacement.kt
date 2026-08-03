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
import eu.akoos.photos.util.FitBox
import eu.akoos.photos.util.FitPoint
import eu.akoos.photos.util.ImageFit
import eu.akoos.photos.util.fitImageInBox
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The pinch-zoom a viewer page is holding: a uniform [scale] about the centre of a
 * [containerW] by [containerH] node, then a translation by ([offsetX], [offsetY]).
 *
 * This is exactly what `Modifier.graphicsLayer(scaleX, scaleY, translationX, translationY)` does to
 * the image, whose default transform origin is the node's centre. Restating it here lets an overlay
 * drawn beside that image follow it without being inside the same layer, which keeps stroke widths
 * at their on-screen size instead of blowing up sixfold at full zoom.
 */
data class ViewerTransform(
    val containerW: Float,
    val containerH: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    companion object {
        /** The page sitting at fit-to-screen, with no pan. */
        fun untransformed(containerW: Float, containerH: Float): ViewerTransform =
            ViewerTransform(containerW, containerH, scale = 1f, offsetX = 0f, offsetY = 0f)
    }
}

/**
 * A zoom and a pan a viewer page can be put at: the pair `Modifier.graphicsLayer` is handed, and the
 * pair the page animates between when a mode moves the photo for its own reasons.
 */
data class ViewerZoom(val scale: Float, val offsetX: Float, val offsetY: Float) {
    companion object {
        /** Fit-to-screen with no pan, where every page starts. */
        val Fit = ViewerZoom(scale = 1f, offsetX = 0f, offsetY = 0f)
    }
}

/**
 * The zoom that puts the whole of an [imageWidth] by [imageHeight] photo inside the part of a
 * [containerW] by [containerH] page the system bars leave alone.
 *
 * A viewer page runs edge to edge, so at fit-to-screen a screenshot's top line sits behind the status
 * bar and its bottom behind the gesture area. Squeezing by the insets rather than by a chosen
 * fraction is what makes that exact: the photo gives up as much as the bars actually take and no
 * more. It is then re-centred on the safe area rather than on the page, so an asymmetric pair of bars
 * leaves it centred in what is left instead of pushed under the deeper one.
 *
 * Never grows the photo. A page whose bars already clear it has nothing to gain from being zoomed
 * into, and enlarging it here would fight the pinch the user owns.
 */
fun fitPhotoInsideInsets(
    imageWidth: Int,
    imageHeight: Int,
    containerW: Float,
    containerH: Float,
    insetLeft: Float,
    insetTop: Float,
    insetRight: Float,
    insetBottom: Float,
): ViewerZoom {
    if (imageWidth <= 0 || imageHeight <= 0) return ViewerZoom.Fit
    if (containerW <= 0f || containerH <= 0f) return ViewerZoom.Fit
    val safeW = containerW - insetLeft - insetRight
    val safeH = containerH - insetTop - insetBottom
    if (safeW <= 0f || safeH <= 0f) return ViewerZoom.Fit
    val fit = fitImageInBox(imageWidth.toFloat(), imageHeight.toFloat(), containerW, containerH)
    val drawnW = imageWidth * fit.scale
    val drawnH = imageHeight * fit.scale
    if (drawnW <= 0f || drawnH <= 0f) return ViewerZoom.Fit
    return ViewerZoom(
        scale = minOf(1f, safeW / drawnW, safeH / drawnH),
        // The zoom pivots on the page's centre, so the pan is the distance between that centre and
        // the safe area's own.
        offsetX = (insetLeft - insetRight) / 2f,
        offsetY = (insetTop - insetBottom) / 2f,
    )
}

/**
 * Where an image-pixel [point] lands on screen: [fit] places it inside the letterboxed draw area,
 * then [transform] applies the zoom and pan the user has dialled in.
 */
fun imagePointToScreen(point: FitPoint, fit: ImageFit, transform: ViewerTransform): FitPoint {
    val centreX = transform.containerW / 2f
    val centreY = transform.containerH / 2f
    val fittedX = fit.toScreenX(point.x)
    val fittedY = fit.toScreenY(point.y)
    return FitPoint(
        x = centreX + (fittedX - centreX) * transform.scale + transform.offsetX,
        y = centreY + (fittedY - centreY) * transform.scale + transform.offsetY,
    )
}

/**
 * Where the photo itself is drawn on screen: its own pixel extent through the same fit and transform
 * the highlights ride on.
 *
 * A page is only as wide or as tall as the photo in one axis, and the app's own bars sit over the
 * rest of it. The overlay is held inside this rectangle, so a highlight can never be painted onto a
 * letterbox bar or onto the chrome in front of it.
 */
fun photoRectOnScreen(
    imageWidth: Int,
    imageHeight: Int,
    fit: ImageFit,
    transform: ViewerTransform,
): FitBox {
    val topLeft = imagePointToScreen(FitPoint(0f, 0f), fit, transform)
    val bottomRight = imagePointToScreen(
        FitPoint(imageWidth.toFloat(), imageHeight.toFloat()), fit, transform,
    )
    return FitBox(
        left = minOf(topLeft.x, bottomRight.x),
        top = minOf(topLeft.y, bottomRight.y),
        right = maxOf(topLeft.x, bottomRight.x),
        bottom = maxOf(topLeft.y, bottomRight.y),
    )
}

/**
 * [corners] pushed outwards by [padPx] on every edge.
 *
 * A highlight sitting tight on the glyphs reads as a box drawn slightly too small, so the shape the
 * user sees is the detected one with a little air around it. Every quad here is a rectangle at some
 * angle, so a corner's outward direction is its own diagonal, and moving it root two times the
 * padding along that diagonal grows both edges meeting there by exactly the padding.
 */
fun expandPolygon(corners: List<FitPoint>, padPx: Float): List<FitPoint> {
    if (padPx <= 0f || corners.size < 3) return corners
    val count = corners.size
    var sumX = 0f
    var sumY = 0f
    corners.forEach { sumX += it.x; sumY += it.y }
    val centreX = sumX / count
    val centreY = sumY / count
    val distance = padPx * DIAGONAL
    return List(count) { index ->
        val current = corners[index]
        val previous = corners[(index - 1 + count) % count]
        val next = corners[(index + 1) % count]
        val fromPrevious = unit(current.x - previous.x, current.y - previous.y)
        val fromNext = unit(current.x - next.x, current.y - next.y)
        // A corner sitting on top of a neighbour has no bisector of its own; the direction out of the
        // shape's centre is the same answer for a rectangle and is defined for anything.
        val outward = if (fromPrevious == null || fromNext == null) {
            unit(current.x - centreX, current.y - centreY)
        } else {
            unit(fromPrevious.x + fromNext.x, fromPrevious.y + fromNext.y)
        }
        if (outward == null) current
        else FitPoint(current.x + outward.x * distance, current.y + outward.y * distance)
    }
}

private fun unit(x: Float, y: Float): FitPoint? {
    val length = sqrt(x * x + y * y)
    if (length < 1e-6f) return null
    return FitPoint(x / length, y / length)
}

/** The step along a rectangle corner's diagonal that grows both edges meeting there by one. */
private val DIAGONAL = sqrt(2f)

/** [block]'s four corners in screen pixels, in draw order, with [padPx] of air around them. */
fun blockCornersOnScreen(
    block: RecognizedTextBlock,
    fit: ImageFit,
    transform: ViewerTransform,
    padPx: Float = 0f,
): List<FitPoint> =
    expandPolygon(block.quad.corners.map { imagePointToScreen(it, fit, transform) }, padPx)

/**
 * Where one run's selectable text node goes, in the screen pixels the quad landed on.
 *
 * [originX] and [originY] are the corner the words start at, [angleDegrees] is the angle its
 * baseline runs at measured clockwise from horizontal, and [widthPx] by [heightPx] is the box the
 * words fill along and across that baseline.
 */
data class ViewerTextBlockPlacement(
    val originX: Float,
    val originY: Float,
    val angleDegrees: Float,
    val widthPx: Float,
    val heightPx: Float,
)

/**
 * Where the text node for [block] sits once [fit] has letterboxed the frame and [transform] has
 * applied the viewer's zoom and pan.
 *
 * A detector's quad is only nearly a rectangle, so the two edges running with the text and the two
 * running across it are each averaged rather than one of them being picked: a corner the detector
 * placed a pixel out then moves the box by half a pixel instead of turning the whole run.
 */
fun blockPlacement(
    block: RecognizedTextBlock,
    fit: ImageFit,
    transform: ViewerTransform,
): ViewerTextBlockPlacement {
    val topLeft = imagePointToScreen(block.quad.topLeft, fit, transform)
    val topRight = imagePointToScreen(block.quad.topRight, fit, transform)
    val bottomRight = imagePointToScreen(block.quad.bottomRight, fit, transform)
    val bottomLeft = imagePointToScreen(block.quad.bottomLeft, fit, transform)
    val radians = atan2((topRight.y - topLeft.y).toDouble(), (topRight.x - topLeft.x).toDouble())
    return ViewerTextBlockPlacement(
        originX = topLeft.x,
        originY = topLeft.y,
        angleDegrees = Math.toDegrees(radians).toFloat(),
        widthPx = (span(topLeft, topRight) + span(bottomLeft, bottomRight)) / 2f,
        heightPx = (span(topLeft, bottomLeft) + span(topRight, bottomRight)) / 2f,
    )
}

private fun span(from: FitPoint, to: FitPoint): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * The type size to set a run of [heightPx] tall words at.
 *
 * The node is given the quad's own height as its line height, which is what the platform draws the
 * selection over, so this only has to put glyphs of about the right size inside that line. A quad is
 * drawn tight around the ink, and ink for mixed-case Latin runs from the cap height to the descender
 * rather than over the whole em, so the em that produced it is a little larger than the quad is
 * tall.
 */
fun blockFontSizePx(heightPx: Float): Float = heightPx * INK_TO_EM

/**
 * How far a run of natural width [naturalWidthPx] has to be squeezed or stretched to cover the
 * [widthPx] its words actually occupy on the photograph.
 *
 * The photo's own type is never the device's, so the same characters come out at some other width
 * and the invisible run would end short of the words or run past them, taking the selection
 * highlight and the drag target with it. Per-character alignment is not on offer, so the run is
 * scaled along its baseline to land on both ends and the letters fall where they fall in between.
 * Clamped, because one absurd measurement should not produce a node stretched across the screen.
 */
fun blockHorizontalScale(widthPx: Float, naturalWidthPx: Float): Float {
    if (widthPx <= 0f || naturalWidthPx <= 0f) return 1f
    return (widthPx / naturalWidthPx).coerceIn(MIN_STRETCH, MAX_STRETCH)
}

/** How much of an em the ink of a mixed-case Latin run takes up. */
private const val INK_TO_EM = 0.8f

private const val MIN_STRETCH = 0.2f
private const val MAX_STRETCH = 5f

/**
 * How far block [index] of [count] is into its own reveal when the whole set is [progress] through
 * the animation. Each block starts a little after the one before it, so a page of text lands as a
 * quick sweep down the photo rather than one flat flash.
 */
fun blockReveal(progress: Float, index: Int, count: Int): Float {
    val clamped = progress.coerceIn(0f, 1f)
    if (count <= 1) return clamped
    val start = STAGGER_BUDGET * index.coerceIn(0, count - 1) / (count - 1)
    return ((clamped - start) / (1f - STAGGER_BUDGET)).coerceIn(0f, 1f)
}

/** The slice of the reveal spent handing out staggered start times; the rest is each block's own. */
private const val STAGGER_BUDGET = 0.4f
