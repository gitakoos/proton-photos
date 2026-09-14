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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** The two background model walks that share the gate. Named so a walk that finds the OTHER rail holding
 *  the gate can tell the user which task it is waiting behind. */
enum class MlRail { FACE, SEMANTIC }

/**
 * One process-wide gate that lets only a single heavy on-device model walk hold its ONNX sessions at a
 * time. The face-indexing and semantic-indexing background walks each open large native sessions for the
 * length of a pass; run at once they are both resident on the large heap and can push the process to an
 * out-of-memory kill. Each background walk takes this gate before it opens its session and releases it
 * once the session is closed, so at most one rail's sessions are ever resident.
 *
 * The lock is fair (first waiter served first), so whichever walk asked first runs and the other waits.
 * The callers start the face walk ahead of the semantic one, so face keeps its priority.
 *
 * [activeRail] names whichever walk currently holds the gate (or null when it is free), so the waiting
 * walk's settings card can say it is standing by for that task rather than appearing frozen for no reason.
 *
 * Only the long background walks take the gate. The short, user-initiated scans (a viewer's on-demand
 * face scan, a person's "find more photos" sweep) are left ungated so they stay responsive.
 */
@Singleton
class MlWalkGate @Inject constructor() {

    private val gate = Mutex()

    private val _activeRail = MutableStateFlow<MlRail?>(null)

    /** The rail holding the gate right now, or null when free. A walk that reads the OTHER rail here is
     *  about to wait behind it. */
    val activeRail: StateFlow<MlRail?> = _activeRail.asStateFlow()

    /** Suspends until no other walk holds the gate, then takes it for [rail]. Pair every call with
     *  [release]. */
    suspend fun acquire(rail: MlRail) {
        gate.lock()
        _activeRail.value = rail
    }

    /** Releases the gate taken by [acquire] for [rail]. */
    fun release(rail: MlRail) {
        if (_activeRail.value == rail) _activeRail.value = null
        gate.unlock()
    }
}
