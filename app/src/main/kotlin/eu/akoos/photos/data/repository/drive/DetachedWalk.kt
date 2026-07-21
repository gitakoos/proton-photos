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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId

/**
 * Owns one long-running per-user job on a scope that outlives whoever asked for it, and hands
 * every concurrent caller that same job.
 *
 * A full walk of a large library runs for tens of minutes, far longer than the Settings screen,
 * the Photos tab or a foregrounded Activity survive. Rooted in the caller's job, the walk dies
 * with the screen and the library is never listed to its end — which also means the passes that
 * only run once pagination reaches the end never run at all. Rooted in [scope] instead, the
 * caller merely awaits alongside it: cancelling the caller ends its wait and nothing else.
 *
 * Callers still get a result. [run] returns when the walk returns, and rethrows what the walk
 * threw, so a caller that must not proceed on a failed walk still cannot.
 *
 * Concurrent callers for the same user share one walk rather than queueing behind each other.
 * Queueing was survivable while a walk died with its caller; now that it does not, every
 * launch, resume, tab switch and pull-to-refresh would add another whole walk to a backlog
 * nothing cancels. Sharing bounds the scope to one walk per user by construction.
 *
 * A walk for a different user supersedes the one in flight. The app holds a single primary user
 * at a time, so a second id means the account was switched, and the outgoing account's walk has
 * nothing left to write for.
 */
class DetachedWalk(private val scope: CoroutineScope) {

    private val lock = Mutex()
    private var owner: UserId? = null
    private var inFlight: Deferred<Unit>? = null
    // Set while a walk is in flight to ask it for one more pass once it ends; at most one pending.
    private var rerunForced = false

    /**
     * Runs [block] on [scope], or joins the walk already running for [userId], and waits for it.
     *
     * The wait belongs to the caller and the walk does not: a cancelled caller stops awaiting,
     * while the walk carries on to its end for whoever asks next.
     *
     * A [forced] caller guarantees a listing pass runs for its press. A walk already in flight
     * began before the press and answers nothing it asked for, so rather than ride it the forced
     * caller has the walk make one more pass once the current one ends; a gentle caller just waits
     * alongside the pass in flight. [block] is told whether the pass it runs is forced. A burst of
     * forced presses during one walk collapses to a single extra pass, since the latch holds at
     * most one pending rerun.
     */
    suspend fun run(userId: UserId, forced: Boolean = false, block: suspend (forced: Boolean) -> Unit) {
        val walk = lock.withLock {
            val existing = inFlight
            if (existing != null && existing.isActive) {
                if (owner != userId) {
                    // Superseded, not joined: a walk writes rows keyed to the account it started for.
                    existing.cancel()
                    rerunForced = false
                } else {
                    // A walk for this user is already running. A forced caller asks it to run one
                    // more pass once the current one ends, rather than silently riding a pass that
                    // began before the press; a gentle caller simply waits alongside it.
                    if (forced) rerunForced = true
                    return@withLock existing
                }
            }
            owner = userId
            rerunForced = false
            scope.async {
                var passForced = forced
                while (true) {
                    block(passForced)
                    val again = lock.withLock {
                        if (rerunForced) {
                            rerunForced = false
                            true
                        } else {
                            // Commit to finishing under the same lock a forced caller would take to
                            // request another pass, so a press can never land between this check and
                            // the coroutine ending and be dropped. Unpublishing here also means the
                            // next refresh starts a real walk instead of joining a spent one.
                            if (owner == userId) {
                                inFlight = null
                                owner = null
                            }
                            false
                        }
                    }
                    if (!again) break
                    passForced = true
                }
            }.also { inFlight = it }
        }
        walk.await()
    }

    /**
     * Stops [userId]'s walk and waits for it to unwind.
     *
     * Callers wipe that account's rows and key material immediately after, so this suspends until
     * the walk has actually stopped — a walk still paginating during the wipe would write the
     * signed-out account's photos back into the table it was just cleared from, against key
     * material that no longer exists.
     *
     * A walk belonging to another signed-in account is left alone, and a call with nothing in
     * flight does nothing.
     */
    suspend fun cancelFor(userId: UserId) {
        val walk = lock.withLock {
            val existing = inFlight
            if (existing == null || owner != userId) return@withLock null
            inFlight = null
            owner = null
            // Drop any queued forced rerun: the follow-up pass must not fire once this account's
            // rows and key material are wiped.
            rerunForced = false
            existing
        }
        walk?.cancelAndJoin()
    }
}
