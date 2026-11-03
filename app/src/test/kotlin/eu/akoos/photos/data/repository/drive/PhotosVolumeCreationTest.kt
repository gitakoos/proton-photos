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

import eu.akoos.photos.data.api.DriveApiService
import eu.akoos.photos.data.api.dto.CreatePhotosVolumeRequest
import eu.akoos.photos.data.api.dto.CreatePhotosVolumeResponse
import eu.akoos.photos.data.api.dto.PhotosShareDto
import eu.akoos.photos.data.api.dto.PhotosShareResponse
import eu.akoos.photos.data.api.dto.PhotosVolumeDto
import eu.akoos.photos.data.crypto.AddressSigningKey
import eu.akoos.photos.data.crypto.DriveCryptoHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiManager
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.session.SessionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A new account has no Photos volume, and every Photos call answers 2501 until a client creates one. The
 * share lookup creates the volume on exactly that answer and reads the share again; any other error is
 * passed on untouched, and a volume another client made in the meantime is not created twice.
 */
class PhotosVolumeCreationTest {

    private val userId = UserId("user1")
    private val api = mockk<DriveApiService>()
    private val cryptoHelper = mockk<DriveCryptoHelper>(relaxed = true)
    private val bootstrap = mockk<PhotosVolumeBootstrap>()
    private val signingKey = mockk<AddressSigningKey>()
    private val createBody = mockk<CreatePhotosVolumeRequest>()
    private lateinit var service: PhotosShareService

    private val share = PhotosShareResponse(
        volume = PhotosVolumeDto("vol1"),
        share = PhotosShareDto("share1", linkId = "root1"),
        code = 1000,
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

    private fun protonError(code: Int) = ApiException(
        ApiResult.Error.Http(422, "http 422", ApiResult.Error.ProtonData(code = code, error = "refused")),
    )

    @Before
    fun setUp() {
        val apiProvider = mockk<ApiProvider>()
        val sessionProvider = mockk<SessionProvider>()
        every { apiProvider.sessionProvider } returns sessionProvider
        coEvery { sessionProvider.getSessionId(any()) } returns SessionId("session1")
        coEvery { apiProvider.get(DriveApiService::class, any<SessionId>()) } returns manager
        coEvery { cryptoHelper.getPrimaryAddressSigningKey(userId) } returns signingKey
        coEvery { bootstrap.build(userId, signingKey) } returns createBody
        service = PhotosShareService(apiProvider, cryptoHelper, mockk(relaxed = true), bootstrap)
    }

    @Test
    fun `a missing Photos volume is created and the share read again`() = runTest {
        var created = false
        coEvery { api.getPhotosShare() } answers { if (created) share else throw protonError(2501) }
        coEvery { api.createOrGetPhotosVolume(createBody) } answers {
            created = true
            CreatePhotosVolumeResponse(PhotosVolumeDto("vol1"), 1000)
        }

        assertEquals("vol1", service.getVolumeId(userId))
        assertEquals("share1", service.shareId())
        coVerify(exactly = 1) { api.createOrGetPhotosVolume(createBody) }
    }

    @Test
    fun `the new volume is signed with the primary address`() = runTest {
        var created = false
        coEvery { api.getPhotosShare() } answers { if (created) share else throw protonError(2501) }
        coEvery { api.createOrGetPhotosVolume(createBody) } answers {
            created = true
            CreatePhotosVolumeResponse(PhotosVolumeDto("vol1"), 1000)
        }

        service.getVolumeId(userId)

        coVerify(exactly = 1) { bootstrap.build(userId, signingKey) }
        coVerify(exactly = 0) { cryptoHelper.getAddressSigningKey(any()) }
    }

    @Test
    fun `a volume another client created meanwhile is not created again`() = runTest {
        var reads = 0
        coEvery { api.getPhotosShare() } answers { if (++reads == 1) throw protonError(2501) else share }

        assertEquals("vol1", service.getVolumeId(userId))
        coVerify(exactly = 0) { api.createOrGetPhotosVolume(any()) }
    }

    @Test
    fun `a create answered with already exists still reads the share`() = runTest {
        var reads = 0
        coEvery { api.getPhotosShare() } answers { if (++reads <= 2) throw protonError(2501) else share }
        coEvery { api.createOrGetPhotosVolume(createBody) } throws protonError(2500)

        assertEquals("vol1", service.getVolumeId(userId))
        coVerify(exactly = 1) { api.createOrGetPhotosVolume(createBody) }
    }

    @Test
    fun `any other share error is passed on and creates nothing`() = runTest {
        coEvery { api.getPhotosShare() } throws protonError(2011)

        try {
            service.getVolumeId(userId)
            fail("expected the share error")
        } catch (e: ApiException) {
            assertEquals(2011, (e.error as ApiResult.Error.Http).proton?.code)
        }
        coVerify(exactly = 0) { api.createOrGetPhotosVolume(any()) }
        assertTrue(service.volumeId() == null)
    }
}
