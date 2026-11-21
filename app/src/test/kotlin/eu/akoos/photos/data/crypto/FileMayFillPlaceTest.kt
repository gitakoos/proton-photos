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

package eu.akoos.photos.data.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A place left out of a cloud image's XAttr on purpose is never brought back from the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileMayFillPlaceTest {

    @Test
    fun `an image whose XAttr records a Camera block keeps its missing place`() {
        assertFalse(fileMayFillPlace("image/jpeg", hasXAttr = true, xAttrJson = """{"Camera":{"Orientation":1}}"""))
    }

    @Test
    fun `an image whose XAttr could not be decrypted is not filled from its file`() {
        assertFalse(fileMayFillPlace("image/heic", hasXAttr = true, xAttrJson = null))
    }

    @Test
    fun `an image with no XAttr may take its place from its file`() {
        assertTrue(fileMayFillPlace("image/jpeg", hasXAttr = false, xAttrJson = null))
    }

    @Test
    fun `an image whose XAttr was written without reading its EXIF may take its place from its file`() {
        assertTrue(fileMayFillPlace("image/jpeg", hasXAttr = true, xAttrJson = """{"Common":{"Size":10}}"""))
    }

    @Test
    fun `a video may take its place from its file`() {
        assertTrue(fileMayFillPlace("video/mp4", hasXAttr = true, xAttrJson = null))
    }
}
