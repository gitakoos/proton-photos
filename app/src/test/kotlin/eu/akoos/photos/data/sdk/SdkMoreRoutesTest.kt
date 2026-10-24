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

package eu.akoos.photos.data.sdk

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.Author
import me.proton.drive.sdk.entity.DriveEvent
import me.proton.drive.sdk.entity.DriveEventId
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.MemberRole
import me.proton.drive.sdk.entity.Membership
import me.proton.drive.sdk.entity.NodeResultPair
import me.proton.drive.sdk.entity.OwnedBy
import me.proton.drive.sdk.entity.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The change feed, shared-with-me albums and the foreign-album add through the SDK. */
class SdkMoreRoutesTest {

    private val userId = UserId("u")
    private lateinit var client: ProtonPhotosClient
    private lateinit var provider: SdkDriveClientProvider

    @Before
    fun setUp() {
        client = mockk()
        provider = mockk()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
    }

    private fun uid(linkId: String, volume: String = "v") = LegacyNodeUid(volumeId = volume, linkId = linkId)
    private fun id(value: String) = DriveEventId(value)

    // Change feed

    @Test
    fun `changes become fetches and removals, a trashed link leaves, and the last event is the cursor`() {
        val batch = eventBatchOf(
            listOf(
                DriveEvent.NodeUpdated(id("e1"), uid("a"), null, isTrashed = false, isShared = false),
                DriveEvent.NodeUpdated(id("e2"), uid("b"), null, isTrashed = true, isShared = false),
                DriveEvent.NodeDeleted(id("e3"), uid("c"), null),
                DriveEvent.NodeUpdated(id("e4"), uid("c"), null, isTrashed = false, isShared = false),
            ),
            startCursor = "e0",
        )
        assertEquals(listOf("a"), batch.upserts)
        assertEquals(listOf("b", "c"), batch.deletes)
        assertEquals("e4", batch.cursor)
        assertFalse(batch.continuityLost)
    }

    @Test
    fun `a quiet feed keeps the cursor it started from, and lost continuity is reported`() {
        assertEquals("e0", eventBatchOf(emptyList(), "e0").cursor)
        val lost = eventBatchOf(listOf(DriveEvent.ContinuityLost(id("e9"))), "e0")
        assertTrue(lost.continuityLost)
        assertEquals("e9", lost.cursor)
    }

    @Test
    fun `arming the feed takes the newest event id`() = runTest {
        every { client.enumerateEvents(any(), null) } returns flowOf(DriveEvent.CursorAdvanced(id("latest")))
        assertEquals("latest", SdkEventOps(provider).latestCursor(userId, "v"))
    }

    // Shared-with-me albums

    private val author = Result.success(Author("owner@example.com"))

    @Suppress("DEPRECATION")
    private fun album(linkId: String, shareId: String?, role: MemberRole = MemberRole.EDITOR) = AlbumNode(
        uid = uid(linkId, volume = "owner-volume"),
        parentUid = null,
        treeEventScopeId = ScopeId("owner-volume"),
        name = Result.success("Trip"),
        creationTime = Instant.ofEpochSecond(1),
        trashTime = null,
        nameAuthor = author,
        keyAuthor = author,
        ownedBy = OwnedBy(email = "owner@example.com", organization = null),
        isShared = true,
        isSharedByUrl = false,
        directRole = role,
        membership = Membership(role, Instant.ofEpochSecond(1), Result.success(Author("inviter@example.com"))),
        errors = emptyList(),
        photoCount = 12L,
        coverPhotoNodeUid = uid("cover", volume = "owner-volume"),
        lastActivityTime = Instant.ofEpochSecond(100),
        deprecatedShareId = shareId,
    )

    @Test
    fun `a shared album keeps its share id, volume and edit rights`() = runTest {
        every { client.enumerateSharedWithMeNodeUids() } returns flowOf(uid("a1", "owner-volume"))
        every { client.enumerateNodes(any()) } returns flowOf(album("a1", "share-1"))

        val listed = SdkSharedOps(provider).listSharedAlbums(userId).single()

        assertEquals("a1", listed.linkId)
        assertEquals("owner-volume", listed.volumeId)
        assertEquals("share-1", listed.shareId)
        assertEquals("cover", listed.coverLinkId)
        assertEquals(100_000L, listed.lastActivityTimeMs)
        assertEquals("owner@example.com", listed.sharedByEmail)
        assertEquals(6L, listed.permissions)
    }

    @Test
    fun `one album without its share id fails the whole list so the app's own listing answers`() = runTest {
        every { client.enumerateSharedWithMeNodeUids() } returns flowOf(uid("a1"), uid("a2"))
        every { client.enumerateNodes(any()) } returns flowOf(album("a1", "share-1"), album("a2", null))
        try {
            SdkSharedOps(provider).listSharedAlbums(userId)
            fail("expected the list to fail as a whole")
        } catch (_: SdkSharedException) {
        }
    }

    @Test
    fun `roles map to Drive's permission bits and only an editor or admin may add`() {
        assertEquals(4L, permissionsOf(MemberRole.VIEWER))
        assertEquals(6L, permissionsOf(MemberRole.EDITOR))
        assertEquals(22L, permissionsOf(MemberRole.ADMIN))
        assertNull(permissionsOf(MemberRole.INHERITED))
    }

    @Test
    fun `adding to a foreign album names the album on its owner's volume and the photos on this user's`() = runTest {
        every { client.addPhotosToAlbum(uid("album", "owner-volume"), listOf(uid("p1", "mine"))) } returns
            flowOf(NodeResultPair.Success(uid("p1", "mine")))
        val done = mutableListOf<String>()

        val refused = SdkAlbumOps(provider).addPhotosToForeignAlbum(userId, "owner-volume", "album", "mine", listOf("p1")) { done += it }

        assertTrue(refused.isEmpty())
        assertEquals(listOf("p1"), done)
        verify(exactly = 1) { client.addPhotosToAlbum(uid("album", "owner-volume"), listOf(uid("p1", "mine"))) }
    }
}
