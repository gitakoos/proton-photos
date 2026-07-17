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
 * Proves [PerceptualHash.clusterSimilar] produces the IDENTICAL clusters as a brute-force O(n²)
 * pairwise union-find over the same hashes and threshold (so the duplicate finder groups exactly the
 * same photos as before), and that it stays bounded on the input that OOMs the pair-list approach:
 * a large library where many photos share a band bucket. All randomness is seeded for determinism.
 */
class PerceptualHashClusterSimilarTest {

    /** Reference truth: union every i<j pair whose Hamming distance is within threshold, the obvious
     *  correct clustering, then canonicalise every index to its root. */
    private fun bruteForceRoots(hashes: LongArray, threshold: Int): IntArray {
        val parent = IntArray(hashes.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                if (PerceptualHash.distance(hashes[i], hashes[j]) <= threshold) parent[find(i)] = find(j)
            }
        }
        for (i in parent.indices) parent[i] = find(i)
        return parent
    }

    /** A root array as the set of index groups it defines, so two clusterings compare structurally
     *  regardless of which member each picked as the root. */
    private fun partition(root: IntArray): Set<Set<Int>> {
        val byRoot = HashMap<Int, MutableSet<Int>>()
        for (i in root.indices) byRoot.getOrPut(root[i]) { mutableSetOf() }.add(i)
        return byRoot.values.map { it.toSet() }.toSet()
    }

    private fun flipBits(base: Long, count: Int, rng: Random): Long {
        var h = base
        val used = HashSet<Int>()
        repeat(count) {
            var bit: Int
            do { bit = rng.nextInt(64) } while (!used.add(bit))
            h = h xor (1L shl bit)
        }
        return h
    }

    @Test
    fun matches_brute_force_on_seeded_random_libraries_with_near_duplicates() {
        val t = PerceptualHash.SIMILARITY_THRESHOLD
        for (seed in 1..40) {
            val rng = Random(seed)
            val hashes = ArrayList<Long>()
            // A spread of far-apart singletons.
            repeat(rng.nextInt(20, 80)) { hashes.add(rng.nextLong()) }
            // A handful of near-duplicate runs: each variant is <= t/2 bits from its base, so any two
            // in a run are <= t apart (one cluster), while distinct runs stay far apart.
            repeat(rng.nextInt(2, 6)) {
                val base = rng.nextLong()
                repeat(rng.nextInt(2, 7)) { hashes.add(flipBits(base, rng.nextInt(0, t / 2 + 1), rng)) }
            }
            hashes.shuffle(rng)
            val arr = hashes.toLongArray()

            assertEquals(
                "clusterSimilar must match the pairwise sweep (seed=$seed, n=${arr.size})",
                partition(bruteForceRoots(arr, t)),
                partition(PerceptualHash.clusterSimilar(arr, t)),
            )
        }
    }

    @Test
    fun identical_hashes_collapse_into_one_cluster_each_without_exploding() {
        val t = PerceptualHash.SIMILARITY_THRESHOLD
        // 10k copies of one fingerprint and 10k of a far-apart one. The old candidate-pair set would
        // add ~10000²/2 = 50 million entries for a single band bucket and run the heap out; the
        // collapse makes this two O(n) unions. The two values are > t apart, so they stay separate.
        val a = 0x0123456789ABCDEFL
        val b = a.inv()
        assertTrue("fixtures must be far apart", PerceptualHash.distance(a, b) > t)
        val arr = LongArray(20_000) { if (it < 10_000) a else b }

        val part = partition(PerceptualHash.clusterSimilar(arr, t))
        assertEquals("two identical-hash blocks form two clusters", 2, part.size)
        assertTrue("each cluster holds all 10000 copies", part.all { it.size == 10_000 })
    }

    @Test
    fun a_large_burst_of_distinct_similar_frames_is_one_cluster() {
        val t = PerceptualHash.SIMILARITY_THRESHOLD
        val rng = Random(99)
        val base = rng.nextLong()
        // 1500 DISTINCT frames each within t/2 bits of the base: all pairwise within t, so exactly one
        // cluster. Exercises the distinct-representative bucket path at scale without a pair set.
        val arr = LongArray(1500) { flipBits(base, rng.nextInt(0, t / 2 + 1), rng) }

        val part = partition(PerceptualHash.clusterSimilar(arr, t))
        assertEquals("the whole burst is a single cluster", 1, part.size)
        assertEquals("no frame is dropped", 1500, part.first().size)
    }

    @Test
    fun unrelated_hashes_never_group() {
        val t = PerceptualHash.SIMILARITY_THRESHOLD
        // Values pairwise far apart (each differs from the previous by all 64 bits alternating) give
        // no similar pairs, so every index is its own cluster and the finder shows no false groups.
        val arr = longArrayOf(0L, -1L, 0x5555555555555555L, 0xAAAAAAAAAAAAAAAAUL.toLong())
        assertEquals(
            partition(bruteForceRoots(arr, t)),
            partition(PerceptualHash.clusterSimilar(arr, t)),
        )
        assertEquals("four unrelated hashes stay four singletons", 4, partition(PerceptualHash.clusterSimilar(arr, t)).size)
    }
}
