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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for [ExifAsciiText]. An EXIF text field is ASCII, and the encoder maps anything it
 * cannot represent to a question mark, so the transliteration is what decides whether an accented
 * caption stays readable in the file. No Android, no ExifInterface, no file: plain JVM assertions on
 * the string mapping.
 */
class ExifAsciiTextTest {

    @Test
    fun `maps every Hungarian accented letter onto its ASCII base`() {
        assertEquals("aeiooouuu", ExifAsciiText.transliterate("áéíóöőúüű"))
        assertEquals("AEIOOOUUU", ExifAsciiText.transliterate("ÁÉÍÓÖŐÚÜŰ"))
    }

    @Test
    fun `leaves an already plain ASCII string untouched`() {
        val ascii = "Summer trip 2026 (Croatia), photo by A. Kovacs - CC BY-SA 4.0!"
        assertEquals(ascii, ExifAsciiText.transliterate(ascii))
    }

    @Test
    fun `reduces text with no ASCII base to an empty string`() {
        assertEquals("", ExifAsciiText.transliterate("🌊🏖️😀"))
        assertEquals("", ExifAsciiText.transliterate(""))
        assertEquals("", ExifAsciiText.transliterate("   "))
    }

    @Test
    fun `transliterates a mixed caption and drops what has no ASCII base`() {
        assertEquals(
            "Nyaralas Horvatorszagban 2026",
            ExifAsciiText.transliterate("Nyaralás Horvátországban 2026 🌊"),
        )
    }

    @Test
    fun `folds a line break or a tab into one space instead of running the words together`() {
        assertEquals(
            "Nyaralas Horvatorszagban",
            ExifAsciiText.transliterate("Nyaralás\nHorvátországban"),
        )
        assertEquals("first second", ExifAsciiText.transliterate("first\tsecond"))
        // A character with no ASCII base sits between two spaces, so dropping it must not leave a
        // double space behind.
        assertEquals("sea and sand", ExifAsciiText.transliterate("sea 🌊 and\r\nsand"))
    }

    @Test
    fun `never leaves a question mark behind for an accented letter`() {
        val result = ExifAsciiText.transliterate("Ősz a Városligetben")
        assertEquals("Osz a Varosligetben", result)
        assertFalse(result.contains('?'))
    }

    @Test
    fun `collapses whitespace runs and trims the edges`() {
        assertEquals("Balaton nyaron", ExifAsciiText.transliterate("   Balaton    nyáron\t\n "))
    }

    @Test
    fun `caps the result at the maximum length`() {
        val overlong = "á".repeat(ExifAsciiText.MAX_LENGTH * 2)
        val result = ExifAsciiText.transliterate(overlong)
        assertEquals(ExifAsciiText.MAX_LENGTH, result.length)
        assertEquals("a".repeat(ExifAsciiText.MAX_LENGTH), result)
    }

    @Test
    fun `a capped result never ends on a dangling space`() {
        val head = "a".repeat(ExifAsciiText.MAX_LENGTH - 1)
        val result = ExifAsciiText.transliterate("$head szo")
        assertTrue(result.length < ExifAsciiText.MAX_LENGTH)
        assertEquals(head, result)
    }
}
