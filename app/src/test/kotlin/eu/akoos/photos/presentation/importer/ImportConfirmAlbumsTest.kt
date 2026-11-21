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

package eu.akoos.photos.presentation.importer

import eu.akoos.photos.domain.importer.ImportAlbumMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether confirming the review builds albums, which decides the confirm button when nothing is new. */
class ImportConfirmAlbumsTest {

    @Test
    fun `an export without album folders builds no album in the default mode`() {
        assertFalse(buildsAlbums(hasAlbums = false, albumMode = ImportAlbumMode.DEFAULT))
    }

    @Test
    fun `an export with album folders builds its albums in the default mode`() {
        assertTrue(buildsAlbums(hasAlbums = true, albumMode = ImportAlbumMode.DEFAULT))
    }

    @Test
    fun `empty album shells count as building albums`() {
        assertTrue(buildsAlbums(hasAlbums = true, albumMode = ImportAlbumMode.SHELLS_ONLY))
    }

    @Test
    fun `photos only builds no album even when the export has album folders`() {
        assertFalse(buildsAlbums(hasAlbums = true, albumMode = ImportAlbumMode.NONE))
    }
}
