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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies which local-tag rows a media scan is allowed to delete, WITHOUT a device, MediaStore or
 * the database in the loop.
 *
 * Every test here is really the same question asked from a different angle: is absence from the
 * scan's result actually proof that the file is gone? It is proof only inside the part of the device
 * the scan covered, and a wrong answer deletes a row for a file that still exists, taking the
 * categories a person picked for it with no way back.
 */
class LocalTagPruneTest {

    private val images = "content://media/external/images/media"
    private val video = "content://media/external/video/media"
    private val bothRoots = listOf(images, video)

    /** No row carries a user choice, which is the ordinary case for a scanner-written cache. */
    private val noUserTags: (String) -> String = { "" }

    private fun stale(
        cached: Collection<String>,
        live: Set<String>,
        roots: Collection<String> = bothRoots,
        userTags: (String) -> String = noUserTags,
    ): List<String> = LocalTagPrune.staleUris(cached, live, roots, userTags)

    // ── the plain decision ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a row whose file the scan no longer sees is dropped`() {
        val gone = "$images/2"
        val result = stale(
            cached = listOf("$images/1", gone),
            live = setOf("$images/1"),
        )
        assertEquals(listOf(gone), result)
    }

    @Test
    fun `a row whose file the scan still sees is kept`() {
        assertTrue(stale(cached = listOf("$images/1"), live = setOf("$images/1")).isEmpty())
    }

    @Test
    fun `nothing to drop returns nothing`() {
        // Every cached file is live, so the caller must be able to skip the delete entirely rather
        // than issue a statement that deletes no rows on every single media scan.
        val live = setOf("$images/1", "$images/2", "$video/7")
        assertTrue(stale(cached = live.toList(), live = live).isEmpty())
    }

    @Test
    fun `an empty cache returns nothing`() {
        assertTrue(stale(cached = emptyList(), live = setOf("$images/1")).isEmpty())
    }

    @Test
    fun `both collections are pruned in the same pass`() {
        val result = stale(
            cached = listOf("$images/1", "$images/2", "$video/7", "$video/8"),
            live = setOf("$images/1", "$video/8"),
        )
        assertEquals(listOf("$images/2", "$video/7"), result)
    }

    // ── the empty live set: a failed or unfinished scan ──────────────────────────────────────────

    @Test
    fun `an empty live set drops nothing`() {
        // Safe here means "change nothing at all". A scan that failed, that was cut short, or that
        // ran before its data arrived produces exactly this shape: no live uris and a full cache.
        // Read literally it says every file on the device is gone, so acting on it would empty the
        // table in one pass and destroy every category a user has picked. A device with genuinely no
        // photos left costs only the rows the next successful scan would have cleared anyway.
        val cached = listOf("$images/1", "$images/2", "$video/7")
        assertTrue(stale(cached = cached, live = emptySet()).isEmpty())
    }

    @Test
    fun `no scanned root drops nothing`() {
        // Same reasoning one step earlier: if not one collection query got through, the live set
        // describes nothing and cannot contradict any row.
        assertTrue(
            stale(cached = listOf("$images/1"), live = setOf("$video/7"), roots = emptyList()).isEmpty()
        )
    }

    // ── only what the scan actually covered ──────────────────────────────────────────────────────

    @Test
    fun `a collection whose query failed keeps all of its rows`() {
        // Images and videos are separate permissions from Android 13, so a user who grants photos
        // but not videos fails the video query on every scan while the images query succeeds. The
        // videos are all still on the device; only the scan cannot see them.
        val result = stale(
            cached = listOf("$images/1", "$images/2", "$video/7", "$video/8"),
            live = setOf("$images/1"),
            roots = listOf(images),
        )
        assertEquals(listOf("$images/2"), result)
    }

    @Test
    fun `a vaulted file uri is never dropped`() {
        // A hidden device-only photo lives in app-private storage and no MediaStore query returns
        // it, so it is absent from every live set for as long as it exists. It can still carry
        // categories, picked in the viewer exactly like any other local photo.
        val vaulted = "file:///data/user/0/eu.akoos.photos/files/vault/IMG_0001.jpg"
        assertTrue(stale(cached = listOf(vaulted), live = setOf("$images/1")).isEmpty())
    }

    @Test
    fun `a uri that merely starts like a scanned root is not treated as part of it`() {
        // The root is matched with its trailing separator, so a sibling path sharing the prefix
        // stays outside the scan's proven area instead of being deleted by a string coincidence.
        val neighbour = "${images}_backup/1"
        assertTrue(stale(cached = listOf(neighbour), live = setOf("$images/1")).isEmpty())
    }

    // ── a chosen category outranks the scan ──────────────────────────────────────────────────────

    @Test
    fun `a row carrying a user choice survives even when the scan cannot see the file`() {
        // A trashed file is the case that makes this necessary: it still exists, it is restorable
        // for weeks, and a default MediaStore query omits it. The detection beside the choice is
        // rebuildable, the choice is not, so the row stays and a restore finds it intact.
        val chosen = "$images/2"
        assertTrue(
            stale(
                cached = listOf(chosen),
                live = setOf("$images/1"),
                userTags = { if (it == chosen) "2,8" else "" },
            ).isEmpty()
        )
    }

    @Test
    fun `a detection-only row beside a chosen one is still dropped`() {
        // The protection is per row, not a switch that spares the whole pass: the rows that grow
        // with the library are the scanner's, and those must still go.
        val chosen = "$images/2"
        val plain = "$images/3"
        val result = stale(
            cached = listOf("$images/1", chosen, plain),
            live = setOf("$images/1"),
            userTags = { if (it == chosen) "5" else "" },
        )
        assertEquals(listOf(plain), result)
    }

    @Test
    fun `a row whose choice was cleared back to empty is prunable again`() {
        // Clearing the last category stores the empty string, the same value a row that was never
        // asked holds, so the row goes back to being nothing but a rebuildable detection.
        val cleared = "$images/2"
        val result = stale(
            cached = listOf(cleared),
            live = setOf("$images/1"),
            userTags = { "" },
        )
        assertEquals(listOf(cleared), result)
    }
}
