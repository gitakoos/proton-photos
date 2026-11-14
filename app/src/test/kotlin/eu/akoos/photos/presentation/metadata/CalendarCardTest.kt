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

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The month layout and the latest-day limit behind the calendar card. */
class CalendarCardTest {

    @Test
    fun monday_first_leaves_the_blanks_before_the_first_day() {
        // 1 November 2026 is a Sunday: six blanks when the week starts on Monday.
        val cells = monthCells(2026, 11, DayOfWeek.MONDAY)
        assertEquals(6, cells.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 11, 1), cells[6])
    }

    @Test
    fun sunday_first_puts_the_first_day_in_the_first_column() {
        val cells = monthCells(2026, 11, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), cells[0])
    }

    @Test
    fun cells_fill_whole_weeks() {
        assertEquals(0, monthCells(2026, 11, DayOfWeek.MONDAY).size % 7)
        assertNull(monthCells(2026, 11, DayOfWeek.MONDAY).last())
    }

    @Test
    fun february_length_follows_the_leap_year() {
        assertEquals(29, monthCells(2024, 2, DayOfWeek.MONDAY).count { it != null })
        assertEquals(28, monthCells(2026, 2, DayOfWeek.MONDAY).count { it != null })
    }

    @Test
    fun a_day_after_the_latest_is_disabled() {
        val latest = LocalDate.of(2026, 11, 14)
        assertTrue(isDayEnabled(LocalDate.of(2026, 11, 14), latest))
        assertTrue(isDayEnabled(LocalDate.of(2026, 11, 13), latest))
        assertFalse(isDayEnabled(LocalDate.of(2026, 11, 15), latest))
    }

    @Test
    fun no_latest_leaves_every_day_enabled() {
        assertTrue(isDayEnabled(LocalDate.of(2099, 1, 1), null))
    }
}
