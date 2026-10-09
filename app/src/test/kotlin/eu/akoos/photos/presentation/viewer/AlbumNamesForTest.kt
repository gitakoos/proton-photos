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

import eu.akoos.photos.domain.entity.Album
import org.junit.Assert.assertEquals
import org.junit.Test

/** The album names the photo-details sheet lists for a photo, from its album ids and the album list. */
class AlbumNamesForTest {

    private fun album(id: String, name: String) =
        Album(linkId = id, name = name, photoCount = 0, coverLinkId = null, lastActivityTimeMs = null)

    @Test
    fun lists_only_the_albums_the_photo_is_in_sorted_by_name() {
        val albums = listOf(album("a", "Zoo"), album("b", "Holiday"), album("c", "Other"))
        assertEquals(listOf("Holiday", "Zoo"), albumNamesFor(setOf("a", "b"), albums))
    }

    @Test
    fun a_photo_in_no_album_has_no_names() {
        assertEquals(emptyList<String>(), albumNamesFor(emptySet(), listOf(album("a", "Zoo"))))
    }

    @Test
    fun an_album_id_missing_from_the_list_is_skipped_and_equal_names_collapse() {
        val albums = listOf(album("a", "Trip"), album("b", "Trip"))
        assertEquals(listOf("Trip"), albumNamesFor(setOf("a", "b", "gone"), albums))
    }
}
