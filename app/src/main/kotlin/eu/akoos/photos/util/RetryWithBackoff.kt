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
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

private const val TAG = "RetryBackoff"

/**
 * Runs [block] up to [maxAttempts] times with jittered exponential backoff between attempts.
 *
 * Retries are triggered by:
 *   • [IOException] (network failure, socket reset, DNS error)
 *   • Any exception whose message contains "429", "503", "502", "504"
 *   • A custom [shouldRetry] predicate evaluated on the thrown exception
 *
 * Backoff: `min(baseMs * 2^attempt, maxBackoffMs)` + random 0..baseMs jitter.
 * The final attempt's failure is propagated.
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
            val transient = e is IOException ||
                msg.contains("429") || msg.contains("503") ||
                msg.contains("502") || msg.contains("504") ||
                shouldRetry(e)
            if (!transient || attempt == maxAttempts - 1) throw e
            val backoff = minOf(baseMs shl attempt, maxBackoffMs)
            val jitter = Random.nextLong(0, baseMs)
            Log.w(TAG, "attempt ${attempt + 1}/$maxAttempts failed (${e.javaClass.simpleName}: $msg), retrying in ${backoff + jitter}ms")
            delay(backoff + jitter)
        }
    }
    throw lastError ?: IllegalStateException("retryWithBackoff exhausted")
}

/**
 * Inspects an OkHttp [Response] for a `Retry-After` header (seconds) and returns the
 * suggested delay in milliseconds, or null if absent / unparsable.
 */
fun Response.retryAfterMs(): Long? =
    header("Retry-After")?.toLongOrNull()?.let { it * 1000L }
