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

import kotlin.math.min

/** A point in either image space or screen space, whichever the surrounding call says. */
data class FitPoint(val x: Float, val y: Float)

/** An axis-aligned rectangle in either image space or screen space. */
data class FitBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The uniform scale plus letterbox offsets that centre a W×H image inside a box — the same
 * transform Compose's `ContentScale.Fit` applies when it draws that image at that size. Holding
 * it as a value lets an overlay drawn on top of the image convert between the image's own pixel
 * coordinates and the on-screen pixels the user touches.
 */
data class ImageFit(val scale: Float, val offsetX: Float, val offsetY: Float) {

    fun toScreenX(imageX: Float): Float = offsetX + imageX * scale

    fun toScreenY(imageY: Float): Float = offsetY + imageY * scale

    fun toImageX(screenX: Float): Float = (screenX - offsetX) / scale

    fun toImageY(screenY: Float): Float = (screenY - offsetY) / scale

    fun toScreen(point: FitPoint): FitPoint = FitPoint(toScreenX(point.x), toScreenY(point.y))

    fun toImage(point: FitPoint): FitPoint = FitPoint(toImageX(point.x), toImageY(point.y))

    fun toScreen(box: FitBox): FitBox = FitBox(
        left = toScreenX(box.left),
        top = toScreenY(box.top),
        right = toScreenX(box.right),
        bottom = toScreenY(box.bottom),
    )

    fun toImage(box: FitBox): FitBox = FitBox(
        left = toImageX(box.left),
        top = toImageY(box.top),
        right = toImageX(box.right),
        bottom = toImageY(box.bottom),
    )
}

/**
 * Fits an [imageW]×[imageH] image into a [boxW]×[boxH] box: the larger axis touches the box and
 * the smaller one is centred, so the returned offsets are the letterbox bars.
 *
 * Callers pass box dimensions coerced to at least 1, because a container reports zero size on its
 * first measure pass.
 */
fun fitImageInBox(imageW: Float, imageH: Float, boxW: Float, boxH: Float): ImageFit {
    val scale = min(boxW / imageW, boxH / imageH)
    val drawnW = imageW * scale
    val drawnH = imageH * scale
    return ImageFit(scale, (boxW - drawnW) / 2f, (boxH - drawnH) / 2f)
}

/**
 * How a decoded frame was turned to reach the orientation the user actually sees: a horizontal
 * mirror of the source frame first, then a clockwise rotation. Every EXIF orientation decomposes
 * into exactly one of these eight pairs.
 */
data class DisplayOrientation(val rotationDegrees: Int, val mirrored: Boolean) {

    /** True when the rotation swaps the width and height axes. */
    val swapsAxes: Boolean get() = rotationDegrees % 180 != 0

    companion object {
        /** The frame is shown exactly as decoded. */
        val None = DisplayOrientation(0, mirrored = false)
    }
}

/**
 * Maps [point] from the source frame (as the decoder produced it, [srcW]×[srcH]) to where that
 * same feature lands after this orientation is baked in. A caller that recognised something in the
 * displayed frame needs this to reason about the file's own coordinates.
 */
fun DisplayOrientation.orient(point: FitPoint, srcW: Float, srcH: Float): FitPoint {
    val x = if (mirrored) srcW - point.x else point.x
    val y = point.y
    return when (((rotationDegrees % 360) + 360) % 360) {
        90 -> FitPoint(srcH - y, x)
        180 -> FitPoint(srcW - x, srcH - y)
        270 -> FitPoint(y, srcW - x)
        else -> FitPoint(x, y)
    }
}
