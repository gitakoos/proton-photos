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

package eu.akoos.photos.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the untouched-versus-cleared decision behind
 * [WriteLocalPhotoMetadataUseCase.writeDescriptiveText]. An untouched tag has to stay out of the EXIF
 * pass entirely, and a cleared tag has to reach it as null, because an empty string writes an empty
 * tag instead of removing it. Tag names are stand-ins, so no ExifInterface is loaded.
 */
class TextTagWritesTest {

    private val description = "description"
    private val artist = "artist"
    private val copyright = "copyright"

    @Test
    fun `an unchanged tag never reaches the pass`() {
        val writes = WriteLocalPhotoMetadataUseCase.textTagWrites(
            listOf(
                description to TextTagEdit.Unchanged,
                artist to TextTagEdit.SetTo("A. Kovacs"),
                copyright to TextTagEdit.Unchanged,
            )
        )
        assertEquals(listOf(artist to "A. Kovacs"), writes)
    }

    @Test
    fun `all tags unchanged leaves nothing to write`() {
        val writes = WriteLocalPhotoMetadataUseCase.textTagWrites(
            listOf(
                description to TextTagEdit.Unchanged,
                artist to TextTagEdit.Unchanged,
                copyright to TextTagEdit.Unchanged,
            )
        )
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a blank value clears the tag with null rather than an empty string`() {
        val writes = WriteLocalPhotoMetadataUseCase.textTagWrites(
            listOf(
                description to TextTagEdit.SetTo(""),
                artist to TextTagEdit.SetTo("   "),
            )
        )
        assertEquals(listOf(description, artist), writes.map { it.first })
        writes.forEach { assertNull(it.second) }
    }

    @Test
    fun `a value with no ASCII base clears the tag too`() {
        val writes = WriteLocalPhotoMetadataUseCase.textTagWrites(
            listOf(description to TextTagEdit.SetTo("😀🌊"))
        )
        assertEquals(listOf(description to null), writes)
    }

    @Test
    fun `a written value is transliterated on the way in`() {
        val writes = WriteLocalPhotoMetadataUseCase.textTagWrites(
            listOf(description to TextTagEdit.SetTo("Nyaralás Horvátországban"))
        )
        assertEquals(listOf(description to "Nyaralas Horvatorszagban"), writes)
    }

    @Test
    fun `the three tags keep the order the caller supplies`() {
        val writes = WriteLocalPhotoMetadataUseCase.textTagWrites(
            listOf(
                description to TextTagEdit.SetTo("Balaton"),
                artist to TextTagEdit.SetTo("Akos"),
                copyright to TextTagEdit.SetTo("CC BY-SA 4.0"),
            )
        )
        assertEquals(
            listOf(
                description to "Balaton",
                artist to "Akos",
                copyright to "CC BY-SA 4.0",
            ),
            writes,
        )
    }
}
