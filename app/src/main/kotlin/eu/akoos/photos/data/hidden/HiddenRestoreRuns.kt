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

package eu.akoos.photos.data.hidden

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where a reveal actually runs, and how many times.
 *
 * A reveal writes the photo back to the device BEFORE it clears the records naming the vault copy, so
 * the screen that asked for one must not be able to stop it in between. [detached] runs the work on
 * [scope] — one the app owns, not a screen — and hands the caller only the WAIT: the caller still
 * gets the result and still gets the failure, so it can say what happened, but it no longer holds the
 * power to abandon the work half-way. A caller that goes away therefore leaves a finished photo
 * behind rather than one sitting on the device under a vault record nothing can reach, in a folder
 * that can never finish coming back.
 *
 * [forPhoto] adds the other half. A photo already on its way back joins the run in flight instead of
 * starting a second one over the same bytes, so a second tap on Unhide, or a folder reveal reaching a
 * photo the viewer already started, moves the file once and both callers hear the same answer.
 *
 * A run leaves the register the moment it ends, failure and cancellation included, so a photo that did
 * not come back can be asked for again.
 */
class HiddenRestoreRuns(private val scope: CoroutineScope) {

    private val running = mutableMapOf<String, Deferred<HiddenRestoreOutcome>>()
    private val lock = Mutex()

    /** Run [block] where no screen can cancel it, and wait for what it answers. */
    suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    /** Run [block] for [uri] the same way, or wait on the run already going for that photo. */
    suspend fun forPhoto(uri: String, block: suspend () -> HiddenRestoreOutcome): HiddenRestoreOutcome {
        val run = lock.withLock {
            running.getOrPut(uri) {
                // Lazy so the body cannot reach its own de-registration while the register is still
                // locked; starting it is the first thing done once the lock is out of the way, and
                // nothing suspends in between, so a cancelled caller cannot leave one unstarted.
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        block()
                    } finally {
                        // Leaving the register is the one step a cancellation may not skip. Taking
                        // the lock is a suspension point, so a run cancelled where it stands can be
                        // refused it and leave its entry behind — and every later ask for that photo
                        // then waits on a run that already ended and is handed its cancellation, so
                        // the photo can never be revealed again while the app lives.
                        withContext(NonCancellable) { lock.withLock { running -= uri } }
                    }
                }
            }
        }
        run.start()
        return run.await()
    }
}
