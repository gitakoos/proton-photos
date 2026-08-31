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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [moveTargetRelativePath], the one pure decision behind a move: it turns a user-typed folder name
 * into the MediaStore RELATIVE_PATH the in-place update writes. The target must always be a single safe
 * segment nested under DCIM/ with a trailing slash, and a name that reduces to nothing must be rejected
 * rather than producing a bare "DCIM/" that would scatter files into the DCIM root.
 */
class MoveToFolderTargetsTest {

    @Test
    fun `a clean name nests under DCIM with a trailing slash`() {
        assertEquals("DCIM/Budapest/", moveTargetRelativePath("Budapest"))
    }

    @Test
    fun `internal spaces are preserved`() {
        assertEquals("DCIM/Summer 2026/", moveTargetRelativePath("Summer 2026"))
    }

    @Test
    fun `forward and back slashes collapse to underscores so the folder stays one segment`() {
        assertEquals("DCIM/Summer_2026/", moveTargetRelativePath("Summer/2026"))
        assertEquals("DCIM/a_b/", moveTargetRelativePath("a\\b"))
    }

    @Test
    fun `a colon is replaced with an underscore`() {
        assertEquals("DCIM/9_30 AM/", moveTargetRelativePath("9:30 AM"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("DCIM/Trip/", moveTargetRelativePath("   Trip   "))
    }

    @Test
    fun `runs of whitespace collapse to a single space`() {
        assertEquals("DCIM/My Album/", moveTargetRelativePath("My     Album"))
        assertEquals("DCIM/a b/", moveTargetRelativePath("a\t\nb"))
    }

    @Test
    fun `a mixed nasty name reduces to one safe segment`() {
        val out = moveTargetRelativePath("  Holiday/2026:Beach\\Day  ")
        assertEquals("DCIM/Holiday_2026_Beach_Day/", out)
    }

    @Test
    fun `unicode letters are kept intact`() {
        assertEquals("DCIM/Nyaralás 2026/", moveTargetRelativePath("Nyaralás 2026"))
    }

    @Test
    fun `the target always starts at DCIM and ends with a slash and holds a single segment`() {
        for (name in listOf("Budapest", "Summer/2026", "9:30 AM", "  Holiday\\Trip  ", "One Two Three")) {
            val out = moveTargetRelativePath(name)
            assertTrue("must be rooted at DCIM/: $out", out.startsWith("DCIM/"))
            assertTrue("must end with a slash: $out", out.endsWith("/"))
            // DCIM/<segment>/ has exactly two separators; anything more means a stray path separator
            // survived sanitizing and the file would land in an unintended nested folder.
            assertEquals("must be a single segment under DCIM: $out", 2, out.count { it == '/' })
            assertFalse("no backslash may survive: $out", out.contains('\\'))
            assertFalse("no colon may survive: $out", out.contains(':'))
        }
    }

    @Test
    fun `a blank or separators-only name is rejected instead of yielding a bare DCIM path`() {
        assertThrows(IllegalArgumentException::class.java) { moveTargetRelativePath("") }
        assertThrows(IllegalArgumentException::class.java) { moveTargetRelativePath("   ") }
        assertThrows(IllegalArgumentException::class.java) { moveTargetRelativePath("\t\n") }
    }
}
