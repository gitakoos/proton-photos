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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the 256-bit duplicate clustering: the 32-band multi-index returns the identical partition a full
 * pairwise sweep would (so the fast path never mis-groups), a transitive chain of near-duplicates groups
 * as one, and two frames with the same structure but different palettes are kept apart by the colour gate.
 */
class PdqHashClusterTest {

    private val sameColor = ByteArray(PdqHash.COLOR_BYTES) { 100 }

    private fun fp(bits: LongArray, color: ByteArray = sameColor) =
        PdqHash.Fingerprint(bits, quality = 80, color = color)

    /** A 256-bit hash with the given bit indices (0..255) flipped from all-zero. */
    private fun bitsWith(vararg setBits: Int): LongArray {
        val out = LongArray(4)
        for (i in setBits) out[i ushr 6] = out[i ushr 6] or (1L shl (i and 63))
        return out
    }

    /** Flip [count] distinct bits of [base], starting at [from], returning a new hash. */
    private fun flip(base: LongArray, from: Int, count: Int): LongArray {
        val out = base.copyOf()
        for (k in 0 until count) {
            val i = (from + k) % 256
            out[i ushr 6] = out[i ushr 6] xor (1L shl (i and 63))
        }
        return out
    }

    /** Canonical cluster label per index: the smallest index sharing its root, so two partitions can be
     *  compared for equality regardless of which representative each happened to pick. */
    private fun canonical(roots: IntArray): IntArray {
        val minOfRoot = HashMap<Int, Int>()
        for (i in roots.indices) {
            val r = roots[i]
            minOfRoot[r] = minOf(minOfRoot[r] ?: i, i)
        }
        return IntArray(roots.size) { minOfRoot[roots[it]]!! }
    }

    private fun bruteForce(fps: List<PdqHash.Fingerprint>): IntArray {
        val n = fps.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (PdqHash.matches(fps[i], fps[j])) parent[find(i)] = find(j)
        }
        for (i in 0 until n) parent[i] = find(i)
        return parent
    }

    @Test
    fun identical_fingerprints_land_in_one_cluster() {
        val roots = PdqHash.clusterSimilar(listOf(fp(bitsWith(1, 2, 3)), fp(bitsWith(1, 2, 3))))
        assertEquals(roots[0], roots[1])
    }

    @Test
    fun far_apart_fingerprints_stay_separate() {
        val a = bitsWith(0, 1, 2, 3)
        val b = flip(a, 10, 90) // 90 bits apart, well beyond the threshold
        val roots = PdqHash.clusterSimilar(listOf(fp(a), fp(b)))
        assertTrue(roots[0] != roots[1])
    }

    @Test
    fun a_transitive_chain_groups_as_one() {
        // A~B and B~C each within the threshold, but A and C past it: single-link still unites all three.
        val a = LongArray(4)
        val b = flip(a, 0, 40)    // distance 40 from A, within the threshold
        val c = flip(b, 40, 40)   // distance 40 from B, 80 from A (past MATCH_THRESHOLD)
        assertTrue("A and C are past the direct threshold", PdqHash.distance(a, c) > PdqHash.MATCH_THRESHOLD)
        val roots = PdqHash.clusterSimilar(listOf(fp(a), fp(b), fp(c)))
        assertEquals(roots[0], roots[1])
        assertEquals(roots[1], roots[2])
    }

    @Test
    fun the_colour_gate_keeps_same_structure_different_palette_apart() {
        val bits = bitsWith(5, 6, 7)
        val red = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 0) 255.toByte() else 0 }
        val blue = ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 2) 255.toByte() else 0 }
        val roots = PdqHash.clusterSimilar(listOf(fp(bits, red), fp(bits.copyOf(), blue)))
        assertTrue("identical structure, opposite palette: not one cluster", roots[0] != roots[1])
    }

    @Test
    fun the_band_index_returns_the_same_partition_as_a_full_sweep() {
        // Validates the band index in its EXACT regime: variants are kept within Hamming 31, where the
        // 32-band pigeonhole guarantees a shared band, so the fast path must equal the exhaustive sweep
        // bit for bit. (Above 31 the index is a high-recall filter, not exact, so it is not asserted here.)
        val rng = Random(20260913)
        val fps = ArrayList<PdqHash.Fingerprint>()
        // Random bases, most mutually far apart.
        repeat(40) { fps.add(fp(LongArray(4) { rng.nextLong() })) }
        // Near-duplicate variants of some bases, within the exact-banding radius, so real clusters form.
        repeat(8) {
            val base = fps[rng.nextInt(40)].bits
            fps.add(fp(flip(base, rng.nextInt(256), rng.nextInt(1, 32))))
        }
        val fast = canonical(PdqHash.clusterSimilar(fps))
        val slow = canonical(bruteForce(fps))
        assertArrayEquals("the banded clustering matches the exhaustive sweep", slow, fast)
    }

    @Test
    fun the_band_index_matches_a_full_sweep_with_mixed_palettes_too() {
        val rng = Random(778899)
        val palettes = listOf(
            ByteArray(PdqHash.COLOR_BYTES) { 30 },
            ByteArray(PdqHash.COLOR_BYTES) { 200.toByte() },
            ByteArray(PdqHash.COLOR_BYTES) { if (it % 3 == 0) 255.toByte() else 20 },
        )
        val fps = ArrayList<PdqHash.Fingerprint>()
        repeat(30) { fps.add(fp(LongArray(4) { rng.nextLong() }, palettes[rng.nextInt(palettes.size)])) }
        repeat(10) {
            val base = fps[rng.nextInt(30)]
            fps.add(fp(flip(base.bits, rng.nextInt(256), rng.nextInt(0, 32)), palettes[rng.nextInt(palettes.size)]))
        }
        assertArrayEquals(canonical(bruteForce(fps)), canonical(PdqHash.clusterSimilar(fps)))
    }
}
