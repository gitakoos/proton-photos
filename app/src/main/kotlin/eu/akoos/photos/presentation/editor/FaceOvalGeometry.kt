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

package eu.akoos.photos.presentation.editor

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * An oriented ellipse in image pixel coordinates. [rotationDegrees] rotates the ellipse clockwise
 * around ([centerX], [centerY]); [radiusX] is the semi-axis along the (unrotated) x, [radiusY]
 * along y.
 */
data class OvalSpec(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val rotationDegrees: Float,
)

/**
 * Two eyes closer than this many pixels are treated as one point, and the face is drawn level
 * rather than at whatever wild angle a sub-pixel gap between them would imply.
 */
private const val COINCIDENT_EYE_EPSILON_PX = 1e-3f

/**
 * Build the cover ellipse for one face. Inputs are plain floats in image pixels: the box
 * ([left], [top], [right], [bottom]) and the two eye landmarks ([leftEyeX], [leftEyeY]),
 * ([rightEyeX], [rightEyeY]).
 *
 * The ellipse is centred on the box, angled along the eye line, and grown by [padding] (default
 * ~0.12) a little past the box so hair and jaw at the corners are still covered. Coordinates stay
 * in the input's own image-pixel space and are not clamped here; a later piece clamps to the
 * bitmap when it draws. A degenerate box yields non-negative radii rather than a negative one.
 */
internal fun faceOval(
    left: Float, top: Float, right: Float, bottom: Float,
    leftEyeX: Float, leftEyeY: Float,
    rightEyeX: Float, rightEyeY: Float,
    padding: Float = 0.12f,
): OvalSpec {
    val eyeDx = rightEyeX - leftEyeX
    val eyeDy = rightEyeY - leftEyeY
    val rotationDegrees = if (hypot(eyeDx, eyeDy) < COINCIDENT_EYE_EPSILON_PX) {
        0f
    } else {
        Math.toDegrees(atan2(eyeDy, eyeDx).toDouble()).toFloat()
    }

    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f

    val grow = 1f + padding
    val radiusX = ((right - left) / 2f * grow).coerceAtLeast(0f)
    val radiusY = ((bottom - top) / 2f * grow).coerceAtLeast(0f)

    return OvalSpec(centerX, centerY, radiusX, radiusY, rotationDegrees)
}
