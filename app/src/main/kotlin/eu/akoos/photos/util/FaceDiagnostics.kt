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

/**
 * Privacy-safe, in-memory face-indexing diagnostics, so a stalled or standing-still scan is legible
 * in the copied diagnostics instead of a blind spot. Mirrors [PerfDiagnostics] / [SyncDiagnostics]:
 * a handful of live fields the scheduler pushes and a numbers-only [snapshot] the user copies from
 * Settings. Nothing here is auto-sent.
 *
 * Every value is a NON-IDENTIFYING count, flag, or short fixed label. Never a photo name, key, id, or
 * path. The scheduler pushes only aggregate counts and the health gate's own short reason string.
 */
object FaceDiagnostics {

    /** The walk's last reported state (Idle / WaitingModel / Running / Paused / Done). */
    @Volatile
    var state: String = "Idle"

    /** How many photos have been scanned so far, out of the whole library. */
    @Volatile
    var indexed: Int = 0

    @Volatile
    var total: Int = 0

    /** Device-only files skipped this session because they yielded no image or video frame (corrupt or
     *  unsupported). A non-zero count is what would otherwise have wedged the walk before the skip. */
    @Volatile
    var unloadableLocal: Int = 0

    /** The health gate's reason the last time a worker parked (e.g. "user interacting", "battery ..%
     *  not charging", "power saver on"), so a scan that is standing still says why. */
    @Volatile
    var lastParkReason: String = "none"

    /** Record the current walk state and progress; called wherever the scheduler updates its flow. */
    fun record(state: String, indexed: Int, total: Int) {
        this.state = state
        this.indexed = indexed
        this.total = total
    }

    /** Note that a worker parked on the health gate, with the gate's own reason. */
    fun recordPark(reason: String) {
        lastParkReason = reason
    }

    /** Note one more device-only file skipped for want of any decodable frame. */
    fun recordUnloadableLocal() {
        unloadableLocal++
    }

    /** Reset to the signed-out baseline, so a new account does not inherit the previous one's counts. */
    fun clear() {
        state = "Idle"
        indexed = 0
        total = 0
        unloadableLocal = 0
        lastParkReason = "none"
    }

    /** Live numbers-only block for the copied diagnostics: state, progress, pending, skipped, last park
     *  reason. Pending is what is left to reach the whole library, so a scan stuck short of the end is
     *  obvious at a glance. */
    fun snapshot(): String {
        val pending = (total - indexed).coerceAtLeast(0)
        return buildString {
            append("state=").append(state).append('\n')
            append("indexed=").append(indexed).append('/').append(total)
            append(" pending=").append(pending).append('\n')
            append("skippedUnloadable=").append(unloadableLocal).append('\n')
            append("lastPark=").append(lastParkReason)
        }
    }
}
