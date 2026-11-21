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

package eu.akoos.photos.data.repository.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects decrypted-thumbnail URLs and hands them to [write] as one batch.
 *
 * Room invalidates by table, so every `photo_listing` write re-runs the timeline query and, on a
 * large library, re-reads every row. One write per decrypted thumbnail did that about once a second
 * through a scroll. The cells already get each URL live from [ThumbnailUrlStore], so the database copy
 * only has to be there for the next cold start. This buffer therefore waits until no URL has arrived
 * for [idleMs] (or [maxWaitMs] has passed under a sustained stream) and writes everything pending in
 * one call, which the caller runs as a single transaction: one invalidation per batch.
 *
 * Waiting is counted in [idleMs] steps rather than read off a clock, so the rule is the same under a
 * test scheduler. A caller that must not lose a pending URL (the app going to the background) calls
 * [flush].
 */
internal class ThumbnailUrlWriteBuffer(
    private val scope: CoroutineScope,
    private val idleMs: Long,
    private val maxWaitMs: Long,
    private val write: suspend (Map<String, String>) -> Unit,
) {
    private val pending = HashMap<String, String>()

    // Held while a batch is being written, so a flush that starts mid-write waits for it.
    private val flushLock = Mutex()

    // An arrival counter: the timer sees a quiet spell when it did not move across one idle step.
    private val arrivals = AtomicLong()

    // A timer coroutine is waiting to flush.
    private val timerRunning = AtomicBoolean(false)

    fun add(linkId: String, url: String) {
        synchronized(pending) { pending[linkId] = url }
        arrivals.incrementAndGet()
        startTimer()
    }

    /** Forget the pending URLs of [linkIds], for files that were just deleted. */
    fun discard(linkIds: Collection<String>) {
        synchronized(pending) { linkIds.forEach { pending.remove(it) } }
    }

    /** Forget every pending URL, once any batch already being written has finished. */
    suspend fun discardAll() {
        flushLock.withLock { synchronized(pending) { pending.clear() } }
    }

    /** Writes everything pending now. A write that fails drops its batch: a thumbnail whose URL never
     *  reached the database is re-pointed from the disk cache on the next scroll. */
    suspend fun flush() {
        flushLock.withLock {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) return
                HashMap(pending).also { pending.clear() }
            }
            try {
                write(batch)
            } catch (e: CancellationException) {
                synchronized(pending) { batch.forEach { (k, v) -> pending.putIfAbsent(k, v) } }
                throw e
            } catch (_: Exception) {
                // Dropped, see above.
            }
        }
    }

    private fun startTimer() {
        if (!timerRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                var waited = 0L
                while (true) {
                    val seen = arrivals.get()
                    delay(idleMs)
                    waited += idleMs
                    if (arrivals.get() == seen || waited >= maxWaitMs) break
                }
                flush()
            } finally {
                timerRunning.set(false)
            }
            // A URL that arrived while the batch was being written still needs its own flush.
            if (synchronized(pending) { pending.isNotEmpty() }) startTimer()
        }
    }
}
