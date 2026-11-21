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

package eu.akoos.photos.presentation.gallery

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.presentation.util.dayMonthYearFormat
import eu.akoos.photos.presentation.util.monthYearFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

class TimelineGroupingTest {

    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before
    fun save() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
    }

    @After
    fun restore() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private fun item(i: Int, ms: Long) = GalleryItem.LocalOnly(
        LocalMediaItem("content://media/$i", ms, "IMG_$i.jpg", "image/jpeg", 10L, "Camera"),
    )

    /** The plain per-photo grouping the timeline headers are defined by. */
    private fun reference(items: List<GalleryItem>, format: java.text.SimpleDateFormat) =
        items.groupBy { format.format(Date(it.captureTimeMs)) }.entries.map { it.key to it.value }

    /** Newest first, as the feed arrives, with bursts on one day, hours either side of midnight and the
     *  daylight-saving changes, and a few photos from before 1970. */
    private fun library(): List<GalleryItem> {
        val random = Random(7)
        val times = ArrayList<Long>()
        repeat(4_000) { times += random.nextLong(-400_000_000_000L, 1_800_000_000_000L) }
        // Every hour across the spring and autumn changes of 2024 in Europe and the US.
        for (start in listOf(1_711_670_400_000L, 1_729_987_200_000L, 1_710_028_800_000L, 1_730_592_000_000L)) {
            for (h in -30..30) times += start + h * 3_600_000L + random.nextLong(0, 3_600_000L)
        }
        repeat(50) { times += 1_700_000_000_000L + it }
        times += 0L
        return times.sortedDescending().mapIndexed { i, ms -> item(i, ms) }
    }

    @Test
    fun `month and day headers match a per-photo grouping in every zone`() {
        val items = library()
        for (zone in listOf("Europe/Budapest", "America/New_York", "Asia/Kolkata", "Australia/Lord_Howe", "UTC", "Pacific/Chatham")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            for (locale in listOf(Locale.US, Locale.forLanguageTag("hu-HU"))) {
                Locale.setDefault(locale)
                assertEquals("month, $zone, $locale", reference(items, monthYearFormat()), groupByMonth(items))
                assertEquals("day, $zone, $locale", reference(items, dayMonthYearFormat()), groupByDay(items))
            }
        }
    }

    @Test
    fun `an empty list has no headers`() {
        assertEquals(emptyList<Pair<String, List<GalleryItem>>>(), groupByMonth(emptyList()))
        assertEquals(emptyList<Pair<String, List<GalleryItem>>>(), groupByDay(emptyList()))
    }
}
