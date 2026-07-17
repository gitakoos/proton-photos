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

import eu.akoos.photos.domain.usecase.PendingDeleteNotificationUseCase.Companion.pendingDeleteEntryIsStale
import eu.akoos.photos.domain.usecase.PendingDeleteNotificationUseCase.Companion.trimPendingDeleteQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-value coverage for the two decisions that keep the delete-after-backup consent queue
 * bounded, extracted from [PendingDeleteNotificationUseCase]: when a queued URI is done with, and
 * what survives the cap. The consent notification hands the user to the OS trash dialog and gets no
 * callback, so the staleness probe is the only thing that ever empties the queue - an entry it
 * keeps calling alive is re-posted forever. The invariant pinned here is that a trashed entry reads
 * stale whether or not MediaStore still hands its row back, since that turns on file ownership the
 * app does not have. No Android, no Context, no ContentResolver, no MediaStore: plain JVM
 * assertions on the inputs.
 */
class PendingDeleteQueueTest {

    private fun uri(id: Long) = "content://media/external/images/media/$id"

    // ─── staleness probe ──────────────────────────────────────────────────────

    @Test
    fun `a row that is present and untrashed is still alive`() {
        // The only combination that keeps an entry queued: the device copy is really still there.
        assertFalse(pendingDeleteEntryIsStale(rowExists = true, isTrashed = false))
    }

    @Test
    fun `a row that is present but trashed is stale`() {
        // MediaStore handed the row back (the owner exception) and flagged it trashed: the user
        // already accepted the dialog, so the entry must leave the queue.
        assertTrue(pendingDeleteEntryIsStale(rowExists = true, isTrashed = true))
    }

    @Test
    fun `a row that is gone is stale`() {
        // Deleted outright, or withheld by MediaStore precisely because it is trashed.
        assertTrue(pendingDeleteEntryIsStale(rowExists = false, isTrashed = false))
    }

    @Test
    fun `a row that is gone is stale even when reported trashed`() {
        // No row means nothing to ask consent for, whatever the flag reads.
        assertTrue(pendingDeleteEntryIsStale(rowExists = false, isTrashed = true))
    }

    @Test
    fun `a trashed entry is stale under either MediaStore visibility`() {
        // The whole point of the two-armed probe: accepting the OS dialog retires the entry whether
        // the provider hides the trashed row from the probe or hands it back with the flag set.
        for (rowExists in listOf(true, false)) {
            assertTrue(
                "a trashed entry must be stale with rowExists=$rowExists",
                pendingDeleteEntryIsStale(rowExists = rowExists, isTrashed = true),
            )
        }
    }

    // ─── cap ──────────────────────────────────────────────────────────────────

    @Test
    fun `a queue under the cap is handed back untouched`() {
        val queue = listOf(uri(3), uri(1), uri(2))
        assertEquals(queue, trimPendingDeleteQueue(queue, max = 200))
    }

    @Test
    fun `a queue exactly at the cap is handed back untouched`() {
        val queue = (1L..200L).map { uri(it) }
        assertEquals(queue, trimPendingDeleteQueue(queue, max = 200))
    }

    @Test
    fun `a queue over the cap is trimmed to the cap`() {
        val queue = (1L..250L).map { uri(it) }
        assertEquals(200, trimPendingDeleteQueue(queue, max = 200).size)
    }

    @Test
    fun `the cap drops the longest-waiting entries and keeps the newest`() {
        // Row ids ascend as the provider records files, so the lowest ids are the entries that
        // have waited longest through un-granted consent prompts.
        val trimmed = trimPendingDeleteQueue((1L..10L).map { uri(it) }, max = 3)
        assertEquals(listOf(uri(10), uri(9), uri(8)), trimmed)
    }

    @Test
    fun `the cap survives an unordered queue`() {
        // A DataStore string set has no insertion order, so the trim must not lean on one.
        val trimmed = trimPendingDeleteQueue(listOf(uri(7), uri(2), uri(9), uri(4)), max = 2)
        assertEquals(listOf(uri(9), uri(7)), trimmed)
    }

    @Test
    fun `the cap keeps the same survivors on every pass`() {
        // A stable total order matters: an arbitrary trim would drop a different slice each pass
        // and churn the notification's contents.
        val queue = listOf(uri(5), uri(1), uri(9), uri(3))
        val first = trimPendingDeleteQueue(queue, max = 2)
        assertEquals(first, trimPendingDeleteQueue(queue.shuffled(), max = 2))
        assertEquals(first, trimPendingDeleteQueue(queue.reversed(), max = 2))
    }

    @Test
    fun `an entry with no numeric row id is dropped first`() {
        // Not an item URI createTrashRequest could act on, so it is the first to go.
        val trimmed = trimPendingDeleteQueue(listOf(uri(2), "content://media/external/images/media", uri(1)), max = 2)
        assertEquals(listOf(uri(2), uri(1)), trimmed)
    }

    @Test
    fun `a cap of zero empties the queue`() {
        assertEquals(emptyList<String>(), trimPendingDeleteQueue(listOf(uri(1), uri(2)), max = 0))
    }
}
