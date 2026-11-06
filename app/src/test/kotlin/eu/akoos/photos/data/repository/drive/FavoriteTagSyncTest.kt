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

import eu.akoos.photos.data.db.dao.LinkTags
import org.junit.Assert.assertEquals
import org.junit.Test

/** A favourite given or taken away on another device reaches the stored rows. */
class FavoriteTagSyncTest {

    @Test
    fun `a photo favourited elsewhere gains the tag, keeping its other tags`() {
        val writes = favoriteTagWrites(setOf("a"), listOf(LinkTags("a", "3,5")), emptyList())
        assertEquals(mapOf("a" to "0,3,5"), writes)
    }

    @Test
    fun `a photo unfavourited elsewhere loses only that tag`() {
        val writes = favoriteTagWrites(emptySet(), emptyList(), listOf(LinkTags("b", "0,4")))
        assertEquals(mapOf("b" to "4"), writes)
    }

    @Test
    fun `rows already right are not written`() {
        val writes = favoriteTagWrites(setOf("a"), listOf(LinkTags("a", "0")), listOf(LinkTags("a", "0")))
        assertEquals(emptyMap<String, String>(), writes)
    }

    @Test
    fun `a favourite the library does not hold yet writes nothing`() {
        assertEquals(emptyMap<String, String>(), favoriteTagWrites(setOf("new"), emptyList(), emptyList()))
    }
}
