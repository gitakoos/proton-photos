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

/*
 * The four-corner warp and the quarter turn for a column of text below are derived from mobile_ocr,
 * which is MIT licensed:
 *
 *   Copyright (c) 2025 Laurens Priem
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, subject to the conditions of the MIT License.
 */

package eu.akoos.photos.data.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import eu.akoos.photos.domain.ocr.TextQuad

/**
 * Cuts [quad] out of [frame] as a straightened line the reader can take.
 *
 * One four-corner warp does all of it. The quad's own corners become the crop's own corners, so a
 * sign photographed at a slant arrives level rather than as the upright box around it, which would
 * carry in half of the line above. A column of text lands quarter-turned by the same warp: doing it
 * in the destination points rather than by rotating the finished bitmap saves allocating the crop
 * twice, and this runs once per detected region.
 *
 * Returns null when the frame is gone from under it, which a swipe can do mid-read.
 */
fun cropTextQuad(frame: Bitmap, quad: TextQuad, size: OcrCropSize): Bitmap? {
    if (frame.isRecycled) return null

    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val source = floatArrayOf(
        quad.topLeft.x, quad.topLeft.y,
        quad.topRight.x, quad.topRight.y,
        quad.bottomRight.x, quad.bottomRight.y,
        quad.bottomLeft.x, quad.bottomLeft.y,
    )
    val destination = if (size.turnsUpright) {
        // A quarter turn clockwise, written straight into where each corner lands.
        floatArrayOf(height, 0f, height, width, 0f, width, 0f, 0f)
    } else {
        floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
    }

    val matrix = Matrix()
    if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return null

    val crop = Bitmap.createBitmap(size.readWidth, size.readHeight, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    Canvas(crop).drawBitmap(frame, matrix, paint)
    return crop
}

/**
 * Writes [crop] into slot [slot] of a batch [tensor] that is [batchWidth] columns wide, and answers
 * how many of those columns the crop itself covers.
 *
 * Both networks take the same thing: the crop squeezed to [READ_HEIGHT], centred on zero, laid out
 * as three colour planes in blue, green, red order, and zero-padded out to the run's shared width.
 *
 * [upsideDown] is applied by reading the pixels backwards rather than by turning the bitmap. Turning
 * it would mean allocating the crop a second time for every line the classifier flags, and a
 * half-turn is exactly a reversed traversal.
 */
internal fun writeCropInto(
    crop: Bitmap,
    upsideDown: Boolean,
    tensor: FloatArray,
    slot: Int,
    batchWidth: Int,
): Int {
    val plane = READ_HEIGHT * batchWidth
    val base = slot * 3 * plane
    val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1)
    val contentWidth = readContentWidth(ratio, batchWidth)

    val scaled = Bitmap.createScaledBitmap(crop, contentWidth, READ_HEIGHT, true)
    val pixels = IntArray(contentWidth * READ_HEIGHT)
    try {
        scaled.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, READ_HEIGHT)
    } finally {
        // createScaledBitmap hands back the very same instance when nothing needs scaling.
        if (scaled !== crop && !scaled.isRecycled) scaled.recycle()
    }

    for (y in 0 until READ_HEIGHT) {
        val row = y * batchWidth
        val sourceRow = (if (upsideDown) READ_HEIGHT - 1 - y else y) * contentWidth
        for (x in 0 until contentWidth) {
            val pixel = pixels[sourceRow + if (upsideDown) contentWidth - 1 - x else x]
            val cell = row + x
            tensor[base + cell] = ((pixel and 0xFF) * INV_255 - CENTRE) / CENTRE
            tensor[base + plane + cell] = (((pixel shr 8) and 0xFF) * INV_255 - CENTRE) / CENTRE
            tensor[base + 2 * plane + cell] = (((pixel shr 16) and 0xFF) * INV_255 - CENTRE) / CENTRE
        }
        // The padding past the content is left at zero, which is the mean the network was centred on.
    }
    return contentWidth
}

private const val INV_255 = 1f / 255f
private const val CENTRE = 0.5f
