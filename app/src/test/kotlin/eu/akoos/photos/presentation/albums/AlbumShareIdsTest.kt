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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins which share id the album screen keeps after a call that can create one.
 *
 * Managing a share is addressed by its id, so the cases that matter are the ones where a bad
 * answer strands the member list and every permission control: adopting nothing when a share was
 * just minted, and adopting a non-id over one that works. Plain JVM assertions, no Android.
 */
class AlbumShareIdsTest {

    @Test
    fun `a newly minted share becomes the held id`() {
        assertEquals("share-1", AlbumShareIds.resolve(current = null, created = "share-1"))
    }

    @Test
    fun `a failed create keeps the id already held`() {
        assertEquals("share-1", AlbumShareIds.resolve(current = "share-1", created = null))
    }

    @Test
    fun `a failed create on an unshared album holds no id`() {
        assertNull(AlbumShareIds.resolve(current = null, created = null))
    }

    @Test
    fun `a blank id names no share and never replaces one`() {
        assertEquals("share-1", AlbumShareIds.resolve(current = "share-1", created = ""))
        assertEquals("share-1", AlbumShareIds.resolve(current = "share-1", created = "   "))
        assertNull(AlbumShareIds.resolve(current = null, created = ""))
    }

    @Test
    fun `a reported id replaces a stale one`() {
        assertEquals("share-2", AlbumShareIds.resolve(current = "share-1", created = "share-2"))
    }

    @Test
    fun `a batch takes the last id that names a share`() {
        val reported = listOf("share-1", null, "share-2")
        assertEquals("share-2", AlbumShareIds.resolveBatch(current = null, created = reported))
    }

    @Test
    fun `a batch ignores trailing failures and blanks`() {
        val reported = listOf("share-1", null, "", "   ")
        assertEquals("share-1", AlbumShareIds.resolveBatch(current = null, created = reported))
    }

    @Test
    fun `a batch where every call failed keeps the id already held`() {
        assertEquals("share-1", AlbumShareIds.resolveBatch("share-1", listOf(null, null)))
        assertNull(AlbumShareIds.resolveBatch(null, listOf(null, null)))
    }

    @Test
    fun `an empty batch changes nothing`() {
        assertEquals("share-1", AlbumShareIds.resolveBatch("share-1", emptyList()))
        assertNull(AlbumShareIds.resolveBatch(null, emptyList()))
    }
}
