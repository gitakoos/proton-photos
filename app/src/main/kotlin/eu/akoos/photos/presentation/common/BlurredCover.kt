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

package eu.akoos.photos.presentation.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * Fills the [width] x [height] box at ([left], [top]) with a heavily blurred copy of [bmp] scaled to cover
 * it (a downscale then upscale, a cheap blur that needs no API-31 RenderEffect), so a photo that leaves
 * room around itself sits on its own frosted backdrop. [bmp] itself is left as it is.
 *
 * [workingLongSide] is the long side, in pixels, of the small copy the blur is made from: the fewer its
 * pixels, the heavier the blur. By default it is relative to the DESTINATION (not the source), a tenth
 * of the box's long side held to 96..320. That keeps recognisable soft shapes where a fixed 1/24 source
 * downscale gives a pixelated smear that reads as a blurred thumbnail, and holds a steady blur strength
 * no matter the source or output resolution.
 */
internal fun drawBlurredCover(
    canvas: Canvas,
    paint: Paint,
    bmp: Bitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    workingLongSide: Float = (maxOf(width, height) / 10f).coerceIn(96f, 320f),
) {
    val iw = bmp.width.toFloat()
    val ih = bmp.height.toFloat()
    if (iw <= 0f || ih <= 0f) return
    // Downscale to the working size, then upscale to cover.
    val targetLong = workingLongSide
    val srcScale = (targetLong / maxOf(iw, ih)).coerceAtMost(1f)
    val tw = (iw * srcScale).roundToInt().coerceAtLeast(2)
    val th = (ih * srcScale).roundToInt().coerceAtLeast(2)
    val tiny = runCatching { Bitmap.createScaledBitmap(bmp, tw, th, true) }.getOrNull() ?: return
    val cover = maxOf(width / iw, height / ih)
    val drawW = iw * cover
    val drawH = ih * cover
    val matrix = Matrix().apply {
        setScale(drawW / tw, drawH / th)
        postTranslate(left + width / 2f - drawW / 2f, top + height / 2f - drawH / 2f)
    }
    canvas.save()
    canvas.clipRect(left, top, left + width, top + height)
    canvas.drawBitmap(tiny, matrix, paint)
    canvas.restore()
    // createScaledBitmap hands back [bmp] itself when there is nothing to scale, and that one is the caller's.
    if (tiny !== bmp) tiny.recycle()
}
