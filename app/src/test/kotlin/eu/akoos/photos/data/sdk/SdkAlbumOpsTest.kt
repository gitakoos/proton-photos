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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.entity.AlbumNode
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.NodeResultPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Album writes through the SDK answer the way the app's own requests do, so a fallback takes only what is left. */
class SdkAlbumOpsTest {

    private val userId = UserId("u")
    private lateinit var client: ProtonPhotosClient
    private lateinit var provider: SdkDriveClientProvider
    private lateinit var ops: SdkAlbumOps

    @Before
    fun setUp() {
        client = mockk()
        provider = mockk()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
        ops = SdkAlbumOps(provider)
    }

    private fun uid(linkId: String) = LegacyNodeUid(volumeId = "v", linkId = linkId)

    private fun failure(linkId: String, type: String) =
        NodeResultPair.Failure(uid(linkId), ProtonDriveSdkException("no", error = ProtonSdkError(message = "no", type = type)))

    @Test
    fun `a photo the album already holds counts as added, any other refusal is returned`() = runTest {
        every { client.addPhotosToAlbum(uid("album"), any()) } returns flowOf(
            NodeResultPair.Success(uid("p1")),
            failure("p2", "PhotoAlreadyInTargetException"),
            failure("p3", "ProtonApiException"),
        )
        val done = mutableListOf<String>()

        val refused = ops.addPhotos(userId, "v", "album", listOf("p1", "p2", "p3")) { done += it }

        assertEquals(setOf("p3"), refused)
        assertEquals(listOf("p1", "p2"), done)
    }

    @Test
    fun `an add that breaks off keeps the photos already answered for the caller`() = runTest {
        every { client.addPhotosToAlbum(uid("album"), any()) } returns flow {
            emit(NodeResultPair.Success(uid("p1")))
            throw ProtonDriveSdkException("transport")
        }
        val done = mutableListOf<String>()

        try {
            ops.addPhotos(userId, "v", "album", listOf("p1", "p2")) { done += it }
            fail("expected the failure to surface so the app's own request takes the rest")
        } catch (e: ProtonDriveSdkException) {
            assertEquals("transport", e.message)
        }
        assertEquals(listOf("p1"), done)
    }

    @Test
    fun `a remove never counts an already-in-album refusal as removed`() = runTest {
        every { client.removePhotosFromAlbum(uid("album"), any()) } returns flowOf(
            NodeResultPair.Success(uid("p1")),
            failure("p2", "PhotoAlreadyInTargetException"),
        )

        assertEquals(setOf("p2"), ops.removePhotos(userId, "v", "album", listOf("p1", "p2")))
    }

    @Test
    fun `a delete the album's own photos block asks the caller instead of failing`() = runTest {
        val blocked = ProtonDriveSdkException(
            "blocked",
            error = ProtonSdkError(
                message = "blocked",
                type = "AlbumContainsPhotosNotInTimelineException",
                additionalData = ProtonSdkError.Data.AlbumContainsPhotosNotInTimeline(listOf(uid("p1"))),
            ),
        )
        coEvery { client.deleteAlbum(uid("album"), force = false, saveToTimeline = false) } throws blocked

        assertFalse(ops.deleteAlbum(userId, "v", "album", deletePhotosToo = false))
    }

    @Test
    fun `a delete with its photos too forces it and any other error surfaces`() = runTest {
        coEvery { client.deleteAlbum(uid("album"), force = true, saveToTimeline = false) } returns Unit
        assertTrue(ops.deleteAlbum(userId, "v", "album", deletePhotosToo = true))
        coVerify(exactly = 1) { client.deleteAlbum(uid("album"), force = true, saveToTimeline = false) }

        coEvery { client.deleteAlbum(uid("other"), force = false, saveToTimeline = false) } throws
            ProtonDriveSdkException("offline", error = ProtonSdkError(message = "offline", type = "HttpRequestException"))
        try {
            ops.deleteAlbum(userId, "v", "other", deletePhotosToo = false)
            fail("expected the failure to surface")
        } catch (e: ProtonDriveSdkException) {
            assertEquals("offline", e.message)
        }
    }

    @Test
    fun `a created album answers with its link id`() = runTest {
        val node = mockk<AlbumNode> { every { uid } returns uid("new") }
        coEvery { client.createAlbum("Trip") } returns node

        assertEquals("new", ops.createAlbum(userId, "Trip"))
    }

    @Test
    fun `a device the SDK cannot run on hands every call to the app's own path`() = runTest {
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } returns null
        try {
            ops.createAlbum(userId, "Trip")
            fail("expected SdkAlbumException")
        } catch (_: SdkAlbumException) {
        }
    }
}
