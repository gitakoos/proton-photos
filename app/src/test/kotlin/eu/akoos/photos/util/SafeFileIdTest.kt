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

package eu.akoos.photos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFileIdTest {
    @Test
    fun `base64url style ids pass unchanged`() {
        val id = "Ab3_-xYz0987654321=="
        assertEquals(id, safeFileId(id))
        assertEquals("a", safeFileId("a"))
        val max = "a".repeat(200)
        assertEquals(max, safeFileId(max))
    }

    @Test
    fun `unsafe ids become a hash name`() {
        for (bad in listOf("../x", "a/b", "a\b", "..", ".", "", "a b", "a.b", "a".repeat(201), "ä")) {
            val out = safeFileId(bad)
            assertTrue("$bad -> $out", out.matches(Regex("^[0-9a-f]{64}$")))
        }
    }

    @Test
    fun `mapping is deterministic and distinct`() {
        assertEquals(safeFileId("../x"), safeFileId("../x"))
        assertNotEquals(safeFileId("../x"), safeFileId("../y"))
    }
}
