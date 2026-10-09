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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.Album
import org.junit.Assert.assertEquals
import org.junit.Test

/** The name search on the Add to album screen: case and accents ignored, the order of the list kept. */
class AlbumsMatchingNameTest {

    private fun album(id: String, name: String) =
        Album(linkId = id, name = name, photoCount = 0, coverLinkId = null, lastActivityTimeMs = null)

    private val albums = listOf(album("1", "Nyár 2024"), album("2", "Holiday"), album("3", "Winter"), album("4", "Istanbul"))

    @Test
    fun `a blank query keeps every album in order`() {
        assertEquals(albums, albumsMatchingName(albums, "  "))
    }

    @Test
    fun `case and accents are ignored`() {
        assertEquals(listOf("1"), albumsMatchingName(albums, "NYAR").map { it.linkId })
    }

    @Test
    fun `a Turkish dotless i still finds its album`() {
        assertEquals(listOf("4"), albumsMatchingName(albums, "ISTANBUL").map { it.linkId })
        assertEquals(listOf("4"), albumsMatchingName(albums, "\u0131stanbul").map { it.linkId })
    }

    @Test
    fun `the match is anywhere in the name and the list order is kept`() {
        assertEquals(listOf("2", "3"), albumsMatchingName(albums, "i").map { it.linkId }.filter { it in setOf("2", "3") })
        assertEquals(emptyList<Album>(), albumsMatchingName(albums, "zzz"))
    }
}
