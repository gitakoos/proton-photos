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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three decisions the album-membership prefetch makes before it asks anything: which volume an
 * album's children live on, whether its edge rows still owe a walk, and which shared albums a pass
 * may spend requests on.
 *
 * The shared half of it lands on another user's volume and is charged to them, so the bounds are the
 * whole safety story. Plain values → no DI, no DB, no network.
 */
class SharedMembershipPrefetchTest {

    private fun ownedAlbum(linkId: String, photoCount: Int = 4) = Album(
        linkId = linkId,
        name = "Album $linkId",
        photoCount = photoCount,
        coverLinkId = "cover-$linkId",
        lastActivityTimeMs = null,
    )

    private fun sharedAlbum(
        linkId: String,
        sharingShareId: String? = "share-$linkId",
        volumeId: String? = "owner-volume",
    ) = Album(
        linkId = linkId,
        name = "Album $linkId",
        // The shared listing's backup-feed half states no count, so a row it builds reports zero.
        photoCount = 0,
        coverLinkId = "cover-$linkId",
        lastActivityTimeMs = null,
        sharingShareId = sharingShareId,
        sharedByEmail = "ann@example.com",
        volumeId = volumeId,
    )

    // ── Which volume the children listing is asked on ───────────────────────────

    @Test
    fun `an owned album answers on this user's own volume`() {
        // Nothing the user owns records a volume of its own, so the caller's is the only answer.
        assertEquals("my-volume", albumChildrenVolumeId(ownedAlbum("a1"), "my-volume"))
    }

    @Test
    fun `a shared album answers on the owner's volume, not the caller's`() {
        assertEquals("owner-volume", albumChildrenVolumeId(sharedAlbum("a1"), "my-volume"))
    }

    @Test
    fun `a shared album needs no own volume to resolve`() {
        assertEquals("owner-volume", albumChildrenVolumeId(sharedAlbum("a1"), null))
    }

    @Test
    fun `a blank volume on the row falls back rather than naming an empty collection`() {
        assertEquals("my-volume", albumChildrenVolumeId(sharedAlbum("a1", volumeId = "  "), "my-volume"))
    }

    @Test
    fun `an album with no volume anywhere resolves to nothing`() {
        assertNull(albumChildrenVolumeId(sharedAlbum("a1", volumeId = null), null))
    }

    // ── Whether the rows still owe a walk ───────────────────────────────────────

    @Test
    fun `an album with no cached rows always owes a walk`() {
        assertTrue(needsMembershipPrefetch(cachedCount = 0, reportedPhotoCount = 9, countIsKnown = true))
        assertTrue(needsMembershipPrefetch(cachedCount = 0, reportedPhotoCount = 0, countIsKnown = false))
    }

    @Test
    fun `a failed count read owes a walk`() {
        // The DAO read answers -1 when it throws; treating that as "cached" would strand the album.
        assertTrue(needsMembershipPrefetch(cachedCount = -1, reportedPhotoCount = 9, countIsKnown = true))
        assertTrue(needsMembershipPrefetch(cachedCount = -1, reportedPhotoCount = 0, countIsKnown = false))
    }

    @Test
    fun `rows matching Drive's own count are trusted`() {
        assertFalse(needsMembershipPrefetch(cachedCount = 9, reportedPhotoCount = 9, countIsKnown = true))
    }

    @Test
    fun `rows disagreeing with Drive's own count are re-walked`() {
        assertTrue(needsMembershipPrefetch(cachedCount = 7, reportedPhotoCount = 9, countIsKnown = true))
    }

    @Test
    fun `an owned album emptied on the server still re-walks so its stale rows go`() {
        // A count of zero is a real answer on the owned listing, and the rows behind it are wrong.
        assertTrue(needsMembershipPrefetch(cachedCount = 9, reportedPhotoCount = 0, countIsKnown = true))
    }

    @Test
    fun `a shared album with rows is left alone when the count cannot be trusted`() {
        // Part of the shared listing reports zero for every album it builds and nothing tells those
        // rows apart, so measuring against the count would re-walk another user's volume on every
        // reload. Rows on hand are enough for the paint this prefetch serves.
        assertFalse(needsMembershipPrefetch(cachedCount = 9, reportedPhotoCount = 0, countIsKnown = false))
        assertFalse(needsMembershipPrefetch(cachedCount = 1, reportedPhotoCount = 0, countIsKnown = false))
    }

