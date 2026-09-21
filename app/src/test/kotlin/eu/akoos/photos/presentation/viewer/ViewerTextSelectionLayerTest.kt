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

package eu.akoos.photos.presentation.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one invariant the selectable OCR layer cannot break: a run's text carries no hard line
 * break. A trailing newline opens a second line the one-line node clips away, and a selection dragged
 * onto that clipped offset is what the platform selection manager throws an IllegalStateException on,
 * which crashed the viewer on a long line. The separator has to stay a space.
 */
class ViewerTextSelectionLayerTest {

    @Test
    fun a_run_is_closed_by_a_single_space() {
        assertEquals("Main St ", viewerRunSelectableText("Main St"))
    }

    @Test
    fun a_run_carries_no_hard_line_break() {
        val closed = viewerRunSelectableText(
            "linux: Loading kernel 'boot():/linux-cachyos/vmlinuz-linux-cachyos'",
        )
        assertFalse("a run must not contain a newline", closed.contains('\n'))
        assertFalse("a run must not contain a carriage return", closed.contains('\r'))
    }

    @Test
    fun crossed_runs_keep_a_gap_between_their_words() {
        // Two runs a selection crosses are concatenated with nothing added between them, so each
        // run's own trailing separator is the only thing keeping the words apart.
        val joined = viewerRunSelectableText("Shop") + viewerRunSelectableText("1 High St")
        assertTrue("crossed runs read with a gap", joined.startsWith("Shop 1 High St"))
    }

    @Test
    fun an_empty_reading_still_closes_cleanly() {
        // textRun drops empty reads before this is reached, but the separator rule must not depend on
        // that: even an empty string comes back as just the separator, never a bare line break.
        assertEquals(" ", viewerRunSelectableText(""))
    }
}
