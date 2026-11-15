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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The feed of photos this user shares, through the SDK or the app's own walk. */
class SdkSharedByMeRouteTest {

    private val userId = UserId("u")
    private val label = SdkRoute.SHARED_BY_ME.label
    private lateinit var client: ProtonPhotosClient
    private lateinit var provider: SdkDriveClientProvider

    @Before
    fun setUp() {
        SdkDiagnostics.clear()
        client = mockk()
        provider = mockk()
        coEvery { provider.withPhotosClient<Any?>(userId, any()) } coAnswers {
            secondArg<suspend (ProtonPhotosClient) -> Any?>().invoke(client)
        }
    }

    private fun uid(linkId: String, volume: String = "v") = LegacyNodeUid(volumeId = volume, linkId = linkId)

    @Test
    fun `the route starts switched off`() {
        assertFalse(SdkRoute.SHARED_BY_ME.defaultOn)
        assertFalse(routeSwitchedOn(null, SdkRoute.SHARED_BY_ME.defaultOn))
    }

    @Test
    fun `the SDK feed gives the link ids in the server's order`() = runTest {
        every { client.enumerateSharedNodeUids() } returns flowOf(uid("b"), uid("a"), uid("c"))

        assertEquals(listOf("b", "a", "c"), SdkSharedOps(provider).sharedByMeLinkIds(userId, "v"))
    }

    @Test
    fun `an item on another volume fails the whole SDK feed`() = runTest {
        every { client.enumerateSharedNodeUids() } returns flowOf(uid("a"), uid("b", volume = "other"))
        try {
            SdkSharedOps(provider).sharedByMeLinkIds(userId, "v")
            fail("expected the feed to fail as a whole")
        } catch (_: SdkSharedException) {
        }
    }

    @Test
    fun `switched off, only the app's own walk runs and its answer comes back unchanged`() = runTest {
        val own = listOf("x", "y")
        var sdkCalls = 0
        val result = runRouted(label, on = false, viaSdk = { sdkCalls++; listOf("sdk") }, fallback = { own })
        assertSame(own, result)
        assertEquals(0, sdkCalls)
        assertEquals(0, SdkDiagnostics.sdkCount(label))
        assertEquals(0, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `switched off, a failed own walk still means no feed`() = runTest {
        assertNull(runRouted<List<String>?>(label, on = false, viaSdk = { listOf("sdk") }, fallback = { null }))
    }

    @Test
    fun `a failed SDK feed falls back to the app's own walk`() = runTest {
        every { client.enumerateSharedNodeUids() } returns flowOf(uid("a", volume = "other"))
        val ops = SdkSharedOps(provider)

        val result = runRouted(label, on = true, viaSdk = { ops.sharedByMeLinkIds(userId, "v") }, fallback = { listOf("own") })

        assertEquals(listOf("own"), result)
        assertEquals(1, SdkDiagnostics.fallbackCount(label))
    }

    @Test
    fun `a working SDK feed never runs the app's own walk`() = runTest {
        every { client.enumerateSharedNodeUids() } returns flowOf(uid("a"))
        val ops = SdkSharedOps(provider)
        var ownCalls = 0

        val result = runRouted(label, on = true, viaSdk = { ops.sharedByMeLinkIds(userId, "v") }, fallback = { ownCalls++; null })

        assertEquals(listOf("a"), result)
        assertEquals(0, ownCalls)
        assertEquals(1, SdkDiagnostics.sdkCount(label))
        verify(exactly = 1) { client.enumerateSharedNodeUids() }
    }
}
