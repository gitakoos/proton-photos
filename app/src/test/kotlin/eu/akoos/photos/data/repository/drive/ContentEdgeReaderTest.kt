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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.RevisionBlockDto
import eu.akoos.photos.data.api.dto.RevisionResponse
import eu.akoos.photos.data.api.dto.RevisionWithBlocksDto
import eu.akoos.photos.domain.entity.CloudPhoto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiManager
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.session.SessionProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Which content blocks the map's file GPS read fetches: the first, the last, the last two run together for
 * a movie box split across them, and nothing past the first for a single-block file. The CDN and the
 * decrypt are faked, so each block's bytes name the block they came from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentEdgeReaderTest {

    private val userId = UserId("u")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = mockk<DriveApiService>()
    private val cdn = mockk<CdnBlockFetcher>()
    private val crypto = mockk<CryptoServiceClient>()
    private val links = mockk<LinkDetailHelpers>()
    private val share = mockk<PhotosShareService>()
    private val fetched = mutableListOf<String>()

    private val photo = CloudPhoto(
        linkId = "l1", shareId = "s", volumeId = "v", captureTime = 1L, displayName = "clip.mp4",
        mimeType = "video/mp4", sizeBytes = 1L, thumbnailUrl = null, revisionId = "r1",
    )

    private val manager = object : ApiManager<DriveApiService> {
        override suspend fun <T> invoke(block: suspend DriveApiService.() -> T): ApiResult<T> = invoke(false, block)

        override suspend fun <T> invoke(
            forceNoRetryOnConnectionErrors: Boolean,
            block: suspend DriveApiService.() -> T,
        ): ApiResult<T> = try {
            ApiResult.Success(api.block())
        } catch (e: ApiException) {
            e.error
        }
    }

    private fun block(index: Int) = RevisionBlockDto(index = index, bareUrl = "https://cdn/b$index", token = "t")

    private fun revisionWith(vararg indices: Int) {
        coEvery { api.getRevisionByVolume("v", "l1", "r1") } returns
            RevisionResponse(RevisionWithBlocksDto(id = "r1", blocks = indices.map { block(it) }, contentKeyPacket = "ckp"), 1000)
    }

    private fun reader(): ContentEdgeReader {
        val apiProvider = mockk<ApiProvider>()
        val sessions = mockk<SessionProvider>()
        every { apiProvider.sessionProvider } returns sessions
        coEvery { sessions.getSessionId(any()) } returns SessionId("session")
        coEvery { apiProvider.get(DriveApiService::class, any<SessionId>()) } returns manager
        return ContentEdgeReader(apiProvider, crypto, share, links, cdn, context)
    }

    @Before
    fun setUp() {
        val link = mockk<BatchLinkDto>(relaxed = true)
        every { link.link.nodeKey } returns "nodeKey"
        every { link.link.nodePassphrase } returns "nodePass"
        every { link.link.parentLinkId } returns "root"
        every { link.photo } returns null
        every { link.link.fileProperties } returns null
        every { link.link.activeRevision } returns null
        coEvery { links.batchFetchLinkDetails(userId, "v", listOf("l1"), strict = true) } returns mapOf("l1" to link)
        coEvery { share.getRootLinkKeyBytes(userId) } returns ByteArray(1)
        every { share.photosRootLinkId() } returns "root"
        every { share.networkSemaphore } returns Semaphore(4)
        coEvery { crypto.decryptNodeKey(any(), any(), any()) } returns ByteArray(2)
        coEvery { crypto.decryptSessionKey("ckp", any()) } returns SessionKey(ByteArray(3))
        coEvery { cdn.fetchBlock(any(), any(), any(), any()) } answers {
            val url = firstArg<String>()
            fetched += url.substringAfterLast('/')
            url.substringAfterLast('/').toByteArray()
        }
        coEvery { crypto.decryptWithSessionKey(any(), any()) } answers { secondArg<File>().readBytes() }
    }

    @Test
    fun `the first block alone is read for an image`() = runTest {
        revisionWith(2, 0, 1)

        val edges = reader().readContentEdges(userId, photo, withFirst = true, tailBlocks = 0)

        assertArrayEquals("b0".toByteArray(), edges.first)
        assertNull(edges.last)
        assertEquals(3, edges.blockCount)
        assertEquals(listOf("b0"), fetched)
    }

    @Test
    fun `the first and last blocks are read for a video and nothing in between`() = runTest {
        revisionWith(0, 1, 2, 3)

        val edges = reader().readContentEdges(userId, photo, withFirst = true, tailBlocks = 1)

        assertArrayEquals("b0".toByteArray(), edges.first)
        assertArrayEquals("b3".toByteArray(), edges.last)
        assertEquals(listOf("b0", "b3"), fetched)
    }

    @Test
    fun `the last two blocks are read together for a movie box split across them`() = runTest {
        revisionWith(0, 1, 2, 3)

        val edges = reader().readContentEdges(userId, photo, withFirst = false, tailBlocks = 2)

        assertNull(edges.first)
        assertArrayEquals("b2b3".toByteArray(), edges.last)
        assertEquals(listOf("b2", "b3"), fetched)
    }

    @Test
    fun `a single-block file is read once and has no last block`() = runTest {
        revisionWith(0)

        val edges = reader().readContentEdges(userId, photo, withFirst = true, tailBlocks = 1)

        assertArrayEquals("b0".toByteArray(), edges.first)
        assertNull(edges.last)
        assertEquals(1, edges.blockCount)
        coVerify(exactly = 1) { cdn.fetchBlock(any(), any(), any(), any()) }
    }
}
