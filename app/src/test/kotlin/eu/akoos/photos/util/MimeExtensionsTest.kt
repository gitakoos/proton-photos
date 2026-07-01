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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [mimeToFileExtension] — the single source of truth mapping a MIME type to the
 * cache-file extension. The explicit table entries exist because a naive subtype split yields
 * container hints (".quicktime", ".x-matroska") that ExoPlayer/Coil can't decode, so each special
 * case is locked down here, plus the subtype fallback and the video/audio-split on "mpeg".
 */
class MimeExtensionsTest {

    @Test
    fun `jpeg maps to jpg`() {
        assertEquals("jpg", mimeToFileExtension("image/jpeg"))
    }

    @Test
    fun `png falls through to the raw subtype`() {
        // Not in the explicit table; the subtype IS the extension.
        assertEquals("png", mimeToFileExtension("image/png"))
    }

    @Test
    fun `quicktime maps to mov not the unusable raw subtype`() {
        assertEquals("mov", mimeToFileExtension("video/quicktime"))
    }

    @Test
    fun `matroska maps to mkv`() {
        assertEquals("mkv", mimeToFileExtension("video/x-matroska"))
    }

    @Test
    fun `msvideo maps to avi and ms-wmv maps to wmv`() {
        assertEquals("avi", mimeToFileExtension("video/x-msvideo"))
        assertEquals("wmv", mimeToFileExtension("video/x-ms-wmv"))
    }

    @Test
    fun `mpeg resolves to mpg for video but mp3 for audio`() {
        // The same "mpeg" subtype splits on the top-level type.
        assertEquals("mpg", mimeToFileExtension("video/mpeg"))
        assertEquals("mp3", mimeToFileExtension("audio/mpeg"))
    }

    @Test
    fun `subtype is lowercased`() {
        assertEquals("png", mimeToFileExtension("IMAGE/PNG"))
    }

    @Test
    fun `webp and heic fall through to their subtypes`() {
        assertEquals("webp", mimeToFileExtension("image/webp"))
        assertEquals("heic", mimeToFileExtension("image/heic"))
    }
}
