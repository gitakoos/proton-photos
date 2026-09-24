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

package eu.akoos.photos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two answers every destructive reader of a device scan leans on, without a device in the loop:
 * whether a scan saw the whole device, and which of a prune's candidates a lookup by id confirms are
 * gone. A wrong "complete" lets reconcile demote and drop rows, and the face reaper delete named
 * faces, for files that are still there; a wrong "gone" drops the only capture date a downloaded PNG
 * keeps.
 */
class DeviceScanCompletenessTest {

    private val images = "content://media/external/images/media"
    private val video = "content://media/external/video/media"
    private val roots = listOf(images, video)

    private fun complete(
        fullAccess: Boolean = true,
        rootsRead: Int = 2,
        repeatsDropped: Boolean = false,
        rows: Int = 10,
    ) = isCompleteScan(
        fullAccess = fullAccess,
        rootsQueried = 2,
        rootsRead = rootsRead,
        repeatsDropped = repeatsDropped,
        rows = rows,
    )

    // ── the completeness decision ────────────────────────────────────────────────────────────────

    @Test
    fun `a scan with both grants, every collection read and no repeats is complete`() {
        assertTrue(complete())
    }

    @Test
    fun `a scan without both media grants is incomplete`() {
        // A collection queried without its own grant answers with the app's own files or nothing.
        assertFalse(complete(fullAccess = false))
    }

    @Test
    fun `a scan whose collection failed mid-walk is incomplete though it returned rows`() {
        assertFalse(complete(rootsRead = 1))
        assertFalse(complete(rootsRead = 0))
    }

    @Test
    fun `a scan that dropped a repeated row is incomplete`() {
        // The shift that repeats a row also pushes one past the cursor's end, unread.
        assertFalse(complete(repeatsDropped = true))
    }

    @Test
    fun `a scan that found nothing is incomplete`() {
        // Also what a storage volume not yet mounted answers.
        assertFalse(complete(rows = 0))
    }

    // ── the trashed-aware candidate filter ───────────────────────────────────────────────────────

    /** A provider that holds [present] (trash included) and records every lookup it answers. */
    private class FakeProvider(private val present: Set<String>, private val failing: Set<String> = emptySet()) {
        val lookups = mutableListOf<Pair<String, List<Long>>>()

        fun presentIds(root: String, ids: List<Long>): Set<Long>? {
            lookups += root to ids
            if (root in failing) return null
            return ids.filterTo(HashSet()) { "$root/$it" in present }
        }
    }

    @Test
    fun `a candidate the provider no longer holds is confirmed gone`() {
        val provider = FakeProvider(present = emptySet())
        assertEquals(listOf("$images/5"), confirmedGone(listOf("$images/5"), roots, presentIds = provider::presentIds))
    }

    @Test
    fun `a trashed file is still present, so its candidate is kept`() {
        // The default scan leaves a trashed file out; the lookup counts the trash, so the saved date
        // of a trashed PNG survives until the file is really deleted.
        val trashed = "$images/7"
        val provider = FakeProvider(present = setOf(trashed))
        assertTrue(confirmedGone(listOf(trashed), roots, presentIds = provider::presentIds).isEmpty())
    }

    @Test
    fun `a lookup that cannot answer keeps every candidate it covered`() {
        val provider = FakeProvider(present = emptySet(), failing = setOf(video))
        val gone = confirmedGone(listOf("$images/1", "$video/2", "$video/3"), roots, presentIds = provider::presentIds)
        assertEquals(listOf("$images/1"), gone)
    }

    @Test
    fun `a candidate that is no id under a known root is kept and never looked up`() {
        val provider = FakeProvider(present = emptySet())
        val odd = listOf(
            "file:///data/user/0/eu.akoos.photos/files/hidden/a.jpg",
            "content://media/external/images/mediaX/5",
            "$images/not-a-number",
        )
        assertTrue(confirmedGone(odd, roots, presentIds = provider::presentIds).isEmpty())
        assertTrue(provider.lookups.isEmpty())
    }

    @Test
    fun `candidates are looked up per collection in bounded chunks`() {
        val candidates = (1L..7L).map { "$images/$it" } + (1L..2L).map { "$video/$it" }
        val provider = FakeProvider(present = setOf("$images/3", "$video/2"))

        val gone = confirmedGone(candidates, roots, chunkSize = 3, presentIds = provider::presentIds)

        assertEquals(
            listOf(images to listOf(1L, 2L, 3L), images to listOf(4L, 5L, 6L), images to listOf(7L), video to listOf(1L, 2L)),
            provider.lookups,
        )
        assertEquals(
            listOf("$images/1", "$images/2", "$images/4", "$images/5", "$images/6", "$images/7", "$video/1"),
            gone,
        )
    }

    @Test
    fun `nothing to confirm costs no lookup`() {
        val provider = FakeProvider(present = emptySet())
        assertTrue(confirmedGone(emptyList(), roots, presentIds = provider::presentIds).isEmpty())
        assertTrue(provider.lookups.isEmpty())
    }
}
