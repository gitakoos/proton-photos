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

package eu.akoos.photos.presentation.settings

import eu.akoos.photos.worker.FreeUpPipelineWorker

/**
 * Pure UI phase for the Free up space screen, derived from WorkInfo phase/counts so a JVM test can
 * pin checking → freeing → stopped/done without Compose or WorkManager.
 */
enum class FreeUpSpacePhase {
    Idle,
    Checking,
    Freeing,
    Stopped,
    Done,
    Error,
}

/**
 * Coarse WorkInfo activity for selecting among retained unique-work entries without constructing
 * Android [androidx.work.WorkInfo] objects in JVM tests.
 */
enum class FreeUpWorkInfoKind {
    /** RUNNING / ENQUEUED / BLOCKED */
    Active,
    /** SUCCEEDED / FAILED / CANCELLED */
    Terminal,
    /** Unexpected / unused wire states */
    Other,
}

/**
 * Prefer the first active entry; otherwise the newest terminal (last terminal in [kinds]).
 * Returns the index into [kinds], or null when nothing usable is present.
 */
fun selectPreferredFreeUpWorkInfoIndex(kinds: List<FreeUpWorkInfoKind>): Int? {
    val active = kinds.indexOfFirst { it == FreeUpWorkInfoKind.Active }
    if (active >= 0) return active
    val terminal = kinds.indexOfLast { it == FreeUpWorkInfoKind.Terminal }
    return terminal.takeIf { it >= 0 }
}

/**
 * Terminal Done/Error must only publish for work observed active during this ViewModel lifetime —
 * retained unique-work terminals from a prior process must not replay on route reopen.
 */
fun shouldPublishFreeUpTerminal(
    workId: String?,
    observedActiveIds: Set<String>,
): Boolean = workId != null && workId in observedActiveIds

/**
 * Derives the screen phase from pipeline WorkInfo.
 *
 * [workerPhase] is the WorkInfo progress/output [FreeUpPipelineWorker.KEY_PHASE] wire name
 * (`verifying` / `deleting` / `awaiting_consent`). Awaiting consent maps to [FreeUpSpacePhase.Stopped]
 * so the screen keeps the candidate grid and shows Continue (no Ready/empty-grid flash).
 */
fun deriveFreeUpSpaceState(
    workRunning: Boolean,
    workSucceeded: Boolean,
    workFailed: Boolean,
    doneMessage: Boolean,
    workerPhase: String? = null,
    stopped: Boolean = false,
    workCancelled: Boolean = false,
): FreeUpSpacePhase = when {
    workFailed -> FreeUpSpacePhase.Error
    stopped -> FreeUpSpacePhase.Stopped
    doneMessage -> FreeUpSpacePhase.Done
    workRunning -> phaseWhileRunning(workerPhase)
    workerPhase == FreeUpPipelineWorker.PHASE_AWAITING_CONSENT -> FreeUpSpacePhase.Stopped
    workCancelled -> FreeUpSpacePhase.Stopped
    workSucceeded -> FreeUpSpacePhase.Idle
    else -> FreeUpSpacePhase.Idle
}

private fun phaseWhileRunning(workerPhase: String?): FreeUpSpacePhase = when (workerPhase) {
    FreeUpPipelineWorker.PHASE_DELETING -> FreeUpSpacePhase.Freeing
    // awaiting_consent mid-run is terminal in practice; treat as Checking until WorkInfo settles.
    else -> FreeUpSpacePhase.Checking // verifying, or enqueued with no progress yet
}
