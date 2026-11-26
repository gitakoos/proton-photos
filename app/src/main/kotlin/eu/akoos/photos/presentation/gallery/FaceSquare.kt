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

package eu.akoos.photos.presentation.gallery

import kotlin.math.roundToInt

/** The square around a face, as a multiple of the face's longer side. */
internal const val FACE_ZOOM_OUT = 1.4

/** The least room around a face, as a multiple of its longer side, that a zoom in near an edge keeps. */
internal const val FACE_ZOOM_OUT_MIN = 1.2

/** A square cut out of a picture, in pixels. */
internal data class FaceSquare(val left: Int, val top: Int, val size: Int)

/** The face's square in a [width] x [height] picture, zoomed in near an edge before it moves off centre; the desktop cuts covers by the same rule. */
internal fun faceSquare(box: FaceBox, width: Int, height: Int): FaceSquare {
    val w = width.toDouble()
    val h = height.toDouble()
    val l = box.left.toDouble()
    val t = box.top.toDouble()
    val r = box.right.toDouble()
    val b = box.bottom.toDouble()
    val face = maxOf((r - l) * w, (b - t) * h)
    val cx = ((l + r) / 2) * w
    val cy = ((t + b) / 2) * h
    val room = 2 * minOf(minOf(cx, w - cx), minOf(cy, h - cy))
    val wanted = maxOf(face * FACE_ZOOM_OUT_MIN, minOf(face * FACE_ZOOM_OUT, room))
    val size = maxOf(1, minOf(wanted, minOf(w, h)).roundToInt())
    val left = minOf(maxOf(cx - size / 2.0, 0.0), w - size).roundToInt()
    val top = minOf(maxOf(cy - size / 2.0, 0.0), h - size).roundToInt()
    return FaceSquare(left, top, size)
}
