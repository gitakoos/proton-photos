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

package eu.akoos.photos.presentation.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderCoverSelectionTest {

    private val a = "content://media/external/images/media/1"
    private val b = "content://media/external/images/media/2"
    private val vaulted = "file:///data/user/0/eu.akoos.photos/files/hidden/abc"

    @Test
    fun `a single device photo is the cover`() {
        assertEquals(a, FolderCoverSelection.pinnable(setOf(a), emptySet()))
    }

    @Test
    fun `an empty selection names no cover`() {
        assertNull(FolderCoverSelection.pinnable(emptySet(), emptySet()))
    }

    @Test
    fun `two selected photos name no cover`() {
        assertNull(FolderCoverSelection.pinnable(setOf(a, b), emptySet()))
    }

    @Test
    fun `a single hidden photo names no cover`() {
        assertNull(FolderCoverSelection.pinnable(setOf(vaulted), setOf(vaulted)))
    }

    @Test
    fun `a hidden photo beside a device one still names no cover`() {
        assertNull(FolderCoverSelection.pinnable(setOf(a, vaulted), setOf(vaulted)))
    }

    @Test
    fun `an unrelated hidden photo does not block the selected one`() {
        assertEquals(a, FolderCoverSelection.pinnable(setOf(a), setOf(vaulted)))
    }

    @Test
    fun `a blank uri names no cover`() {
        assertNull(FolderCoverSelection.pinnable(setOf(""), emptySet()))
    }
}
