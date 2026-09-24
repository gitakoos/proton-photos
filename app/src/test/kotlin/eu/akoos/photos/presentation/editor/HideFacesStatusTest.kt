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

package eu.akoos.photos.presentation.editor

import eu.akoos.photos.presentation.editor.HideFacesStatus.Busy
import eu.akoos.photos.presentation.editor.HideFacesStatus.Covered
import eu.akoos.photos.presentation.editor.HideFacesStatus.NoFaces
import eu.akoos.photos.presentation.editor.HideFacesStatus.None
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Hide-faces status line to the covers the edit carries. Undo and Redo swap the covers but leave
 * the detect flow where the last detection put it, so a count read off that flow claims faces are hidden
 * when none are, or names the wrong number. Each Undo / Redo step below keeps the flow state and changes
 * only the live cover count, as the editor does.
 */
class HideFacesStatusTest {

    private val message = "The face model could not be loaded."

    private fun statuses(vararg steps: Pair<HideFacesState, Int>) =
        steps.map { (state, liveCovers) -> hideFacesStatus(state, liveCovers) }

    @Test
    fun `a running detection shows the spinner`() {
        assertEquals(Busy, hideFacesStatus(HideFacesState.Detecting, liveCovers = 0))
        // A re-detect over earlier covers spins too, instead of showing their count.
        assertEquals(Busy, hideFacesStatus(HideFacesState.Detecting, liveCovers = 3))
    }

    @Test
    fun `a failed detection shows its message`() {
        val failed = HideFacesState.Failed(message)
        assertEquals(HideFacesStatus.Failed(message), hideFacesStatus(failed, liveCovers = 0))
        // Also when a failed re-detect left an earlier detection's covers in place.
        assertEquals(HideFacesStatus.Failed(message), hideFacesStatus(failed, liveCovers = 2))
    }

    @Test
    fun `a finished detection shows the covers it placed`() {
        assertEquals(Covered(1), hideFacesStatus(HideFacesState.Ready(1), liveCovers = 1))
        assertEquals(Covered(3), hideFacesStatus(HideFacesState.Ready(3), liveCovers = 3))
    }

    @Test
    fun `covers are counted whatever the settled flow state`() {
        // Clear resets the flow to Idle, and an Undo then restores the covers.
        assertEquals(Covered(2), hideFacesStatus(HideFacesState.Idle, liveCovers = 2))
        // An open model download prompt leaves any covers in place.
        assertEquals(Covered(2), hideFacesStatus(HideFacesState.NeedsConsent, liveCovers = 2))
    }

    @Test
    fun `a detection that found no face says so`() {
        assertEquals(NoFaces, hideFacesStatus(HideFacesState.Ready(0), liveCovers = 0))
    }

    @Test
    fun `nothing shows without covers or a result to report`() {
        assertEquals(None, hideFacesStatus(HideFacesState.Idle, liveCovers = 0))
        assertEquals(None, hideFacesStatus(HideFacesState.NeedsConsent, liveCovers = 0))
        // Reset takes a detection's covers off and leaves the flow Ready.
        assertEquals(None, hideFacesStatus(HideFacesState.Ready(3), liveCovers = 0))
    }

    @Test
    fun `undoing a detection drops its count, and redoing it brings the count back`() {
        assertEquals(
            listOf(Covered(3), None, Covered(3)),
            statuses(
                HideFacesState.Ready(3) to 3, // Detect finds 3
                HideFacesState.Ready(3) to 0, // Undo
                HideFacesState.Ready(3) to 3, // Redo
            ),
        )
    }

    @Test
    fun `undoing a re-detect counts the covers it restores, not the ones the re-detect found`() {
        assertEquals(
            listOf(Covered(3), Covered(2), Covered(3), Covered(2)),
            statuses(
                HideFacesState.Ready(3) to 3, // Detect finds 3
                HideFacesState.Ready(2) to 2, // re-Detect finds 2
                HideFacesState.Ready(2) to 3, // Undo
                HideFacesState.Ready(2) to 2, // Redo
            ),
        )
    }

    @Test
    fun `undoing a re-detect that found no face counts the covers it restores`() {
        assertEquals(
            listOf(Covered(3), NoFaces, Covered(3), NoFaces),
            statuses(
                HideFacesState.Ready(3) to 3, // Detect finds 3
                HideFacesState.Ready(0) to 0, // re-Detect finds none and removes them
                HideFacesState.Ready(0) to 3, // Undo
                HideFacesState.Ready(0) to 0, // Redo
            ),
        )
    }
}
