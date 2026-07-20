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

package eu.akoos.photos.data.repository.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh sweep's removal decision. [removableListingIds] answers "which stored rows did this
 * pass fail to account for", and both sweep sites feed it the same shape: an own-volume candidate
 * set plus one protection set per reason a row may be missing yet still valid (the server listing,
 * an upload the index has not caught up with, a stub whose detail batch failed).
 *
 * The volume half of the rule is enforced by the query that builds the candidate set and is pinned
 * in PhotoListingDaoTest; what is pinned here is that a protected row is never offered up and an
 * unaccounted-for one always is. Pure sets → no DI / DB / network is exercised.
 */
class RemovableListingIdsTest {

    @Test
    fun `a stored row the listing did not return is removable`() {
        val stored = listOf("own1", "own2", "gone")
        val listed = setOf("own1", "own2")

        assertEquals(listOf("gone"), removableListingIds(stored, listed, emptySet()))
    }

    @Test
    fun `a stored row the listing returned is kept`() {
        val stored = listOf("own1", "own2")
        val listed = setOf("own1", "own2")

        assertTrue(removableListingIds(stored, listed, emptySet()).isEmpty())
    }

    @Test
    fun `a just-uploaded row missing from the listing is kept`() {
        // The photo-stream index is eventually consistent, so a fresh upload can be absent from a
        // successful listing. Pruning it would flicker its synced badge off until the next refresh.
        val stored = listOf("own1", "fresh")
        val listed = setOf("own1")
        val recentUploads = setOf("fresh")

        assertTrue(removableListingIds(stored, listed, recentUploads).isEmpty())
    }

    @Test
    fun `a stub row missing from the found set is kept`() {
        // A stub is a row whose detail batch failed transiently. It still carries the contentHash
        // the dedup index needs, so pruning it would invite a re-upload of a photo already on Drive.
        val stored = listOf("own1", "stub")
        val found = setOf("own1")
        val stubs = setOf("stub")

        assertTrue(removableListingIds(stored, found, emptySet(), stubs).isEmpty())
    }

    @Test
    fun `one protection set is enough to keep a row`() {
        // The sweep sites name different reasons, so the sets are OR'd: a row in any of them stays.
        val stored = listOf("a", "b", "c", "d")

        val removable = removableListingIds(stored, setOf("a"), setOf("b"), setOf("c"))

        assertEquals(listOf("d"), removable)
    }

    @Test
    fun `nothing stored means nothing to remove`() {
        assertTrue(removableListingIds(emptyList(), setOf("own1")).isEmpty())
    }

    @Test
    fun `with no protection sets every stored row is removable`() {
        val stored = listOf("a", "b")

        assertEquals(stored, removableListingIds(stored))
    }
}
