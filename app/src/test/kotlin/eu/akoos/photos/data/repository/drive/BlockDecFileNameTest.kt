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

package eu.akoos.photos.data.repository.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A download through the Drive SDK clears the block files an interrupted download left for the same
 * photo, and only those: a photo whose id merely starts the same keeps its own resume state.
 */
class BlockDecFileNameTest {

    @Test
    fun `the photo's own block files match`() {
        assertTrue(isBlockDecFileOf("dec_abc_0.bin", "abc"))
        assertTrue(isBlockDecFileOf("dec_abc_17.bin", "abc"))
    }

    @Test
    fun `another photo whose id starts the same is left alone`() {
        assertFalse(isBlockDecFileOf("dec_abc_1_0.bin", "abc"))
        assertFalse(isBlockDecFileOf("dec_abcd_0.bin", "abc"))
    }

    @Test
    fun `other files in the cache are left alone`() {
        assertFalse(isBlockDecFileOf("abc.hashes", "abc"))
        assertFalse(isBlockDecFileOf("dec_abc_.bin", "abc"))
        assertFalse(isBlockDecFileOf("dec_abc_0.bin.tmp", "abc"))
        assertFalse(isBlockDecFileOf("abc.jpg", "abc"))
    }
}
