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

package eu.akoos.photos.domain.usecase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A video hands its upload slot back while it transcodes ([releasedWhile]) so the photos behind it keep
 * uploading, and takes the slot back before its own upload. The slot must come back whatever ends the
 * transcode, a failure or a cancel included: the enclosing `withPermit` releases one on the way out, so a
 * slot not taken back would be released twice and the batch would run one upload too many.
 */
class SemaphoreReleasedWhileTest {

    @Test
    fun `the slot is free while the block runs and held again after it`() = runTest {
        val slots = Semaphore(2)
        slots.withPermit {
            assertEquals(1, slots.availablePermits)
            val result = slots.releasedWhile {
                assertEquals(2, slots.availablePermits)
                "transcoded"
            }
            assertEquals("transcoded", result)
            assertEquals(1, slots.availablePermits)
        }
        assertEquals(2, slots.availablePermits)
    }

    @Test
    fun `another upload can take the slot while the block runs`() = runTest {
        val slots = Semaphore(1)
        val transcode = CompletableDeferred<Unit>()
        val photoUploaded = CompletableDeferred<Unit>()
        val video = launch { slots.withPermit { slots.releasedWhile { transcode.await() } } }
        runCurrent()
        val photo = launch { slots.withPermit { photoUploaded.complete(Unit) } }
        runCurrent()
        assertTrue(photoUploaded.isCompleted)
        transcode.complete(Unit)
        video.join()
        photo.join()
        assertEquals(1, slots.availablePermits)
    }

    @Test
    fun `a failing block still takes the slot back`() = runTest {
        val slots = Semaphore(1)
        val failure = runCatching {
            slots.withPermit {
                slots.releasedWhile { error("transcode failed") }
            }
        }.exceptionOrNull()
        assertEquals("transcode failed", failure?.message)
        assertEquals(1, slots.availablePermits)
    }

    @Test
    fun `a cancelled block waits to take the slot back before it lets go`() = runTest {
        val slots = Semaphore(1)
        val video = launch { slots.withPermit { slots.releasedWhile { awaitCancellation() } } }
        runCurrent()
        // A photo takes the slot the video handed back, and the video is cancelled mid-transcode.
        slots.acquire()
        video.cancel()
        runCurrent()
        // The video cannot finish before it holds a slot again, or its withPermit would hand back one it
        // does not hold and two uploads would run on one slot.
        assertFalse(video.isCompleted)
        assertEquals(0, slots.availablePermits)
        slots.release()
        video.join()
        assertTrue(video.isCancelled)
        assertEquals(1, slots.availablePermits)
    }
}
