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

package eu.akoos.photos.presentation.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when the one-tap Share on search and a place's photos starts a batch: a share downloads each
 * cloud original before the chooser opens, so a second press while that runs must not start a second
 * job and raise a second chooser. Plain JVM assertions.
 */
class SelectionShareGuardTest {

    @Test
    fun `a press over a selection starts a batch when none runs`() {
        assertTrue(shareStarts(selectedCount = 1, state = SelectionShareState.Idle))
        assertTrue(shareStarts(selectedCount = 250, state = SelectionShareState.Idle))
    }

    @Test
    fun `a press while a batch runs starts nothing, however far it has got`() {
        assertFalse(shareStarts(selectedCount = 3, state = SelectionShareState.Working(done = 0, total = 3)))
        assertFalse(shareStarts(selectedCount = 3, state = SelectionShareState.Working(done = 2, total = 3)))
        assertFalse(shareStarts(selectedCount = 3, state = SelectionShareState.Working(done = 3, total = 3)))
    }

    @Test
    fun `a press with nothing selected starts nothing`() {
        assertFalse(shareStarts(selectedCount = 0, state = SelectionShareState.Idle))
    }
}
