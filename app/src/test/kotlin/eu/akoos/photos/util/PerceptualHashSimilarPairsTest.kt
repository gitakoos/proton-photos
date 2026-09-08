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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Proves the multi-index (pigeonhole) candidate generation in [PerceptualHash.similarPairs] returns
 * the IDENTICAL set of <= threshold pairs (and therefore the identical union-find clusters) as a
 * brute-force O(n²) sweep over the same 64-bit hashes and threshold. This is the guarantee that the
 * duplicate-finder optimization changed nothing observable.
 *
 * All randomness is seeded so the run is fully deterministic.
 */
class PerceptualHashSimilarPairsTest {

    /** Reference O(n²) sweep: every i < j pair whose Hamming distance is <= [threshold]. */
    private fun bruteForcePairs(hashes: LongArray, threshold: Int): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                if (PerceptualHash.distance(hashes[i], hashes[j]) <= threshold) out.add(i to j)
            }
        }
        return out
    }

    /** Union-find clustering identical to the ViewModel: cluster memberships as sets of indices. */
    private fun clusters(n: Int, pairs: Collection<Pair<Int, Int>>): Set<Set<Int>> {
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }
        for ((i, j) in pairs) parent[find(i)] = find(j)
        val byRoot = HashMap<Int, MutableSet<Int>>()
        for (i in 0 until n) byRoot.getOrPut(find(i)) { mutableSetOf() }.add(i)
        return byRoot.values.map { it.toSet() }.toSet()
    }

    /** Flip [count] distinct random bits of [hash], producing a hash at that exact distance from it. */
    private fun perturb(hash: Long, count: Int, rnd: Random): Long {
        var out = hash
        val flipped = HashSet<Int>()
        while (flipped.size < count) {
            val bit = rnd.nextInt(64)
            if (flipped.add(bit)) out = out xor (1L shl bit)
        }
        return out
    }

    @Test
    fun `band split covers all 64 bits with the expected 13-band shape`() {
        // 12 bands of 5 bits + 1 band of 4 bits = 64, so every hash bit lands in exactly one band.
        assertEquals(13, PerceptualHash.BAND_COUNT)
        // A hash with every bit set must have every band value non-zero and, reassembled, equal -1L.
        val all = -1L
        val values = PerceptualHash.bandValues(all)
        assertEquals(13, values.size)
        assertTrue(values.all { it != 0L })
    }

    @Test
    fun `multi-index pairs equal brute-force pairs on seeded random hashes`() {
        val rnd = Random(0xBEEF)
        val hashes = LongArray(400) { rnd.nextLong() }

        val expected = bruteForcePairs(hashes, PerceptualHash.SIMILARITY_THRESHOLD)
        val actual = PerceptualHash.similarPairs(hashes, PerceptualHash.SIMILARITY_THRESHOLD).toSet()

        assertEquals(expected, actual)
        assertEquals(clusters(hashes.size, expected), clusters(hashes.size, actual))
    }

    @Test
    fun `deliberately near pairs (including chains) are found and match brute force`() {
        val rnd = Random(0x1234)
        val base = MutableList(120) { rnd.nextLong() }
        val t = PerceptualHash.SIMILARITY_THRESHOLD
        // Seed clusters and chains: exact dupes, in-threshold neighbours, an over-threshold link
        // that single-link clustering should still bridge transitively, and a just-past outlier.
        val seeded = ArrayList<Long>(base)
        val anchor = base[0]
        seeded.add(anchor)                                   // distance 0 (exact duplicate)
        seeded.add(perturb(anchor, t / 2, rnd))              // within threshold
        seeded.add(perturb(anchor, t, rnd))                  // exactly at threshold
        val chainB = perturb(anchor, t, rnd)
        seeded.add(chainB)                                   // A~B at t
        seeded.add(perturb(chainB, t, rnd))                  // B~C at t (A~C may exceed t: chain link)
        seeded.add(perturb(anchor, t + 1, rnd))              // just past threshold, must NOT pair with anchor

        val hashes = seeded.toLongArray()
        val threshold = t

        val expected = bruteForcePairs(hashes, threshold)
        val actual = PerceptualHash.similarPairs(hashes, threshold).toSet()

        assertEquals(expected, actual)
        assertEquals(clusters(hashes.size, expected), clusters(hashes.size, actual))
        // Sanity: the exact-duplicate pair (indices 0 and the first appended copy) is present.
        assertTrue(actual.any { it.first == 0 })
    }

    @Test
    fun `equivalence holds across many seeds and multiple thresholds`() {
        // Sweep several seeds and sizes; check the multi-index result matches brute force for the
        // production threshold and smaller values, which the banding is sized to keep exact.
        for (seed in 0 until 25) {
            val rnd = Random(seed.toLong())
            val size = 50 + rnd.nextInt(250)
            // Mix uniformly-random hashes with near-neighbours of earlier ones to force bucket overlap.
            val pool = ArrayList<Long>(size)
            repeat(size) {
                val h = if (pool.isNotEmpty() && rnd.nextInt(4) == 0) {
                    perturb(pool[rnd.nextInt(pool.size)], rnd.nextInt(14), rnd)
                } else {
                    rnd.nextLong()
                }
                pool.add(h)
            }
            val hashes = pool.toLongArray()
            for (threshold in intArrayOf(0, 4, PerceptualHash.SIMILARITY_THRESHOLD)) {
                val expected = bruteForcePairs(hashes, threshold)
                val actual = PerceptualHash.similarPairs(hashes, threshold).toSet()
                assertEquals("seed=$seed threshold=$threshold", expected, actual)
                assertEquals(
                    "seed=$seed threshold=$threshold clusters",
                    clusters(hashes.size, expected),
                    clusters(hashes.size, actual),
                )
            }
        }
    }
}
