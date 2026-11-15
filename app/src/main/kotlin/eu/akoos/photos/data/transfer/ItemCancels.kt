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
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets one item of a running batch be stopped on its own, keyed by a stable item key.
 *
 * Each item runs as its own child job under [run], so cancelling it ends only that item, through the
 * same cancellation path a stopped batch takes, while the batch's other items carry on. An item
 * cancelled before its turn is never started.
 */
class ItemCancels {
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<String, Job>()

    /** Stop the item [key]: the running job is cancelled, a waiting one will not start. */
    fun cancel(key: String) {
        requested.add(key)
        running[key]?.cancel()
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
            val job = launch(start = CoroutineStart.UNDISPATCHED) { block() }
            running[key] = job
            try {
                // A cancel that landed before the registration, while the item ran up to its first pause.
                if (key in requested) job.cancel()
                job.join()
            } finally {
                running.remove(key, job)
            }
            job.isCancelled && key in requested
        }
    }

    /** Forget every request, for a new batch. */
    fun clear() {
        requested.clear()
    }
}
