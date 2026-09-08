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

    /** Hamming distance at or below this counts as "visually similar" (tunable). 12/64 catches
     *  re-encoded / mildly-edited copies and burst frames (whose fingerprints sit several bits apart)
     *  while staying well below the distance unrelated shots fall at, so different photos are not
     *  grouped. The band split below is sized to keep candidate generation exact at this distance. */
    const val SIMILARITY_THRESHOLD = 12

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

    /** Bit widths of the 13 disjoint bands the 64-bit hash is split into: 12 bands of 5 bits plus a
     *  final band of 4 bits (5*12 + 4 = 64). With 13 bands, any two hashes at distance <= 12 differ
     *  in at most 12 bands by the pigeonhole principle, so at least one band is identical, which keeps
     *  the multi-index candidate generation exact at [SIMILARITY_THRESHOLD]. */
    private val BAND_WIDTHS = intArrayOf(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4)

    /** Number of bands the hash is divided into for multi-index candidate generation. */
    val BAND_COUNT = BAND_WIDTHS.size

    /**
     * Split [hash] into the [BAND_COUNT] band values. Each entry is the raw bit value of one band;
     * callers must pair it with the band index to key a bucket (a value in band i and the same value
     * in band j are unrelated). Pure and allocation-light so it can be unit-tested off-device.
     */
    fun bandValues(hash: Long): LongArray {
        val out = LongArray(BAND_COUNT)
        var shift = 0
        for (i in 0 until BAND_COUNT) {
            val width = BAND_WIDTHS[i]
            val mask = (1L shl width) - 1
            out[i] = (hash ushr shift) and mask
            shift += width
        }
        return out
    }

    /**
     * Every unordered pair (i, j) of indices into [hashes] whose Hamming distance is <= [threshold].
     *
     * EXACT for the multi-index (pigeonhole) scheme: hashes are bucketed by (band index, band value),
     * and only hashes sharing at least one band are distance-checked. Because any pair within
     * [threshold] (<= the 12 the [BAND_COUNT] banding is sized for) must share a band, this returns
     * the identical set the brute-force O(n²) sweep would, just with far fewer comparisons.
     *
     * The returned pairs always have i < j and each pair appears once.
     */
    fun similarPairs(hashes: LongArray, threshold: Int): List<Pair<Int, Int>> {
        val n = hashes.size
        if (n < 2) return emptyList()
        // bucket key = band index * 2^maxBandWidth + band value; kept unique across bands.
        val bucketShift = BAND_WIDTHS.max()
        val buckets = HashMap<Long, MutableList<Int>>()
        for (idx in 0 until n) {
            val values = bandValues(hashes[idx])
            for (band in 0 until BAND_COUNT) {
                val key = (band.toLong() shl bucketShift) or values[band]
                buckets.getOrPut(key) { mutableListOf() }.add(idx)
            }
        }
        // Dedupe pairs across the (up to BAND_COUNT) shared buckets two candidates can share.
        val seen = HashSet<Long>()
        val pairs = ArrayList<Pair<Int, Int>>()
        for (members in buckets.values) {
            if (members.size < 2) continue
            for (a in members.indices) {
                val i = members[a]
                for (b in a + 1 until members.size) {
                    val j = members[b]
                    val lo = if (i < j) i else j
                    val hi = if (i < j) j else i
                    val pairKey = lo.toLong() * n + hi
                    if (!seen.add(pairKey)) continue
                    if (distance(hashes[lo], hashes[hi]) <= threshold) {
                        pairs.add(lo to hi)
                    }
                }
            }
        }
        return pairs
    }

    /**
     * Single-link cluster every index into [hashes] whose Hamming distance is <= [threshold], and
     * return the union-find root of each index (indices with the same root are one cluster). This is
     * the memory-bounded counterpart of [similarPairs] for the duplicate finder: it produces the
     * IDENTICAL clusters a full pairwise sweep would, but never materialises the candidate-pair set
     * (a repeated union is idempotent, so the clustering needs the pairs only as they are found, not
     * as a stored list).
     *
     * Two things keep the footprint flat regardless of how the hashes are distributed:
     *  - byte-identical fingerprints are collapsed up front in O(n) (distance 0 is always <= threshold),
     *    so a burst of identical or uniform photos costs O(k) instead of the O(k²) candidate pairs that
     *    would otherwise be generated for a single huge band bucket, and
     *  - only the DISTINCT representatives are bucketed and distance-checked, unioned in place.
     * Peak extra memory is O(n) for the collapse map plus O([BAND_COUNT] * distinct) for the buckets.
     */
    fun clusterSimilar(hashes: LongArray, threshold: Int): IntArray {
        val n = hashes.size
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

        // Collapse identical fingerprints: keep one representative per distinct hash, union the rest
        // straight onto it. The pairwise sweep would union these too (distance 0), so this changes no
        // cluster; it only removes the duplicates before the O(bucket²) inner loop can see them.
        val repByHash = HashMap<Long, Int>()
        val reps = ArrayList<Int>()
        for (idx in 0 until n) {
            val existing = repByHash.putIfAbsent(hashes[idx], idx)
            if (existing == null) reps.add(idx) else union(idx, existing)
        }
        if (reps.size >= 2) {
            // Multi-index bucketing over the distinct representatives; union survivors as found, with no
            // global seen-set. A distinct pair may be re-examined across shared bands, but a repeated
            // union is idempotent, so the result is exact while the buckets stay bounded.
            val bucketShift = BAND_WIDTHS.max()
            val buckets = HashMap<Long, MutableList<Int>>()
            for (idx in reps) {
                val values = bandValues(hashes[idx])
                for (band in 0 until BAND_COUNT) {
                    val key = (band.toLong() shl bucketShift) or values[band]
                    buckets.getOrPut(key) { mutableListOf() }.add(idx)
                }
            }
            for (members in buckets.values) {
                if (members.size < 2) continue
                for (a in members.indices) {
                    val i = members[a]
                    for (b in a + 1 until members.size) {
                        val j = members[b]
                        if (distance(hashes[i], hashes[j]) <= threshold) union(i, j)
                    }
                }
            }
        }
        // Flatten to canonical roots so callers can read parent[i] directly without a find().
        for (i in 0 until n) parent[i] = find(i)
        return parent
    }

    private fun luminance(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
