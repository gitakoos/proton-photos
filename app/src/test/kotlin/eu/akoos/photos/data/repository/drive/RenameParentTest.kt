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

package eu.akoos.photos.data.repository.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Branch coverage for [renameParentFor], the gate an in-place photo rename passes before any key is
 * chosen. The cost of a wrong answer is asymmetric: a needless [RenameParent.UNSUPPORTED] only
 * declines a rename, while a wrong [RenameParent.PHOTOS_ROOT] encrypts the new name to a key the
 * link's recorded parent does not hold, which stores a name nothing can read back. Every case that
 * is not an exact, non-empty match is therefore expected to decline.
 */
class RenameParentTest {

    private val photosRoot = "photos-root-link"
    private val album = "album-link"

    @Test
    fun `wire parent matching the photos root takes the root key set`() {
        assertEquals(RenameParent.PHOTOS_ROOT, renameParentFor(photosRoot, photosRoot))
    }

    @Test
    fun `album wire parent is declined`() {
        // A photo whose NodePassphrase was rewrapped to an album key keeps a photos-root wire
        // parent, so an album showing up HERE means the server really files the link under the
        // album and the root NodeHashKey answers for a different name space.
        assertEquals(RenameParent.UNSUPPORTED, renameParentFor(album, photosRoot))
    }

    @Test
    fun `unknown wire parent is declined`() {
        assertEquals(RenameParent.UNSUPPORTED, renameParentFor(null, photosRoot))
    }

    @Test
    fun `unresolved photos root is declined`() {
        // A session that has not bootstrapped its root link yet cannot vouch for any parent.
        assertEquals(RenameParent.UNSUPPORTED, renameParentFor(photosRoot, null))
    }

    @Test
    fun `two blank ids do not count as a match`() {
        assertEquals(RenameParent.UNSUPPORTED, renameParentFor("", ""))
        assertEquals(RenameParent.UNSUPPORTED, renameParentFor("", photosRoot))
    }

    @Test
    fun `null on both sides is declined`() {
        assertEquals(RenameParent.UNSUPPORTED, renameParentFor(null, null))
    }
}
