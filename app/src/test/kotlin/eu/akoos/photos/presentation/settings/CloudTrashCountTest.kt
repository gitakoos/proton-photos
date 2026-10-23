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
import org.junit.Assert.assertNull
import org.junit.Test

/** The Settings row's Drive trash count follows a trash, restore or permanent delete right away. */
class CloudTrashCountTest {

    @Test
    fun `a trash adds and a restore or delete takes away`() {
        assertEquals(12, shiftedTrashCount(9, 3))
        assertEquals(8, shiftedTrashCount(9, -1))
        assertEquals(0, shiftedTrashCount(8, -8))
    }

    @Test
    fun `the count never goes below zero`() {
        assertEquals(0, shiftedTrashCount(2, -5))
    }

    @Test
    fun `an unknown count stays unknown`() {
        assertNull(shiftedTrashCount(null, 4))
    }
}
