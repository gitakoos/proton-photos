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

package eu.akoos.photos.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the step between the reading network's per-column scores and the words the viewer shows.
 *
 * CTC is where a reader quietly goes wrong. Nothing crashes when the collapse is off by one rule:
 * doubled letters silently lose a half, the alphabet shifts by one and every photo comes back as
 * plausible nonsense, and neither is something to find out by looking at a screenshot.
 */
class OcrTextDecodingTest {

    /** Index 0 is the blank, so a, b and c land on 1, 2 and 3 and the space lands last. */
    private val dictionary = buildCharacterDictionary(listOf("a", "b", "c"))

    private fun decode(vararg columns: Int): DecodedText =
        ctcDecode(columns, FloatArray(columns.size) { 1f }, dictionary)

    // ─── the alphabet ───────────────────────────────────────────────────────

    @Test
    fun `the alphabet puts the blank first and a space last`() {
        assertEquals(listOf("", "a", "b", "c", " "), dictionary)
    }

    @Test
    fun `an empty dictionary file still leaves the blank and the space`() {
        assertEquals(listOf("", " "), buildCharacterDictionary(emptyList()))
    }

    // ─── the collapse ───────────────────────────────────────────────────────

    @Test
    fun `nothing to read is empty rather than a guess`() {
        assertEquals(DecodedText("", 0f), ctcDecode(IntArray(0), FloatArray(0), dictionary))
        assertEquals("", decode(0, 0, 0, 0).text)
        assertEquals(0f, decode(0, 0, 0).confidence, 0f)
    }

    @Test
    fun `one column per character reads straight through`() {
        assertEquals("abc", decode(1, 2, 3).text)
    }

    @Test
    fun `a character held across several columns is read once`() {
        // A wide letter lights up every column it covers; that is one letter, not four.
        assertEquals("a", decode(1, 1, 1, 1).text)
        assertEquals("abc", decode(1, 1, 2, 2, 2, 3).text)
    }

    @Test
    fun `blanks between characters are dropped`() {
        assertEquals("abc", decode(0, 1, 0, 0, 2, 0, 3, 0).text)
    }

    @Test
    fun `a genuine double letter survives because a blank splits it`() {
        // This is the whole reason the blank exists: without the column between them the two halves
        // are one run and the word loses a letter.
        assertEquals("aa", decode(1, 0, 1).text)
        assertEquals("aa", decode(1, 1, 0, 0, 1, 1).text)
        assertEquals("a", decode(1, 1, 1).text)
    }

    @Test
    fun `a space is a character like any other`() {
        assertEquals("a c", decode(1, 4, 3).text)
    }

    @Test
    fun `an index the alphabet has no entry for is skipped without shifting the rest`() {
        // A short dictionary must not turn into a wrong reading: the run is consumed and nothing is
        // emitted for it, so the characters around it stay where they were.
        assertEquals("ac", decode(1, 99, 99, 3).text)
    }

    @Test
    fun `only as many columns as both inputs carry are read`() {
        assertEquals("a", ctcDecode(intArrayOf(1, 2, 3), floatArrayOf(1f), dictionary).text)
    }

    // ─── the confidence ─────────────────────────────────────────────────────

    @Test
    fun `confidence is the mean over characters, not over columns`() {
        // Two characters, one of them four columns wide. Averaging the columns would let the wide
        // one outvote the narrow one and hide a bad read.
        val decoded = ctcDecode(
            intArrayOf(1, 1, 1, 1, 0, 2),
            floatArrayOf(1f, 1f, 1f, 1f, 0f, 0.5f),
            dictionary,
        )
        assertEquals("ab", decoded.text)
        assertEquals(0.75f, decoded.confidence, 1e-5f)
    }

    @Test
    fun `a run's own confidence is the mean of its columns`() {
        val decoded = ctcDecode(intArrayOf(1, 1), floatArrayOf(0.4f, 0.8f), dictionary)
        assertEquals(0.6f, decoded.confidence, 1e-5f)
    }

    @Test
    fun `blank columns do not drag the confidence down`() {
        val decoded = ctcDecode(intArrayOf(0, 1, 0), floatArrayOf(0.1f, 0.9f, 0.1f), dictionary)
        assertEquals("a", decoded.text)
        assertEquals(0.9f, decoded.confidence, 1e-5f)
    }

    @Test
    fun `an unreadable column contributes no confidence either`() {
        val decoded = ctcDecode(intArrayOf(99, 1), floatArrayOf(1f, 0.5f), dictionary)
        assertEquals("a", decoded.text)
        assertEquals(0.5f, decoded.confidence, 1e-5f)
        assertTrue(decoded.confidence <= 1f)
    }
}
