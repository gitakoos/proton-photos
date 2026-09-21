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

package eu.akoos.photos.presentation.gifmaker

/**
 * Framing of the exported GIF: [ORIGINAL] keeps the source's aspect ratio, [SQUARE] renders into a 1:1
 * canvas (the whole frame fits with black padding at zoom 1, and zooming fills the square).
 */
enum class GifAspect { ORIGINAL, SQUARE }

/**
 * One frame's placement, in pixels: the [srcLeft]..[srcBottom] rectangle sampled from the decoded source
 * frame, mapped onto the [dstLeft]..[dstBottom] rectangle of the output canvas. Anything of the canvas
 * outside the destination rectangle is left as black padding. All values are already clamped to their
 * respective bounds, so the source rectangle never falls outside the frame and the destination never
 * falls outside the canvas.
 */
data class GifFraming(
    val srcLeft: Float,
    val srcTop: Float,
    val srcRight: Float,
    val srcBottom: Float,
    val dstLeft: Float,
    val dstTop: Float,
    val dstRight: Float,
    val dstBottom: Float,
)

/**
 * The GIF canvas for a source shown at [srcW] x [srcH] under [aspect]. [ORIGINAL] scales the source ratio
 * so its longest edge is at most [maxEdge]; [SQUARE] is [maxEdge] x [maxEdge]. Both dimensions are floored
 * to even and at least 2, matching the encoder's fixed even-sized pixel buffer.
 */
fun gifCanvasFor(srcW: Int, srcH: Int, aspect: GifAspect, maxEdge: Int): Pair<Int, Int> {
    val edge = (maxEdge and 1.inv()).coerceAtLeast(2)
    return when (aspect) {
        GifAspect.SQUARE -> edge to edge
        GifAspect.ORIGINAL -> {
            val w = srcW.coerceAtLeast(1)
            val h = srcH.coerceAtLeast(1)
            val longest = maxOf(w, h)
            val scale = if (longest > edge) edge.toFloat() / longest else 1f
            val canvasW = ((w * scale).toInt() and 1.inv()).coerceAtLeast(2)
            val canvasH = ((h * scale).toInt() and 1.inv()).coerceAtLeast(2)
            canvasW to canvasH
        }
    }
}

/**
 * Where the source frame lands on the canvas for a given zoom and pan, as the pixel rectangles the drawing
 * step samples and fills. Pure geometry so the preview and the exporter share one result and stay
 * pixel-identical (WYSIWYG).
 *
 * [zoom] is at least 1; [panX]/[panY] are the view centre's normalised offset in -1..1 and are coerced
 * into that range here. The source is scaled by (fit scale * zoom) and centred, then panned:
 *  - Fit scale = min(canvasW / srcW, canvasH / srcH), so at zoom 1 the whole frame fits inside the canvas.
 *    With an [GifAspect.ORIGINAL] canvas (source ratio) that fills the canvas exactly; with a
 *    [GifAspect.SQUARE] canvas the shorter axis is letterboxed, leaving black bars.
 *  - At zoom above 1 the scaled content overflows the canvas, so the destination grows to the covered
 *    area and the source rectangle becomes a sub-region (a crop). Pan shifts within that overflow only,
 *    clamped so no source edge is ever pulled inside the canvas once the content covers it.
 */
fun gifFraming(
    srcW: Int,
    srcH: Int,
    canvasW: Int,
    canvasH: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
): GifFraming {
    val sw = srcW.coerceAtLeast(1).toFloat()
    val sh = srcH.coerceAtLeast(1).toFloat()
    val cw = canvasW.coerceAtLeast(1).toFloat()
    val ch = canvasH.coerceAtLeast(1).toFloat()
    val z = zoom.coerceAtLeast(1f)
    val px = panX.coerceIn(-1f, 1f)
    val py = panY.coerceIn(-1f, 1f)

    val fitScale = minOf(cw / sw, ch / sh)
    val scale = fitScale * z
    val scaledW = sw * scale
    val scaledH = sh * scale

    // How far the scaled content exceeds the canvas on each axis; 0 while it is letterboxed there, so pan
    // has no effect on an axis the content does not yet cover.
    val overshootX = (scaledW - cw).coerceAtLeast(0f)
    val overshootY = (scaledH - ch).coerceAtLeast(0f)

    // Centre the scaled content, then shift by the clamped pan within the available overshoot. With pan in
    // -1..1 the content edge is never pulled inside the canvas once it covers it.
    val posX = (cw - scaledW) / 2f + px * overshootX / 2f
    val posY = (ch - scaledH) / 2f + py * overshootY / 2f

    // Canvas region the content actually covers; the remainder stays black padding.
    val dstLeft = posX.coerceIn(0f, cw)
    val dstTop = posY.coerceIn(0f, ch)
    val dstRight = (posX + scaledW).coerceIn(0f, cw)
    val dstBottom = (posY + scaledH).coerceIn(0f, ch)

    // Source pixels that map onto that region, kept inside the frame as a final guard.
    val srcLeft = ((dstLeft - posX) / scale).coerceIn(0f, sw)
    val srcTop = ((dstTop - posY) / scale).coerceIn(0f, sh)
    val srcRight = ((dstRight - posX) / scale).coerceIn(0f, sw)
    val srcBottom = ((dstBottom - posY) / scale).coerceIn(0f, sh)

    return GifFraming(srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop, dstRight, dstBottom)
}
