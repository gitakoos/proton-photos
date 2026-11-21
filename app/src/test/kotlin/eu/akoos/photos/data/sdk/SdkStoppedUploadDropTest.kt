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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [dropStoppedSdkUpload]: the SDK handles of a stopped upload are freed only after the SDK finished
 * dropping it, never while that still runs, even when the caller stops waiting.
 */
class SdkStoppedUploadDropTest {

    @Test
    fun `a slow drop keeps running in the background and the handles are freed only after it`() = runTest {
        val events = mutableListOf<String>()
        val background = CoroutineScope(coroutineContext)
        val outcome = dropStoppedSdkUpload(
            cancelAndDispose = {
                delay(60_000)
                events += "disposed"
            },
            close = { events += "closed" },
            launchHolding = { work -> background.launch { work() } },
            waitMs = 10_000,
        )
        assertEquals(StoppedUploadDrop.STILL_RUNNING, outcome)
        assertTrue("nothing freed while the SDK still works", events.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("disposed", "closed"), events)
    }

    @Test
    fun `a quick drop answers dropped and frees the handles after it`() = runTest {
        val events = mutableListOf<String>()
        val background = CoroutineScope(coroutineContext)
        val outcome = dropStoppedSdkUpload(
            cancelAndDispose = { events += "disposed" },
            close = { events += "closed" },
            launchHolding = { work -> background.launch { work() } },
            waitMs = 10_000,
        )
        advanceUntilIdle()
        assertEquals(StoppedUploadDrop.DROPPED, outcome)
        assertEquals(listOf("disposed", "closed"), events)
    }

    @Test
    fun `with no background available the drop runs to its end before the handles are freed`() = runTest {
        val events = mutableListOf<String>()
        val outcome = dropStoppedSdkUpload(
            cancelAndDispose = {
                delay(30_000)
                events += "disposed"
            },
            close = { events += "closed" },
            launchHolding = { null },
            waitMs = 10_000,
        )
        assertEquals(StoppedUploadDrop.DROPPED, outcome)
        assertEquals(listOf("disposed", "closed"), events)
    }

    @Test
    fun `a failed drop still frees the handles and says so`() = runTest {
        var closed = false
        val outcome = dropStoppedSdkUpload(
            cancelAndDispose = { error("dispose failed") },
            close = { closed = true },
            launchHolding = { null },
            waitMs = 10_000,
        )
        assertEquals(StoppedUploadDrop.FAILED, outcome)
        assertTrue(closed)
        assertFalse(outcome == StoppedUploadDrop.DROPPED)
    }

    private val earlier = SdkUploadResult("old", "r0")
    private val made = SdkUploadResult("new", "r1")

    @Test
    fun `a failed upload with nothing committed drops its draft`() = runTest {
        var dropped = 0
        var kept = 0
        val outcome = dropFailedSdkUpload(
            before = listOf(earlier), findAfter = { listOf(earlier) }, lookupMs = 1_000,
            drop = { dropped++ }, keep = { kept++ },
        )
        assertEquals(null, outcome)
        assertEquals(1, dropped)
        assertEquals(0, kept)
    }

    @Test
    fun `a failed upload whose commit went through keeps its draft`() = runTest {
        var dropped = 0
        var kept = 0
        val outcome = dropFailedSdkUpload(
            before = emptyList(), findAfter = { listOf(made) }, lookupMs = 1_000,
            drop = { dropped++ }, keep = { kept++ },
        )
        assertEquals(SdkUploadAfterFailure.Committed(made), outcome)
        assertEquals(0, dropped)
        assertEquals(1, kept)
    }

    @Test
    fun `a failed upload whose commit cannot be told keeps its draft`() = runTest {
        var dropped = 0
        var kept = 0
        val failedLookup = dropFailedSdkUpload(
            before = emptyList(), findAfter = { throw java.io.IOException("offline") }, lookupMs = 1_000,
            drop = { dropped++ }, keep = { kept++ },
        )
        val slowLookup = dropFailedSdkUpload(
            before = emptyList(), findAfter = { delay(5_000); emptyList() }, lookupMs = 1_000,
            drop = { dropped++ }, keep = { kept++ },
        )
        assertEquals(SdkUploadAfterFailure.Unknown, failedLookup)
        assertEquals(SdkUploadAfterFailure.Unknown, slowLookup)
        assertEquals(0, dropped)
        assertEquals(2, kept)
    }
}
