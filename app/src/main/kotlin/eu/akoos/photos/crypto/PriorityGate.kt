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

package eu.akoos.photos.crypto

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A permit gate that admits at most [permits] holders at once and, when a permit frees with callers
 * waiting, admits a [DecryptPriority.FOREGROUND] waiter ahead of any [DecryptPriority.BACKGROUND]
 * one (FIFO within a priority). It reorders WHO waits, never the count in flight, so it is a drop-in
 * for a fixed-size semaphore that simply lets interactive decrypts overtake background ones.
 *
 * A running holder is never preempted: priority only decides which waiter is admitted when a permit
 * is released. State lives under a plain [ReentrantLock] and the only suspension point is a
 * per-waiter [CompletableDeferred], so no thread is ever blocked while a coroutine is suspended and a
 * cancelled waiter reclaims its slot instead of leaking a permit.
 */
class PriorityGate(private val permits: Int) {

    init { require(permits >= 1) { "permits must be >= 1" } }

    private val lock = ReentrantLock()
    private var available = permits
    private val foreground = ArrayDeque<CompletableDeferred<Unit>>()
    private val background = ArrayDeque<CompletableDeferred<Unit>>()

    /** Runs [block] holding exactly one permit, admitting foreground waiters ahead of background ones. */
    suspend fun <T> withPermit(priority: DecryptPriority, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: DecryptPriority) {
        val ticket = lock.withLock {
            if (available > 0) {
                available -= 1
                return
            }
            CompletableDeferred<Unit>().also { d ->
                if (priority == DecryptPriority.FOREGROUND) foreground.addLast(d) else background.addLast(d)
            }
        }
        try {
            ticket.await()
        } catch (t: Throwable) {
            // Cancelled while queued. Drop our ticket; if a release already handed us the permit
            // (ticket no longer in a queue) pass it on so the freed slot is never leaked.
            lock.withLock {
                val stillQueued = foreground.remove(ticket) || background.remove(ticket)
                if (!stillQueued) handoffLocked()
            }
            throw t
        }
    }

    private fun release() {
        lock.withLock { handoffLocked() }
    }

    /** Under [lock]: give the freed permit to the next waiter (foreground first, FIFO within a
     *  priority), or return it to the pool when none waits. Incrementing [available] only when both
     *  queues are empty keeps a free permit and a waiter from ever coexisting, so the fast path can
     *  never let a background caller jump a queued foreground one. */
    private fun handoffLocked() {
        val next = foreground.removeFirstOrNull() ?: background.removeFirstOrNull()
        if (next != null) next.complete(Unit) else available += 1
    }
}
