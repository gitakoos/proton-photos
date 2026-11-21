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

import eu.akoos.photos.crypto.CryptoServiceClient
import eu.akoos.photos.data.api.dto.BatchLinkDto
import eu.akoos.photos.data.api.dto.LinkCoreDto
import eu.akoos.photos.data.db.dao.PhotoListingDao
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.repository.drive.AlbumCryptoChain
import eu.akoos.photos.data.repository.drive.LinkDetailHelpers
import eu.akoos.photos.data.repository.drive.OwnerAlbumKeyCache
import eu.akoos.photos.data.repository.drive.PhotosShareService
import eu.akoos.photos.domain.repository.DriveCopyXAttr
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The declared size read from a photo's XAttr: the link details the caller holds first, then the listing's
 * cached blob, then, only when allowed, the one its revision returns; any failure reads as no size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XAttrClaimedSizeReaderTest {

    private val userId = UserId("u1")
    private val rootKey = byteArrayOf(1)
    private val albumKey = byteArrayOf(2)
    private val nodeKey = byteArrayOf(3)
    private lateinit var crypto: CryptoServiceClient
    private lateinit var listing: PhotoListingDao
    private lateinit var share: PhotosShareService
    private lateinit var links: LinkDetailHelpers
    private lateinit var albums: AlbumCryptoChain
    private lateinit var reader: XAttrClaimedSizeReader
    private val albumKeys = OwnerAlbumKeyCache()

    private fun row(encXAttr: String?, parent: String = "root") = PhotoListingEntity(
        linkId = "L1", shareId = "s1", volumeId = "v1", userId = "u1", captureTime = 0L,
        displayName = "IMG_1_edit_20260801_101500.jpg", mimeType = "image/jpeg", sizeBytes = 2100L,
        revisionId = "r1", encNodeKey = "nk", encNodePassphrase = "np", parentLinkId = parent, encXAttr = encXAttr,
    )

    private fun xattr(size: Long) = """{"Common":{"Size":$size,"ModificationTime":"2026-08-01T10:15:00Z"}}"""

    @Before
    fun setUp() {
        crypto = mockk()
        listing = mockk()
        share = mockk()
        links = mockk()
        albums = mockk()
        every { share.photosRootLinkId() } returns "root"
        coEvery { share.getRootLinkKeyBytes(userId) } returns rootKey
        coEvery { crypto.decryptNodeKey(any(), any(), any()) } returns nodeKey
        reader = XAttrClaimedSizeReader(crypto, listing, share, links, albums, albumKeys)
    }

    @Test
    fun `the cached XAttr is read without a fetch`() = runTest {
        coEvery { listing.getByLinkId("L1") } returns row("cached")
        coEvery { crypto.decryptXAttr("cached", nodeKey) } returns xattr(1564L)

        assertEquals(1564L, reader.claimedSize(userId, "L1", fetchIfUncached = true))
        coVerify(exactly = 0) { links.fetchRevisionXAttrOrThrow(any(), any(), any(), any(), any()) }
        coVerify { crypto.decryptNodeKey("nk", "np", rootKey) }
    }

    @Test
    fun `an uncached XAttr is fetched only when allowed`() = runTest {
        coEvery { listing.getByLinkId("L1") } returns row(null)
        coEvery { links.fetchRevisionXAttrOrThrow(userId, "v1", "s1", "L1", "r1") } returns "fetched"
        coEvery { crypto.decryptXAttr("fetched", nodeKey) } returns xattr(2048L)

        assertNull(reader.claimedSize(userId, "L1", fetchIfUncached = false))
        coVerify(exactly = 0) { links.fetchRevisionXAttrOrThrow(any(), any(), any(), any(), any()) }

        assertEquals(2048L, reader.claimedSize(userId, "L1", fetchIfUncached = true))
    }

    @Test
    fun `the link details the caller holds come first, and the cache stands in when they do not open`() = runTest {
        coEvery { listing.getByLinkId("L1") } returns row("cached")
        coEvery { crypto.decryptXAttr("fresh", nodeKey) } returns xattr(900L)
        coEvery { crypto.decryptXAttr("cached", nodeKey) } returns xattr(1564L)
        val fresh = DriveCopyXAttr("fresh", "fnk", "fnp", "root", "v1")

        assertEquals(900L, reader.claimedSize(userId, "L1", fetchIfUncached = false, fresh = fresh))

        coEvery { crypto.decryptXAttr("fresh", nodeKey) } throws IllegalStateException("bad blob")
        assertEquals(1564L, reader.claimedSize(userId, "L1", fetchIfUncached = false, fresh = fresh))
    }

    @Test
    fun `a failed decrypt, a missing listing row or an XAttr without a size reads as no size`() = runTest {
        coEvery { listing.getByLinkId("L1") } returns row("cached")
        coEvery { crypto.decryptXAttr("cached", nodeKey) } throws IllegalStateException("bad blob")
        assertNull(reader.claimedSize(userId, "L1", fetchIfUncached = true))

        coEvery { crypto.decryptXAttr("cached", nodeKey) } returns """{"Common":{"ModificationTime":"x"}}"""
        assertNull(reader.claimedSize(userId, "L1", fetchIfUncached = true))

        coEvery { listing.getByLinkId("L1") } returns null
        assertNull(reader.claimedSize(userId, "L1", fetchIfUncached = true))
    }

    @Test
    fun `a photo inside an album opens through the album's key, looked up once`() = runTest {
        coEvery { listing.getByLinkId("L1") } returns row("cached", parent = "A1")
        coEvery { links.batchFetchLinkDetails(userId, "v1", listOf("A1")) } returns mapOf(
            "A1" to BatchLinkDto(link = LinkCoreDto(linkId = "A1", type = 1, state = 1, nodeKey = "ank", nodePassphrase = "anp")),
        )
        coEvery { albums.decryptAlbumKey("ank", "anp", rootKey, any()) } returns albumKey
        coEvery { crypto.decryptXAttr("cached", nodeKey) } returns xattr(1564L)

        assertEquals(1564L, reader.claimedSize(userId, "L1", fetchIfUncached = false))
        assertEquals(1564L, reader.claimedSize(userId, "L1", fetchIfUncached = false))
        coVerify { crypto.decryptNodeKey("nk", "np", albumKey) }
        coVerify(exactly = 1) { links.batchFetchLinkDetails(userId, "v1", listOf("A1")) }
    }

    @Test
    fun `an album key decrypted before a sign-out is decrypted again after it`() = runTest {
        coEvery { listing.getByLinkId("L1") } returns row("cached", parent = "A1")
        coEvery { links.batchFetchLinkDetails(userId, "v1", listOf("A1")) } returns mapOf(
            "A1" to BatchLinkDto(link = LinkCoreDto(linkId = "A1", type = 1, state = 1, nodeKey = "ank", nodePassphrase = "anp")),
        )
        coEvery { albums.decryptAlbumKey("ank", "anp", rootKey, any()) } answers { byteArrayOf(2) }
        coEvery { crypto.decryptXAttr("cached", nodeKey) } returns xattr(1564L)

        assertEquals(1564L, reader.claimedSize(userId, "L1", fetchIfUncached = false))
        albumKeys.clear()
        assertEquals(1564L, reader.claimedSize(userId, "L1", fetchIfUncached = false))

        coVerify(exactly = 2) { links.batchFetchLinkDetails(userId, "v1", listOf("A1")) }
    }
}
