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

import eu.akoos.photos.data.api.dto.AlbumDto
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.api.dto.LinkPhotoDto
import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.domain.entity.AlbumChild
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.AlbumItem
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.Author
import me.proton.drive.sdk.entity.FileThumbnail
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.MemberRole
import me.proton.drive.sdk.entity.OwnedBy
import me.proton.drive.sdk.entity.PhotosTimelineItem
import me.proton.drive.sdk.entity.ScopeId
import me.proton.drive.sdk.entity.ThumbnailType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The SDK library reads and the mapping that turns their answers into the app's own list shapes. */
class SdkListingOpsTest {

    private val userId = UserId("u")
    private lateinit var client: ProtonPhotosClient
    private lateinit var ops: SdkListingOps

    @Before
    fun setUp() {
        client = mockk()
        val provider = mockk<SdkDriveClientProvider>()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
        ops = SdkListingOps(provider)
    }

    private fun uid(linkId: String, volume: String = "v") = LegacyNodeUid(volumeId = volume, linkId = linkId)

    private fun expectListingFailure(block: suspend () -> Unit) = runTest {
        try {
            block()
            fail("expected the read to fail as a whole")
        } catch (_: SdkListingException) {
        }
    }

    // Timeline

    @Test
    fun `the timeline keeps the server's order and each capture time in seconds`() = runTest {
        every { client.enumerateTimeline() } returns flowOf(
            PhotosTimelineItem(uid("b"), Instant.ofEpochSecond(300)),
            PhotosTimelineItem(uid("a"), Instant.ofEpochSecond(200)),
        )

        assertEquals(
            listOf(SdkListedPhoto("b", 300), SdkListedPhoto("a", 200)),
            ops.timeline(userId, "v"),
        )
    }

    @Test
    fun `a timeline photo on another volume fails the whole timeline`() = expectListingFailure {
        every { client.enumerateTimeline() } returns flowOf(
            PhotosTimelineItem(uid("a"), Instant.ofEpochSecond(1)),
            PhotosTimelineItem(uid("b", volume = "other"), Instant.ofEpochSecond(2)),
        )
        ops.timeline(userId, "v")
    }

    private val restListing = listOf(
        PhotoLinkDto(linkId = "p2", captureTime = 2_000, hash = "h2", contentHash = "c2", tags = listOf(0, 3)),
        PhotoLinkDto(linkId = "p1", captureTime = 1_000, hash = "h1", contentHash = "c1", tags = emptyList()),
    )

    private fun detailOf(stub: PhotoLinkDto) = BatchLinkDto(
        link = LinkCoreDto(linkId = stub.linkId, type = 2, hash = stub.hash),
        photo = LinkPhotoDto(captureTime = stub.captureTime, contentHash = stub.contentHash, tags = stub.tags.orEmpty()),
    )

    @Test
    fun `the SDK timeline filled from the photos' links reads exactly like the app's own listing`() {
        val listed = restListing.map { SdkListedPhoto(it.linkId, it.captureTime!!) }
        val facts = restListing.associate { it.linkId to detailOf(it).toListedPhotoFacts() }

        val stubs = timelineStubsOf(listed, facts)

        assertEquals(restListing.map { it.linkId }, stubs.map { it.linkId })
        assertEquals(restListing.map { it.captureTime }, stubs.map { it.captureTime })
        assertEquals(restListing.map { it.contentHash }, stubs.map { it.contentHash })
        assertEquals(restListing.map { it.hash }, stubs.map { it.hash })
        assertEquals(restListing.map { it.tags.orEmpty().sorted() }, stubs.map { it.tags.orEmpty().sorted() })
    }

    @Test
    fun `a timeline photo whose link did not come back fails the whole fill`() {
        val listed = restListing.map { SdkListedPhoto(it.linkId, it.captureTime!!) }
        val facts = mapOf("p2" to detailOf(restListing[0]).toListedPhotoFacts())
        try {
            timelineStubsOf(listed, facts)
            fail("expected a partial answer to fail")
        } catch (_: SdkListingException) {
        }
    }

    @Test
    fun `a timeline photo whose link names another capture time fails the whole fill`() {
        val facts = mapOf("p1" to ListedPhotoFacts(hash = null, contentHash = "c1", tags = emptyList(), captureTime = 999))
        try {
            timelineStubsOf(listOf(SdkListedPhoto("p1", 1_000)), facts)
            fail("expected a mismatch to fail")
        } catch (_: SdkListingException) {
        }
    }

    @Test
    fun `a link without a capture time takes the SDK's`() {
        val facts = mapOf("p1" to ListedPhotoFacts(hash = null, contentHash = "c1", tags = listOf(0), captureTime = null))
        val stub = timelineStubsOf(listOf(SdkListedPhoto("p1", 1_000)), facts).single()
        assertEquals(1_000L, stub.captureTime)
        assertEquals(listOf(0), stub.tags)
    }

