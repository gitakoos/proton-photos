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

import eu.akoos.photos.util.ExifDateFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure coverage for the EXIF OffsetTime tags, both the [ExifDateFormat.toExifOffset] value written into
 * them and the [ExifDateFormat.fromExif] read that honours one found on a file. The datetime tags hold a
 * bare wall clock, so this offset is the only thing that pins a photo to one absolute instant: the pair
 * has to resolve back to the instant it was derived from, in a positive zone, a negative one, a
 * non-whole-hour one and across a DST boundary. No Android, no real file.
 */
class ExifDateFormatOffsetTest {

    private val exifFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    // The one instant every offset case below is written as a different wall clock of.
    private val instantMs = 1_784_828_102_000L // 2026-07-23T17:35:02Z

    @Test
    fun `formats a positive offset`() {
        val summer = Instant.parse("2026-07-23T17:35:02Z").toEpochMilli()
        val winter = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
        val budapest = ZoneId.of("Europe/Budapest")
        assertEquals("+02:00", ExifDateFormat.toExifOffset(summer, budapest))
        assertEquals("+01:00", ExifDateFormat.toExifOffset(winter, budapest))
    }

    @Test
    fun `formats a negative offset`() {
        val summer = Instant.parse("2026-07-23T17:35:02Z").toEpochMilli()
        val winter = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
        val newYork = ZoneId.of("America/New_York")
        assertEquals("-04:00", ExifDateFormat.toExifOffset(summer, newYork))
        assertEquals("-05:00", ExifDateFormat.toExifOffset(winter, newYork))
    }

    @Test
    fun `formats offsets that are not whole hours`() {
        val winter = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
        val summer = Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()
        assertEquals("+05:45", ExifDateFormat.toExifOffset(winter, ZoneId.of("Asia/Kathmandu")))
        assertEquals("+09:30", ExifDateFormat.toExifOffset(summer, ZoneId.of("Australia/Adelaide")))
        assertEquals("-03:30", ExifDateFormat.toExifOffset(winter, ZoneId.of("America/St_Johns")))
    }

    @Test
    fun `formats UTC as a zero offset, not a zone id`() {
        val ms = Instant.parse("2026-07-23T17:35:02Z").toEpochMilli()
        assertEquals("+00:00", ExifDateFormat.toExifOffset(ms, ZoneOffset.UTC))
        assertEquals("-08:00", ExifDateFormat.toExifOffset(ms, ZoneOffset.ofHours(-8)))
    }

    @Test
    fun `takes the offset in force at the instant, not the standard one`() {
        val newYork = ZoneId.of("America/New_York")
        // New York springs forward at 07:00 UTC (02:00 -> 03:00 local).
        assertEquals(
            "-05:00",
            ExifDateFormat.toExifOffset(Instant.parse("2021-03-14T06:59:00Z").toEpochMilli(), newYork),
        )
        assertEquals(
            "-04:00",
            ExifDateFormat.toExifOffset(Instant.parse("2021-03-14T07:00:00Z").toEpochMilli(), newYork),
        )
    }

    // ── fromExif: an offset on the file replaces the device-zone guess ───────────────────────────

    @Test
    fun `reads a positive offset as an exact instant`() {
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 19:35:02", "+02:00", ZoneOffset.UTC))
    }

    @Test
    fun `reads Z as UTC`() {
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 17:35:02", "Z", ZoneOffset.ofHours(9)))
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 17:35:02", "z", ZoneOffset.ofHours(9)))
    }

    @Test
    fun `reads a negative offset`() {
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 12:35:02", "-05:00", ZoneOffset.UTC))
    }

    @Test
    fun `reads an offset written without its colon`() {
        // Real files carry both spellings of the same half-hour zone.
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 23:05:02", "+0530", ZoneOffset.UTC))
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 23:05:02", "+05:30", ZoneOffset.UTC))
    }

    @Test
    fun `falls back to the device zone when no offset is on the file`() {
        val budapest = ZoneId.of("Europe/Budapest") // +02:00 in July
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 19:35:02", null, budapest))
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 19:35:02", "", budapest))
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 19:35:02", "   ", budapest))
    }

    @Test
    fun `falls back to the device zone when the offset is unreadable`() {
        val budapest = ZoneId.of("Europe/Budapest")
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 19:35:02", "banana", budapest))
        assertEquals(instantMs, ExifDateFormat.fromExif("2026:07:23 19:35:02", "+99:00", budapest))
    }

    @Test
    fun `returns null when the datetime itself is unreadable`() {
        assertNull(ExifDateFormat.fromExif("not-a-date", "+02:00", ZoneOffset.UTC))
        assertNull(ExifDateFormat.fromExif("2026:13:99 99:99:99", "Z", ZoneOffset.UTC))
        assertNull(ExifDateFormat.fromExif("", null, ZoneOffset.UTC))
    }

    // ── hasUsableOffset: whether the caller may treat the result as exact ────────────────────────

    @Test
    fun `reports a usable offset exactly when one would be honoured`() {
        assertTrue(ExifDateFormat.hasUsableOffset("+02:00"))
        assertTrue(ExifDateFormat.hasUsableOffset("-05:00"))
        assertTrue(ExifDateFormat.hasUsableOffset("+0530"))
        assertTrue(ExifDateFormat.hasUsableOffset("Z"))
        assertTrue(ExifDateFormat.hasUsableOffset(" +02:00 "))
        assertFalse(ExifDateFormat.hasUsableOffset(null))
        assertFalse(ExifDateFormat.hasUsableOffset(""))
        assertFalse(ExifDateFormat.hasUsableOffset("   "))
        assertFalse(ExifDateFormat.hasUsableOffset("banana"))
        assertFalse(ExifDateFormat.hasUsableOffset("+99:00"))
    }

    @Test
    fun `datetime plus offset resolve back to the original instant`() {
        val instants = listOf(
            "2026-07-23T17:35:02Z",
            "2026-01-15T12:00:00Z",
            "2021-03-14T07:00:00Z",
        ).map { Instant.parse(it).toEpochMilli() }
        val zones = listOf(
            ZoneId.of("Europe/Budapest"),
            ZoneId.of("America/New_York"),
            ZoneId.of("Asia/Kathmandu"),
            ZoneId.of("America/St_Johns"),
            ZoneOffset.UTC,
        )
        for (ms in instants) {
            for (zone in zones) {
                val stamp = ExifDateFormat.toExifLocal(ms, zone)
                val offset = ExifDateFormat.toExifOffset(ms, zone)
                val readBack = LocalDateTime.parse(stamp, exifFormatter)
                    .atOffset(ZoneOffset.of(offset))
                    .toInstant()
                    .toEpochMilli()
                assertEquals("$zone read back wrong", ms, readBack)
            }
        }
    }
}
