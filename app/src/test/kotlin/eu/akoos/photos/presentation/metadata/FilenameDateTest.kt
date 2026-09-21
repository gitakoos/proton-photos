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

package eu.akoos.photos.presentation.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pins the filename date reader: the whole point is that a no-EXIF meme gets its real date from its
 * name and never a wrong one from a random digit run, so the assertions hold every shape it must read
 * and every non-date it must reject. Plain JVM, a fixed UTC zone, and the current instant passed in.
 */
class FilenameDateTest {

    private val utc = ZoneId.of("UTC")
    // A ceiling far enough ahead that every 2023 date is in the past and a 2099 one is the future.
    private val now = ms(2030, 1, 1, 0, 0, 0)

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(utc).toInstant().toEpochMilli()

    private fun parse(name: String) = FilenameDate.parse(name, now, utc)

    @Test fun `camera date and time`() {
        val expected = ms(2023, 1, 15, 12, 34, 56)
        assertEquals(expected, parse("IMG_20230115_123456.jpg"))
        assertEquals(expected, parse("VID_20230115_123456.mp4"))
        assertEquals(expected, parse("PXL_20230115_123456.jpg"))
        assertEquals(expected, parse("20230115_123456"))
        assertEquals(expected, parse("20230115123456.png"))
        assertEquals(expected, parse("20230115-123456.jpg"))
    }

    @Test fun `compact screenshot name with a copy suffix`() {
        // The exact shape a duplicated Android screenshot carries: a Screenshot_ prefix, the compact
        // yyyyMMdd-HHmmss, then a " (2)" copy marker after the time. The date is read, the marker ignored.
        assertEquals(ms(2025, 10, 23, 6, 34, 22), parse("Screenshot_20251023-063422 (2).png"))
    }

    @Test fun `separated date with time`() {
        assertEquals(ms(2023, 1, 15, 12, 34, 56), parse("Screenshot_2023-01-15-12-34-56.png"))
        assertEquals(ms(2023, 1, 15, 9, 5, 0), parse("2023-01-15 09-05.jpg"))
    }

    @Test fun `date only lands at noon`() {
        val noon = ms(2023, 1, 15, 12, 0, 0)
        assertEquals(noon, parse("IMG-20230115-WA0001.jpg"))
        assertEquals(noon, parse("20230115.jpg"))
        assertEquals(noon, parse("2023-01-15.png"))
        assertEquals(noon, parse("2023.01.15.jpg"))
        assertEquals(noon, parse("2023_01_15.jpg"))
        assertEquals(noon, parse("Screenshot_2023-01-15.png"))
    }

    @Test fun `an invalid time falls back to the date`() {
        // Hour 25 is not a real time, so the date alone is read and lands at noon.
        assertEquals(ms(2023, 1, 15, 12, 0, 0), parse("20230115_256789.jpg"))
    }

    @Test fun `a contiguous date with a non-time counter falls back to the date`() {
        // SquareQuick appends a counter, not a clock: 20191212 then 594755 (an impossible 59:47:55). With
        // no separator the bare-date pattern cannot split the run, so the datetime match's date is read.
        assertEquals(ms(2019, 12, 12, 12, 0, 0), parse("SquareQuick_20191212594755"))
    }

    @Test fun `a sub-second suffix after the time is tolerated`() {
        // A 14-digit yyyyMMddHHmmss immediately followed by a 3-digit millisecond tail still reads.
        assertEquals(ms(2020, 11, 13, 11, 55, 27), parse("20201113115527539.png"))
        assertNull(parse("20209913115527539.png")) // month 99, even with a millis tail, stays rejected
    }

    @Test fun `no readable date returns null`() {
        assertNull(parse("meme.jpg"))
        assertNull(parse("funny_cat.png"))
        assertNull(parse(""))
        assertNull(parse("photo"))
        assertNull(parse("IMG_1234.jpg"))
    }

    @Test fun `impossible calendar dates are rejected`() {
        assertNull(parse("20211345.jpg")) // month 13
        assertNull(parse("20210230.jpg")) // 30 February
        assertNull(parse("2023-13-01.jpg")) // month 13, separated
    }

    @Test fun `dates before 1990 and in the future are rejected`() {
        assertNull(parse("19891231.jpg")) // before the floor
        assertNull(parse("20991231.jpg")) // after now (2030)
        assertNull(parse("2099-12-31.jpg"))
    }

    @Test fun `a bare unix timestamp is not read as a date`() {
        // A random 10-digit meme id must not be guessed into a plausible date.
        assertNull(parse("1673789696.jpg"))
        assertNull(parse("1234567890.png"))
    }

    @Test fun `a date embedded among other text is found`() {
        assertEquals(ms(2023, 6, 30, 12, 0, 0), parse("download-2023-06-30-final.jpg"))
        assertEquals(ms(2023, 6, 30, 8, 15, 42), parse("VID_20230630_081542_edited.mp4"))
    }
}
