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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the capture time a vaulted photo carries in its own file name, WITHOUT the vault, a
 * device or MediaStore in the loop.
 *
 * A vault file is a copy with no MediaStore row and, for a PNG, a WebP, a screenshot or a video, no
 * EXIF date either, so this name is the only record of when the photo was taken. Every side that
 * reads one has to agree with the side that wrote it: the grid that displays the date, the restore
 * that rebuilds DATE_TAKEN, and the rename that has to carry the record onto a name a person chose.
 */
class HiddenCaptureTimeTest {

    private val captureMs = 1_783_507_135_000L
    private val uuidStem = "3f1a9c72-4d6b-4a5e-9f21-7c0b8e5d2a44"

    // ── the round trip the vault actually performs ───────────────────────────────────────────────

    @Test
    fun `a name written with a capture time reads it back`() {
        val name = "$uuidStem${HiddenCaptureTime.suffix(captureMs)}.jpg"
        assertEquals(captureMs, HiddenCaptureTime.parse(name))
    }

    @Test
    fun `the name is read with or without its extension`() {
        val stem = "$uuidStem${HiddenCaptureTime.suffix(captureMs)}"
        assertEquals(captureMs, HiddenCaptureTime.parse(stem))
        assertEquals(captureMs, HiddenCaptureTime.parse("$stem.mp4"))
        assertEquals(captureMs, HiddenCaptureTime.parse("$stem.jpeg"))
    }

    // ── an unknown capture time records nothing at all ───────────────────────────────────────────

    @Test
    fun `an unknown capture time writes no suffix`() {
        // The name then says nothing rather than claiming a date, which is what leaves each reader
        // free to fall back: the grid to the file's modified time, restore to EXIF and then to now.
        assertEquals("", HiddenCaptureTime.suffix(null))
        assertEquals("", HiddenCaptureTime.suffix(0L))
        assertEquals("", HiddenCaptureTime.suffix(-1L))
    }

    @Test
    fun `a name recording nothing reads as nothing`() {
        // A vault entry whose name records no capture time looks exactly like this, so null is an
        // ordinary answer and never a failure.
        assertNull(HiddenCaptureTime.parse("$uuidStem.jpg"))
        assertNull(HiddenCaptureTime.parse(uuidStem))
        assertNull(HiddenCaptureTime.parse(""))
    }

    @Test
    fun `a name whose tail is not a usable time reads as nothing`() {
        assertNull(HiddenCaptureTime.parse("${uuidStem}__notatime.jpg"))
        assertNull(HiddenCaptureTime.parse("${uuidStem}__.jpg"))
        assertNull(HiddenCaptureTime.parse("${uuidStem}__0.jpg"))
        assertNull(HiddenCaptureTime.parse("${uuidStem}__-5.jpg"))
    }

    // ── the rename path: a person's name reaches the stem ────────────────────────────────────────

    @Test
    fun `a renamed file keeps its capture time`() {
        // Rename re-encodes what the old name recorded onto the new stem, so the vault's only record
        // of the date survives the user naming the file.
        val stored = "${uuidStem}__$captureMs.jpg"
        val renamed = "Beach trip${HiddenCaptureTime.suffix(HiddenCaptureTime.parse(stored))}.jpg"
        assertEquals("Beach trip__$captureMs.jpg", renamed)
        assertEquals(captureMs, HiddenCaptureTime.parse(renamed))
    }

    @Test
    fun `a chosen name containing the separator still resolves`() {
        // Nothing stops a person typing the separator into a name, and reading from the last one is
        // what keeps their own text out of the number.
        assertEquals(captureMs, HiddenCaptureTime.parse("holiday__2026__$captureMs.jpg"))
        assertNull(HiddenCaptureTime.parse("holiday__2026__sunset.jpg"))
    }

    @Test
    fun `renaming a file that records no capture time adds none`() {
        assertEquals("", HiddenCaptureTime.suffix(HiddenCaptureTime.parse("$uuidStem.jpg")))
    }

    // ── the name a person sees, and the one a reveal writes to the device ────────────────────────

    @Test
    fun `stripping leaves the name without the vault's bookkeeping`() {
        assertEquals("Beach trip.jpg", HiddenCaptureTime.strip("Beach trip__$captureMs.jpg"))
        assertEquals("$uuidStem.mp4", HiddenCaptureTime.strip("${uuidStem}__$captureMs.mp4"))
        assertEquals("Beach trip", HiddenCaptureTime.strip("Beach trip__$captureMs"))
    }

    @Test
    fun `a name recording no capture time survives stripping unchanged`() {
        assertEquals("$uuidStem.jpg", HiddenCaptureTime.strip("$uuidStem.jpg"))
        assertEquals("holiday__sunset.jpg", HiddenCaptureTime.strip("holiday__sunset.jpg"))
        assertEquals("${uuidStem}__0.jpg", HiddenCaptureTime.strip("${uuidStem}__0.jpg"))
        assertEquals("", HiddenCaptureTime.strip(""))
    }

    @Test
    fun `only the last separator is stripped, so a chosen name keeps its own`() {
        assertEquals("holiday__2026.jpg", HiddenCaptureTime.strip("holiday__2026__$captureMs.jpg"))
    }

    @Test
    fun `a name that is nothing but a capture time keeps it`() {
        // Stripping would leave a file with no name at all, which is worse than a name that reads
        // like bookkeeping.
        assertEquals("__$captureMs.jpg", HiddenCaptureTime.strip("__$captureMs.jpg"))
    }

    // ── a date edit moves the one record the vault reads a date from ─────────────────────────────

    @Test
    fun `restamping replaces the recorded time and keeps the rest of the name`() {
        val edited = 1_600_000_000_000L
        assertEquals("Beach trip__$edited.jpg", HiddenCaptureTime.restamp("Beach trip__$captureMs.jpg", edited))
        assertEquals(edited, HiddenCaptureTime.parse(HiddenCaptureTime.restamp("$uuidStem.jpg", edited)))
    }

    @Test
    fun `restamping a name twice records only the last date`() {
        // The suffix is a single record, not a history: a second edit must not leave the first one
        // behind for [parse] to find.
        val first = HiddenCaptureTime.restamp("$uuidStem.jpg", 1_600_000_000_000L)
        val second = HiddenCaptureTime.restamp(first, captureMs)
        assertEquals("${uuidStem}__$captureMs.jpg", second)
    }

    @Test
    fun `restamping to no date at all removes the record`() {
        assertEquals("$uuidStem.jpg", HiddenCaptureTime.restamp("${uuidStem}__$captureMs.jpg", null))
    }
}
