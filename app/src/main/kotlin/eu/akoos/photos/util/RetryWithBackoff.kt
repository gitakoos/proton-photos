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

import android.util.Log
import kotlinx.coroutines.delay
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import kotlin.random.Random

private const val TAG = "RetryBackoff"

/**
 * Runs [block] up to [maxAttempts] times with jittered exponential backoff between attempts.
 *
 * Retries are triggered by:
 *   • [isTransientApiError] (network [IOException], a 429 / 5xx [me.proton.core.network.domain.ApiException], a connectivity error)
 *   • A custom [shouldRetry] predicate evaluated on the thrown exception
 *
 * Backoff: `min(baseMs * 2^attempt, maxBackoffMs)` + random 0..baseMs jitter, but when the failure
 * is a 429 / 5xx that carries a `Retry-After`, that server-specified wait (clamped to
 * [maxServerRetryAfterMs], not the shorter generic [maxBackoffMs]) is honoured instead, so a
 * rate-limited caller waits the full window the server asked for rather than resuming early and
 * re-triggering the limit. The final attempt's failure is propagated.
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,
    baseMs: Long = 500,
    maxBackoffMs: Long = 8_000,
    maxServerRetryAfterMs: Long = 60_000,
    shouldRetry: (Throwable) -> Boolean = { _ -> false },
    block: suspend (attempt: Int) -> T,
): T {
    var lastError: Throwable? = null
    for (attempt in 0 until maxAttempts) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            lastError = e
            val msg = e.message ?: ""
            val transient = isTransientApiError(e) || shouldRetry(e)
            if (!transient || attempt == maxAttempts - 1) throw e
            val expBackoff = minOf(baseMs shl attempt, maxBackoffMs)
            val jitter = Random.nextLong(0, baseMs)
            // A server Retry-After is honoured up to maxServerRetryAfterMs (60s) rather than the
            // shorter generic maxBackoffMs, so the app waits the full server-requested window; the
            // cap only defends against a hostile / absurd value. The generic exponential path keeps
            // maxBackoffMs.
            val serverWait = e.retryAfterMsOrNull()?.coerceAtMost(maxServerRetryAfterMs)
            val wait = serverWait ?: (expBackoff + jitter)
            Log.w(TAG, "attempt ${attempt + 1}/$maxAttempts failed (${e.javaClass.simpleName}: $msg), retrying in ${wait}ms")
            // Survives the release log strip so a rate-limited / flaky upload shows the real cause
            // (error type + any server Retry-After) and the backoff cost in the in-app diagnostics.
            val httpCode = ((e as? ApiException)?.error as? ApiResult.Error.Http)?.httpCode
            SyncDiagnostics.log(
                "retry ${attempt + 1}/$maxAttempts ${e.javaClass.simpleName}" +
                    (httpCode?.let { " http=$it" } ?: "") +
                    (serverWait?.let { " (server Retry-After ${it}ms)" } ?: "") +
                    " -> backoff ${wait}ms" +
                    (msg.take(70).let { if (it.isNotBlank()) " [$it]" else "" })
            )
            delay(wait)
        }
    }
    throw lastError ?: IllegalStateException("retryWithBackoff exhausted")
}

/**
 * The `Retry-After` a ProtonCore 429 [ApiException] carries (in [ApiResult.Error.Http.retryAfter],
 * a [kotlin.time.Duration]), in milliseconds, or null when absent / not an HTTP error. Lets the
 * backoff respect a server-specified wait instead of guessing.
 */
private fun Throwable.retryAfterMsOrNull(): Long? {
    val http = (this as? ApiException)?.error as? ApiResult.Error.Http ?: return null
    val ms = http.retryAfter?.inWholeMilliseconds ?: return null
    return if (ms > 0L) ms else null
}

