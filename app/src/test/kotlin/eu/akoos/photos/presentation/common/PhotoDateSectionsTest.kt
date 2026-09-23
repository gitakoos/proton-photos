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

package eu.akoos.photos.presentation.common

import eu.akoos.photos.presentation.albums.AlbumPhotoGrouping
import eu.akoos.photos.presentation.util.dayMonthYearFormat
import eu.akoos.photos.presentation.util.monthYearFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the date headers an album or a device folder splits its photos under.
 *
 * Month is what both screens have always shown, so it has to read exactly as the grouping they run
 * today, labels and order alike. Every grouping keeps each photo's position in the list, since the
 * viewer opens on it, and keeps each section of a sorted list in one run whichever way the list is
 * sorted. The locale and zone are fixed so the labels read the same on any machine. Plain JVM
 * assertions, no Android.
 */
class PhotoDateSectionsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private lateinit var systemLocale: Locale
    private lateinit var systemZone: TimeZone

    @Before
    fun pinDefaults() {
        systemLocale = Locale.getDefault()
        systemZone = TimeZone.getDefault()
        Locale.setDefault(Locale.UK)
        TimeZone.setDefault(utc)
    }

    @After
    fun restoreDefaults() {
        Locale.setDefault(systemLocale)
        TimeZone.setDefault(systemZone)
    }

    private data class Shot(val name: String, val ms: Long)

    /** Epoch millis for a wall-clock time in UTC. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun sections(items: List<Shot>, grouping: AlbumPhotoGrouping) =
        photoDateSections(items, grouping, Locale.UK, utc) { it.ms }

    /** Newest first, the way an album opens: two photos on one day, another day that month, another
     *  month, then two months of the year before. */
    private fun newestFirst() = listOf(
        Shot("evening", at(2026, 3, 5, hour = 21)),
        Shot("morning", at(2026, 3, 5, hour = 8)),
        Shot("third", at(2026, 3, 3)),
        Shot("valentine", at(2026, 2, 14)),
        Shot("christmas", at(2025, 12, 24)),
        Shot("summer", at(2025, 6, 1)),
    )

    @Test
    fun `month reads exactly as the month headers albums and folders show today`() {
        // The grouping both screens run today, over the same list. The stray March photo past June
        // joins the March section instead of opening a second March header.
        val items = newestFirst() + Shot("stray", at(2026, 3, 1)) + Shot("dateless", 0L)
        val fmt = monthYearFormat()
        val today = items.withIndex().groupBy { fmt.format(Date(it.value.ms)) }

        val sections = photoDateSections(items, AlbumPhotoGrouping.Month) { it.ms }

        assertEquals(today, sections)
        assertEquals(today.keys.toList(), sections.keys.toList())
        assertEquals(
            listOf("March 2026", "February 2026", "December 2025", "June 2025", "January 1970"),
            sections.keys.toList(),
        )
        assertEquals(listOf(0, 1, 2, 6), sections.getValue("March 2026").map { it.index })
    }

    @Test
    fun `day and year read as the timeline heads the same spans`() {
        val items = newestFirst()
        val timelineDay = dayMonthYearFormat()
        val timelineYear = SimpleDateFormat("yyyy", Locale.getDefault())

        assertEquals(
            items.map { timelineDay.format(Date(it.ms)) }.distinct(),
            photoDateSections(items, AlbumPhotoGrouping.Day) { it.ms }.keys.toList(),
        )
        assertEquals(
            items.map { timelineYear.format(Date(it.ms)) }.distinct(),
            photoDateSections(items, AlbumPhotoGrouping.Year) { it.ms }.keys.toList(),
        )
    }

    @Test
    fun `day gives each date in a month its own section`() {
        val march = listOf(
            Shot("evening", at(2026, 3, 5, hour = 21)),
            Shot("morning", at(2026, 3, 5, hour = 8)),
            Shot("third", at(2026, 3, 3)),
            Shot("first", at(2026, 3, 1, hour = 0)),
        )

        val byDay = sections(march, AlbumPhotoGrouping.Day)

        assertEquals(listOf("5 March 2026", "3 March 2026", "1 March 2026"), byDay.keys.toList())
        assertEquals(listOf("evening", "morning"), byDay.getValue("5 March 2026").map { it.value.name })
        assertEquals(listOf("March 2026"), sections(march, AlbumPhotoGrouping.Month).keys.toList())
    }

    @Test
    fun `year gathers several months into one section`() {
        val items = newestFirst()

        val byYear = sections(items, AlbumPhotoGrouping.Year)

        assertEquals(listOf("2026", "2025"), byYear.keys.toList())
        assertEquals(
            listOf("evening", "morning", "third", "valentine"),
            byYear.getValue("2026").map { it.value.name },
        )
        assertEquals(listOf("christmas", "summer"), byYear.getValue("2025").map { it.value.name })
        assertEquals(4, sections(items, AlbumPhotoGrouping.Month).size)
    }

    @Test
    fun `sections stay one run each and in order whichever way the list is sorted`() {
        val newest = newestFirst()
        val oldest = newest.reversed()

        for (grouping in AlbumPhotoGrouping.entries) {
            for (items in listOf(newest, oldest)) {
                // Reading the sections top to bottom walks the list front to back, so no section
                // interrupts another and none sits out of place.
                val walked = sections(items, grouping).values.flatten().map { it.index }
                assertEquals(grouping.name, items.indices.toList(), walked)
            }
            assertEquals(
                grouping.name,
                sections(newest, grouping).keys.reversed(),
                sections(oldest, grouping).keys.toList(),
            )
        }
    }

    @Test
    fun `every photo keeps its position in the list it came from`() {
        val items = newestFirst()

        for (grouping in AlbumPhotoGrouping.entries) {
            val entries = sections(items, grouping).values.flatten()
            assertEquals(grouping.name, items.size, entries.size)
            entries.forEach { assertSame(grouping.name, items[it.index], it.value) }
        }
        // The photo opening the second day is the list's third.
        val secondDay = sections(items, AlbumPhotoGrouping.Day).values.elementAt(1)
        assertEquals(IndexedValue(2, items[2]), secondDay.first())
    }

    @Test
    fun `an empty list has no sections`() {
        for (grouping in AlbumPhotoGrouping.entries) {
            assertTrue(grouping.name, sections(emptyList(), grouping).isEmpty())
        }
    }

    @Test
    fun `a photo with no capture time closes a newest-first list under 1970`() {
        val items = newestFirst() + Shot("dateless", 0L)
        val expected = mapOf(
            AlbumPhotoGrouping.Month to "January 1970",
            AlbumPhotoGrouping.Day to "1 January 1970",
            AlbumPhotoGrouping.Year to "1970",
        )

        for (grouping in AlbumPhotoGrouping.entries) {
            val last = sections(items, grouping).entries.last()
            assertEquals(expected.getValue(grouping), last.key)
            assertEquals(listOf(IndexedValue(items.lastIndex, items.last())), last.value)
        }
    }

    @Test
    fun `the locale and zone handed in decide the labels`() {
        // Half past eleven on New Year's Eve in UTC is already the new year an hour east.
        val items = listOf(Shot("countdown", at(2025, 12, 31, hour = 23, minute = 30)))
        val budapest = TimeZone.getTimeZone("Europe/Budapest")
        fun labels(grouping: AlbumPhotoGrouping, locale: Locale, zone: TimeZone) =
            photoDateSections(items, grouping, locale, zone) { it.ms }.keys.toList()

        assertEquals(listOf("2025"), labels(AlbumPhotoGrouping.Year, Locale.UK, utc))
        assertEquals(listOf("2026"), labels(AlbumPhotoGrouping.Year, Locale.UK, budapest))
        assertEquals(listOf("Januar 2026"), labels(AlbumPhotoGrouping.Month, Locale.GERMANY, budapest))
    }

    @Test
    fun `a stored grouping reads back as Month when absent or damaged`() {
        assertEquals(AlbumPhotoGrouping.Month, AlbumPhotoGrouping.Default)
        assertEquals(AlbumPhotoGrouping.Month, AlbumPhotoGrouping.fromOrdinal(null))
        assertEquals(AlbumPhotoGrouping.Month, AlbumPhotoGrouping.fromOrdinal(99))
        assertEquals(AlbumPhotoGrouping.Month, AlbumPhotoGrouping.fromOrdinal(-1))
        // The preference holds the position, so a reorder would regroup every install that picked one.
        assertEquals(
            listOf(AlbumPhotoGrouping.Month, AlbumPhotoGrouping.Day, AlbumPhotoGrouping.Year),
            AlbumPhotoGrouping.entries,
        )
        for (grouping in AlbumPhotoGrouping.entries) {
            assertEquals(grouping, AlbumPhotoGrouping.fromOrdinal(grouping.ordinal))
        }
    }
}
