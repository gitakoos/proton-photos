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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The auto-merge selection: for each named person it folds in the single closest UNNAMED candidate above
 * the threshold, giving a candidate that is close to two named people to the nearer one, and never folding
 * the Unsorted bucket, a named person, or a rejected pair. The vectors below are plain unit-ish directions
 * so the cosines are obvious; the real feature runs this over 512-d face centroids.
 */
class AutoMergePeopleTest {

    @Test
    fun `folds the closest unnamed candidate per named person, skipping other and rejected`() {
        val centroids = mapOf(
            1L to floatArrayOf(1f, 0f, 0f),        // named A
            2L to floatArrayOf(0f, 1f, 0f),        // named B
            10L to floatArrayOf(0.98f, 0.17f, 0f), // unnamed, ~A -> folds into A
            11L to floatArrayOf(0.17f, 0.98f, 0f), // unnamed, ~B -> folds into B
            12L to floatArrayOf(0.9f, 0.436f, 0f), // unnamed, ~A but rejected for A, not near B -> nothing
            13L to floatArrayOf(0f, 0f, 1f),       // unnamed, far from both -> nothing
            99L to floatArrayOf(0.99f, 0.14f, 0f), // Unsorted bucket -> skipped even though ~A
        )
        val pairs = pickAutoMergePairs(
            centroids = centroids,
            namedIds = setOf(1L, 2L),
            otherId = 99L,
            rejectedPairs = setOf(1L to 12L),
            threshold = 0.45f,
        )
        // Each named person absorbs its own closest look-alike; the rejected, distant and Unsorted ones do not.
        assertEquals(mapOf(10L to 1L, 11L to 2L), pairs.associate { it.fromClusterId to it.toPersonId })
    }

    @Test
    fun `never folds a named person into another named person`() {
        val centroids = mapOf(
            1L to floatArrayOf(1f, 0f),       // named A
            3L to floatArrayOf(0.98f, 0.17f), // ANOTHER named, very close to A
        )
        // Only named people, no unnamed candidates: nothing folds even though 3 sits right next to 1.
        assertEquals(emptyList<AutoMergePair>(), pickAutoMergePairs(centroids, setOf(1L, 3L), -1L, emptySet(), 0.45f))
    }

    @Test
    fun `gives a candidate close to two named people to the nearer one`() {
        val centroids = mapOf(
            1L to floatArrayOf(1f, 0f),
            2L to floatArrayOf(0f, 1f),
            20L to floatArrayOf(0.8f, 0.6f), // 0.8 to A, 0.6 to B -> A
        )
        assertEquals(mapOf(20L to 1L), pickAutoMergePairs(centroids, setOf(1L, 2L), -1L, emptySet(), 0.45f).associate { it.fromClusterId to it.toPersonId })
    }

    @Test
    fun `nothing folds when there are no named people or no candidates`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0.99f, 0.14f)
        // No named people at all.
        assertEquals(emptyList<AutoMergePair>(), pickAutoMergePairs(mapOf(1L to a, 2L to b), emptySet(), -1L, emptySet(), 0.45f))
        // Named, but the only other cluster is the Unsorted bucket (never a candidate).
        assertEquals(emptyList<AutoMergePair>(), pickAutoMergePairs(mapOf(1L to a, 9L to b), setOf(1L), 9L, emptySet(), 0.45f))
    }
}
