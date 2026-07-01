/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
 * is a 429 that carries a `Retry-After`, that server-specified wait (clamped to [maxBackoffMs]) is
 * honoured instead, so a rate-limited caller backs off exactly as long as the server asked rather
 * than hammering it sooner. The final attempt's failure is propagated.
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,
    baseMs: Long = 500,
    maxBackoffMs: Long = 8_000,
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
            val serverWait = e.retryAfterMsOrNull()?.coerceAtMost(maxBackoffMs)
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
    if (e is IOException) return true
    val apiError = (e as? ApiException)?.error
    if (apiError != null) {
        when (apiError) {
            is ApiResult.Error.Http ->
                if (apiError.httpCode == 429 || apiError.httpCode in 500..599) return true
            is ApiResult.Error.Connection,
            is ApiResult.Error.Timeout,
            is ApiResult.Error.NoInternet -> return true
            else -> Unit
        }
        if (apiError.cause is IOException) return true
    }
    val msg = e.message ?: ""
    return msg.contains("429") || msg.contains("503") || msg.contains("502") || msg.contains("504")
}

/**
 * Inspects an OkHttp [Response] for a `Retry-After` header (seconds) and returns the
 * suggested delay in milliseconds, or null if absent / unparsable.
 */
fun Response.retryAfterMs(): Long? =
    header("Retry-After")?.toLongOrNull()?.let { it * 1000L }
