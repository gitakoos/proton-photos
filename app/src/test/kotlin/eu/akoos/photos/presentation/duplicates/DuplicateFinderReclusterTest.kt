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

package eu.akoos.photos.presentation.duplicates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the gate that keeps the duplicate finder from re-running its O(n²) similar-photo clustering on
 * every re-emit. Deleting photos re-emits the combined flow, but the shown groups are pruned in place,
 * so a pass that only removed candidates must NOT recluster. Only a new or changed fingerprint does.
 * A burst of deletes on a very large library used to re-allocate the cluster buckets each time until
 * the heap was exhausted; this decision is the fix, verified here without the app, a device, or a user.
 */
class DuplicateFinderReclusterTest {

    @Test
    fun first_pass_with_no_prior_cluster_reclusters() {
        assertTrue(shouldRecluster(current = mapOf("c:a" to 1L), last = null))
    }

    @Test
    fun an_unchanged_candidate_set_does_not_recluster() {
        val fp = mapOf("c:a" to 1L, "c:b" to 2L, "d:x" to 9L)
        assertFalse(shouldRecluster(current = fp, last = fp))
    }

    @Test
    fun deleting_a_candidate_does_not_recluster() {
        val before = mapOf("c:a" to 1L, "c:b" to 2L, "c:c" to 3L)
        val afterDelete = mapOf("c:a" to 1L, "c:c" to 3L) // "c:b" removed
        assertFalse(shouldRecluster(current = afterDelete, last = before))
    }

    @Test
    fun deleting_every_candidate_does_not_recluster() {
        val before = mapOf("c:a" to 1L, "c:b" to 2L)
        assertFalse(shouldRecluster(current = emptyMap(), last = before))
    }

    @Test
    fun a_newly_hashed_candidate_reclusters() {
        val before = mapOf("c:a" to 1L)
        val afterHash = mapOf("c:a" to 1L, "c:b" to 2L) // "c:b" just got a fresh hash
        assertTrue(shouldRecluster(current = afterHash, last = before))
    }

    @Test
    fun a_changed_hash_for_the_same_key_reclusters() {
        val before = mapOf("d:x" to 1L)
        val afterRefingerprint = mapOf("d:x" to 7L) // same photo, re-fingerprinted (edited on device)
        assertTrue(shouldRecluster(current = afterRefingerprint, last = before))
    }

    @Test
    fun a_removal_plus_an_addition_reclusters_because_of_the_addition() {
        val before = mapOf("c:a" to 1L, "c:b" to 2L)
        val mixed = mapOf("c:a" to 1L, "c:new" to 5L) // "c:b" gone, "c:new" arrived
        assertTrue(shouldRecluster(current = mixed, last = before))
    }
}
