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
import org.junit.Assert.assertNotEquals
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
 * sorted. The sections are gathered on a per-photo date key and one label per key, which must come
 * out exactly as formatting every photo and gathering on the label would: same labels, same order,
 * same positions, across zones, locales and their calendars. The locale and zone are fixed so the
 * labels read the same on any machine. Plain JVM assertions, no Android.
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

    // ── One label per key, exactly as one label per photo ──────────────────────────────────────

    /** The reference: every photo formatted on its own, sections gathered on the label. */
    private fun formattedPerPhoto(
        items: List<Long>,
        grouping: AlbumPhotoGrouping,
        locale: Locale,
        zone: TimeZone,
    ): Map<String, List<IndexedValue<Long>>> {
        val fmt = when (grouping) {
            AlbumPhotoGrouping.Month -> monthYearFormat(locale)
            AlbumPhotoGrouping.Day -> dayMonthYearFormat(locale)
            AlbumPhotoGrouping.Year -> SimpleDateFormat("yyyy", locale)
        }
        fmt.timeZone = zone
        return items.withIndex().groupBy { fmt.format(Date(it.value)) }
    }

    private fun assertSameAsFormattedPerPhoto(
        items: List<Long>,
        grouping: AlbumPhotoGrouping,
        locale: Locale,
        zone: TimeZone,
    ) {
        val expected = formattedPerPhoto(items, grouping, locale, zone)
        val actual = photoDateSections(items, grouping, locale, zone) { it }
        val where = "$grouping ${locale.toLanguageTag()} ${zone.id}"
        assertEquals(where, expected.keys.toList(), actual.keys.toList())
        assertEquals(where, expected, actual)
    }

    /** Capture times that sit on the edges a key could get wrong: every 29 minutes through a year
     *  change, both daylight-saving changes, a leap day and a Japanese era change, a millisecond
     *  either side of midnight, no capture time at all, before 1970, and a seeded spread over two
     *  centuries, in the order made. */
    private fun edgeTimes(): List<Long> {
        val times = ArrayList<Long>()
        val windows = listOf(
            at(2025, 12, 30) to at(2026, 1, 2),
            at(2026, 3, 27) to at(2026, 3, 31),
            at(2026, 10, 23) to at(2026, 10, 27),
            at(2024, 2, 27) to at(2024, 3, 2),
            at(2019, 4, 29) to at(2019, 5, 3),
        )
        for ((start, end) in windows) {
            var t = start
            while (t < end) {
                times += t
                t += 29 * 60_000L
            }
        }
        val midnight = at(2026, 5, 1, hour = 0)
        times += listOf(midnight - 1, midnight, 0L, -1L, at(1969, 12, 31, hour = 23), at(1900, 1, 1))
        val random = java.util.Random(7)
        val from = at(1900, 1, 1)
        val span = at(2100, 1, 1) - from
        repeat(400) { times += from + (random.nextDouble() * span).toLong() }
        return times
    }

    @Test
    fun `keyed sections read exactly as formatting every photo, in every order, zone and locale`() {
        val made = edgeTimes()
        val orders = listOf(made, made.sortedDescending(), made.sorted())
        val zones = listOf(
            "UTC", "Europe/Budapest", "America/Sao_Paulo", "Asia/Kolkata", "Pacific/Chatham",
            "America/St_Johns",
        ).map { TimeZone.getTimeZone(it) }
        // English and German month names, Hungarian's year-first order, the Thai locale's Buddhist
        // calendar and a Japanese imperial one, whose years count from each era.
        val locales = listOf(
            Locale.UK,
            Locale.GERMANY,
            Locale.forLanguageTag("hu-HU"),
            Locale.forLanguageTag("th-TH"),
            Locale.forLanguageTag("ja-JP-u-ca-japanese"),
        )
        for (grouping in AlbumPhotoGrouping.entries) {
            for (zone in zones) {
                for (locale in locales) {
                    for (items in orders) assertSameAsFormattedPerPhoto(items, grouping, locale, zone)
                }
            }
        }
    }

    @Test
    fun `a large library groups exactly as formatting every photo, newest or oldest first`() {
        // Twenty thousand photos, one every five hours and a bit, so runs of one day, month and year
        // follow each other the way a big folder's do.
        val oldestFirst = List(20_000) { at(2015, 1, 1) + it * 18_061_000L }
        val budapest = TimeZone.getTimeZone("Europe/Budapest")
        for (grouping in AlbumPhotoGrouping.entries) {
            for (items in listOf(oldestFirst, oldestFirst.reversed())) {
                assertSameAsFormattedPerPhoto(items, grouping, Locale.UK, budapest)
            }
        }
    }

    @Test
    fun `a key holds exactly the span its grouping gathers`() {
        fun key(grouping: AlbumPhotoGrouping, year: Int, month: Int, day: Int, era: Int = 1) =
            photoDateKey(grouping, era = era, year = year, month = month, day = day)

        // A year is one key whatever the month and day.
        assertEquals(key(AlbumPhotoGrouping.Year, 2026, 0, 1), key(AlbumPhotoGrouping.Year, 2026, 11, 31))
        assertNotEquals(key(AlbumPhotoGrouping.Year, 2025, 11, 31), key(AlbumPhotoGrouping.Year, 2026, 0, 1))
        // A month is one key whatever the day, and the next month is another.
        assertEquals(key(AlbumPhotoGrouping.Month, 2026, 2, 1), key(AlbumPhotoGrouping.Month, 2026, 2, 31))
        assertNotEquals(key(AlbumPhotoGrouping.Month, 2026, 2, 31), key(AlbumPhotoGrouping.Month, 2026, 3, 1))
        assertNotEquals(key(AlbumPhotoGrouping.Month, 2025, 11, 1), key(AlbumPhotoGrouping.Month, 2026, 11, 1))
        // Every day is its own key, across a month end and a year end too.
        assertNotEquals(key(AlbumPhotoGrouping.Day, 2026, 2, 5), key(AlbumPhotoGrouping.Day, 2026, 2, 6))
        assertNotEquals(key(AlbumPhotoGrouping.Day, 2026, 0, 31), key(AlbumPhotoGrouping.Day, 2026, 1, 1))
        assertNotEquals(key(AlbumPhotoGrouping.Day, 2025, 11, 31), key(AlbumPhotoGrouping.Day, 2026, 0, 1))
        // The same year of two eras is two keys.
        assertNotEquals(key(AlbumPhotoGrouping.Year, 5, 0, 1, era = 0), key(AlbumPhotoGrouping.Year, 5, 0, 1, era = 1))
    }
}