    // ── Which shared albums a pass may spend requests on ────────────────────────

    @Test
    fun `a shared album with its share and its owner's volume is a target`() {
        assertEquals(
            listOf("a1"),
            sharedMembershipPrefetchTargets(listOf(sharedAlbum("a1")), cap = 8).map { it.linkId },
        )
    }

    @Test
    fun `an album with no sharing share is skipped`() {
        // Without the share there is no membership to enumerate on a volume this user is not in.
        val albums = listOf(sharedAlbum("a1", sharingShareId = null), sharedAlbum("a2"))

        assertEquals(listOf("a2"), sharedMembershipPrefetchTargets(albums, cap = 8).map { it.linkId })
    }

    @Test
    fun `an album with no owner volume is skipped`() {
        val albums = listOf(sharedAlbum("a1", volumeId = null), sharedAlbum("a2"))

        assertEquals(listOf("a2"), sharedMembershipPrefetchTargets(albums, cap = 8).map { it.linkId })
    }

    @Test
    fun `a blank owner volume is skipped`() {
        val albums = listOf(sharedAlbum("a1", volumeId = ""), sharedAlbum("a2"))

        assertEquals(listOf("a2"), sharedMembershipPrefetchTargets(albums, cap = 8).map { it.linkId })
    }

    @Test
    fun `an owned album is never a target of the shared pass`() {
        // The owned list has its own prefetch on this user's own volume; picking it up here would
        // walk it twice.
        assertTrue(sharedMembershipPrefetchTargets(listOf(ownedAlbum("a1")), cap = 8).isEmpty())
    }

    @Test
    fun `the pass stops at the cap`() {
        val albums = (1..30).map { sharedAlbum("a$it") }

        assertEquals(8, sharedMembershipPrefetchTargets(albums, cap = 8).size)
    }

    @Test
    fun `the cap keeps the grid's leading rows`() {
        // The tab draws the list in this order, so the head of it is what a tap is likeliest to open.
        val albums = (1..10).map { sharedAlbum("a$it") }

        assertEquals(
            listOf("a1", "a2", "a3"),
            sharedMembershipPrefetchTargets(albums, cap = 3).map { it.linkId },
        )
    }

    @Test
    fun `a cap of zero or less takes nothing`() {
        val albums = listOf(sharedAlbum("a1"))

        assertTrue(sharedMembershipPrefetchTargets(albums, cap = 0).isEmpty())
        assertTrue(sharedMembershipPrefetchTargets(albums, cap = -1).isEmpty())
    }

    @Test
    fun `an album already walked this process is not re-asked`() {
        val albums = listOf(sharedAlbum("a1"), sharedAlbum("a2"))

        assertEquals(
            listOf("a2"),
            sharedMembershipPrefetchTargets(albums, cap = 8, attempted = setOf("a1")).map { it.linkId },
        )
    }

    @Test
    fun `the attempted set walks the budget down the list`() {
        // Successive passes reach the tail rather than re-spending on the head every reload.
        val albums = (1..6).map { sharedAlbum("a$it") }

        val first = sharedMembershipPrefetchTargets(albums, cap = 2).map { it.linkId }
        val second = sharedMembershipPrefetchTargets(albums, cap = 2, attempted = first.toSet()).map { it.linkId }

        assertEquals(listOf("a1", "a2"), first)
        assertEquals(listOf("a3", "a4"), second)
    }

    @Test
    fun `a linkId listed twice is walked once`() {
        // The listing merges a Photos-endpoint walk with a v2 backup feed, so a duplicate row is a
        // shape the list can genuinely take.
        val albums = listOf(sharedAlbum("a1"), sharedAlbum("a1"), sharedAlbum("a2"))

        assertEquals(
            listOf("a1", "a2"),
            sharedMembershipPrefetchTargets(albums, cap = 8).map { it.linkId },
        )
    }

    @Test
    fun `an empty list is a no-op`() {
        assertTrue(sharedMembershipPrefetchTargets(emptyList(), cap = 8).isEmpty())
    }
}
