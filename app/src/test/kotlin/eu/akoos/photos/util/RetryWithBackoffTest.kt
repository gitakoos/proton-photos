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

package eu.akoos.photos.util

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coverage for [isTransientApiError] (the shared retry predicate), the [retryWithBackoff] delay
 * math, and the [Response.retryAfterMs] header parser. The predicate is classified by EXCEPTION
 * TYPE — ProtonCore carries the HTTP status in [ApiResult.Error.Http.httpCode], never in the
 * exception message — so the tests build real [ApiException]/[ApiResult.Error] values rather than
 * fabricating message strings.
 *
 * The backoff growth/cap is asserted through [runTest]'s virtual clock: each retry's `delay(wait)`
 * advances virtual time, so `testScheduler.currentTime` after a fully-exhausted run equals the SUM of the
 * per-attempt waits. Jitter (0..baseMs) is bounded, so the assertions use ranges, not exact equals,
 * for the jittered path and exact equals for the capped tail where jitter is dwarfed by the cap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryWithBackoffTest {

    private fun httpError(code: Int) = ApiException(ApiResult.Error.Http(code, "http $code"))

    // ─── isTransientApiError: retryable HTTP statuses ─────────────────────────

    @Test
    fun `429 is transient`() {
        assertTrue(isTransientApiError(httpError(429)))
    }

    @Test
    fun `500 502 503 are transient`() {
        assertTrue(isTransientApiError(httpError(500)))
        assertTrue(isTransientApiError(httpError(502)))
        assertTrue(isTransientApiError(httpError(503)))
    }

    @Test
    fun `entire 5xx range is transient`() {
        // Boundary check: 500 and 599 both classify as transient, 499 does not.
        assertTrue(isTransientApiError(httpError(599)))
        assertFalse(isTransientApiError(httpError(499)))
    }

    // ─── isTransientApiError: non-retryable client errors ─────────────────────

    @Test
    fun `400 401 403 404 422 are NOT transient`() {
        assertFalse(isTransientApiError(httpError(400)))
        assertFalse(isTransientApiError(httpError(401)))
        assertFalse(isTransientApiError(httpError(403)))
        assertFalse(isTransientApiError(httpError(404)))
        assertFalse(isTransientApiError(httpError(422)))
    }

    // ─── isTransientApiError: network / connectivity ──────────────────────────

    @Test
    fun `raw IOException is transient`() {
        assertTrue(isTransientApiError(IOException("socket reset")))
    }

    @Test
    fun `SocketTimeoutException (an IOException subtype) is transient`() {
        assertTrue(isTransientApiError(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `Connection and Timeout ApiResult errors are transient`() {
        assertTrue(isTransientApiError(ApiException(ApiResult.Error.Connection(false))))
        assertTrue(isTransientApiError(ApiException(ApiResult.Error.Timeout(false))))
    }

    @Test
    fun `no-network errors are NOT transient so an offline device fails fast instead of spinning`() {
        // #100: a DNS-resolution failure (raw UnknownHostException) or an explicit NoInternet means
        // there is no route right now, not a transient server blip. Retrying in a tight backoff loop
        // across every concurrent caller only spins the radio and drains the battery (flagged on
        // aggressive OEMs); the network-constrained worker / content-observer re-arm resumes the work
        // once connectivity actually returns.
        assertFalse(isTransientApiError(UnknownHostException("Unable to resolve host: No address associated")))
        assertFalse(isTransientApiError(ApiException(ApiResult.Error.NoInternet())))
    }

    @Test
    fun `an http error wrapping UnknownHostException as its cause is NOT transient`() {
        // The wrapped-cause branch still retries a generic network IOException, but a wrapped
        // host-resolution failure is "no network", not a transient blip, so it fails fast.
        val wrapped = ApiException(ApiResult.Error.Http(404, "wrapped", cause = UnknownHostException("no address")))
        assertFalse(isTransientApiError(wrapped))
    }

    @Test
    fun `a Connection error caused by host resolution failure is NOT transient`() {
        // ProtonCore maps a raw UnknownHostException to Connection(cause = UHE), so this is the shape
        // an offline Proton API call actually throws. It must fail fast like a raw UnknownHostException,
        // not spin the backoff loop, while a plain Connection blip (no UHE cause) still retries.
        val offline = ApiException(ApiResult.Error.Connection(false, cause = UnknownHostException("no address")))
        assertFalse(isTransientApiError(offline))
        assertTrue(isTransientApiError(ApiException(ApiResult.Error.Connection(false))))
    }

    @Test
    fun `http error whose cause is an IOException is transient`() {
        // A 4xx that wraps a network IOException as its cause still retries: the cause check
        // catches the wrapped-network case the httpCode branch alone would reject.
        val wrapped = ApiException(ApiResult.Error.Http(404, "wrapped", cause = IOException("reset")))
        assertTrue(isTransientApiError(wrapped))
    }

    // ─── isTransientApiError: message-substring fallback ──────────────────────

    @Test
    fun `bare throwable with 429 in message falls back to transient`() {
        assertTrue(isTransientApiError(RuntimeException("HTTP 429 Too Many Requests")))
    }

    @Test
    fun `bare throwable with an unrelated message is not transient`() {
        assertFalse(isTransientApiError(IllegalStateException("decrypt failed")))
    }

    // ─── retryWithBackoff: attempt accounting ─────────────────────────────────

    @Test
    fun `block that succeeds first try runs exactly once`() = runTest {
        val calls = AtomicInteger(0)
        val result = retryWithBackoff(maxAttempts = 5) { calls.incrementAndGet(); "ok" }
        assertEquals("ok", result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `transient failures retry up to maxAttempts then rethrow`() = runTest {
        val calls = AtomicInteger(0)
        var thrown: Throwable? = null
        try {
            retryWithBackoff(maxAttempts = 4) { calls.incrementAndGet(); throw httpError(503) }
        } catch (e: Throwable) {
            thrown = e
        }
        // maxAttempts total invocations, then the final failure propagates.
        assertEquals(4, calls.get())
        assertTrue(thrown is ApiException)
    }

    @Test
    fun `non-transient failure is not retried`() = runTest {
        val calls = AtomicInteger(0)
        try {
            retryWithBackoff(maxAttempts = 5) { calls.incrementAndGet(); throw httpError(400) }
        } catch (_: Throwable) {
        }
        // A 400 can't be fixed by retrying, so the loop throws on the first attempt.
        assertEquals(1, calls.get())
    }

    @Test
    fun `custom shouldRetry can force a retry of an otherwise-permanent error`() = runTest {
        val calls = AtomicInteger(0)
        try {
            retryWithBackoff(maxAttempts = 3, shouldRetry = { it is IllegalArgumentException }) {
                calls.incrementAndGet(); throw IllegalArgumentException("transient-by-policy")
            }
        } catch (_: Throwable) {
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `eventual success after transient failures stops retrying`() = runTest {
        val calls = AtomicInteger(0)
        val result = retryWithBackoff(maxAttempts = 5) {
            if (calls.incrementAndGet() < 3) throw httpError(500) else "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, calls.get())
    }

    // ─── retryWithBackoff: delay growth + cap ─────────────────────────────────

    @Test
    fun `backoff between attempts grows exponentially from baseMs`() = runTest {
        // 3 attempts → 2 waits, at attempts 0 and 1: base*2^0 + j0, base*2^1 + j1.
        // jitter j in [0, base). With base=1000 the waits sum to [3000, 5000).
        val base = 1000L
        val start = testScheduler.currentTime
        try {
            retryWithBackoff(maxAttempts = 3, baseMs = base, maxBackoffMs = 1_000_000L) {
                throw httpError(503)
            }
        } catch (_: Throwable) {
        }
        val elapsed = testScheduler.currentTime - start
        // Lower bound = sum of the un-jittered exponential terms (1000 + 2000).
        assertTrue("elapsed=$elapsed should be >= 3000", elapsed >= 3000L)
        // Upper bound = same + 2 jitter slots, each < base.
        assertTrue("elapsed=$elapsed should be < 5000", elapsed < 5000L)
    }

    @Test
    fun `backoff is capped at maxBackoffMs`() = runTest {
        // With base=1000 and a cap of 1500 the exponential saturates from the SECOND wait on: the
        // first term is `base shl 0` = 1000, still under the cap. The floor is therefore the sum of
        // the capped terms themselves, not cap * waits, which would assume a saturation the first
        // wait never reaches and would then rest on the random jitter to make up the difference.
        val base = 1000L
        val cap = 1500L
        val attempts = 6 // → 5 waits
        val start = testScheduler.currentTime
        try {
            retryWithBackoff(maxAttempts = attempts, baseMs = base, maxBackoffMs = cap) {
                throw httpError(503)
            }
        } catch (_: Throwable) {
        }
        val elapsed = testScheduler.currentTime - start
        val waits = attempts - 1
        val flooredSum = (0 until waits).sumOf { minOf(base shl it, cap) }
        assertTrue("elapsed=$elapsed should be >= $flooredSum", elapsed >= flooredSum)
        // Jitter adds 0..base on top of every wait, so the whole run stays under the floor plus
        // one base per wait however the draws land.
        assertTrue("elapsed=$elapsed should be < ${flooredSum + base * waits}", elapsed < flooredSum + base * waits)
    }

    @Test
    fun `server Retry-After overrides the computed backoff and is clamped to its own cap`() = runTest {
        // A 429 carrying Retry-After=5000ms with a server cap of 2000ms must wait the SERVER CAP
        // (clamped to maxServerRetryAfterMs, independent of the generic maxBackoffMs), not the
        // computed exponential, for each of the 2 waits in a 3-attempt run.
        val retryAfter = ApiException(
            ApiResult.Error.Http(429, "rate limited", retryAfter = 5_000.milliseconds),
        )
        val cap = 2_000L
        val start = testScheduler.currentTime
        try {
            retryWithBackoff(maxAttempts = 3, baseMs = 100L, maxServerRetryAfterMs = cap) { throw retryAfter }
        } catch (_: Throwable) {
        }
        val elapsed = testScheduler.currentTime - start
        // 2 waits, each clamped to the 2000ms server cap, with no jitter on the server-wait path → exactly 4000.
        assertEquals(2 * cap, elapsed)
    }

    @Test
    fun `server Retry-After is honoured up to the 60s default when under the cap`() = runTest {
        // A 429 carrying Retry-After=20000ms is honoured in full under the default 60s server cap,
        // even though it exceeds the generic 8s maxBackoffMs, so the app waits the full server
        // window instead of resuming early and re-triggering the limit.
        val retryAfter = ApiException(
            ApiResult.Error.Http(429, "rate limited", retryAfter = 20_000.milliseconds),
        )
        val start = testScheduler.currentTime
        try {
            retryWithBackoff(maxAttempts = 2, baseMs = 100L) { throw retryAfter }
        } catch (_: Throwable) {
        }
        val elapsed = testScheduler.currentTime - start
        // 1 wait, honoured in full (20000ms) because it is below the 60s default server cap.
        assertEquals(20_000L, elapsed)
    }

    // ─── Response.retryAfterMs ────────────────────────────────────────────────

    @Test
    fun `retryAfterMs parses the header seconds into milliseconds`() {
        val response = mockk<Response>()
        every { response.header("Retry-After") } returns "30"
        assertEquals(30_000L, response.retryAfterMs())
    }

    @Test
    fun `retryAfterMs is null when the header is absent or unparsable`() {
        val absent = mockk<Response>()
        every { absent.header("Retry-After") } returns null
        assertNull(absent.retryAfterMs())

        val httpDate = mockk<Response>()
        // An HTTP-date Retry-After (not a plain seconds count) isn't parsed by this helper.
        every { httpDate.header("Retry-After") } returns "Wed, 21 Oct 2026 07:28:00 GMT"
        assertNull(httpDate.retryAfterMs())
    }
}
