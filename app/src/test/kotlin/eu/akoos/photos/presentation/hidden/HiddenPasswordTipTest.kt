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

package eu.akoos.photos.presentation.hidden

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPasswordTipTest {

    @Test
    fun `a signed-in account without a Hidden password gets the tip`() {
        assertTrue(hiddenPasswordTipDue(signedIn = true, passwordSet = false))
    }

    @Test
    fun `the tip goes once a password is set`() {
        assertFalse(hiddenPasswordTipDue(signedIn = true, passwordSet = true))
    }

    @Test
    fun `a guest never gets the tip, its own warning covers it`() {
        assertFalse(hiddenPasswordTipDue(signedIn = false, passwordSet = false))
        assertFalse(hiddenPasswordTipDue(signedIn = false, passwordSet = true))
    }
}
