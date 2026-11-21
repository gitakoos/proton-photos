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

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [deleteDraftTracked], the draft delete of an upload cancelled on the app's own path: the retry
 * entry is written before the delete is tried, so a process death on the way leaves the draft tracked,
 * and it goes only once the delete landed.
 */
class CancelledDraftDeleteTest {

    private val queue = mutableSetOf<String>()

    @Test
    fun `the retry entry is written before the delete and dropped once it landed`() = runTest {
        var queuedDuringDelete = false
        val dropped = deleteDraftTracked(
            record = { queue += "share|draft" },
            forget = { queue -= "share|draft" },
            delete = { queuedDuringDelete = "share|draft" in queue },
            timeoutMs = 10_000,
        )
        assertTrue(dropped)
        assertTrue("a death during the delete leaves the draft tracked", queuedDuringDelete)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a failed delete leaves the draft queued for the next pass`() = runTest {
        val dropped = deleteDraftTracked(
            record = { queue += "share|draft" },
            forget = { queue -= "share|draft" },
            delete = { error("Drive unreachable") },
            timeoutMs = 10_000,
        )
        assertFalse(dropped)
        assertEquals(setOf("share|draft"), queue)
    }

    @Test
    fun `a delete past the timeout leaves the draft queued for the next pass`() = runTest {
        val dropped = deleteDraftTracked(
            record = { queue += "share|draft" },
            forget = { queue -= "share|draft" },
            delete = { delay(60_000) },
            timeoutMs = 10_000,
        )
        assertFalse(dropped)
        assertEquals(setOf("share|draft"), queue)
    }
}
