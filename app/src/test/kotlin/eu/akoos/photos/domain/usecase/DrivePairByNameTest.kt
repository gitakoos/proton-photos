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

package eu.akoos.photos.domain.usecase

import eu.akoos.photos.data.db.entity.LinkIdName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [pairByUniqueName], the name-only pairing that finds the Drive photo of a device file the backup state does
 * not pair, the same rule the timeline uses to show it with a green cloud: one Drive photo of that name pairs, two
 * or more refuse to guess, and a Drive photo two device files would claim pairs with neither.
 */
class DrivePairByNameTest {

    @Test
    fun `a single Drive photo of the name pairs, case aside`() {
        assertEquals(
            mapOf("u1" to "L1"),
            pairByUniqueName(mapOf("u1" to "SyncTest_01.jpg"), listOf(LinkIdName("L1", "synctest_01.JPG"))),
        )
    }

    @Test
    fun `a name two Drive photos carry pairs with nothing`() {
        assertEquals(
            emptyMap<String, String>(),
            pairByUniqueName(
                mapOf("u1" to "IMG_1.jpg"),
                listOf(LinkIdName("L1", "IMG_1.jpg"), LinkIdName("L2", "IMG_1.jpg")),
            ),
        )
    }

    @Test
    fun `a Drive photo two device files would claim pairs with neither`() {
        assertEquals(
            emptyMap<String, String>(),
            pairByUniqueName(mapOf("u1" to "a.jpg", "u2" to "A.jpg"), listOf(LinkIdName("L1", "a.jpg"))),
        )
    }

    @Test
    fun `a file with no Drive photo of its name stays unpaired`() {
        assertEquals(
            mapOf("u1" to "L1"),
            pairByUniqueName(mapOf("u1" to "a.jpg", "u2" to "b.jpg"), listOf(LinkIdName("L1", "a.jpg"))),
        )
    }
}
