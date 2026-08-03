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

import eu.akoos.photos.domain.entity.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shared-with-me albums a cover prefetch pass may spend a request on.
 *
 * Every one it picks costs the album's OWNER a round trip on their volume, so the selection is the
 * whole safety story of the feature: only tiles that are actually blank, only albums the share-scoped
 * fetch can complete, never the same album twice in a process, and never more than the cap. Plain
 * lists → no DI, no DB, no network.
 */
class SharedCoverPrefetchTest {

    private fun sharedAlbum(
        linkId: String,
        coverLinkId: String? = "cover-$linkId",
        coverThumbnailUrl: String? = null,
        sharingShareId: String? = "share-$linkId",
        volumeId: String? = "owner-volume",
    ) = Album(
        linkId = linkId,
        name = "Album $linkId",
        photoCount = 4,
        coverLinkId = coverLinkId,
        lastActivityTimeMs = null,
        coverThumbnailUrl = coverThumbnailUrl,
        sharingShareId = sharingShareId,
        sharedByEmail = "ann@example.com",
        volumeId = volumeId,
    )

    // ── Missing covers only ─────────────────────────────────────────────────────

    @Test
    fun `an album whose cover is already resolved costs nothing`() {
        // The list build resolves a cover from disk or the listing DB before this runs, so a set URL
        // is the proof no fetch is owed.
        val albums = listOf(
            sharedAlbum("a1", coverThumbnailUrl = "file:///cache/thumbnails/thumb_cover-a1.jpg"),
            sharedAlbum("a2"),
        )

        assertEquals(listOf("a2"), sharedCoverPrefetchTargets(albums, cap = 12).map { it.linkId })
    }

    @Test
    fun `a blank cover URL counts as missing`() {
        val albums = listOf(sharedAlbum("a1", coverThumbnailUrl = "   "))

        assertEquals(listOf("a1"), sharedCoverPrefetchTargets(albums, cap = 12).map { it.linkId })
    }

    @Test
    fun `every cover already cached means no pass at all`() {
        val albums = listOf(
            sharedAlbum("a1", coverThumbnailUrl = "file:///a1.jpg"),
            sharedAlbum("a2", coverThumbnailUrl = "https://cdn.example/a2"),
        )

        assertTrue(sharedCoverPrefetchTargets(albums, cap = 12).isEmpty())
    }

    // ── What the share-scoped fetch needs to run ────────────────────────────────

    @Test
    fun `an album with no cover link has nothing to fetch`() {
        val albums = listOf(sharedAlbum("a1", coverLinkId = null), sharedAlbum("a2"))

        assertEquals(listOf("a2"), sharedCoverPrefetchTargets(albums, cap = 12).map { it.linkId })
    }

    @Test
    fun `an album with no sharing share is skipped`() {
        // The share is what unlocks the album key and answers for links on a volume this user is not
        // a member of; without it the fetch has no path in.
        val albums = listOf(sharedAlbum("a1", sharingShareId = null), sharedAlbum("a2"))

        assertEquals(listOf("a2"), sharedCoverPrefetchTargets(albums, cap = 12).map { it.linkId })
    }

    @Test
    fun `an album with no volume id is skipped`() {
        // A thumbnail URL is minted on the volume the photo lives in, which here is the owner's.
        val albums = listOf(sharedAlbum("a1", volumeId = null), sharedAlbum("a2"))

        assertEquals(listOf("a2"), sharedCoverPrefetchTargets(albums, cap = 12).map { it.linkId })
    }

    // ── The cap and the attempted set ───────────────────────────────────────────

    @Test
    fun `the pass stops at the cap`() {
        val albums = (1..30).map { sharedAlbum("a$it") }

        val targets = sharedCoverPrefetchTargets(albums, cap = 12)

        assertEquals(12, targets.size)
    }

    @Test
    fun `the cap keeps the grid's leading rows`() {
        // The tab draws the list in this order, so the first N are the tiles someone opening it looks
        // at while the pass runs.
        val albums = (1..10).map { sharedAlbum("a$it") }

        val targets = sharedCoverPrefetchTargets(albums, cap = 3)

        assertEquals(listOf("a1", "a2", "a3"), targets.map { it.linkId })
    }

    @Test
    fun `a cap of zero or less takes nothing`() {
        val albums = listOf(sharedAlbum("a1"))

        assertTrue(sharedCoverPrefetchTargets(albums, cap = 0).isEmpty())
        assertTrue(sharedCoverPrefetchTargets(albums, cap = -1).isEmpty())
    }

    @Test
    fun `an album already attempted this process is not re-fetched`() {
        // A cover that never resolves would otherwise cost the owner a round trip on every open of
        // the tab, forever, for the same nothing.
        val albums = listOf(sharedAlbum("a1"), sharedAlbum("a2"))

        val targets = sharedCoverPrefetchTargets(albums, cap = 12, attempted = setOf("a1"))

        assertEquals(listOf("a2"), targets.map { it.linkId })
    }

    @Test
    fun `the cap counts only albums that are still candidates`() {
        // Cached and already-attempted albums must not eat the budget, or one full grid would starve
        // the covers that are genuinely missing.
        val albums = listOf(
            sharedAlbum("done1", coverThumbnailUrl = "file:///done1.jpg"),
            sharedAlbum("tried1"),
            sharedAlbum("a1"),
            sharedAlbum("a2"),
        )

        val targets = sharedCoverPrefetchTargets(albums, cap = 2, attempted = setOf("tried1"))

        assertEquals(listOf("a1", "a2"), targets.map { it.linkId })
    }

    @Test
    fun `a linkId listed twice is fetched once`() {
        // The listing merges a Photos-endpoint walk with a v2 backup feed, so a duplicate row is a
        // shape the list can genuinely take.
        val albums = listOf(sharedAlbum("a1"), sharedAlbum("a1"), sharedAlbum("a2"))

        assertEquals(listOf("a1", "a2"), sharedCoverPrefetchTargets(albums, cap = 12).map { it.linkId })
    }

    @Test
    fun `an empty list is a no-op`() {
        assertTrue(sharedCoverPrefetchTargets(emptyList(), cap = 12).isEmpty())
    }
}
