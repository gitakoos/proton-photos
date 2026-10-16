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

package eu.akoos.photos.data.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceBatchRoutingTest {

    @Test
    fun `a library at or under the limit is regrouped whole`() {
        assertFalse(groupsInBatchesOnly(faceCount = 0, maxFaces = 30_000))
        assertFalse(groupsInBatchesOnly(faceCount = 30_000, maxFaces = 30_000))
    }

    @Test
    fun `a big backlog with no scan running is hosted on the foreground service`() {
        assertTrue(shouldHostGroupingInForeground(ungrouped = 5_000, backgroundEnabled = true, walkRunning = false))
        assertTrue(shouldHostGroupingInForeground(ungrouped = 120_000, backgroundEnabled = true, walkRunning = false))
    }

    @Test
    fun `a small backlog, a running scan or a user opt-out is not hosted`() {
        assertFalse(shouldHostGroupingInForeground(ungrouped = 4_999, backgroundEnabled = true, walkRunning = false))
        assertFalse(shouldHostGroupingInForeground(ungrouped = 120_000, backgroundEnabled = true, walkRunning = true))
        assertFalse(shouldHostGroupingInForeground(ungrouped = 120_000, backgroundEnabled = false, walkRunning = false))
    }

    @Test
    fun `a library past the limit is grouped in batches only`() {
        assertTrue(groupsInBatchesOnly(faceCount = 30_001, maxFaces = 30_000))
        assertTrue(groupsInBatchesOnly(faceCount = 122_461, maxFaces = 30_000))
    }

    @Test
    fun `a library grouped in batches never falls from its pass end to a full regroup`() {
        // A batched run that failed is tried again at the next pass instead of rerun at once as a final one.
        assertFalse(passEndRegroups(batchedOnly = true, reattached = false, fullRegroupDue = true))
        assertFalse(passEndRegroups(batchedOnly = true, reattached = true, fullRegroupDue = false))
    }

    @Test
    fun `a smaller library regroups after a reattach or when the rule calls for it`() {
        assertTrue(passEndRegroups(batchedOnly = false, reattached = true, fullRegroupDue = false))
        assertTrue(passEndRegroups(batchedOnly = false, reattached = false, fullRegroupDue = true))
        assertFalse(passEndRegroups(batchedOnly = false, reattached = false, fullRegroupDue = false))
    }

    @Test
    fun `a batched run merges between batches only after many new people and enough batches`() {
        assertTrue(midRunMergeDue(openedSinceMerge = MID_RUN_MERGE_MIN_NEW_PEOPLE, batchesSinceMerge = MID_RUN_MERGE_MIN_BATCHES))
        assertTrue(midRunMergeDue(openedSinceMerge = 9_000, batchesSinceMerge = 40))
        assertFalse(midRunMergeDue(openedSinceMerge = MID_RUN_MERGE_MIN_NEW_PEOPLE - 1, batchesSinceMerge = 40))
        assertFalse(midRunMergeDue(openedSinceMerge = 9_000, batchesSinceMerge = MID_RUN_MERGE_MIN_BATCHES - 1))
    }
}
