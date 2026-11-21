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

import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldRemovalsTest {

    private fun item(uri: String) = LocalMediaItem(uri, 1_000L, "a.jpg", "image/jpeg", 10L, "Camera")

    @Test
    fun `a removed file the scan still lists is held until the hold runs out`() {
        val items = listOf(item("u1"), item("u2"), item("u3"))
        val removed = mapOf("u2" to 1_000L, "u3" to 500L)

        assertEquals(removed, heldRemovals(items, removed, now = 1_400L, holdMs = 1_000L))
        assertEquals(mapOf("u2" to 1_000L), heldRemovals(items, removed, now = 1_600L, holdMs = 1_000L))
    }

    @Test
    fun `a removed file the scan no longer lists is let go`() {
        val items = listOf(item("u1"))
        val removed = mapOf("u1" to 1_000L, "gone" to 1_000L)

        assertEquals(mapOf("u1" to 1_000L), heldRemovals(items, removed, now = 1_001L, holdMs = 60_000L))
        assertTrue(heldRemovals(emptyList(), removed, now = 1_001L, holdMs = 60_000L).isEmpty())
    }

    @Test
    fun `a large delete over a large scan holds exactly the listed files`() {
        val items = (0 until 50_000).map { item("content://media/$it") }
        val removed = (0 until 5_000).associate { "content://media/${it * 10}" to 0L } +
            (0 until 1_000).associate { "content://media/missing-$it" to 0L }

        val held = heldRemovals(items, removed, now = 10L, holdMs = 60_000L)

        assertEquals(5_000, held.size)
        assertTrue(held.keys.all { !it.contains("missing") })
    }
}
