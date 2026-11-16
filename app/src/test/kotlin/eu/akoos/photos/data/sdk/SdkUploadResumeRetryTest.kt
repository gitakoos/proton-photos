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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.proton.drive.sdk.UploadAbortedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Pins [awaitResumingPauses] (a paused SDK upload is resumed, never started over) and [uploadRetryingAbort]
 * (an aborted SDK upload with nothing committed starts once more before the app's own upload takes over).
 */
class SdkUploadResumeRetryTest {

    @After
    fun reset() = SdkDiagnostics.clear()

    private val photo = SdkUploadResult("link", "rev")

    @Test
    fun `an upload paused on a recoverable error is resumed and completes`() = runTest {
        var awaits = 0
        val events = mutableListOf<String>()
        val waits = mutableListOf<Long>()

        val result = awaitResumingPauses(
            awaitCompletion = { if (awaits++ < 2) throw IOException("connection failed") else "done" },
            isPaused = { true },
            resume = { events += "resume" },
            stopped = { false },
            waitBeforeResume = { waits += it },
        )

        assertEquals("done", result)
        assertEquals(listOf("resume", "resume"), events)
        assertEquals(listOf(sdkResumeBackoffMs(1), sdkResumeBackoffMs(2)), waits)
    }

    @Test
    fun `an upload still paused after its resumes throws its last error`() = runTest {
        var resumes = 0
        val last = IOException("still offline")
        try {
            awaitResumingPauses<String>(
                awaitCompletion = { throw if (resumes == MAX_SDK_UPLOAD_RESUMES) last else IOException("offline") },
                isPaused = { true },
                resume = { resumes++ },
                stopped = { false },
                waitBeforeResume = {},
            )
            fail("the last error must be thrown")
        } catch (e: IOException) {
            assertSame(last, e)
        }
        assertEquals(MAX_SDK_UPLOAD_RESUMES, resumes)
    }

    @Test
    fun `an aborted or unpaused upload is never resumed`() = runTest {
        var resumes = 0
        val aborted = UploadAbortedException(IOException("storage 400"))
        try {
            awaitResumingPauses<String>({ throw aborted }, { true }, { resumes++ }, { false }, {})
            fail("an abort must be thrown")
        } catch (e: UploadAbortedException) {
            assertSame(aborted, e)
        }
        try {
            awaitResumingPauses<String>({ throw IOException("x") }, { false }, { resumes++ }, { false }, {})
            fail("an unpaused failure must be thrown")
        } catch (e: IOException) {
            assertEquals("x", e.message)
        }
        assertEquals(0, resumes)
    }

    @Test
    fun `a stop during the wait before a resume ends the upload without resuming it`() = runTest {
        var stopped = false
        var resumes = 0
        val paused = IOException("connection failed")
        try {
            awaitResumingPauses<String>(
                awaitCompletion = { throw paused },
                isPaused = { true },
                resume = { resumes++ },
                stopped = { stopped },
                waitBeforeResume = { stopped = true },
            )
            fail("the stopped upload must end")
        } catch (e: IOException) {
            // The caller turns this into a cancel because the stop came first.
            assertSame(paused, e)
        }
        assertEquals(0, resumes)
    }

    @Test
    fun `a cancel during the wait before a resume passes through`() = runTest {
        var resumes = 0
        val gate = CompletableDeferred<Unit>()
        val run = async {
            awaitResumingPauses<String>(
                awaitCompletion = { throw IOException("connection failed") },
                isPaused = { true },
                resume = { resumes++ },
                stopped = { false },
                waitBeforeResume = { gate.await() },
            )
        }
        runCurrent()
        run.cancel()
        try {
            run.await()
            fail("cancellation must pass through")
        } catch (e: CancellationException) {
            assertEquals(0, resumes)
        }
    }

    @Test
    fun `an aborted upload with nothing committed starts once more and its retry stands`() = runTest {
        var attempts = 0
        val result = uploadRetryingAbort(
            upload = { if (attempts++ == 0) throw UploadAbortedException(IOException("storage 400")) else photo },
            afterFailure = { SdkUploadAfterFailure.NothingCommitted },
        )
        assertSame(photo, result)
        assertEquals(2, attempts)
    }

    @Test
    fun `an upload aborted again after its retry fails for the app's own upload to take over`() = runTest {
        var attempts = 0
        var ownPath = 0
        val second = UploadAbortedException(IOException("storage 400 again"))
        val routed = runRouted(
            "upload",
            on = true,
            viaSdk = {
                uploadRetryingAbort(
                    upload = { throw if (attempts++ == 0) UploadAbortedException(IOException("storage 400")) else second },
                    afterFailure = { SdkUploadAfterFailure.NothingCommitted },
                )
            },
            fallback = { ownPath++; SdkUploadResult("own", "own") },
        )
        assertEquals("own", routed.linkId)
        assertEquals(2, attempts)
        assertEquals(1, ownPath)
        // The two SDK attempts are one fallback, as before.
        assertEquals(1, SdkDiagnostics.fallbackCount("upload"))
        assertEquals(0, SdkDiagnostics.sdkCount("upload"))
    }

    @Test
    fun `a failure that is not an abort is not retried`() = runTest {
        var attempts = 0
        try {
            uploadRetryingAbort(
                upload = { attempts++; throw IOException("still offline") },
                afterFailure = { SdkUploadAfterFailure.NothingCommitted },
            )
            fail("the failure must be thrown")
        } catch (e: IOException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `an abort whose commit went through or cannot be told is never started again`() = runTest {
        var attempts = 0
        val committed = uploadRetryingAbort(
            upload = { attempts++; throw UploadAbortedException(IOException("late")) },
            afterFailure = { SdkUploadAfterFailure.Committed(photo) },
        )
        assertSame(photo, committed)
        try {
            uploadRetryingAbort(
                upload = { attempts++; throw UploadAbortedException(IOException("late")) },
                afterFailure = { SdkUploadAfterFailure.Unknown },
            )
            fail("an unknown outcome must not upload again")
        } catch (e: SdkOutcomeUnknownException) {
            assertTrue(e.cause is UploadAbortedException)
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `a cancel during the wait before the retry stops it`() = runTest {
        var attempts = 0
        val run = async {
            uploadRetryingAbort(
                upload = { attempts++; throw UploadAbortedException(IOException("storage 400")) },
                afterFailure = { SdkUploadAfterFailure.NothingCommitted },
            )
        }
        advanceTimeBy(SDK_ABORT_RETRY_DELAY_MS / 2)
        run.cancel()
        try {
            run.await()
            fail("cancellation must pass through")
        } catch (e: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `a cancel of the retried upload itself passes through without a fallback`() = runTest {
        var attempts = 0
        var ownPath = 0
        try {
            runRouted(
                "upload",
                on = true,
                viaSdk = {
                    uploadRetryingAbort(
                        upload = {
                            if (attempts++ == 0) throw UploadAbortedException(IOException("storage 400"))
                            throw CancellationException("upload cancelled by the user")
                        },
                        afterFailure = { SdkUploadAfterFailure.NothingCommitted },
                    )
                },
                fallback = { ownPath++; photo },
            )
            fail("the cancel must pass through")
        } catch (e: CancellationException) {
            assertEquals(2, attempts)
            assertEquals(0, ownPath)
            assertEquals(0, SdkDiagnostics.fallbackCount("upload"))
        }
    }
}
