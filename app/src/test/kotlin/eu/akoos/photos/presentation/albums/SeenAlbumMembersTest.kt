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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenAlbumMembersTest {

    @Test
    fun `a photo a later reload dropped still counts as a member`() {
        val members = SeenAlbumMembers()
        members.startAlbum("album-1")
        members.add(listOf("p1", "p2"))

        members.startAlbum("album-1")
        members.add(emptyList())

        assertTrue(members.containsAny(listOf("p2")))
    }

    @Test
    fun `opening another album forgets the first album's photos`() {
        val members = SeenAlbumMembers()
        members.startAlbum("album-1")
        members.add(listOf("p1"))

        members.startAlbum("album-2")

        assertFalse(members.containsAny(listOf("p1")))
    }

    @Test
    fun `a photo never listed in the album is not a member`() {
        val members = SeenAlbumMembers()
        members.startAlbum("album-1")
        members.add(listOf("p1"))

        assertFalse(members.containsAny(listOf("p9")))
    }

    @Test
    fun `an empty id list matches nothing`() {
        val members = SeenAlbumMembers()
        members.startAlbum("album-1")
        members.add(listOf("p1"))

        assertFalse(members.containsAny(emptyList()))
    }
}
