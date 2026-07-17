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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * Keeps a Room-backed [Flow] alive across a transient torn read. A concurrent write can fault a
 * cursor window mid-read (an `IllegalStateException` from `CursorWindow.nativeGetString`), which
 * would otherwise reach the collector uncaught and force-close the app. This re-subscribes the whole
 * upstream with a capped backoff so the stream keeps running and the screen refills on the next
 * emission, instead of a `catch` that would terminate the flow and freeze the screen. [onRetry] runs
 * on each failure (to clear a loading state or surface a soft error). Cancellation is not retried:
 * flow operators are cancellation-transparent, so a scope cancel still propagates.
 */
fun <T> Flow<T>.retryOnDbTear(tag: String, onRetry: (Throwable) -> Unit = {}): Flow<T> =
    retryWhen { cause, attempt ->
        Log.w(tag, "stream failed (attempt $attempt), re-subscribing: ${cause.message}")
        onRetry(cause)
        delay(retryBackoffMs(attempt))
        true
    }

/** Capped linear backoff between re-subscribes: 500ms, 1s, 1.5s, … up to a 5s ceiling, so a source
 *  that keeps failing can't spin the CPU in a tight re-subscribe loop. */
internal fun retryBackoffMs(attempt: Long): Long = (500L * (attempt + 1)).coerceAtMost(5_000L)
