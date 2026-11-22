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
import eu.akoos.photos.data.db.entity.PhotoListingEntity
import eu.akoos.photos.data.db.entity.PhotoLocationEntity
import eu.akoos.photos.data.repository.PhotoLocationResolver.XAttrPlace
import eu.akoos.photos.data.repository.drive.OwnerAlbumKeyCache
import eu.akoos.photos.data.repository.drive.PhotosShareService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a cloud photo's XAttr tells the map: a fix, no fix, or nothing yet because its key did not decrypt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoLocationResolverPlaceTest {

    private val userId = UserId("u")
    private val crypto = mockk<CryptoServiceClient>()
    private val shares = mockk<PhotosShareService> {
        every { photosRootLinkId() } returns "root"
        coEvery { getRootLinkKeyBytes(userId) } returns byteArrayOf(1)
    }
    private val resolver = PhotoLocationResolver(
        crypto, mockk(relaxed = true), mockk(relaxed = true), shares, mockk(relaxed = true), mockk(relaxed = true), OwnerAlbumKeyCache(),
    )

    private fun row(encNodeKey: String? = "key") = PhotoListingEntity(
        linkId = "p1", shareId = "s", volumeId = "v", userId = "u", captureTime = 1L,
        displayName = "p1.jpg", mimeType = "image/jpeg", sizeBytes = 1L, revisionId = "r1",
        encNodeKey = encNodeKey, encNodePassphrase = "pass", parentLinkId = "root",
    )

    @Test
    fun `an XAttr with a location gives its fix`() = runTest {
        coEvery { crypto.decryptNodeKey(any(), any(), any()) } returns byteArrayOf(2)
        coEvery { crypto.decryptXAttr("armored", any()) } returns """{"Location":{"Latitude":38.9,"Longitude":1.43}}"""

        val place = resolver.placeOf(userId, row(), "armored")

        assertEquals(XAttrPlace.Located(PhotoLocationEntity(id = "p1", userId = "u", latitude = 38.9, longitude = 1.43)), place)
    }

    @Test
    fun `an XAttr without a location is no location`() = runTest {
        coEvery { crypto.decryptNodeKey(any(), any(), any()) } returns byteArrayOf(2)
        coEvery { crypto.decryptXAttr("armored", any()) } returns """{"Camera":{}}"""

        assertEquals(XAttrPlace.NoLocation, resolver.placeOf(userId, row(), "armored"))
    }

    @Test
    fun `a photo with no XAttr is no location`() = runTest {
        assertEquals(XAttrPlace.NoLocation, resolver.placeOf(userId, row(), null))
    }

    @Test
    fun `a row without its node key is a missing key`() = runTest {
        assertEquals(XAttrPlace.KeyMissing, resolver.placeOf(userId, row(encNodeKey = null), "armored"))
    }

    @Test
    fun `a parent key that cannot be had is a missing key`() = runTest {
        coEvery { shares.getRootLinkKeyBytes(userId) } returns null

        assertEquals(XAttrPlace.KeyMissing, resolver.placeOf(userId, row(), "armored"))
    }

    @Test
    fun `a node key that fails to decrypt is a missing key`() = runTest {
        coEvery { crypto.decryptNodeKey(any(), any(), any()) } throws IllegalStateException("bad key")

        assertEquals(XAttrPlace.KeyMissing, resolver.placeOf(userId, row(), "armored"))
    }
}
