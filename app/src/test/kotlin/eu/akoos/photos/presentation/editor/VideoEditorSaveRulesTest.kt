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

package eu.akoos.photos.presentation.editor

import eu.akoos.photos.domain.entity.TimestampSanity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * The three value decisions a video save makes before any file is written: what the copy is called,
 * which instant is stamped into it, and which way up it plays.
 *
 * The name matters beyond cosmetics — the device copy and the cloud copy of one save are paired back
 * together by name and date, so a name built from a different instant leaves the edit split into two
 * unrelated halves. The capture time is what MediaStore reads out of the mvhd, so it decides which
 * day the edited video lands on in the timeline. The rotation is the muxer's orientation hint, and a
 * negative remainder reaching it plays the video upside down.
 */
class VideoEditorSaveRulesTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    /** A local wall-clock instant, so the formatted stamp is predictable in the machine's own zone. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    // ── the name a saved edit takes ─────────────────────────────────────────────────────────────

    @Test
    fun `a saved edit keeps its base name and gains the save instant`() {
        assertEquals(
            "clip_edit_20260731_140509.mp4",
            stampedEditName("clip.mp4", at(2026, 7, 31, 14, 5, 9)),
        )
    }

    @Test
    fun `a saved edit is always an mp4, whatever the source container was`() {
        val ms = at(2026, 7, 31, 14, 5, 9)
        assertEquals("clip_edit_20260731_140509.mp4", stampedEditName("clip.mov", ms))
        assertEquals("clip_edit_20260731_140509.mp4", stampedEditName("clip.3gp", ms))
        assertEquals("clip_edit_20260731_140509.mp4", stampedEditName("clip.MP4", ms))
    }

    @Test
    fun `a name with no extension still gets one`() {
        assertEquals("clip_edit_20260731_140509.mp4", stampedEditName("clip", at(2026, 7, 31, 14, 5, 9)))
    }

    @Test
    fun `only the last dot separates the extension`() {
        assertEquals(
            "my.holiday.clip_edit_20260731_140509.mp4",
            stampedEditName("my.holiday.clip.mp4", at(2026, 7, 31, 14, 5, 9)),
        )
    }

    @Test
    fun `a name that begins with a dot is kept whole`() {
        // A leading dot is not an extension separator, so the hidden name survives into the copy.
        assertEquals(".hidden_edit_20260731_140509.mp4", stampedEditName(".hidden", at(2026, 7, 31, 14, 5, 9)))
    }

    @Test
    fun `editing an edit stacks another marker rather than replacing the first`() {
        val first = stampedEditName("clip.mp4", at(2026, 7, 31, 14, 5, 9))
        val second = stampedEditName(first, at(2026, 7, 31, 14, 6, 0))
        assertEquals("clip_edit_20260731_140509_edit_20260731_140600.mp4", second)
    }

    @Test
    fun `two saves a second apart are two different names`() {
        assertNotEquals(
            stampedEditName("clip.mp4", at(2026, 7, 31, 14, 5, 9)),
            stampedEditName("clip.mp4", at(2026, 7, 31, 14, 5, 10)),
        )
    }

    @Test
    fun `the device copy and the cloud copy of one save are named identically`() {
        // Both are derived from the SAME instant, which is what lets the two be paired back together.
        val ms = at(2026, 7, 31, 14, 5, 9)
        assertEquals(stampedEditName("clip.mp4", ms), stampedEditName("clip.mp4", ms))
    }

    @Test
    fun `the stamp is the same digits whatever calendar and digits the phone is set to`() {
        // A Buddhist-calendar or Arabic-digit locale would otherwise rename the file to a year and a
        // numeral set MediaStore cannot match back to the cloud copy.
        val ms = at(2026, 7, 31, 14, 5, 9)
        val expected = stampedEditName("clip.mp4", ms)
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
        assertEquals(expected, stampedEditName("clip.mp4", ms))
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        assertEquals(expected, stampedEditName("clip.mp4", ms))
        assertTrue("the year has to be the Gregorian one", expected.contains("2026"))
    }

    // ── the instant stamped into the saved bytes ────────────────────────────────────────────────

    @Test
    fun `a backed-up video keeps its cloud sibling's original capture time`() {
        assertEquals(1_700_000_000_000L, editedVideoCaptureMs(1_700_000_000L, 1_800_000_000_000L))
    }

    @Test
    fun `a video with no cloud sibling is stamped with the save instant`() {
        assertEquals(1_800_000_000_000L, editedVideoCaptureMs(null, 1_800_000_000_000L))
    }

    @Test
    fun `a sibling whose capture time is zero falls back to the save instant`() {
        // Drive answers 0 for a photo it holds no capture timestamp for. Zero is not null, so a bare
        // null check would take it at face value and stamp the edited video with the epoch.
        assertEquals(1_800_000_000_000L, editedVideoCaptureMs(0L, 1_800_000_000_000L))
    }

    @Test
    fun `a sibling time below the sanity floor is treated the same as no time at all`() {
        // The floor is the app's single rule for telling a real cloud capture time from Drive's
        // "none", so the editor reads one the same way every list and grouping does.
        assertEquals(1_800_000_000_000L, editedVideoCaptureMs(1L, 1_800_000_000_000L))
        assertEquals(1_800_000_000_000L, editedVideoCaptureMs(60L, 1_800_000_000_000L))
        assertEquals(
            1_800_000_000_000L,
            editedVideoCaptureMs(TimestampSanity.FLOOR_MS / 1000L, 1_800_000_000_000L),
        )
    }

    @Test
    fun `the sibling's seconds are promoted to milliseconds`() {
        assertEquals(1_700_000_000_000L, editedVideoCaptureMs(1_700_000_000L, 0L))
        assertEquals(TimestampSanity.FLOOR_MS + 1_000L, editedVideoCaptureMs(TimestampSanity.FLOOR_MS / 1000L + 1L, 0L))
    }

    // ── which way up the saved video plays ──────────────────────────────────────────────────────

    @Test
    fun `the user's turns are added to the rotation the source already carried`() {
        assertEquals(0, normalizedRotation(0, 0))
        assertEquals(90, normalizedRotation(0, 90))
        assertEquals(180, normalizedRotation(90, 90))
        assertEquals(270, normalizedRotation(180, 90))
    }

    @Test
    fun `a full turn comes back to upright rather than to three hundred and sixty`() {
        assertEquals(0, normalizedRotation(270, 90))
        assertEquals(0, normalizedRotation(180, 180))
        assertEquals(90, normalizedRotation(270, 180))
        assertEquals(0, normalizedRotation(360, 0))
        assertEquals(90, normalizedRotation(450, 0))
    }

    @Test
    fun `a negative rotation never reaches the muxer`() {
        // Kotlin's remainder keeps the sign, so without the second wrap a source tagged -90 would be
        // handed to the orientation hint as -90 and play the video the wrong way up.
        assertEquals(270, normalizedRotation(-90, 0))
        assertEquals(270, normalizedRotation(0, -90))
        assertEquals(180, normalizedRotation(-90, -90))
        assertEquals(0, normalizedRotation(-360, 0))
    }

    @Test
    fun `the result is always a quarter turn between zero and two seventy`() {
        for (source in -720..720 step 90) {
            for (user in 0..270 step 90) {
                val out = normalizedRotation(source, user)
                assertTrue("$source + $user gave $out", out in 0..270 && out % 90 == 0)
            }
        }
    }
}
