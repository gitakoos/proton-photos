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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins Free-up screen phase transitions and WorkInfo selection for the observe-only pipeline flow.
 */
class FreeUpSpaceDeriveStateTest {

    @Test
    fun `checking while verify work is running`() {
        assertEquals(
            FreeUpSpacePhase.Checking,
            deriveFreeUpSpaceState(
                workRunning = true,
                workSucceeded = false,
                workFailed = false,
                doneMessage = false,
                workerPhase = FreeUpPipelineWorker.PHASE_VERIFYING,
            ),
        )
    }

    @Test
    fun `checking when enqueued with no phase yet`() {
        assertEquals(
            FreeUpSpacePhase.Checking,
            deriveFreeUpSpaceState(
                workRunning = true,
                workSucceeded = false,
                workFailed = false,
                doneMessage = false,
                workerPhase = null,
            ),
        )
    }

    @Test
    fun `awaiting_consent maps to stopped for continue CTA`() {
        assertEquals(
            FreeUpSpacePhase.Stopped,
            deriveFreeUpSpaceState(
                workRunning = false,
                workSucceeded = true,
                workFailed = false,
                doneMessage = false,
                workerPhase = FreeUpPipelineWorker.PHASE_AWAITING_CONSENT,
            ),
        )
    }

    @Test
    fun `idle after success with no ready machinery`() {
        assertEquals(
            FreeUpSpacePhase.Idle,
            deriveFreeUpSpaceState(
                workRunning = false,
                workSucceeded = true,
                workFailed = false,
                doneMessage = false,
            ),
        )
    }

    @Test
    fun `freeing while worker phase is deleting`() {
        assertEquals(
            FreeUpSpacePhase.Freeing,
            deriveFreeUpSpaceState(
                workRunning = true,
                workSucceeded = false,
                workFailed = false,
                doneMessage = false,
                workerPhase = FreeUpPipelineWorker.PHASE_DELETING,
            ),
        )
    }

    @Test
    fun `stopped stays visible even when work info is terminal`() {
        assertEquals(
            FreeUpSpacePhase.Stopped,
            deriveFreeUpSpaceState(
                workRunning = false,
                workSucceeded = true,
                workFailed = false,
                doneMessage = false,
                stopped = true,
            ),
        )
    }

    @Test
    fun `done after a terminal message`() {
        assertEquals(
            FreeUpSpacePhase.Done,
            deriveFreeUpSpaceState(
                workRunning = false,
                workSucceeded = true,
                workFailed = false,
                doneMessage = true,
            ),
        )
    }

    @Test
    fun `error when verify work failed`() {
        assertEquals(
            FreeUpSpacePhase.Error,
            deriveFreeUpSpaceState(
                workRunning = false,
                workSucceeded = false,
                workFailed = true,
                doneMessage = false,
            ),
        )
    }

    @Test
    fun `cancelled maps to stopped`() {
        assertEquals(
            FreeUpSpacePhase.Stopped,
            deriveFreeUpSpaceState(
                workRunning = false,
                workSucceeded = false,
                workFailed = false,
                doneMessage = false,
                workCancelled = true,
            ),
        )
    }

    @Test
    fun `prefer active WorkInfo over older terminal`() {
        assertEquals(
            1,
            selectPreferredFreeUpWorkInfoIndex(
                listOf(
                    FreeUpWorkInfoKind.Terminal,
                    FreeUpWorkInfoKind.Active,
                    FreeUpWorkInfoKind.Terminal,
                ),
            ),
        )
    }

    @Test
    fun `newest terminal wins when nothing is active`() {
        assertEquals(
            2,
            selectPreferredFreeUpWorkInfoIndex(
                listOf(
                    FreeUpWorkInfoKind.Terminal,
                    FreeUpWorkInfoKind.Other,
                    FreeUpWorkInfoKind.Terminal,
                ),
            ),
        )
    }

    @Test
    fun `empty work infos yield no selection`() {
        assertNull(selectPreferredFreeUpWorkInfoIndex(emptyList()))
    }

    @Test
    fun `terminal Done publishes only for work observed active this lifetime`() {
        assertTrue(shouldPublishFreeUpTerminal("run-a", setOf("run-a", "run-b")))
        assertFalse(shouldPublishFreeUpTerminal("run-old", setOf("run-a")))
        assertFalse(shouldPublishFreeUpTerminal(null, setOf("run-a")))
    }
}
