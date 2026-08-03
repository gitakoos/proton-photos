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

package eu.akoos.photos.presentation.shared

import eu.akoos.photos.domain.entity.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure decisions behind the Shared tab's cache-first paint: whether the skeleton is still
 * owed once the cache read has landed ([sharedTabHasCachedContent]), and what a failed refresh may
 * say afterwards ([sharedRefreshError]).
 *
 * Together they are the whole difference between a tab that shimmers on every open and blanks
 * itself offline, and one that shows what it already knows. Plain values → no DI, no DB, no
 * network, no Android.
 */
class SharedCachePaintTest {

    private fun album(linkId: String, sharingShareId: String? = null, sharedBy: String? = null) = Album(
        linkId = linkId,
        name = "Album $linkId",
        photoCount = 2,
        coverLinkId = null,
        lastActivityTimeMs = null,
        sharingShareId = sharingShareId,
        sharedByEmail = sharedBy,
    )

    // ── When the skeleton is still owed ─────────────────────────────────────────

    @Test
    fun `a device that has never listed a share still owes the skeleton`() {
        assertFalse(sharedTabHasCachedContent(emptyList(), emptyList()))
    }

    @Test
    fun `a cached shared-with-me album is content, so no skeleton`() {
        val sharedWithMe = listOf(album("s1", sharingShareId = "share1", sharedBy = "ann@example.com"))

        assertTrue(sharedTabHasCachedContent(cachedOwnAlbums = emptyList(), cachedSharedWithMeAlbums = sharedWithMe))
    }

    @Test
    fun `a cached album this user shared out is content too`() {
        // The other filter's section. One refresh feeds both, so either one having rows means the
        // tab has something to draw and the skeleton would be covering a populated grid.
        val own = listOf(album("o1", sharingShareId = "share1"), album("o2"))

        assertTrue(sharedTabHasCachedContent(cachedOwnAlbums = own, cachedSharedWithMeAlbums = emptyList()))
    }

    @Test
    fun `owned albums that were never shared are not content for this tab`() {
        // The main albums grid is full and the Shared tab is genuinely empty. Counting these would
        // suppress the skeleton on exactly the device that has nothing shared to show.
        val own = listOf(album("o1"), album("o2"), album("o3"))

        assertFalse(sharedTabHasCachedContent(cachedOwnAlbums = own, cachedSharedWithMeAlbums = emptyList()))
    }

    @Test
    fun `one shared album among many owned ones is enough`() {
        val own = listOf(album("o1"), album("o2"), album("o3", sharingShareId = "share1"))

        assertTrue(sharedTabHasCachedContent(cachedOwnAlbums = own, cachedSharedWithMeAlbums = emptyList()))
    }

    // ── What a failed refresh may say ───────────────────────────────────────────

    @Test
    fun `a failed refresh over a painted list says nothing`() {
        // The list the cache painted is still on screen and still readable, so an error sheet over
        // it would report a loss the user cannot see and did not suffer.
        assertNull(
            sharedRefreshError(hasPaintedContent = true, isNetworkFailure = false, message = "auth failed"),
        )
        assertNull(
            sharedRefreshError(hasPaintedContent = true, isNetworkFailure = true, message = "auth failed"),
        )
    }

    @Test
    fun `a network drop on a blank tab stays quiet for the offline banner`() {
        assertNull(
            sharedRefreshError(hasPaintedContent = false, isNetworkFailure = true, message = "no route to host"),
        )
    }

    @Test
    fun `an auth or crypto fault on a blank tab reaches the error sheet`() {
        // Nothing on screen and nothing else reporting it: the one case with a message to give.
        assertEquals(
            "session expired",
            sharedRefreshError(hasPaintedContent = false, isNetworkFailure = false, message = "session expired"),
        )
    }

    @Test
    fun `a painted list outranks the fault that would otherwise be surfaced`() {
        val blank = sharedRefreshError(hasPaintedContent = false, isNetworkFailure = false, message = "boom")
        val painted = sharedRefreshError(hasPaintedContent = true, isNetworkFailure = false, message = "boom")

        assertEquals("boom", blank)
        assertNull("the same failure is silent once a cached list is up", painted)
    }

    // ── Folding a cover prefetch back into the painted list ─────────────────────

    @Test
    fun `a freshly fetched cover fills the blank tile it was fetched for`() {
        val current = listOf(album("s1"), album("s2"))

        val merged = mergeResolvedCovers(current, mapOf("s1" to "file:///thumb_s1.jpg"))

        assertEquals("file:///thumb_s1.jpg", merged.first { it.linkId == "s1" }.coverThumbnailUrl)
        assertNull(merged.first { it.linkId == "s2" }.coverThumbnailUrl)
    }

    @Test
    fun `a cover already on screen is left alone`() {
        // The cache read behind the merge sees every shared album, not only the ones the pass
        // touched, so without this a working tile repaints for no gain.
        val current = listOf(album("s1").copy(coverThumbnailUrl = "https://cdn.example/s1"))

        val merged = mergeResolvedCovers(current, mapOf("s1" to "file:///thumb_s1.jpg"))

        assertEquals("https://cdn.example/s1", merged.single().coverThumbnailUrl)
    }

    @Test
    fun `the merge adds and drops no rows`() {
        // A background pass must not shift a grid the user is reading; membership is the refresh's
        // answer to give, not this one's.
        val current = listOf(album("s1"), album("s2"))

        val merged = mergeResolvedCovers(current, mapOf("s2" to "file:///thumb_s2.jpg", "gone" to "file:///x.jpg"))

        assertEquals(listOf("s1", "s2"), merged.map { it.linkId })
    }

    @Test
    fun `a pass that resolved nothing returns the same list`() {
        val current = listOf(album("s1"), album("s2"))

        assertEquals(current, mergeResolvedCovers(current, emptyMap()))
    }
}
