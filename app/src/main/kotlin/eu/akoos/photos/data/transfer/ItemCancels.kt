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

package eu.akoos.photos.data.transfer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Lets one item of a running batch be stopped on its own, keyed by a stable item key.
 *
 * Each item runs as its own child job under [run], so cancelling it ends only that item, through the
 * same cancellation path a stopped batch takes, while the batch's other items carry on. An item
 * cancelled before its turn is never started. An item that reached its commit point
 * ([ItemCommitPoint.reach]) is past the point a cancel can undo, so a later cancel lets it finish.
 */
class ItemCancels {
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val committed = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    /** Stop the item [key]: the running job is cancelled unless it is committed, a waiting one will not start. */
    fun cancel(key: String) {
        val job = synchronized(lock) {
            requested.add(key)
            if (key in committed) null else running[key]
        }
        job?.cancel()
    }

    fun isCancelled(key: String): Boolean = key in requested

    /**
     * Runs [block] as the item [key]. Returns true when the item was cancelled, before it started or
     * while it ran; false when it ran to its end. A cancellation of the caller itself propagates.
     */
    suspend fun run(key: String, block: suspend () -> Unit): Boolean {
        if (key in requested) return true
        return coroutineScope {
            // Undispatched, so the item starts at once on this coroutine exactly as a plain call would and
            // the batch's own stop checks keep their order.
            val job = launch(ItemCommitPoint { commit(key) }, start = CoroutineStart.UNDISPATCHED) { block() }
            // A cancel that landed before the registration, while the item ran up to its first pause.
            val cancelNow = synchronized(lock) {
                running[key] = job
                key in requested && key !in committed
            }
            try {
                if (cancelNow) job.cancel()
                job.join()
            } finally {
                running.remove(key, job)
            }
            job.isCancelled && key in requested && key !in committed
        }
    }

    /** Marks [key] committed; false when a cancel came first, so the item must stop instead. */
    private fun commit(key: String): Boolean = synchronized(lock) {
        if (key in requested) {
            false
        } else {
            committed.add(key)
            true
        }
    }

    /** Forget every request, for a new batch. */
    fun clear() {
        synchronized(lock) {
            requested.clear()
            committed.clear()
        }
    }
}

/**
 * The point in an item's work after which stopping it would lose more than it saves, such as an upload
 * about to commit or a download already saved. Work running under [ItemCancels.run] finds it in its
 * coroutine context; anywhere else there is none and [reach] always answers true.
 */
class ItemCommitPoint internal constructor(private val onReach: () -> Boolean) :
    AbstractCoroutineContextElement(ItemCommitPoint) {
    companion object Key : CoroutineContext.Key<ItemCommitPoint> {
        /** Marks the running item committed. False when the user cancelled it first: the caller stops it. */
        suspend fun reach(): Boolean = currentCoroutineContext()[ItemCommitPoint]?.onReach?.invoke() ?: true
    }
}
