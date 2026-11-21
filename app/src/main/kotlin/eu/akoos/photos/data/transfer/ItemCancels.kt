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

import kotlinx.coroutines.CancellationException
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
 * ([ItemCommitPoint.reach]) is past the point a cancel can undo, so a later cancel lets it finish. While
 * an item runs a step that has its own way to stop ([ItemCommitPoint.stopVia]), a cancel goes to that
 * step instead of cancelling the coroutine, so a step that still completes is kept.
 */
class ItemCancels {
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val committed = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<String, Job>()
    private val stoppers = ConcurrentHashMap<String, () -> Unit>()
    private val lock = Any()

    /**
     * Stop the item [key]: a waiting one will not start; a running one is cancelled, or told to stop
     * through its own step's stop, unless it is committed.
     */
    fun cancel(key: String) {
        var stop: (() -> Unit)? = null
        var job: Job? = null
        synchronized(lock) {
            requested.add(key)
            if (key !in committed) {
                stop = stoppers[key]
                if (stop == null) job = running[key]
            }
        }
        stop?.invoke()
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
            val job = launch(ItemCommitPoint(this@ItemCancels, key), start = CoroutineStart.UNDISPATCHED) { block() }
            // A cancel that landed before the registration, while the item ran up to its first pause.
            val cancelNow = synchronized(lock) {
                running[key] = job
                key in requested && key !in committed && !stoppers.containsKey(key)
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
    internal fun commit(key: String): Boolean = synchronized(lock) {
        if (key in committed) {
            true
        } else if (key in requested) {
            false
        } else {
            committed.add(key)
            true
        }
    }

    /** Routes a cancel of [key] to [onStop] from now on; false when a cancel came first. */
    internal fun routeStop(key: String, onStop: () -> Unit): Boolean = synchronized(lock) {
        if (key in requested) {
            false
        } else {
            stoppers[key] = onStop
            true
        }
    }

    /** Ends [routeStop] for [key]. [committedNow] marks it committed in the same step, a cancel or not. */
    internal fun endStop(key: String, committedNow: Boolean) {
        synchronized(lock) {
            stoppers.remove(key)
            if (committedNow) committed.add(key)
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
 * coroutine context; anywhere else there is none: [reach] always answers true and [stopVia] just runs.
 */
class ItemCommitPoint internal constructor(
    private val owner: ItemCancels,
    private val itemKey: String,
) : AbstractCoroutineContextElement(ItemCommitPoint) {
    companion object Key : CoroutineContext.Key<ItemCommitPoint> {
        /** Marks the running item committed. False when the user cancelled it first: the caller stops it. */
        suspend fun reach(): Boolean =
            currentCoroutineContext()[ItemCommitPoint]?.let { it.owner.commit(it.itemKey) } ?: true

        /**
         * Runs [block], a step that commits when it returns and can be stopped through [onStop] (for example
         * an upload that cancels its own transfer), with a cancel of this item sent to [onStop] instead of
         * cancelling the coroutine. A [block] that returns normally committed: the item is marked committed
         * even when a cancel came meanwhile, so the work after the commit still runs. A cancel that came
         * before this call stops the item at once.
         */
        suspend fun <T> stopVia(onStop: () -> Unit, block: suspend () -> T): T {
            val point = currentCoroutineContext()[ItemCommitPoint] ?: return block()
            if (!point.owner.routeStop(point.itemKey, onStop)) {
                throw CancellationException("cancelled by the user")
            }
            var done = false
            try {
                val result = block()
                done = true
                return result
            } finally {
                point.owner.endStop(point.itemKey, committedNow = done)
            }
        }
    }
}
