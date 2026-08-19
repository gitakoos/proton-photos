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

package eu.akoos.photos.data.face

import android.graphics.PointF
import android.graphics.RectF

/**
 * One face the detector found, in the original bitmap's own pixel coordinates.
 *
 * [box] bounds the face. [landmarks] are the five SCRFD points in a fixed order: left eye, right eye,
 * nose, left mouth corner, right mouth corner. [score] is the detector's confidence, higher is surer.
 * A plain data holder with no behaviour, so a later piece can draw, cover, or count these as it likes.
 */
data class DetectedFace(
    val box: RectF,
    val landmarks: List<PointF>,
    val score: Float,
)
