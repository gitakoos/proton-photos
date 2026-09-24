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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Runs one expensive read at a time and answers every caller with a read that began after it asked.
 *
 * [fresh] never answers with a read that was already under way when it was called, since that read
 * may have passed the change the caller is reacting to. Such a caller waits for the next read, and
 * every caller that arrives before that next read begins shares it, so a burst of callers costs one
 * read however many there are. Reads never overlap, which keeps a whole-library read to one copy in
 * flight.
 *
 * The read runs on [scope], not in a caller: a caller that is cancelled stops waiting while the
 * others still get their answer. A failed read throws to every caller that shared it, and the next
 * call starts a new read. A read that runs out of memory also reaches [scope]'s handler, as it would
 * anywhere else. Every successful read is also published on [results], in the order the reads ran.
 */
internal class FreshQueryGate<T>(
    private val scope: CoroutineScope,
    private val query: suspend () -> T,
) {
    private val lock = Any()

    /** The read a caller can still join: created, its query not yet begun. */
    private var joinable: CompletableDeferred<T>? = null

    /** True from the moment a read is launched until it has finished, so the next one waits for it. */
    private var busy = false

    private val published = MutableSharedFlow<T>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every successful read, in order. Holds nothing while no one collects it. */
    val results: SharedFlow<T> = published.asSharedFlow()

    suspend fun fresh(): T {
        var launchNow = false
        val read = synchronized(lock) {
            joinable ?: CompletableDeferred<T>().also { created ->
                joinable = created
                if (!busy) {
                    busy = true
                    launchNow = true
                }
            }
        }
        if (launchNow) launchRead(read)
        return read.await()
    }

    private fun launchRead(read: CompletableDeferred<T>) {
        scope.launch {
            // From here on the query can pass a change a newer caller reacts to, so no one joins it.
            synchronized(lock) { if (joinable === read) joinable = null }
            val outcome = try {
                Result.success(query())
            } catch (e: Throwable) {
                // Running out of memory is no failed read for a caller to retry past, and a cancelled
                // read is not a failed one either: both end this coroutine as they are, and the
                // completion handler below still hands them to every caller of the read.
                if (e is VirtualMachineError || e is CancellationException) throw e
                Result.failure(e)
            }
            outcome.onSuccess { published.tryEmit(it) }
            finish(read, outcome)
        }.invokeOnCompletion { cause ->
            // A read the scope cancelled, before it began or while it ran, still releases its callers.
            if (cause != null) finish(read, Result.failure(cause))
        }
    }

    private fun finish(read: CompletableDeferred<T>, outcome: Result<T>) {
        if (read.isCompleted) return
        val next = synchronized(lock) {
            if (joinable === read) joinable = null
            joinable.also { busy = it != null }
        }
        outcome.fold({ read.complete(it) }, { read.completeExceptionally(it) })
        if (next != null) launchRead(next)
    }
}
