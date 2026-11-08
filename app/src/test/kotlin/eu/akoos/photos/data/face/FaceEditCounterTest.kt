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
package eu.akoos.photos.data.face

import eu.akoos.photos.domain.usecase.FaceRegroupOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count a full regroup checks before it writes: an edit that started, ended or is still running since the
 * regroup took its mark turns the write away, and only one more regroup is asked for however many edits land.
 */
class FaceEditCounterTest {

    private val edits = FaceEditCounter()

    @Test
    fun `a mark with no edit since allows the write`() {
        val mark = edits.mark()
        assertTrue(edits.unchangedSince(mark))
    }

    @Test
    fun `an edit that ended since the mark turns the write away`() {
        val mark = edits.mark()
        edits.edit { }
        assertFalse(edits.unchangedSince(mark))
        assertTrue("a mark taken after the edit allows", edits.unchangedSince(edits.mark()))
    }

    @Test
    fun `an edit still running turns away even a mark taken while it runs`() {
        val before = edits.mark()
        edits.edit {
            assertFalse(edits.unchangedSince(before))
            assertFalse(edits.unchangedSince(edits.mark()))
        }
    }

    @Test
    fun `an edit that throws still lets later writes through`() {
        runCatching { edits.edit { error("write failed") } }
        assertTrue(edits.unchangedSince(edits.mark()))
    }

    @Test
    fun `only a regroup an edit turned away asks for another, and only when none is queued`() {
        val edited = FaceRegroupOutcome(committed = false, leftUngrouped = 0, editedMeanwhile = true)
        assertTrue(regroupAgainAfterEdit(edited, queuedRegroups = 0))
        assertFalse(regroupAgainAfterEdit(edited, queuedRegroups = 1))
        assertFalse(
            "the import fence moves on its own",
            regroupAgainAfterEdit(FaceRegroupOutcome(committed = false, leftUngrouped = 0), queuedRegroups = 0),
        )
        assertFalse(regroupAgainAfterEdit(FaceRegroupOutcome(committed = true, leftUngrouped = 3), queuedRegroups = 0))
    }
}
