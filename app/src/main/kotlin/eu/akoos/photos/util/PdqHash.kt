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

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A DCT-based perceptual fingerprint for the duplicate finder, far more discriminating than a plain
 * difference hash. It follows the well-known spectral-hash recipe: reduce the photo to a 64x64
 * luminance field, take its 2-D discrete cosine transform, keep the 16x16 block of lowest frequencies
 * (dropping the DC term so overall brightness does not matter), and set each of the 256 bits from
 * whether its coefficient is above the block median. Two photos that look the same to a person land a
 * small Hamming distance apart; two different photos that merely share a coarse light/dark layout, which
 * a difference hash confuses, disagree across the richer frequency signature.
 *
 * Two extra signals cut the false matches a single grayscale hash cannot:
 *  - a [quality] score from how much detail the downsampled image carries, so a near-flat frame (a black,
 *    white or evenly grey shot) is discarded rather than matched against every other flat frame, and
 *  - a coarse [color] signature, so two shots with the same structure but different palettes are told
 *    apart, which luminance alone cannot do.
 *
 * The math runs on a plain ARGB pixel array via [fingerprintFromPixels] with its own area-average
 * downscale, so the whole algorithm is unit-tested off-device; [fingerprint] is only the thin bitmap
 * read on top. No third-party code: the recipe is standard and implemented here from scratch.
 */
object PdqHash {

    /** Bump to invalidate every stored fingerprint when the algorithm below changes shape, so old and
     *  new fingerprints are never compared as if they meant the same thing. */
    const val ALGO_VERSION = 1

    /** 256-bit hash, held as four longs. */
    const val HASH_LONGS = 4

    /** Structural Hamming distance (out of 256) at or below which two photos count as the same image,
     *  before the colour gate. Calibrated to this pipeline's own distance scale, not a textbook figure:
     *  measured on a real library, genuine near-duplicates (re-encoded, lightly edited, burst frames) sit
     *  up to the mid-60s while unrelated photos cluster past ~90, a clean gap, so the bar sits inside it.
     *  It is deliberately looser than the reference 256-bit hash's ~12%, because this area-averaged
     *  variant spreads a near-duplicate's bits wider; the colour gate below guards the looser structural
     *  bar, so two unrelated shots that merely share a coarse layout are still rejected. */
    const val MATCH_THRESHOLD = 64

    /** Below this [quality] a frame carries too little detail to fingerprint reliably (a near-flat black,
     *  white or grey shot), so it is dropped from the finder instead of matching every other flat frame.
     *  On our own 0..100 detail scale (not a third-party one), tuned to drop only near-flat frames. */
    const val MIN_QUALITY = 10

    /** Mean per-channel gap (0..255) allowed between two photos' coarse colour grids for a match. Two
     *  frames whose structure matches but whose palettes differ by more than this are not the same shot. */
    const val MAX_COLOR_DISTANCE = 40

    /** Side of the square colour grid: a 4x4 average-colour signature, 3 bytes per cell. */
    const val COLOR_CELLS = 4
    const val COLOR_BYTES = COLOR_CELLS * COLOR_CELLS * 3

    private const val GRID = 64   // the DCT works on a 64x64 luminance field
    private const val COEFFS = 16 // and keeps the 16x16 lowest-frequency block (skipping DC)

    /** A photo's fingerprint: the 256-bit structural [bits], its [quality] detail score (0..100), and a
     *  coarse [color] grid ([COLOR_BYTES] bytes, cell-major R,G,B). */
    class Fingerprint(val bits: LongArray, val quality: Int, val color: ByteArray)

    /** Fingerprint a bitmap. The caller owns the bitmap; this only reads it. */
    fun fingerprint(bmp: Bitmap): Fingerprint {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return fingerprintFromPixels(px, w, h)
    }

