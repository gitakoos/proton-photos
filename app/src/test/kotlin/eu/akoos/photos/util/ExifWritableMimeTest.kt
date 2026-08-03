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

package eu.akoos.photos.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the shared writable-image mime gate that the metadata writer and the downloader
 * both route through: only JPEG / PNG / WebP take the embedded EXIF leg, and the check is case- and
 * parameter-insensitive. No Android is instantiated.
 */
class ExifWritableMimeTest {

    @Test
    fun `jpeg png webp are writable across case and parameters`() {
        assertTrue(isExifWritableImageMime("image/jpeg"))
        assertTrue(isExifWritableImageMime("image/JPEG"))
        assertTrue(isExifWritableImageMime("image/jpeg; codecs=x"))
        assertTrue(isExifWritableImageMime("image/png"))
        assertTrue(isExifWritableImageMime("image/webp"))
    }

    @Test
    fun `heic heif avif video and blank are not writable`() {
        assertFalse(isExifWritableImageMime("image/heic"))
        assertFalse(isExifWritableImageMime("image/heif"))
        assertFalse(isExifWritableImageMime("image/avif"))
        assertFalse(isExifWritableImageMime("video/mp4"))
        assertFalse(isExifWritableImageMime(""))
    }
}