    // Albums

    private val author = Result.success(Author("me@example.com"))

    @Suppress("DEPRECATION")
    private fun album(linkId: String, count: Long, cover: String?, lastActivity: Long?) = AlbumNode(
        uid = uid(linkId),
        parentUid = null,
        treeEventScopeId = ScopeId("v"),
        name = Result.success("Album $linkId"),
        creationTime = Instant.ofEpochSecond(1),
        trashTime = null,
        nameAuthor = author,
        keyAuthor = author,
        ownedBy = OwnedBy(email = "me@example.com", organization = null),
        isShared = false,
        isSharedByUrl = false,
        directRole = MemberRole.ADMIN,
        membership = null,
        errors = emptyList(),
        photoCount = count,
        coverPhotoNodeUid = cover?.let { uid(it) },
        lastActivityTime = lastActivity?.let { Instant.ofEpochSecond(it) },
        deprecatedShareId = null,
    )

    @Test
    fun `the SDK album list reads exactly like the app's own album list`() = runTest {
        val rest = listOf(
            AlbumDto(linkId = "a2", photoCount = 5, coverLinkId = "p9", lastActivityTime = 1_700),
            AlbumDto(linkId = "a1", photoCount = 0, coverLinkId = null, lastActivityTime = null),
        )
        every { client.enumerateAlbumNodeUids() } returns flowOf(uid("a2"), uid("a1"))
        every { client.enumerateNodes(any()) } returns flowOf(album("a1", 0, null, null), album("a2", 5, "p9", 1_700))

        assertEquals(rest.map { it.toListedAlbum() }, ops.albums(userId, "v"))
    }

    @Test
    fun `an album without its node fails the whole album list`() = expectListingFailure {
        every { client.enumerateAlbumNodeUids() } returns flowOf(uid("a1"), uid("a2"))
        every { client.enumerateNodes(any()) } returns flowOf(album("a1", 1, null, null))
        ops.albums(userId, "v")
    }

    @Test
    fun `no albums reads no nodes`() = runTest {
        every { client.enumerateAlbumNodeUids() } returns flowOf()

        assertEquals(emptyList<ListedAlbum>(), ops.albums(userId, "v"))
        verify(exactly = 0) { client.enumerateNodes(any()) }
    }

    // Album photos

    @Test
    fun `an album's photos keep the server's order and become album children without the flags`() = runTest {
        every { client.enumerateAlbum(uid("album")) } returns flowOf(
            AlbumItem(uid("p2"), Instant.ofEpochSecond(20)),
            AlbumItem(uid("p1"), Instant.ofEpochSecond(10)),
        )

        val children = ops.albumPhotos(userId, "v", "album").map { it.toAlbumChild() }

        assertEquals(
            listOf(
                AlbumChild(linkId = "p2", captureTime = 20, addedTime = null, isChildOfAlbum = false),
                AlbumChild(linkId = "p1", captureTime = 10, addedTime = null, isChildOfAlbum = false),
            ),
            children,
        )
    }

    @Test
    fun `an album photo on another volume fails the whole album`() = expectListingFailure {
        every { client.enumerateAlbum(uid("album")) } returns flowOf(AlbumItem(uid("p1", volume = "other"), Instant.ofEpochSecond(1)))
        ops.albumPhotos(userId, "v", "album")
    }

    // Thumbnails

    @Test
    fun `a thumbnail comes back as its bytes`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        every { client.enumerateThumbnails(listOf(uid("p1")), ThumbnailType.THUMBNAIL) } returns
            flowOf(FileThumbnail(uid("p1"), Result.success(bytes)))

        assertArrayEquals(bytes, ops.thumbnail(userId, "v", "p1", ThumbnailType.THUMBNAIL))
    }

    @Test
    fun `a failed thumbnail fails the read`() = expectListingFailure {
        every { client.enumerateThumbnails(any(), ThumbnailType.PREVIEW) } returns
            flowOf(FileThumbnail(uid("p1"), Result.failure(IllegalStateException("no preview"))))
        ops.thumbnail(userId, "v", "p1", ThumbnailType.PREVIEW)
    }

    @Test
    fun `a missing or empty thumbnail fails the read`() = expectListingFailure {
        every { client.enumerateThumbnails(any(), ThumbnailType.THUMBNAIL) } returns
            flowOf(FileThumbnail(uid("p1"), Result.success(ByteArray(0))))
        ops.thumbnail(userId, "v", "p1", ThumbnailType.THUMBNAIL)
    }

    @Test
    fun `a thumbnail for another photo does not count`() = expectListingFailure {
        every { client.enumerateThumbnails(any(), ThumbnailType.THUMBNAIL) } returns
            flowOf(FileThumbnail(uid("other"), Result.success(byteArrayOf(1))))
        ops.thumbnail(userId, "v", "p1", ThumbnailType.THUMBNAIL)
    }
}
