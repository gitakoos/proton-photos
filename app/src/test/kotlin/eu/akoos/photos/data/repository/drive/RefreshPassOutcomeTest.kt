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
 * The two completeness answers a refresh pass gives, and what each one is allowed to gate.
 *
 * They were once a single flag that also required the pass to have started fresh, which is what
 * stopped a library too large to list in one pass from ever pruning: such a walk alternates fresh
 * and resumed for good, so the flag was never true and a deletion made on another client never
 * reached the device. Pure booleans → no DI / DB / network is exercised.
 */
class RefreshPassOutcomeTest {

    @Test
    fun `a clean pass that walked to the end is complete on both counts`() {
        val outcome = refreshPassOutcome(paginationComplete = true, failedBatches = 0)

        assertTrue(outcome.paginationComplete)
        assertTrue(outcome.detailComplete)
    }

    @Test
    fun `a failed detail batch no longer blocks the sweep`() {
        val outcome = refreshPassOutcome(paginationComplete = true, failedBatches = 2)

        // The listing still named every link the server holds, and that is the whole input the
        // sweep consumes, so a batch that failed to fetch detail for some of them changes nothing
        // about which photos are absent.
        assertTrue("pagination reaching the end is what the sweep waits for", outcome.paginationComplete)
        // The DB does NOT hold the whole library though — the failed batch left a region as bare
        // stubs — so the stored complete flag and the event anchor both stay shut.
        assertFalse("a region of rows is missing its detail", outcome.detailComplete)
    }

    @Test
    fun `a pass cut short mid-listing is complete on neither count`() {
        val outcome = refreshPassOutcome(paginationComplete = false, failedBatches = 0)

        assertFalse("pages are still unwalked, so absence proves nothing yet", outcome.paginationComplete)
        assertFalse(outcome.detailComplete)
    }

    @Test
    fun `the event anchor arms on a library whose detail batches did not all land`() {
        // The case the anchor could never reach before. A library large enough to need hundreds of
        // consecutive detail batches will have one fail sooner or later, and the old gate wanted all
        // of them clean, so the anchor stayed unarmed for good on exactly the accounts the
        // incremental path exists to spare. What the anchor asserts is that the local SET matches
        // the server's, and membership was settled by the listing: a failed batch leaves its rows
        // present under the right linkIds with only their detail columns unfilled, which the events
        // feed handles exactly as it handles a photo with no row at all.
        val outcome = refreshPassOutcome(paginationComplete = true, failedBatches = 1)

        // lastFullRefreshComplete, the field gating the anchor, is now this one.
        assertTrue("the listing named every link the server holds", outcome.paginationComplete)
        assertFalse("while the stored complete flag still waits on the detail", outcome.detailComplete)
    }

    @Test
    fun `a truncated listing still cannot arm the anchor`() {
        // The other half of the same rule: loosening the anchor to pagination must not loosen it to
        // nothing. A walk that stopped mid-listing never established membership, so the events feed
        // would be tracking deltas against a library it only partly knows.
        assertFalse(refreshPassOutcome(paginationComplete = false, failedBatches = 0).paginationComplete)
    }

    @Test
    fun `detail completeness never outruns pagination`() {
        // A pass can process every batch it was handed and still have listed only part of the
        // library, so a clean batch record on its own says nothing about the whole.
        assertFalse(refreshPassOutcome(paginationComplete = false, failedBatches = 0).detailComplete)
    }
}
