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

package eu.akoos.photos.presentation.calendar

import eu.akoos.photos.presentation.util.isoDateFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the Calendar's day bucketing in [groupItemsByDay]: which photos share a day, where the day
 * boundary falls, how month and year rollovers split the keys, the insertion order the buckets keep,
 * and that a non-positive capture time is dropped. The ISO formatter's own round-trip is covered in
 * FormatUtilsTest, so this fixes the grouping alone.
 *
 * Timestamps are built through a UTC Calendar and read back through the same [isoDateFormat] the
 * production path uses, so the day keys are deterministic on any machine and never lean on the wall
 * clock. Pure JVM: no ViewModel, no coroutines, no Android.
 */
class CalendarBucketingTest {

    private data class Item(val id: String, val ts: Long)

    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale
    private lateinit var iso: SimpleDateFormat

    @Before
    fun fixZoneAndLocale() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        // A fixed zone makes midnight unambiguous, so the day-boundary case cannot ride a DST shift.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        iso = isoDateFormat()
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private fun group(items: List<Item>): Map<String, List<Item>> =
        groupItemsByDay(items, captureTimeMs = { it.ts }, isoDate = { iso.format(Date(it)) })

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
        milli: Int = 0,
    ): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, milli)
        return cal.timeInMillis
    }

    @Test
    fun `empty input yields no buckets`() {
        assertTrue(group(emptyList()).isEmpty())
    }

    @Test
    fun `photos on the same day land in one bucket in arrival order`() {
        val morning = Item("morning", ms(2024, 5, 3, hour = 8))
        val evening = Item("evening", ms(2024, 5, 3, hour = 20))
        val noon = Item("noon", ms(2024, 5, 3, hour = 12))

        val grouped = group(listOf(morning, evening, noon))

        assertEquals(setOf("2024-05-03"), grouped.keys)
        // Insertion order, not time order: the month walk sorts by time later, the grouping does not.
        assertEquals(listOf("morning", "evening", "noon"), grouped.getValue("2024-05-03").map { it.id })
    }

    @Test
    fun `photos on different days split into separate buckets keyed by first appearance`() {
        val d3a = Item("d3a", ms(2024, 5, 3, hour = 9))
        val d4 = Item("d4", ms(2024, 5, 4, hour = 9))
        val d3b = Item("d3b", ms(2024, 5, 3, hour = 21))

        val grouped = group(listOf(d3a, d4, d3b))

        assertEquals(listOf("2024-05-03", "2024-05-04"), grouped.keys.toList())
        assertEquals(listOf("d3a", "d3b"), grouped.getValue("2024-05-03").map { it.id })
        assertEquals(listOf("d4"), grouped.getValue("2024-05-04").map { it.id })
    }

    @Test
    fun `the day boundary is midnight in the active zone`() {
        val startOfDay = Item("startOfDay", ms(2024, 5, 3, hour = 0, minute = 0, second = 0, milli = 0))
        val endOfDay = Item("endOfDay", ms(2024, 5, 3, hour = 23, minute = 59, second = 59, milli = 999))
        val nextMidnight = Item("nextMidnight", ms(2024, 5, 4, hour = 0, minute = 0, second = 0, milli = 0))

        val grouped = group(listOf(startOfDay, endOfDay, nextMidnight))

        assertEquals(listOf("2024-05-03", "2024-05-04"), grouped.keys.toList())
        // First and last instant of the day share a bucket; the very next instant starts a new one.
        assertEquals(listOf("startOfDay", "endOfDay"), grouped.getValue("2024-05-03").map { it.id })
        assertEquals(listOf("nextMidnight"), grouped.getValue("2024-05-04").map { it.id })
    }

    @Test
    fun `buckets cross month and year boundaries including a leap day`() {
        val dec31 = Item("dec31", ms(2023, 12, 31, hour = 22))
        val jan1 = Item("jan1", ms(2024, 1, 1, hour = 1))
        val feb29 = Item("feb29", ms(2024, 2, 29, hour = 12))

        val grouped = group(listOf(dec31, jan1, feb29))

        assertEquals(setOf("2023-12-31", "2024-01-01", "2024-02-29"), grouped.keys)
        assertEquals(listOf("dec31"), grouped.getValue("2023-12-31").map { it.id })
        assertEquals(listOf("jan1"), grouped.getValue("2024-01-01").map { it.id })
        assertEquals(listOf("feb29"), grouped.getValue("2024-02-29").map { it.id })
    }

    @Test
    fun `non-positive capture times are dropped`() {
        val zero = Item("zero", 0L)
        val negative = Item("negative", -1_000L)
        val ok = Item("ok", ms(2024, 5, 3))

        val grouped = group(listOf(zero, negative, ok))

        assertEquals(setOf("2024-05-03"), grouped.keys)
        assertEquals(listOf("ok"), grouped.getValue("2024-05-03").map { it.id })
    }

    @Test
    fun `input with only non-positive times yields no buckets`() {
        assertTrue(group(listOf(Item("zero", 0L), Item("negative", -5L))).isEmpty())
    }
}