/**
 * Whether [e] is a transient API failure worth retrying. Shared by [retryWithBackoff] and callers
 * running their own slower outer backoff (e.g. resuming a rate-limited large-library listing).
 *
 * Classified by TYPE, not by message text: ProtonCore surfaces an HTTP failure as an
 * [ApiException] whose [ApiException.error] is [ApiResult.Error.Http] carrying the status in the
 * separate [ApiResult.Error.Http.httpCode] field — the status never appears in `message`, so the
 * old substring check missed every server-issued 429 / 5xx. Treated as transient:
 *   • [ApiResult.Error.Http] with httpCode 429 (rate limit) or any 5xx (server-side, retryable)
 *   • the connectivity [ApiResult.Error] variants (Connection / Timeout / NoInternet)
 *   • a network [IOException] (raw, or wrapped as the ApiException error's cause)
 * Non-transient 4xx (e.g. 400 / 403 / 404 / 422) are NOT retried — a retry can't fix them.
 *
 * The message-substring fallback is kept last so any wrapper shape we don't recognise by type
 * still behaves as it did before.
 */
fun isTransientApiError(e: Throwable): Boolean {
    // A host-resolution failure (UnknownHostException) or an explicit no-internet result means there
    // is no network route right now, not a transient server blip. Retrying in a tight backoff loop
    // cannot fix it and just spins the radio and CPU across every concurrent caller (a background
    // battery drain flagged on aggressive OEMs), so it is non-transient: fail fast and let the
    // network-constrained worker or the content-observer re-arm resume once connectivity returns.
    // Genuinely transient server errors (429 / 5xx / timeout / connection reset) still retry.
    if (e is UnknownHostException) return false
    if (e is IOException) return true
    val apiError = (e as? ApiException)?.error
    if (apiError != null) {
        when (apiError) {
            is ApiResult.Error.Http ->
                if (apiError.httpCode == 429 || apiError.httpCode in 500..599) return true
            // NoInternet is a SUBTYPE of Connection, so it must be matched first: an explicit
            // "no internet" is offline and fails fast, while a generic Connection blip can recover.
            is ApiResult.Error.NoInternet -> return false
            // ProtonCore wraps a host-resolution failure as Connection(cause = UnknownHostException):
            // that is "no network route right now", not a transient blip, so fail fast. A plain
            // connection reset (any other cause) can still recover, so retry that.
            is ApiResult.Error.Connection -> return apiError.cause !is UnknownHostException
            is ApiResult.Error.Timeout -> return true
            else -> Unit
        }
        val cause = apiError.cause
        if (cause is IOException && cause !is UnknownHostException) return true
    }
    val msg = e.message ?: ""
    return msg.contains("429") || msg.contains("503") || msg.contains("502") || msg.contains("504")
}

/**
 * Whether [e] is a "there is no network route right now" failure rather than a server refusal: a
 * host-resolution failure, or ProtonCore's explicit no-internet / offline connection result. These
 * are the cases [isTransientApiError] deliberately reports as non-transient, so a retry loop stops
 * instead of spinning the radio while offline.
 *
 * A caller that has to tell "the server refused this" from "the phone is offline" needs them back
 * apart: being offline is evidence about the connection, not about whatever the request was doing,
 * so it must not be read as a refusal (dropping an event anchor over a lost signal forces a full
 * library re-walk once connectivity returns).
 */
fun isOfflineError(e: Throwable): Boolean {
    if (e is UnknownHostException) return true
    val apiError = (e as? ApiException)?.error ?: return false
    return when (apiError) {
        // NoInternet is a subtype of Connection, so match it first.
        is ApiResult.Error.NoInternet -> true
        is ApiResult.Error.Connection -> apiError.cause is UnknownHostException
        else -> false
    }
}

/**
 * Inspects an OkHttp [Response] for a `Retry-After` header (seconds) and returns the
 * suggested delay in milliseconds, or null if absent / unparsable.
 */
fun Response.retryAfterMs(): Long? =
    header("Retry-After")?.toLongOrNull()?.let { it * 1000L }
