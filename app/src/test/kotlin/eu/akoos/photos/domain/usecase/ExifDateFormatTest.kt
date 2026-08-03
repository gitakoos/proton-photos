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
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure timezone / DST coverage for [ExifDateFormat]. EXIF stores a wall-clock string with no offset, so
 * the supplied zone is what makes the ms<->string mapping correct. No Android, no ExifInterface: the
 * conversion is exercised on the JVM against fixed instants.
 */
class ExifDateFormatTest {

    private val plusTwo = ZoneOffset.ofHours(2)

    @Test
    fun `formats a known instant to local EXIF wall-clock`() {
        // 2026-07-23 19:35:02 at UTC+2 is 17:35:02 UTC.
        val ms = Instant.parse("2026-07-23T17:35:02Z").toEpochMilli()
        assertEquals("2026:07:23 19:35:02", ExifDateFormat.toExifLocal(ms, plusTwo))
    }

    @Test
    fun `round-trips ms to EXIF and back in a fixed-offset zone`() {
        val ms = Instant.parse("2026-07-23T17:35:02Z").toEpochMilli()
        val exif = ExifDateFormat.toExifLocal(ms, plusTwo)
        assertEquals(ms, ExifDateFormat.fromExifLocal(exif, plusTwo))
    }

    @Test
    fun `handles midnight at the day boundary`() {
        // 2026-01-01 00:00:00 at UTC+2 is 2025-12-31 22:00:00 UTC.
        val ms = Instant.parse("2025-12-31T22:00:00Z").toEpochMilli()
        assertEquals("2026:01:01 00:00:00", ExifDateFormat.toExifLocal(ms, plusTwo))
        assertEquals(ms, ExifDateFormat.fromExifLocal("2026:01:01 00:00:00", plusTwo))
    }

    @Test
    fun `resolves the spring-forward DST gap by the actual offset`() {
        val newYork = ZoneId.of("America/New_York")
        // New York springs forward at 07:00 UTC (02:00 -> 03:00 local); the 02:xx hour is skipped.
        assertEquals(
            "2021:03:14 01:59:00",
            ExifDateFormat.toExifLocal(Instant.parse("2021-03-14T06:59:00Z").toEpochMilli(), newYork),
        )
        assertEquals(
            "2021:03:14 03:00:00",
            ExifDateFormat.toExifLocal(Instant.parse("2021-03-14T07:00:00Z").toEpochMilli(), newYork),
        )
    }

    @Test
    fun `returns null for a malformed EXIF string`() {
        assertNull(ExifDateFormat.fromExifLocal("not-a-date", ZoneOffset.UTC))
        assertNull(ExifDateFormat.fromExifLocal("2026:13:99 99:99:99", ZoneOffset.UTC))
        assertNull(ExifDateFormat.fromExifLocal("", ZoneOffset.UTC))
    }
}
