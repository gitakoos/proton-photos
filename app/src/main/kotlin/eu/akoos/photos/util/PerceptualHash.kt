/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import android.graphics.Bitmap

/**
 * Difference hash (dHash) — a classic perceptual fingerprint. Two photos that look the same to a
 * human (same image re-encoded, renamed, or stripped of metadata) yield hashes a small Hamming
 * distance apart, so near-identical copies can be matched without comparing bytes.
 *
 * The hash scales the image to 9x8 and, for each of the 8 rows, walks the 9 pixels left to right
 * comparing each pixel's luminance to its right neighbour. Each comparison sets one bit, giving a
 * 64-bit signature that is robust to scaling, mild compression, and small tonal shifts.
 */
object PerceptualHash {

    /** Hamming distance at or below this counts as "visually similar" (tunable). 8/64 catches
     *  re-encoded / mildly-edited copies and burst frames while avoiding false matches between
     *  unrelated shots that merely share a flat (e.g. all-black) background. */
    const val SIMILARITY_THRESHOLD = 8

    /** Version of the dHash algorithm. Bump to invalidate every stored hash when [dHash] changes
     *  shape (grid size, bit order, luminance weights) so old fingerprints recompute. */
    const val DHASH_ALGO_VERSION = 1

    /** Compute the 64-bit dHash of [bmp]. The input is left untouched; the caller owns its lifecycle. */
    fun dHash(bmp: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            var left = luminance(scaled.getPixel(0, y))
            for (x in 1 until 9) {
                val right = luminance(scaled.getPixel(x, y))
                if (left > right) hash = hash or (1L shl bit)
                left = right
                bit++
            }
        }
        if (scaled !== bmp) scaled.recycle()
        return hash
    }

    /** Number of differing bits between two hashes — the perceptual distance metric. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luminance(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
