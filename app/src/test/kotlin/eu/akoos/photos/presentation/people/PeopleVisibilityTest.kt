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

package eu.akoos.photos.presentation.people

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleVisibilityTest {

    @Test
    fun `scanning on this phone shows the people surfaces`() {
        assertTrue(peopleSurfacesVisible(scanningHere = true, signedIn = true, accountHoldsPeople = false))
        assertTrue(peopleSurfacesVisible(scanningHere = true, signedIn = false, accountHoldsPeople = false))
    }

    @Test
    fun `a signed in account with people sees them with the scan off`() {
        assertTrue(peopleSurfacesVisible(scanningHere = false, signedIn = true, accountHoldsPeople = true))
    }

    @Test
    fun `a signed in account with no people and no scan sees nothing`() {
        assertFalse(peopleSurfacesVisible(scanningHere = false, signedIn = true, accountHoldsPeople = false))
    }

    @Test
    fun `a guest follows the scan switch alone`() {
        assertFalse(peopleSurfacesVisible(scanningHere = false, signedIn = false, accountHoldsPeople = true))
    }
}
