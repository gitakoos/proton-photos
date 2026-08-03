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

package eu.akoos.photos.presentation.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the pure success/failure to outcome mapping the metadata editor reports a batch write
 * with. The counts are the whole input, so the whole matrix is checked WITHOUT a ViewModel, a
 * dispatcher or MediaStore in the loop: an all-or-nothing batch keeps its plain confirmation, and any
 * mix reports both counts rather than presenting itself as a clean save.
 */
class MetadataWriteOutcomeTest {

    @Test
    fun `every write landing reports a plain save`() {
        assertEquals(MetadataEditorEvent.Saved, metadataWriteOutcome(succeeded = 50, failed = 0))
    }

    @Test
    fun `a single item batch reports a plain save`() {
        // The one-photo editor is a one-element batch and must keep its unadorned confirmation.
        assertEquals(MetadataEditorEvent.Saved, metadataWriteOutcome(succeeded = 1, failed = 0))
    }

    @Test
    fun `no write landing reports a failure`() {
        assertEquals(MetadataEditorEvent.Failed, metadataWriteOutcome(succeeded = 0, failed = 50))
    }

    @Test
    fun `a mix reports both counts`() {
        assertEquals(
            MetadataEditorEvent.PartlySaved(saved = 30, failed = 20),
            metadataWriteOutcome(succeeded = 30, failed = 20),
        )
    }

    @Test
    fun `a mostly failed batch is never a plain save`() {
        // 49 of 50 files refused: the outcome has to carry the failures, not read as "Saved".
        val outcome = metadataWriteOutcome(succeeded = 1, failed = 49)
        assertEquals(MetadataEditorEvent.PartlySaved(saved = 1, failed = 49), outcome)
    }

    @Test
    fun `a mostly succeeded batch still reports its failures`() {
        assertEquals(
            MetadataEditorEvent.PartlySaved(saved = 49, failed = 1),
            metadataWriteOutcome(succeeded = 49, failed = 1),
        )
    }

    @Test
    fun `nothing attempted reports nothing`() {
        // Every target deferred to the consent dialog: no message belongs on screen yet.
        assertNull(metadataWriteOutcome(succeeded = 0, failed = 0))
    }
}
