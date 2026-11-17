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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The account's own album keys are held for that account only and zeroed when they go. */
class OwnerAlbumKeyCacheTest {

    @Test
    fun `a key is held for the account that decrypted it`() {
        val cache = OwnerAlbumKeyCache()
        val key = byteArrayOf(7, 7)
        cache.put("u1", "A1", key)

        assertSame(key, cache.get("u1", "A1"))
    }

    @Test
    fun `a sign-out zeroes and drops every key`() {
        val cache = OwnerAlbumKeyCache()
        val key = byteArrayOf(7, 7)
        cache.put("u1", "A1", key)

        cache.clear()

        assertArrayEquals(byteArrayOf(0, 0), key)
        assertNull(cache.get("u1", "A1"))
    }

    @Test
    fun `another account never reads the previous account's keys, which are zeroed`() {
        val cache = OwnerAlbumKeyCache()
        val key = byteArrayOf(7, 7)
        cache.put("u1", "A1", key)

        assertNull(cache.get("u2", "A1"))
        assertArrayEquals(byteArrayOf(0, 0), key)
        // Switching back finds nothing either: the keys went with the switch.
        assertNull(cache.get("u1", "A1"))
    }

    @Test
    fun `a key stored again replaces the old one, which is zeroed`() {
        val cache = OwnerAlbumKeyCache()
        val old = byteArrayOf(7, 7)
        val new = byteArrayOf(8, 8)
        cache.put("u1", "A1", old)
        cache.put("u1", "A1", new)

        assertArrayEquals(byteArrayOf(0, 0), old)
        assertSame(new, cache.get("u1", "A1"))
    }
}
