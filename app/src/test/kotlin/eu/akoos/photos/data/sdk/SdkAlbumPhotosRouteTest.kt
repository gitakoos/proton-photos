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

import eu.akoos.photos.data.api.dto.AlbumChildDto
import eu.akoos.photos.data.repository.drive.isDirectAlbumChild
import eu.akoos.photos.data.repository.drive.wireDecidesAlbumChild
import eu.akoos.photos.domain.entity.AlbumChild
import eu.akoos.photos.util.SdkDiagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.AlbumItem
import me.proton.drive.sdk.entity.LegacyNodeUid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** An album's photos through the SDK, or the app's own album listing, and the album-child rule on both. */
class SdkAlbumPhotosRouteTest {

    private val userId = UserId("u")
    private val label = SdkRoute.ALBUM_PHOTOS.label
    private lateinit var client: ProtonPhotosClient
    private lateinit var ops: SdkListingOps

    /** The app's own listing of the fixture album, as its pages would hand it over. */
    private val ownPages = listOf(
        AlbumChildDto(linkId = "p3", captureTime = 3_000, addedTime = 9_000, isChildOfAlbum = true),
        AlbumChildDto(linkId = "p2", captureTime = 2_000, addedTime = 8_000),
        AlbumChildDto(linkId = "p1", captureTime = 2_000, addedTime = 7_000),
    )

    /** The children the app's own listing produces, mapped as the app maps them. */
    private val ownChildren = ownPages.map {
        AlbumChild(linkId = it.linkId, captureTime = it.captureTime, addedTime = it.addedTime, isChildOfAlbum = it.isChildOfAlbum)
    }

    @Before
    fun setUp() {
        SdkDiagnostics.clear()
        client = mockk()
        val provider = mockk<SdkDriveClientProvider>()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
        ops = SdkListingOps(provider)
        every { client.enumerateAlbum(LegacyNodeUid("v", ALBUM)) } returns flowOf(
            *ownPages.map { AlbumItem(LegacyNodeUid("v", it.linkId), Instant.ofEpochSecond(it.captureTime!!)) }.toTypedArray(),
        )
    }

    private suspend fun route(on: Boolean): List<AlbumChild> = runRouted(
        label,
        on,
        viaSdk = { ops.albumPhotos(userId, "v", ALBUM).map { it.toAlbumChild() } },
        fallback = { ownChildren },
    )

    @Test
    fun `the route starts switched off`() {
        assertFalse(SdkRoute.ALBUM_PHOTOS.defaultOn)
        assertFalse(routeSwitchedOn(null, SdkRoute.ALBUM_PHOTOS.defaultOn))
    }

    @Test
    fun `switched off, the app's own listing answers and the SDK is never asked`() = runTest {
        assertEquals(ownChildren, route(on = false))
        verify(exactly = 0) { client.enumerateAlbum(any()) }
        assertEquals(0, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `the SDK listing gives the app's own photos in the app's own order with the same capture times`() = runTest {
        val viaSdk = route(on = true)

        assertEquals(ownChildren.map { it.linkId }, viaSdk.map { it.linkId })
        assertEquals(ownChildren.map { it.captureTime }, viaSdk.map { it.captureTime })
        assertEquals(1, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `the SDK listing's children build the same album-child rows wherever the parent decides`() = runTest {
        val viaSdk = route(on = true)
        val albums = setOf(ALBUM, OTHER_ALBUM)
        for (parent in listOf(null, ROOT, ALBUM, OTHER_ALBUM)) {
            assertFalse(wireDecidesAlbumChild(parent, albums, ROOT))
            ownChildren.zip(viaSdk).forEach { (own, sdk) ->
                assertEquals(
                    isDirectAlbumChild(parent, albums, ROOT, own.isChildOfAlbum),
                    isDirectAlbumChild(parent, albums, ROOT, sdk.isChildOfAlbum),
                )
            }
        }
    }

    @Test
    fun `a parent that is no known album leaves the flag to decide, so the app's own listing is asked`() {
        assertTrue(wireDecidesAlbumChild("unknown", setOf(ALBUM), ROOT))
        assertTrue(isDirectAlbumChild("unknown", setOf(ALBUM), ROOT, wireSaysChild = true))
        assertFalse(isDirectAlbumChild("unknown", setOf(ALBUM), ROOT, wireSaysChild = false))
    }

    @Test
    fun `a failed SDK listing falls back to the app's own`() = runTest {
        every { client.enumerateAlbum(any()) } throws IllegalStateException("boom")

        assertEquals(ownChildren, route(on = true))
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `a photo the SDK names on another volume falls back to the app's own listing`() = runTest {
        every { client.enumerateAlbum(LegacyNodeUid("v", ALBUM)) } returns flowOf(
            AlbumItem(LegacyNodeUid("v", "p3"), Instant.ofEpochSecond(3_000)),
            AlbumItem(LegacyNodeUid("other", "p2"), Instant.ofEpochSecond(2_000)),
        )

        assertEquals(ownChildren, route(on = true))
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    private companion object {
        const val ALBUM = "album"
        const val OTHER_ALBUM = "album2"
        const val ROOT = "root"
    }
}
