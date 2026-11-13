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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPersonTokenTest {

    @Test
    fun `a made token is recognised and two are never the same`() {
        val a = HiddenPersonToken.make()
        val b = HiddenPersonToken.make()
        assertTrue(HiddenPersonToken.isToken(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `an ordinary name, blank or missing is not a token`() {
        assertFalse(HiddenPersonToken.isToken("Anna"))
        assertFalse(HiddenPersonToken.isToken("hidden"))
        assertFalse(HiddenPersonToken.isToken(""))
        assertFalse(HiddenPersonToken.isToken(null))
    }

    @Test
    fun `a token survives the trim and case fold the name rules apply`() {
        val token = HiddenPersonToken.make()
        assertEquals(token, token.trim())
        assertTrue(samePersonName(token, token.uppercase()))
        assertTrue(HiddenPersonToken.isToken(token.trim().lowercase()))
    }

    @Test
    fun `a surface sees no name for a token or a blank`() {
        assertNull(HiddenPersonToken.visibleName(HiddenPersonToken.make()))
        assertNull(HiddenPersonToken.visibleName("  "))
        assertNull(HiddenPersonToken.visibleName(null))
        assertEquals("Anna", HiddenPersonToken.visibleName("Anna"))
    }
}
