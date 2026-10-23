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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.drive.sdk.entity.LegacyNodeUid
import me.proton.drive.sdk.entity.PhotoTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A route switched on must never lose an operation: an SDK call that fails runs the app's own path, a
 * route switched off never touches the SDK, and only cancellation or a fatal VM error passes through.
 */
class SdkRoutingTest {

    @Before
    fun reset() = SdkDiagnostics.clear()

    @Test
    fun `a route switched off runs only the app's own path`() = runTest {
        var sdkCalls = 0
        val result = runRouted("tags", on = false, viaSdk = { sdkCalls++; "sdk" }, fallback = { "own" })
        assertEquals("own", result)
        assertEquals(0, sdkCalls)
        assertEquals(0, SdkDiagnostics.sdkCount("tags"))
    }

    @Test
    fun `a route switched on runs the SDK and counts it`() = runTest {
        var fallbackCalls = 0
        val result = runRouted("tags", on = true, viaSdk = { "sdk" }, fallback = { fallbackCalls++; "own" })
        assertEquals("sdk", result)
        assertEquals(0, fallbackCalls)
        assertEquals(1, SdkDiagnostics.sdkCount("tags"))
        assertEquals(0, SdkDiagnostics.fallbackCount("tags"))
    }

    @Test
    fun `a failed SDK call falls back to the app's own path and is counted`() = runTest {
        val result = runRouted<String>("tags", on = true, viaSdk = { error("native failure") }, fallback = { "own" })
        assertEquals("own", result)
        assertEquals(0, SdkDiagnostics.sdkCount("tags"))
        assertEquals(1, SdkDiagnostics.fallbackCount("tags"))
        assertTrue(SdkDiagnostics.snapshot().contains("lastFallback=tags IllegalStateException"))
    }

    @Test
    fun `a failure of the app's own path after a fallback still reaches the caller`() = runTest {
        try {
            runRouted<String>("tags", on = true, viaSdk = { error("sdk") }, fallback = { throw java.io.IOException("offline") })
            fail("the fallback's failure must reach the caller")
        } catch (e: java.io.IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test
    fun `cancellation is not a failure and does not fall back`() = runTest {
        var fallbackCalls = 0
        try {
            runRouted<String>("tags", on = true, viaSdk = { throw CancellationException("stop") }, fallback = { fallbackCalls++; "own" })
            fail("cancellation must pass through")
        } catch (e: CancellationException) {
            assertEquals(0, fallbackCalls)
        }
        assertEquals(0, SdkDiagnostics.fallbackCount("tags"))
    }

    @Test
    fun `a link the server no longer holds counts as trashed, any other refusal does not`() {
        assertTrue(linkAlreadyGone(2501L, null))
        assertTrue(linkAlreadyGone(null, 2501L))
        assertEquals(false, linkAlreadyGone(2511L, null))
        assertEquals(false, linkAlreadyGone(null, null))
    }

    @Test
    fun `an SDK download stands only with bytes, and as many as Drive says when it says`() {
        assertTrue(sdkDownloadComplete(actualBytes = 1234, expectedBytes = 1234))
        assertTrue(sdkDownloadComplete(actualBytes = 1234, expectedBytes = 0))
        assertEquals(false, sdkDownloadComplete(actualBytes = 1000, expectedBytes = 1234))
        assertEquals(false, sdkDownloadComplete(actualBytes = 0, expectedBytes = 0))
    }

    @Test
    fun `every Drive tag id maps to the SDK tag of the same id, added or removed`() {
        val expected = listOf(
            PhotoTag.Favorite, PhotoTag.Screenshot, PhotoTag.Video, PhotoTag.LivePhoto, PhotoTag.MotionPhoto,
            PhotoTag.Selfie, PhotoTag.Portrait, PhotoTag.Burst, PhotoTag.Panorama, PhotoTag.Raw,
        )
        for (id in 0..9) {
            val add = photoTagsUpdate("vol", "link", id, add = true)
            assertEquals(listOf(expected[id]), add.tagsToAdd)
            assertTrue(add.tagsToRemove.isEmpty())
            val remove = photoTagsUpdate("vol", "link", id, add = false)
            assertEquals(listOf(expected[id]), remove.tagsToRemove)
            assertTrue(remove.tagsToAdd.isEmpty())
            val uid = add.nodeUid as LegacyNodeUid
            assertEquals("vol", uid.volumeId)
            assertEquals("link", uid.linkId)
        }
    }

    @Test
    fun `an SDK outcome that cannot be confirmed never runs the app's own path`() = runTest {
        var fallbackCalls = 0
        try {
            runRouted<String>(
                "upload",
                on = true,
                viaSdk = { throw SdkOutcomeUnknownException("unknown", null) },
                fallback = { fallbackCalls++; "own" },
            )
            fail("expected the unknown outcome to pass through")
        } catch (e: SdkOutcomeUnknownException) {
            assertEquals(0, fallbackCalls)
            assertEquals(1, SdkDiagnostics.fallbackCount("upload"))
        }
    }
}
