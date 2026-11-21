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

import eu.akoos.photos.data.api.dto.PhotoLinkDto
import eu.akoos.photos.util.SdkDiagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.drive.sdk.ProtonPhotosClient
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.PhotosTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The timeline listing through the SDK, filled from the photos' links, or the app's own walk. */
class SdkTimelineRouteTest {

    private val userId = UserId("u")
    private val label = SdkRoute.TIMELINE.label
    private lateinit var client: ProtonPhotosClient
    private lateinit var ops: SdkListingOps

    /** The app's own listing of the fixture library, as its pages would hand it over. */
    private val ownListing = listOf(
        PhotoLinkDto(linkId = "p3", captureTime = 3_000, hash = "h3", contentHash = "c3", tags = listOf(0)),
        PhotoLinkDto(linkId = "p2", captureTime = 2_000, hash = "h2", contentHash = "c2", tags = emptyList()),
        PhotoLinkDto(linkId = "p1", captureTime = 2_000, hash = "h1", contentHash = "c1", tags = listOf(3, 9)),
    )
    private val facts = ownListing.associate {
        it.linkId to ListedPhotoFacts(it.hash, it.contentHash, it.tags.orEmpty(), it.captureTime)
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
        every { client.enumerateTimeline() } returns flowOf(
            *ownListing.map { PhotosTimelineItem(LegacyNodeUid("v", it.linkId), Instant.ofEpochSecond(it.captureTime!!)) }.toTypedArray(),
        )
    }

    private suspend fun viaSdk(withFacts: Map<String, ListedPhotoFacts> = facts) =
        timelineStubsOf(ops.timeline(userId, "v"), withFacts)

    @Test
    fun `the route starts switched off`() {
        assertFalse(SdkRoute.TIMELINE.defaultOn)
        assertFalse(routeSwitchedOn(null, SdkRoute.TIMELINE.defaultOn))
    }

    @Test
    fun `switched off, the app's own walk runs and the SDK is never asked`() = runTest {
        var sdkCalls = 0
        val result = runRouted<List<PhotoLinkDto>?>(label, on = false, viaSdk = { sdkCalls++; viaSdk() }, fallback = { null })
        assertNull(result)
        assertEquals(0, sdkCalls)
        verify(exactly = 0) { client.enumerateTimeline() }
        assertEquals(0, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `the SDK listing gives the app's own items in the app's own order with the same fields`() = runTest {
        val result = runRouted<List<PhotoLinkDto>?>(label, on = true, viaSdk = { viaSdk() }, fallback = { null })!!

        assertEquals(ownListing.map { it.linkId }, result.map { it.linkId })
        assertEquals(ownListing.map { it.captureTime }, result.map { it.captureTime })
        assertEquals(ownListing.map { it.contentHash }, result.map { it.contentHash })
        assertEquals(ownListing.map { it.hash }, result.map { it.hash })
        assertEquals(ownListing.map { it.tags }, result.map { it.tags })
        // The walk sorts the stubs newest first; equal capture times keep the listing's order either way.
        assertEquals(
            ownListing.sortedByDescending { it.captureTime ?: 0L }.map { it.linkId },
            result.sortedByDescending { it.captureTime ?: 0L }.map { it.linkId },
        )
        assertEquals(1, SdkDiagnostics.sdkCount(label))
    }

    @Test
    fun `a failed SDK listing falls back to the app's own walk`() = runTest {
        every { client.enumerateTimeline() } returns flowOf(PhotosTimelineItem(LegacyNodeUid("other", "p1"), Instant.ofEpochSecond(1)))

        assertNull(runRouted<List<PhotoLinkDto>?>(label, on = true, viaSdk = { viaSdk() }, fallback = { null }))
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `a photo whose link did not come back falls back to the app's own walk`() = runTest {
        val partial = facts - "p2"

        assertNull(runRouted<List<PhotoLinkDto>?>(label, on = true, viaSdk = { viaSdk(partial) }, fallback = { null }))
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }
}
