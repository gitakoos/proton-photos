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
import eu.akoos.photos.util.SdkDiagnostics
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
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.MemberRole
import me.proton.drive.sdk.entity.OwnedBy
import me.proton.drive.sdk.entity.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** This user's album list through the SDK, or the app's own album list. */
class SdkAlbumListRouteTest {

    private val userId = UserId("u")
    private val label = SdkRoute.ALBUM_LIST.label
    private lateinit var client: ProtonPhotosClient
    private lateinit var ops: SdkListingOps

    /** The app's own album list of the fixture library, as its pages would hand it over. */
    private val ownList = listOf(
        AlbumDto(linkId = "a2", volumeId = "v", photoCount = 12, coverLinkId = "p9", lastActivityTime = 2_000),
        AlbumDto(linkId = "a1", volumeId = "v", photoCount = 0, coverLinkId = null, lastActivityTime = null),
        AlbumDto(linkId = "a3", volumeId = "v", photoCount = 3, coverLinkId = "p1", lastActivityTime = 1_000),
    )
    private val ownListed = ownList.map { it.toListedAlbum() }

    private val author = Result.success(Author("me@example.com"))

    @Suppress("DEPRECATION")
    private fun nodeOf(dto: AlbumDto) = AlbumNode(
        uid = LegacyNodeUid("v", dto.linkId),
        parentUid = null,
        treeEventScopeId = ScopeId("v"),
        name = Result.success("Album ${dto.linkId}"),
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
        photoCount = dto.photoCount.toLong(),
        coverPhotoNodeUid = dto.coverLinkId?.let { LegacyNodeUid("v", it) },
        lastActivityTime = dto.lastActivityTime?.let { Instant.ofEpochSecond(it) },
        deprecatedShareId = null,
    )

    @Before
    fun setUp() {
        SdkDiagnostics.clear()
        client = mockk()
        val provider = mockk<SdkDriveClientProvider>()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
        ops = SdkListingOps(provider)
        every { client.enumerateAlbumNodeUids() } returns flowOf(*ownList.map { LegacyNodeUid("v", it.linkId) }.toTypedArray())
        // The nodes come back in another order than the list; the list's order must win.
        every { client.enumerateNodes(any()) } returns flowOf(*ownList.reversed().map { nodeOf(it) }.toTypedArray())
    }

    private suspend fun route(on: Boolean): List<ListedAlbum> =
        runRouted(label, on, viaSdk = { ops.albums(userId, "v") }, fallback = { ownListed })

    @Test
    fun `the route starts switched off`() {
        assertFalse(SdkRoute.ALBUM_LIST.defaultOn)
        assertFalse(routeSwitchedOn(null, SdkRoute.ALBUM_LIST.defaultOn))
    }

    @Test
    fun `switched off, the app's own album list answers and the SDK is never asked`() = runTest {
        assertEquals(ownListed, route(on = false))
        verify(exactly = 0) { client.enumerateAlbumNodeUids() }
        verify(exactly = 0) { client.enumerateNodes(any()) }
        assertEquals(0, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `the SDK album list gives the app's own albums in the app's own order with the same fields`() = runTest {
        assertEquals(ownListed, route(on = true))
        assertEquals(1, SdkDiagnostics.sdkCount(label))
        assertEquals(0, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `a failed SDK album list falls back to the app's own`() = runTest {
        every { client.enumerateAlbumNodeUids() } throws IllegalStateException("boom")

        assertEquals(ownListed, route(on = true))
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `an album whose node did not come back falls back to the app's own album list`() = runTest {
        every { client.enumerateNodes(any()) } returns flowOf(*ownList.drop(1).map { nodeOf(it) }.toTypedArray())

        assertEquals(ownListed, route(on = true))
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }
}
