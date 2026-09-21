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

package eu.akoos.photos.presentation.albums

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that decides whether an album rename is worth confirming.
 *
 * The two rename surfaces share it, and both ViewModels reject the same two cases, so the
 * interesting inputs are the ones where whitespace decides the answer. Plain JVM assertions,
 * no Android.
 */
class AlbumRenameInputTest {

    @Test
    fun `a different name is acceptable`() {
        assertTrue(AlbumRenameInput.isAcceptable("Holiday 2026", "Holiday"))
    }

    @Test
    fun `an empty or whitespace-only name is rejected`() {
        assertFalse(AlbumRenameInput.isAcceptable("", "Holiday"))
        assertFalse(AlbumRenameInput.isAcceptable("   ", "Holiday"))
        assertFalse(AlbumRenameInput.isAcceptable("\n\t", "Holiday"))
    }

    @Test
    fun `the unchanged name is rejected`() {
        assertFalse(AlbumRenameInput.isAcceptable("Holiday", "Holiday"))
    }

    @Test
    fun `padding alone does not make a new name`() {
        assertFalse(AlbumRenameInput.isAcceptable("  Holiday  ", "Holiday"))
    }

    @Test
    fun `a name whose trimmed form differs is acceptable`() {
        assertTrue(AlbumRenameInput.isAcceptable("  Holiday 2026  ", "Holiday"))
    }

    @Test
    fun `the comparison keeps the current name untrimmed`() {
        // The stored name is what the server holds; only the typed side is trimmed, so an album
        // that really is called " Holiday " stays renameable to "Holiday".
        assertTrue(AlbumRenameInput.isAcceptable("Holiday", " Holiday "))
    }

    @Test
    fun `case and accents count as a change`() {
        assertTrue(AlbumRenameInput.isAcceptable("holiday", "Holiday"))
        assertTrue(AlbumRenameInput.isAcceptable("Nyaralás", "Nyaralas"))
    }

    @Test
    fun `a first name for a blank-named album is acceptable`() {
        assertTrue(AlbumRenameInput.isAcceptable("Holiday", ""))
    }
}
