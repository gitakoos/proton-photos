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

import me.proton.core.domain.entity.UserId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the tail of a background model walk may start another pass for [nextUserId]: only while it is
 * still the account the scheduler last ran for ([lastUserId], dropped by a sign-out or a rescan) and still
 * the signed-in one ([primaryUserId]), and for a guest (null) only while no account is signed in. Pure so
 * it is unit-tested without Android.
 */
fun walkTailMayRestart(nextUserId: UserId?, lastUserId: UserId?, primaryUserId: UserId?): Boolean =
    nextUserId == lastUserId && nextUserId == primaryUserId

/**
 * The restart a background model walk owes once its current pass lands, and the account it last ran for. A
 * start asked for while a pass still winds down is held ([request]) for that pass's tail to take ([take]),
 * so a resume is never lost; a sign-out or a rescan drops both ([reset]), so a tail that lands after it
 * starts nothing for the account that left. Thread-safe.
 */
class WalkRestart {
    private val requested = AtomicBoolean(false)

    /** The account the walk last ran for or was last asked to run for; null for a guest, or after [reset]. */
    @Volatile
    var lastUserId: UserId? = null

    /** Holds a restart for the tail of the pass running now. */
    fun request() = requested.set(true)

    /** Whether a restart is held, leaving it held. */
    fun isRequested(): Boolean = requested.get()

    /** Takes the held restart, if any, leaving none. */
    fun take(): Boolean = requested.getAndSet(false)

    /** Drops the held restart and the account, for a sign-out or a rescan. */
    fun reset() {
        requested.set(false)
        lastUserId = null
    }

    /** Whether a tail may start a pass for [nextUserId] while [primaryUserId] is signed in
     *  ([walkTailMayRestart]). */
    fun mayRestart(nextUserId: UserId?, primaryUserId: UserId?): Boolean =
        walkTailMayRestart(nextUserId, lastUserId, primaryUserId)
}
