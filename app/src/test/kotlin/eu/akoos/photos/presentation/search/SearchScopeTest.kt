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

package eu.akoos.photos.presentation.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchScopeTest {

    @Test
    fun cloudAlbumKeyYieldsItsLinkId() {
        val scope = SearchScope("c:abc123", "Holiday")
        assertEquals("abc123", scope.cloudLinkId)
        assertNull(scope.folder)
    }

    @Test
    fun deviceFolderKeyYieldsItsBucketName() {
        val scope = SearchScope("f:Camera", "Camera")
        assertEquals("Camera", scope.folder)
        assertNull(scope.cloudLinkId)
    }

    @Test
    fun aFolderNamedLikeAnAlbumKeyKeepsItsWholeName() {
        val scope = SearchScope("f:c:Trip", "c:Trip")
        assertEquals("c:Trip", scope.folder)
        assertNull(scope.cloudLinkId)
    }
}
