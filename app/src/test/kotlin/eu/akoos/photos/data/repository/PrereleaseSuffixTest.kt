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

package eu.akoos.photos.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag filter the auto-check applies before it compares versions at all. GitHub's own
 * pre-release flag is the first gate, but a preview can be published as a full release, so the tag
 * name is checked independently: an installed base on stable must never be walked onto a test
 * build by a background check nobody watched.
 *
 * The match is on the suffix, so a stable tag is never caught by a substring of its own digits or
 * by a word appearing before the dash.
 */
class PrereleaseSuffixTest {

    @Test
    fun `a stable tag is kept`() {
        assertFalse(hasPrereleaseSuffix("v2.4.1"))
        assertFalse(hasPrereleaseSuffix("2.4.1"))
        assertFalse(hasPrereleaseSuffix("v2.4.10"))
    }

    @Test
    fun `every pre-release suffix is skipped`() {
        assertTrue(hasPrereleaseSuffix("v2.4.1-beta"))
        assertTrue(hasPrereleaseSuffix("v2.4.1-alpha"))
        assertTrue(hasPrereleaseSuffix("v2.4.1-rc1"))
        assertTrue(hasPrereleaseSuffix("v2.4.1-pre"))
        assertTrue(hasPrereleaseSuffix("v2.4.1-snapshot"))
    }

    @Test
    fun `the match ignores case`() {
        assertTrue(hasPrereleaseSuffix("v2.4.1-BETA"))
        assertTrue(hasPrereleaseSuffix("V2.4.1-RC2"))
        assertTrue(hasPrereleaseSuffix("v2.4.1-Snapshot"))
    }

    @Test
    fun `a numbered suffix keeps its marker`() {
        assertTrue(hasPrereleaseSuffix("v2.4.1-beta2"))
        assertTrue(hasPrereleaseSuffix("v2.4.1-alpha10"))
    }

    @Test
    fun `the dash is required`() {
        // The word alone is not a pre-release marker, so a release name carrying it stays stable.
        assertFalse(hasPrereleaseSuffix("v2.4.1beta"))
        assertFalse(hasPrereleaseSuffix("precision-2.4.1"))
    }

    @Test
    fun `an empty tag is not a pre-release`() {
        assertFalse(hasPrereleaseSuffix(""))
    }
}