    /**
     * Fingerprint a raw ARGB pixel array of the given size. Pure: no Android, no allocation of a scaled
     * bitmap, so the whole recipe is exercised in a plain JVM test.
     */
    fun fingerprintFromPixels(argb: IntArray, width: Int, height: Int): Fingerprint {
        val lum = FloatArray(GRID * GRID)
        val lumCount = IntArray(GRID * GRID)
        val colR = LongArray(COLOR_CELLS * COLOR_CELLS)
        val colG = LongArray(COLOR_CELLS * COLOR_CELLS)
        val colB = LongArray(COLOR_CELLS * COLOR_CELLS)
        val colCount = IntArray(COLOR_CELLS * COLOR_CELLS)
        val w = if (width > 0) width else 1
        val h = if (height > 0) height else 1
        // One pass over the source: area-average each pixel into both the 64x64 luminance field and the
        // 4x4 colour grid, so neither needs its own scaled copy.
        var i = 0
        for (y in 0 until h) {
            val gy = min(y * GRID / h, GRID - 1)
            val cy = min(y * COLOR_CELLS / h, COLOR_CELLS - 1)
            for (x in 0 until w) {
                val p = argb[i++]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val gx = min(x * GRID / w, GRID - 1)
                val lumIdx = gy * GRID + gx
                lum[lumIdx] += (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
                lumCount[lumIdx]++
                val cx = min(x * COLOR_CELLS / w, COLOR_CELLS - 1)
                val cIdx = cy * COLOR_CELLS + cx
                colR[cIdx] += r.toLong()
                colG[cIdx] += g.toLong()
                colB[cIdx] += b.toLong()
                colCount[cIdx]++
            }
        }
        // Any 64x64 cell no source pixel mapped to (a source smaller than 64 in a dimension) borrows the
        // average of the cells that did, so the DCT reads a smooth field rather than a hole.
        fillEmptyCells(lum, lumCount)

        val quality = quality(lum)
        val dct = lowFreqDct(lum)
        val median = median(dct)
        val bits = LongArray(HASH_LONGS)
        for (k in dct.indices) {
            if (dct[k] > median) bits[k ushr 6] = bits[k ushr 6] or (1L shl (k and 63))
        }

        val color = ByteArray(COLOR_BYTES)
        for (c in 0 until COLOR_CELLS * COLOR_CELLS) {
            val n = if (colCount[c] > 0) colCount[c] else 1
            color[c * 3] = (colR[c] / n).toInt().coerceIn(0, 255).toByte()
            color[c * 3 + 1] = (colG[c] / n).toInt().coerceIn(0, 255).toByte()
            color[c * 3 + 2] = (colB[c] / n).toInt().coerceIn(0, 255).toByte()
        }
        return Fingerprint(bits, quality, color)
    }

    /** Structural Hamming distance between two 256-bit hashes. */
    fun distance(a: LongArray, b: LongArray): Int {
        var d = 0
        val n = min(a.size, b.size)
        for (i in 0 until n) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    /** Mean absolute per-channel difference between two colour grids (0..255). */
    fun colorDistance(a: ByteArray, b: ByteArray): Int {
        val n = min(a.size, b.size)
        if (n == 0) return 0
        var sum = 0
        for (i in 0 until n) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return sum / n
    }

    /**
     * Whether two fingerprints are the same photo: their structure within [MATCH_THRESHOLD] AND their
     * coarse colour within [MAX_COLOR_DISTANCE]. The colour gate is what rejects a structural collision
     * between two frames that share a layout but not a palette.
     */
    fun matches(a: Fingerprint, b: Fingerprint): Boolean =
        distance(a.bits, b.bits) <= MATCH_THRESHOLD && colorDistance(a.color, b.color) <= MAX_COLOR_DISTANCE

    /** Whether a fingerprint carries enough detail to be trusted (else it is a near-flat frame). */
    fun isUsable(fp: Fingerprint): Boolean = fp.quality >= MIN_QUALITY

    /** Bands the 256-bit hash is split into for candidate generation: 32 disjoint bands of 8 bits. Any
     *  two hashes within Hamming 31 share an identical band by the pigeonhole principle, so candidate
     *  generation is EXACT up to 31. Above that, up to [MATCH_THRESHOLD], sharing an 8-bit band is
     *  overwhelmingly likely rather than guaranteed (a near-duplicate leaves most of its 32 bands
     *  untouched, so it almost always collides in at least one), which keeps the search a high-recall
     *  filter, all a best-effort duplicate finder needs. More bands would only shrink the buckets below
     *  what a large library can afford to scan. */
    const val BAND_COUNT = 32

    /** The [BAND_COUNT] eight-bit band values of a 256-bit hash. Band b is byte b of the little-endian
     *  hash (eight bands per 64-bit word), so two hashes sharing band b share those eight bits. */
    fun bandValues(bits: LongArray): IntArray {
        val out = IntArray(BAND_COUNT)
        for (b in 0 until BAND_COUNT) {
            val word = bits[b ushr 3]
            out[b] = ((word ushr ((b and 7) * 8)) and 0xFFL).toInt()
        }
        return out
    }

    /**
     * Single-link cluster the fingerprints: two land in one cluster when they [matches] (structure within
     * [MATCH_THRESHOLD] AND colour within [MAX_COLOR_DISTANCE]), transitively. Returns the union-find root
     * of each index (same root = one cluster). Callers drop low-[quality] fingerprints BEFORE this, so a
     * near-flat frame is never grouped.
     *
     * Candidate pairs come from the 32-band multi-index (only fingerprints sharing a band are compared):
     * exact up to Hamming 31 by the pigeonhole principle, and a high-recall filter from there to
     * [MATCH_THRESHOLD] (a near-duplicate almost always leaves at least one of its 32 bands untouched, so
     * it still collides), which is what a best-effort finder needs and keeps the scan far cheaper than a
     * full O(n^2) sweep. A pair reachable through several shared bands is re-checked, but a repeated union
     * is idempotent, so the outcome is unchanged.
     */
    fun clusterSimilar(fps: List<Fingerprint>): IntArray {
        val n = fps.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        if (n < 2) return parent

        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val values = bandValues(fps[i].bits)
            for (band in 0 until BAND_COUNT) {
                val key = (band shl 8) or values[band]
                buckets.getOrPut(key) { mutableListOf() }.add(i)
            }
        }
        for (members in buckets.values) {
            if (members.size < 2) continue
            for (a in members.indices) {
                val i = members[a]
                for (b in a + 1 until members.size) {
                    val j = members[b]
                    if (find(i) == find(j)) continue
                    if (matches(fps[i], fps[j])) union(i, j)
                }
            }
        }
        for (i in 0 until n) parent[i] = find(i)
        return parent
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** Finish the area-average: divide each accumulated cell by its pixel count. */
    private fun fillEmptyCells(lum: FloatArray, count: IntArray) {
        var sum = 0.0
        var filled = 0
        for (i in lum.indices) {
            if (count[i] > 0) {
                lum[i] /= count[i]
                sum += lum[i]
                filled++
            }
        }
        if (filled == 0) return
        val mean = (sum / filled).toFloat()
        for (i in lum.indices) if (count[i] == 0) lum[i] = mean
    }

    /**
     * The 16x16 block of lowest non-DC frequencies of the 2-D DCT-II of the 64x64 field, row-major
     * (frequency 1..16 on each axis). Computed as two 1-D passes against a cached 16x64 cosine basis, so
     * only the kept coefficients are ever evaluated.
     */
    private fun lowFreqDct(lum: FloatArray): FloatArray {
        val basis = BASIS
        // Rows: for each source row, project onto the 16 kept horizontal frequencies.
        val rowT = FloatArray(GRID * COEFFS)
        for (r in 0 until GRID) {
            val rowOff = r * GRID
            for (ku in 0 until COEFFS) {
                val b = basis[ku]
                var acc = 0.0
                for (n in 0 until GRID) acc += lum[rowOff + n] * b[n]
                rowT[r * COEFFS + ku] = acc.toFloat()
            }
        }
        // Columns: project the row results onto the 16 kept vertical frequencies.
        val out = FloatArray(COEFFS * COEFFS)
        for (kv in 0 until COEFFS) {
            val b = basis[kv]
            for (ku in 0 until COEFFS) {
                var acc = 0.0
                for (r in 0 until GRID) acc += rowT[r * COEFFS + ku] * b[r]
                out[kv * COEFFS + ku] = acc.toFloat()
            }
        }
        return out
    }

    /** Cosine basis for the kept frequencies: BASIS[k][n] = cos(pi * (n + 0.5) * (k + 1) / 64), i.e. the
     *  DCT-II rows for frequencies 1..16 (index 0 here is frequency 1, so the DC term is never kept). */
    private val BASIS: Array<DoubleArray> = Array(COEFFS) { k ->
        DoubleArray(GRID) { n -> cos(Math.PI * (n + 0.5) * (k + 1) / GRID) }
    }

    /** Median of the 256 coefficients (mean of the two central order statistics). */
    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * Detail score 0..100: the mean absolute luminance step between neighbouring cells of the 64x64
     * field, mapped so a richly detailed photo saturates near 100 and a near-flat frame falls near 0.
     * [FULL_DETAIL_STEP] is the neighbour step treated as full detail; both it and [MIN_QUALITY] are our
     * own calibration, tunable against real libraries, not a figure carried over from any other project.
     */
    private fun quality(lum: FloatArray): Int {
        var sum = 0.0
        var n = 0
        for (y in 0 until GRID) {
            val off = y * GRID
            for (x in 0 until GRID) {
                if (x + 1 < GRID) { sum += abs(lum[off + x] - lum[off + x + 1]); n++ }
                if (y + 1 < GRID) { sum += abs(lum[off + x] - lum[off + GRID + x]); n++ }
            }
        }
        if (n == 0) return 0
        val avgStep = sum / n
        return min(100.0, avgStep * 100.0 / FULL_DETAIL_STEP).roundToInt()
    }

    /** Mean neighbour luminance step (0..255) that counts as full detail for the [quality] scale. */
    private const val FULL_DETAIL_STEP = 24.0
}
