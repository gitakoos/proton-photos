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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DetailsFormatTest {

    @Test
    fun `a media type reads as the format a person knows`() {
        assertEquals("JPEG", formatOf("image/jpeg"))
        assertEquals("PNG", formatOf("image/png"))
        assertEquals("HEIC", formatOf("image/heic"))
        assertEquals("MP4", formatOf("video/mp4"))
        assertEquals("MOV", formatOf("video/quicktime"))
        assertEquals("MKV", formatOf("video/x-matroska"))
        assertEquals("DNG", formatOf("image/x-adobe-dng"))
        assertEquals("SVG", formatOf("image/svg+xml"))
        assertEquals("", formatOf(""))
    }

    @Test
    fun `a resolution carries its megapixels, whole from ten up`() {
        assertEquals("1200 × 1600 · 1.9 MP", resolutionText(1200, 1600, Locale.US))
        assertEquals("1200 × 1600 · 1,9 MP", resolutionText(1200, 1600, Locale.forLanguageTag("hu")))
        assertEquals("4000 × 3000 · 12 MP", resolutionText(4000, 3000, Locale.US))
        assertEquals("8160 × 6120 · 50 MP", resolutionText(8160, 6120, Locale.US))
    }
}
