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
import eu.akoos.photos.presentation.common.AlbumMembership
import eu.akoos.photos.presentation.gallery.pickerTapRemoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsAlbumsTest {

    private fun album(id: String, name: String, sharedBy: String? = null, permissions: Long? = null) =
        Album(id, name, photoCount = 1, coverLinkId = null, lastActivityTimeMs = null, sharedByEmail = sharedBy, permissions = permissions)

    private val summer = album("a", "Summer")
    private val family = album("b", "family")
    private val work = album("c", "Work")
    private val sharedEditor = album("d", "Club", sharedBy = "x@y", permissions = 6L)
    private val sharedViewer = album("e", "Trip", sharedBy = "x@y", permissions = 4L)

    @Test
    fun member_albums_come_by_name() {
        val member = detailsMemberAlbums(listOf(work, summer, family, sharedEditor), setOf("a", "b", "d"), emptySet())
        assertEquals(listOf("Club", "family", "Summer"), member.map { it.album.name })
    }

    @Test
    fun a_queued_join_shows_as_pending() {
        val member = detailsMemberAlbums(listOf(summer, family, work), setOf("a"), setOf("b"))
        assertEquals(listOf("family" to true, "Summer" to false), member.map { it.album.name to it.pending })
    }

    @Test
    fun an_album_known_only_by_id_is_left_out_and_duplicates_count_once() {
        val member = detailsMemberAlbums(listOf(summer, summer, work), setOf("a", "zz"), emptySet())
        assertEquals(listOf("Summer"), member.map { it.album.name })
    }

    @Test
    fun the_box_shows_three_covers_and_counts_the_rest() {
        assertEquals(listOf(1, 2) to 0, coversAndRest(listOf(1, 2)))
        assertEquals(listOf(1, 2, 3) to 0, coversAndRest(listOf(1, 2, 3)))
        assertEquals(listOf(1, 2, 3) to 2, coversAndRest(listOf(1, 2, 3, 4, 5)))
        assertEquals(emptyList<Int>() to 0, coversAndRest(emptyList<Int>()))
    }

    @Test
    fun a_picker_tap_removes_only_when_the_album_holds_the_whole_cloud_selection() {
        assertTrue(pickerTapRemoves(AlbumMembership.All, allInCloud = true, album = summer))
        assertFalse(pickerTapRemoves(AlbumMembership.Some(1, 2), allInCloud = true, album = summer))
        assertFalse(pickerTapRemoves(AlbumMembership.None, allInCloud = true, album = summer))
        // A device-only photo in the selection still needs adding, so the tap adds.
        assertFalse(pickerTapRemoves(AlbumMembership.All, allInCloud = false, album = summer))
    }

    @Test
    fun a_picker_tap_removes_from_a_shared_album_only_with_the_right_to_change_it() {
        assertTrue(pickerTapRemoves(AlbumMembership.All, allInCloud = true, album = sharedEditor))
        assertFalse(pickerTapRemoves(AlbumMembership.All, allInCloud = true, album = sharedViewer))
    }
}
