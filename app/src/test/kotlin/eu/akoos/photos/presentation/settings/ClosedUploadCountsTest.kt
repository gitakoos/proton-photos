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

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Sync card's counts after an upload pass closes. */
class ClosedUploadCountsTest {

    @Test
    fun `a batch closes on its own total`() {
        assertEquals(80 to 80, closedUploadCounts(currentTotal = 80, closingDone = 80, closingTotal = 80))
        assertEquals(4 to 4, closedUploadCounts(currentTotal = 4, closingDone = 4, closingTotal = 4))
    }

    @Test
    fun `an empty pass after a finished batch keeps that batch complete`() {
        assertEquals(80 to 80, closedUploadCounts(currentTotal = 80, closingDone = 0, closingTotal = 0))
    }

    @Test
    fun `an empty pass with no batch before it stays empty`() {
        assertEquals(0 to 0, closedUploadCounts(currentTotal = 0, closingDone = 0, closingTotal = 0))
    }

    @Test
    fun `a new batch replaces the previous total`() {
        assertEquals(3 to 3, closedUploadCounts(currentTotal = 80, closingDone = 3, closingTotal = 3))
    }
}
