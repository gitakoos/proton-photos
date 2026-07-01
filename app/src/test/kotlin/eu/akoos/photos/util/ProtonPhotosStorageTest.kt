/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [ProtonPhotosStorage] — the single source of truth for the on-device folder layout.
 * [DEFAULT_PICTURES]/[DEFAULT_MOVIES] read [android.os.Environment] constants, so the class needs
 * Robolectric; [sanitize] is pure string work but rides the same runner for one consistent setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtonPhotosStorageTest {

    // ─── default destinations ─────────────────────────────────────────────────

    @Test
    fun `DEFAULT_PICTURES is the flat Pictures directory`() {
        assertEquals(Environment.DIRECTORY_PICTURES, ProtonPhotosStorage.DEFAULT_PICTURES)
        assertEquals("Pictures", ProtonPhotosStorage.DEFAULT_PICTURES)
    }

    @Test
    fun `DEFAULT_MOVIES is the flat Movies directory`() {
        assertEquals(Environment.DIRECTORY_MOVIES, ProtonPhotosStorage.DEFAULT_MOVIES)
        assertEquals("Movies", ProtonPhotosStorage.DEFAULT_MOVIES)
    }

    @Test
    fun `defaults carry no legacy Proton Photos path segment`() {
        // The layout was flattened away from "Proton Photos/…"; the defaults must be bare bases.
        assertFalse(ProtonPhotosStorage.DEFAULT_PICTURES.contains(ProtonPhotosStorage.ROOT_NAME))
        assertFalse(ProtonPhotosStorage.DEFAULT_MOVIES.contains(ProtonPhotosStorage.ROOT_NAME))
    }

    // ─── sanitize: path-separator + illegal-char collapsing ───────────────────

    @Test
    fun `sanitize leaves a clean album name untouched`() {
        assertEquals("Summer 2026", ProtonPhotosStorage.sanitize("Summer 2026"))
    }

    @Test
    fun `sanitize replaces forward and back slashes with underscore`() {
        assertEquals("a_b_c", ProtonPhotosStorage.sanitize("a/b\\c"))
    }

    @Test
    fun `sanitize replaces a colon with underscore`() {
        assertEquals("9_30 AM", ProtonPhotosStorage.sanitize("9:30 AM"))
    }

    @Test
    fun `sanitize collapses runs of whitespace to a single space`() {
        assertEquals("My Album", ProtonPhotosStorage.sanitize("My     Album"))
    }

    @Test
    fun `sanitize trims leading and trailing whitespace`() {
        assertEquals("Trip", ProtonPhotosStorage.sanitize("   Trip   "))
    }

    @Test
    fun `sanitize collapses tabs and newlines as whitespace`() {
        assertEquals("a b", ProtonPhotosStorage.sanitize("a\t\nb"))
    }

    @Test
    fun `sanitize produces a single safe segment from a mixed nasty name`() {
        // A name combining nested separators, a colon and padding should collapse to one folder
        // segment with no path separators left in it.
        val out = ProtonPhotosStorage.sanitize("  Holiday/2026:Beach\\Day  ")
        assertEquals("Holiday_2026_Beach_Day", out)
        assertFalse(out.contains('/'))
        assertFalse(out.contains('\\'))
        assertFalse(out.contains(':'))
        assertFalse(out.startsWith(" "))
        assertFalse(out.endsWith(" "))
    }

    @Test
    fun `sanitize keeps unicode letters intact`() {
        assertEquals("Nyaralás 2026", ProtonPhotosStorage.sanitize("Nyaralás 2026"))
    }

    @Test
    fun `ROOT_NAME retains the legacy value for migration detection`() {
        // Still load-bearing for detecting pre-flat-layout files even though new writes don't use it.
        assertEquals("Proton Photos", ProtonPhotosStorage.ROOT_NAME)
    }
}
