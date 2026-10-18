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

package eu.akoos.photos.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMirrorFoldersTest {

    @Test
    fun `without the everything default only the switched on folders are albums`() {
        val rule = AlbumMirrorFolders(optedIn = setOf("Camera"))
        assertTrue("Camera" in rule)
        assertFalse("Screenshots" in rule)
    }

    @Test
    fun `the everything default makes every folder an album, a new one included`() {
        val rule = AlbumMirrorFolders(allByDefault = true)
        assertTrue("Camera" in rule)
        assertTrue("A folder made tomorrow" in rule)
    }

    @Test
    fun `the everything default leaves out the folders switched off`() {
        val rule = AlbumMirrorFolders(optedOut = setOf("Screenshots"), allByDefault = true)
        assertFalse("Screenshots" in rule)
        assertTrue("Camera" in rule)
    }

    @Test
    fun `flipping a folder that is on switches it off, and flipping it again back on`() {
        val everything = AlbumMirrorFolders(allByDefault = true)
        val off = everything.flipped("Camera")
        assertFalse("Camera" in off)
        assertEquals(setOf("Camera"), off.optedOut)
        val on = off.flipped("Camera")
        assertTrue("Camera" in on)
        assertTrue(on.optedOut.isEmpty())
        assertEquals(setOf("Camera"), on.optedIn)
    }

    @Test
    fun `turning backup everything off keeps only what was switched on by hand`() {
        val everything = AlbumMirrorFolders(optedIn = setOf("Camera"), allByDefault = true)
        val selected = everything.copy(allByDefault = false)
        assertTrue("Camera" in selected)
        assertFalse("Downloads" in selected)
    }
}
