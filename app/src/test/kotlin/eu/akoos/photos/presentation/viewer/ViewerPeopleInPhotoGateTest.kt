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

package eu.akoos.photos.presentation.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [viewerOffersPeopleInPhoto], the menu entry that scans the photo on screen for faces: offered with face
 * recognition on, and never on a photo put away, so a vaulted or hidden photo is never scanned or tagged.
 */
class ViewerPeopleInPhotoGateTest {

    private fun offers(
        faceFeaturesOn: Boolean = true,
        readOnlyAlbum: Boolean = false,
        secure: Boolean = false,
        vaulted: Boolean = false,
        hidden: Boolean = false,
    ) = viewerOffersPeopleInPhoto(faceFeaturesOn, readOnlyAlbum, secure, vaulted, hidden)

    @Test
    fun `an ordinary photo offers it with face recognition on`() {
        assertTrue(offers())
        assertFalse(offers(faceFeaturesOn = false))
    }

    @Test
    fun `a shared album the user cannot edit never offers it`() {
        assertFalse(offers(readOnlyAlbum = true))
    }

    @Test
    fun `a photo put away never offers it`() {
        assertFalse("the secure viewer", offers(secure = true))
        assertFalse("a vaulted photo", offers(vaulted = true))
        assertFalse("a hidden photo", offers(hidden = true))
    }
}
