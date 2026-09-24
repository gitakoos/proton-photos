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

import eu.akoos.photos.data.face.FaceIndexingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for when the face card shows its paused row with Resume, and the machine-learning page reads the
 * face row as paused: whenever the pause switch is on, whatever the scan reports, and whenever the scan
 * reports itself paused.
 */
class FaceScanPausedRowTest {

    @Test
    fun `a pause switch that is on always offers Resume, whatever the scan reports`() {
        FaceIndexingState.entries.forEach { state ->
            assertTrue("$state", faceScanPausedRow(pauseSwitchOn = true, state = state))
        }
    }

    @Test
    fun `a walk stopped at the switch in a new process, paused at 0 of 0, offers Resume`() {
        assertTrue(faceScanPausedRow(pauseSwitchOn = true, state = FaceIndexingState.Paused))
    }

    @Test
    fun `a switch read before any walk reported still offers Resume`() {
        assertTrue(faceScanPausedRow(pauseSwitchOn = true, state = FaceIndexingState.Idle))
    }

    @Test
    fun `a scan that reports itself paused offers Resume even with the switch off`() {
        assertTrue(faceScanPausedRow(pauseSwitchOn = false, state = FaceIndexingState.Paused))
    }

    @Test
    fun `a scan that is not paused offers no Resume`() {
        listOf(
            FaceIndexingState.Idle,
            FaceIndexingState.WaitingModel,
            FaceIndexingState.Running,
            FaceIndexingState.Done,
        ).forEach { state ->
            assertFalse("$state", faceScanPausedRow(pauseSwitchOn = false, state = state))
        }
    }
}
