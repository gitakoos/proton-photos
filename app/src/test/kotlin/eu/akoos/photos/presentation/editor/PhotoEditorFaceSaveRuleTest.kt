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

import eu.akoos.photos.presentation.editor.FaceCoverSaveStep.Abort
import eu.akoos.photos.presentation.editor.FaceCoverSaveStep.Proceed
import eu.akoos.photos.presentation.editor.FaceCoverSaveStep.Wait
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins what a save does while Hide faces is in play. The failure it guards against is a photo whose
 * faces the user asked to cover being written without the covers: saved while the detection was still
 * running, or after it failed or its model prompt was declined, with nothing said.
 */
class PhotoEditorFaceSaveRuleTest {

    private val failed = HideFacesState.Failed("The face model could not be loaded.")

    @Test
    fun `with Hide faces never used, or its covers cleared, the save goes ahead`() {
        assertEquals(Proceed, faceCoverSaveStep(HideFacesState.Idle, hasCovers = false, waited = false))
        assertEquals(Proceed, faceCoverSaveStep(HideFacesState.Idle, hasCovers = true, waited = false))
    }

    @Test
    fun `a detection still running is waited for`() {
        assertEquals(Wait, faceCoverSaveStep(HideFacesState.Detecting, hasCovers = false, waited = false))
        // A re-detect over earlier covers is waited for too, so the save gets the fresh result.
        assertEquals(Wait, faceCoverSaveStep(HideFacesState.Detecting, hasCovers = true, waited = true))
    }

    @Test
    fun `an open model download prompt is waited for`() {
        assertEquals(Wait, faceCoverSaveStep(HideFacesState.NeedsConsent, hasCovers = false, waited = false))
    }

    @Test
    fun `a finished detection saves with its covers`() {
        assertEquals(Proceed, faceCoverSaveStep(HideFacesState.Ready(2), hasCovers = true, waited = true))
        // No face found: there is nothing to cover, so the photo saves as it is.
        assertEquals(Proceed, faceCoverSaveStep(HideFacesState.Ready(0), hasCovers = false, waited = true))
    }

    @Test
    fun `a failed detection that covered nothing stops the save`() {
        assertEquals(Abort, faceCoverSaveStep(failed, hasCovers = false, waited = true))
        // Also when it had already failed before Save was pressed.
        assertEquals(Abort, faceCoverSaveStep(failed, hasCovers = false, waited = false))
    }

    @Test
    fun `a failed re-detect keeps the covers an earlier one found`() {
        assertEquals(Proceed, faceCoverSaveStep(failed, hasCovers = true, waited = true))
    }

    @Test
    fun `a model prompt declined while the save waited stops the save`() {
        assertEquals(Abort, faceCoverSaveStep(HideFacesState.Idle, hasCovers = false, waited = true))
    }

    @Test
    fun `a save pressed mid-detection waits, then goes ahead once the covers land`() {
        var waited = false
        val flow = listOf(HideFacesState.Detecting, HideFacesState.Ready(1))
        val steps = flow.map { state ->
            faceCoverSaveStep(state, hasCovers = state is HideFacesState.Ready, waited = waited).also {
                if (it == Wait) waited = true
            }
        }
        assertEquals(listOf(Wait, Proceed), steps)
    }
}
