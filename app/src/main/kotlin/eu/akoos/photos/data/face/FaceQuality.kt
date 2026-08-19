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

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure face-quality helpers, free of Android types so the grading rule runs in a plain JVM test. A
 * Laplacian-variance sharpness score and a landmark-based turn measure together mark the weak crops
 * that should not be trusted to merge two people. Faces are never dropped on quality alone: a weak
 * face is instead held to a stricter cluster distance, so a blurred or side-on crop cannot bridge
 * two identities.
 */

/**
 * Variance of the Laplacian over a grayscale image, the standard sharpness proxy: a sharp crop has
 * strong second-derivative responses (high variance), a blurred one has weak ones (low variance).
 * [gray] holds one luminance value per pixel (0..255) in row-major order, [width] by [height]. A
 * degenerate size yields 0, i.e. treated as maximally blurred.
 */
fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
    if (width < 3 || height < 3 || gray.size < width * height) return 0.0
    var sum = 0.0
    var sumSq = 0.0
    var count = 0
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val i = row + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]
            sum += lap
            sumSq += lap.toDouble() * lap
            count++
        }
    }
    if (count == 0) return 0.0
    val mean = sum / count
    return sumSq / count - mean * mean
}

/** One detected facial landmark, in the detector's source pixel space. */
class Landmark(val x: Float, val y: Float)

/**
 * How far from frontal a face is turned, in roughly [0,1], from the five landmarks in the detector's
 * fixed order (left eye, right eye, nose, left mouth, right mouth). A frontal face keeps the nose
 * about equidistant from the two eyes and the two mouth corners; a turned face shifts the nose toward
 * one side and makes those distances lopsided. Returns the mean of the eye asymmetry and the mouth
 * asymmetry, each the normalised absolute difference of the left and right distances to the nose, so
 * the measure is scale-free and near 0 for a straight-on face.
 */
fun sidewaysMeasure(
    leftEye: Landmark,
    rightEye: Landmark,
    nose: Landmark,
    leftMouth: Landmark,
    rightMouth: Landmark,
): Float {
    fun d(a: Landmark, b: Landmark) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    fun asym(a: Landmark, b: Landmark): Double {
        val da = d(a, nose)
        val db = d(b, nose)
        val denom = da + db
        return if (denom <= 0.0) 0.0 else abs(da - db) / denom
    }
    return ((asym(leftEye, rightEye) + asym(leftMouth, rightMouth)) / 2.0).toFloat()
}

/**
 * Parses the `x,y;x,y;...` landmark string the indexer stores and returns its turn measure, or null
 * when fewer than five points are present. Keeps the clusterer free of the storage format.
 */
fun sidewaysFromEncoded(encoded: String): Float? {
    val points = encoded.split(';').mapNotNull { pair ->
        val xy = pair.split(',')
        if (xy.size != 2) return@mapNotNull null
        val x = xy[0].toFloatOrNull() ?: return@mapNotNull null
        val y = xy[1].toFloatOrNull() ?: return@mapNotNull null
        Landmark(x, y)
    }
    if (points.size < 5) return null
    return sidewaysMeasure(points[0], points[1], points[2], points[3], points[4])
}

/** Detection-score, sharpness and turn floors a face must clear to be trusted for confident merging.
 *  Placeholders, adapted to our own embeddings once faces are re-indexed from the HD source. */
const val FACE_MIN_CONFIDENT_SCORE = 0.62f
const val FACE_MIN_CONFIDENT_BLUR = 12.0
const val FACE_MAX_CONFIDENT_SIDEWAYS = 0.18f

/**
 * A face clear enough to anchor a person and to merge at the normal distance. A weak one (low score,
 * blurred, or turned) is not dropped: the caller holds it to a stricter distance instead, so it can
 * still join an obvious match but cannot bridge two identities. A null [blur] or [sideways] (an older
 * row indexed before the metric existed) is treated as unknown and does not fail the check on its own.
 */
fun isConfidentFace(
    detectionScore: Float,
    blur: Double?,
    sideways: Float?,
    minScore: Float = FACE_MIN_CONFIDENT_SCORE,
    minBlur: Double = FACE_MIN_CONFIDENT_BLUR,
    maxSideways: Float = FACE_MAX_CONFIDENT_SIDEWAYS,
): Boolean {
    if (detectionScore < minScore) return false
    if (blur != null && blur < minBlur) return false
    if (sideways != null && sideways > maxSideways) return false
    return true
}
