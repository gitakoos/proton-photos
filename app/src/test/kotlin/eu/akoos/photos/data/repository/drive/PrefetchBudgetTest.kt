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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which outcomes of a shared-album prefetch spend that album's once-per-process budget.
 *
 * Both shared prefetches run behind a delay in a job the Shared tab cancels when the user leaves it,
 * so "the pass ended" and "the album was answered for" are genuinely different things. Counting the
 * first as the second leaves an album that was merely walked past with a blank cover and a grid that
 * cannot enumerate offline for the rest of the process. Pure values → no DI, no DB, no network.
 */
class PrefetchBudgetTest {

    @Test
    fun `an album walked to the end is not walked again this process`() {
        // A finished walk has no second thing to learn, and the requests land on the album OWNER.
        assertTrue(burnsPrefetchBudget(PrefetchOutcome.COMPLETED))
    }

    @Test
    fun `an album that failed definitively is not retried on every tab open`() {
        // The whole point of the set: a share that hands nothing over, or a cover with no thumbnail
        // behind it, costs its owner one round trip per process rather than one per open of the tab.
        assertTrue(burnsPrefetchBudget(PrefetchOutcome.FAILED))
    }

    @Test
    fun `an album whose pass was cancelled keeps its budget`() {
        // Leaving the Shared tab cancels the job both prefetches run in. That says nothing about the
        // album, so the next pass must be free to pick it up.
        assertFalse(burnsPrefetchBudget(PrefetchOutcome.CANCELLED))
    }

    @Test
    fun `cancellation is the only outcome that spends nothing`() {
        // Guards the rule against inverting into "retry forever", which is what the set exists to
        // stop: every answer an album can give still burns its budget.
        val spent = PrefetchOutcome.entries.filter { burnsPrefetchBudget(it) }

        assertTrue(spent.containsAll(listOf(PrefetchOutcome.COMPLETED, PrefetchOutcome.FAILED)))
        assertFalse(spent.contains(PrefetchOutcome.CANCELLED))
    }
}
