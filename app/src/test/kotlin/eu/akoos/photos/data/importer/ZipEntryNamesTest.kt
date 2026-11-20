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

package eu.akoos.photos.data.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipEntryNamesTest {

    @Test fun `outside an import walk a leading slash is refused, as the platform does`() {
        assertTrue(ZipEntryNames.refuses("/", lenient = false))
        assertTrue(ZipEntryNames.refuses("/IMG_1.jpg", lenient = false))
    }

    @Test fun `outside an import walk a dot-dot step is refused, but dots inside a name are not`() {
        assertTrue(ZipEntryNames.refuses("a/../b.jpg", lenient = false))
        assertTrue(ZipEntryNames.refuses("../b.jpg", lenient = false))
        assertFalse(ZipEntryNames.refuses("a..b.jpg", lenient = false))
        assertFalse(ZipEntryNames.refuses("Trip/IMG_1.jpg", lenient = false))
    }

    @Test fun `inside an import walk every name is read`() {
        assertFalse(ZipEntryNames.refuses("/", lenient = true))
        assertFalse(ZipEntryNames.refuses("../b.jpg", lenient = true))
    }

    @Test fun `the walk marks only its own thread and only while it runs`() {
        val during = ZipEntryNames.lenient { ZipEntryNames.refuses("/") }
        var otherThread = false
        ZipEntryNames.lenient {
            Thread { otherThread = ZipEntryNames.refuses("/") }.apply { start(); join() }
        }

        assertFalse(during)
        assertTrue(otherThread)
        assertTrue(ZipEntryNames.refuses("/"))
    }

    @Test fun `a dot-dot step is what an import skips, a dotted name is not`() {
        assertTrue(ZipEntryNames.climbsOut("../x.jpg"))
        assertTrue(ZipEntryNames.climbsOut("a\\..\\x.jpg"))
        assertFalse(ZipEntryNames.climbsOut("a..b/x.jpg"))
    }
}
