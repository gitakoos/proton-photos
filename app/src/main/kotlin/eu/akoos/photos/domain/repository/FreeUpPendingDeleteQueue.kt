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

package eu.akoos.photos.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a MediaStore permanent-delete consent dialog for free-up reclaim. */
data class FreeUpConsentSettlement(
    val uris: Set<String>,
    val granted: Boolean,
)

/** Max age of an in-memory free-up permanent-delete offer before Free up entry clears it. */
const val FREE_UP_PENDING_OFFER_STALE_MS: Long = 15L * 60L * 1_000L

/**
 * Whether a free-up consent offer should be discarded on Free up entry so Continue re-verifies
 * Drive before another permanent-delete consent dialog.
 *
 * Null [offeredAtMs] (empty queue) is never stale. Pure helper — no Android / clock side effects.
 */
fun isFreeUpPendingOfferStale(
    offeredAtMs: Long?,
    nowMs: Long,
    staleAfterMs: Long = FREE_UP_PENDING_OFFER_STALE_MS,
): Boolean {
    if (offeredAtMs == null) return false
    if (staleAfterMs <= 0L) return true
    return nowMs - offeredAtMs >= staleAfterMs
}

/**
 * In-memory permanent-delete URI queue for free-up reclaim. Hard-separated from
 * trash / delete-after-backup pending URIs.
 *
 * Process death clears this queue. [offeredAtMs] is set when the queue becomes non-empty and
 * cleared when it empties again (staleness checks live in presentation).
 */
interface FreeUpPendingDeleteQueue {
    val uris: StateFlow<Set<String>>
    val settled: SharedFlow<FreeUpConsentSettlement>

    /** Wall-clock ms when the current non-empty offer started, or null when the queue is empty. */
    val offeredAtMs: Long?

    fun offer(uris: List<String>)

    /**
     * Removes [uris] from the queue and emits one [FreeUpConsentSettlement].
     * Empty [uris] is a no-op (no emit).
     */
    fun settle(uris: Set<String>, granted: Boolean)

    /**
     * Settles every URI currently in the queue with [granted]. Used when the user denies a consent
     * chunk so remaining offered URIs are not prompted again in this session.
     */
    fun settleAllPending(granted: Boolean)

    /**
     * Silently drops [uris] from the queue without emitting a [FreeUpConsentSettlement].
     * Reserved for just-in-time verifier rejection before a permanent-delete consent dialog —
     * unsafe / inactive Drive links must leave the queue without pretending the user answered.
     */
    fun remove(uris: Set<String>)
}
