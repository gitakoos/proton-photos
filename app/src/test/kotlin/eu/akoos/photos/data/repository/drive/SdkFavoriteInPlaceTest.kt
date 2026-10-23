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
 * The Drive SDK moves or copies a photo outside the user's own timeline into it when it adds a favourite,
 * so only a photo on the own volume, filed under the photos root, may be favourited through it.
 */
class SdkFavoriteInPlaceTest {

    @Test
    fun `a photo in the own timeline is favourited through the SDK`() {
        assertTrue(sdkFavoriteKeepsPhotoInPlace("vol", "vol", parentLinkId = "root", photosRootLinkId = "root"))
    }

    @Test
    fun `a photo filed under an album stays on the app's own request`() {
        assertFalse(sdkFavoriteKeepsPhotoInPlace("vol", "vol", parentLinkId = "album", photosRootLinkId = "root"))
    }

    @Test
    fun `a photo on another volume stays on the app's own request`() {
        assertFalse(sdkFavoriteKeepsPhotoInPlace("their-vol", "vol", parentLinkId = "root", photosRootLinkId = "root"))
    }

    @Test
    fun `anything unknown stays on the app's own request`() {
        assertFalse(sdkFavoriteKeepsPhotoInPlace("vol", null, parentLinkId = "root", photosRootLinkId = "root"))
        assertFalse(sdkFavoriteKeepsPhotoInPlace("vol", "vol", parentLinkId = null, photosRootLinkId = "root"))
        assertFalse(sdkFavoriteKeepsPhotoInPlace("vol", "vol", parentLinkId = null, photosRootLinkId = null))
        assertFalse(sdkFavoriteKeepsPhotoInPlace("vol", "vol", parentLinkId = "", photosRootLinkId = ""))
    }
}
