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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenamedFolderSetTest {

    @Test
    fun `a folder picked for back-up stays picked under its new name`() {
        assertEquals(setOf("Screenshots", "Trip"), renamedFolderSet(setOf("Camera2", "Screenshots"), "Camera2", "Trip", fromStillUsed = false))
    }

    @Test
    fun `the old name stays while some photos could not be moved`() {
        assertEquals(setOf("Camera2", "Trip"), renamedFolderSet(setOf("Camera2"), "Camera2", "Trip", fromStillUsed = true))
    }

    @Test
    fun `a set without the folder, or no set at all, is left alone`() {
        assertNull(renamedFolderSet(setOf("Screenshots"), "Camera2", "Trip", fromStillUsed = false))
        assertNull(renamedFolderSet(null, "Camera2", "Trip", fromStillUsed = false))
        assertNull(renamedFolderSet(setOf("Camera2"), "Camera2", "Camera2", fromStillUsed = false))
    }
}
