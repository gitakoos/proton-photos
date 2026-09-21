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
 * The 112x112 destination template and the similarity (Procrustes / umeyama-without-reflection)
 * alignment follow the published InsightFace ArcFace face-alignment format, which is Apache-2.0
 * licensed:
 *
 *   Copyright (c) 2021 InsightFace
 */

package eu.akoos.photos.data.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF

/**
 * Warps a detected face onto the ArcFace 112x112 template so the recognition model sees a face in the
 * pose it was trained on: the eyes, nose and mouth corners landed on fixed pixels regardless of how
 * the face sat in the original frame.
 *
 * The geometry lives entirely in [similarityTransform], which is pure and android-free so it can be
 * unit-tested on its own; [alignFace] only turns that 2x3 affine into an android draw. This is what a
 * later indexer calls per detected face, feeding the result straight to [FaceEmbedder].
 */
object FaceAlignment {

    /** Side of the square the recognition model reads. */
    const val SIZE = 112

    /**
     * The ArcFace destination landmarks in the [DetectedFace] order (left eye, right eye, nose, left
     * mouth corner, right mouth corner), split into x and y so the pure transform stays android-free.
     */
    val TEMPLATE_X = floatArrayOf(38.2946f, 73.5318f, 56.0252f, 41.5493f, 70.7299f)
    val TEMPLATE_Y = floatArrayOf(51.6963f, 51.5014f, 71.7366f, 92.3655f, 92.2041f)

    /**
     * The 2x3 affine [a, -b, tx, b, a, ty] mapping the source landmarks (srcX, srcY) onto the
     * template, as the closed-form 2D similarity (Procrustes / umeyama without reflection). The
     * mapping it encodes is x' = a*x - b*y + tx and y' = b*x + a*y + ty, where a is s*cos and b is
     * s*sin of the single rotation-plus-uniform-scale that best lands the source on the template.
     *
     * A degenerate source (every point equal, so there is no scale or rotation to recover) has a zero
     * spread and falls back to a plain translation that centres the source mean on the template mean,
     * which stays finite and never yields NaN.
     */
    fun similarityTransform(srcX: FloatArray, srcY: FloatArray): FloatArray {
        val count = minOf(srcX.size, srcY.size, TEMPLATE_X.size)
        if (count == 0) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

        var meanSrcX = 0f
        var meanSrcY = 0f
        var meanDstX = 0f
        var meanDstY = 0f
        for (i in 0 until count) {
            meanSrcX += srcX[i]
            meanSrcY += srcY[i]
            meanDstX += TEMPLATE_X[i]
            meanDstY += TEMPLATE_Y[i]
        }
        meanSrcX /= count
        meanSrcY /= count
        meanDstX /= count
        meanDstY /= count

        var sxx = 0f
        var a = 0f
        var b = 0f
        for (i in 0 until count) {
            val px = srcX[i] - meanSrcX
            val py = srcY[i] - meanSrcY
            val qx = TEMPLATE_X[i] - meanDstX
            val qy = TEMPLATE_Y[i] - meanDstY
            sxx += px * px + py * py
            a += px * qx + py * qy
            b += px * qy - py * qx
        }

        if (sxx == 0f || sxx.isNaN()) {
            return floatArrayOf(1f, 0f, meanDstX - meanSrcX, 0f, 1f, meanDstY - meanSrcY)
        }

        val scap = a / sxx
        val ssin = b / sxx
        val tx = meanDstX - scap * meanSrcX + ssin * meanSrcY
        val ty = meanDstY - ssin * meanSrcX - scap * meanSrcY
        return floatArrayOf(scap, -ssin, tx, ssin, scap, ty)
    }

    /**
     * [source] warped onto a fresh 112x112 ARGB_8888 bitmap through the [similarityTransform] of its
     * [landmarks] (the five [DetectedFace] points, in order). The result is what [FaceEmbedder] reads.
     */
    fun alignFace(source: Bitmap, landmarks: List<PointF>): Bitmap {
        val count = minOf(landmarks.size, TEMPLATE_X.size)
        val srcX = FloatArray(count) { landmarks[it].x }
        val srcY = FloatArray(count) { landmarks[it].y }
        val affine = similarityTransform(srcX, srcY)

        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(
                affine[0], affine[1], affine[2],
                affine[3], affine[4], affine[5],
                0f, 0f, 1f,
            ),
        )

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(out).drawBitmap(source, matrix, paint)
        return out
    }
}
