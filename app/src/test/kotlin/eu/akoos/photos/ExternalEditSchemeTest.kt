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

package eu.akoos.photos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external edit/view parser hands off only content:// URIs. [isAcceptableExternalEditScheme]
 * gates that decision: a caller must not be able to aim the app at a file:// path (its own private
 * storage) through a shared ACTION_EDIT / ACTION_VIEW intent. Pure predicate, no Android runtime.
 */
class ExternalEditSchemeTest {

    @Test
    fun `a content scheme is accepted`() {
        assertTrue(isAcceptableExternalEditScheme("content"))
    }

    @Test
    fun `a file scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme("file"))
    }

    @Test
    fun `a missing scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme(null))
    }

    @Test
    fun `an http scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme("http"))
    }

    @Test
    fun `an empty scheme is rejected`() {
        assertFalse(isAcceptableExternalEditScheme(""))
    }
}
