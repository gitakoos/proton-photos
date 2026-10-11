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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Coverage for [computeOnThisDay], the milestone-anniversary "memories" selector behind the gallery
 * carousel, the search memories row and the Memories screen. "Today" is a fixed moment passed in, so the
 * fixtures are exact calendar days.
 *
 * Milestones surfaced: 1, 2, 5, 10 years ago. The match is by whole local days: the day itself by
 * default, and with a window of N that many whole days before and after. Non-milestone years (3, 4,
 * 6 ...) are intentionally skipped.
 */
class OnThisDayHelperTest {

    /** Today for every test: 2026-10-11 15:30 local time. */
    private fun today(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, 11, 15, 30, 0)
    }

    /** A cloud-only gallery item whose effective captureTimeMs is exactly [ms]. */
    private fun itemAt(ms: Long, id: String = "link-$ms"): GalleryItem = GalleryItem.CloudOnly(
        CloudPhoto(
            linkId = id,
            shareId = "share1",
            volumeId = "vol1",
            // CloudOnly.captureTimeMs == cloud.captureTime * 1000, so feed seconds.
            captureTime = ms / 1000L,
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            thumbnailUrl = null,
            revisionId = "rev1",
        ),
    )

    /** A moment on the day [dayDelta] days from [yearsAgo] years before [today], at [hour]:[minute]. */
    private fun at(yearsAgo: Int, dayDelta: Int = 0, hour: Int = 12, minute: Int = 0): Long =
        today().apply {
            add(Calendar.YEAR, -yearsAgo)
            add(Calendar.DAY_OF_YEAR, dayDelta)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }.timeInMillis

    private fun memories(items: List<GalleryItem>, windowDays: Int = 0) =
        computeOnThisDay(items, windowDays, today())

    @Test
    fun `empty input yields no memories`() {
        assertEquals(emptyList<Pair<Int, List<GalleryItem>>>(), memories(emptyList()))
    }

    @Test
    fun `a photo on the same date one year ago is a 1-year memory`() {
        val result = memories(listOf(itemAt(at(1))))
        assertEquals(1, result.size)
        assertEquals(2025, result.first().first)
        assertEquals(1, result.first().second.size)
    }

    @Test
    fun `by default a photo from the day before or after is not shown`() {
        assertTrue(memories(listOf(itemAt(at(1, dayDelta = -1)))).isEmpty())
        assertTrue(memories(listOf(itemAt(at(1, dayDelta = 1)))).isEmpty())
    }

    @Test
    fun `the whole day counts from its first minute to its last`() {
        val items = listOf(
            itemAt(at(2, hour = 0, minute = 0), id = "first"),
            itemAt(at(2, hour = 23, minute = 59), id = "last"),
        )
        val result = memories(items)
        assertEquals(1, result.size)
        assertEquals(2, result.first().second.size)
    }

    @Test
    fun `the last minute of the day before and the first of the day after stay out`() {
        assertTrue(memories(listOf(itemAt(at(2, dayDelta = -1, hour = 23, minute = 59)))).isEmpty())
        assertTrue(memories(listOf(itemAt(at(2, dayDelta = 1, hour = 0, minute = 0)))).isEmpty())
    }

    @Test
    fun `a window of one day adds the neighbouring days and no more`() {
        val items = listOf(
            itemAt(at(2, dayDelta = -1), id = "before"),
            itemAt(at(2), id = "day"),
            itemAt(at(2, dayDelta = 1), id = "after"),
            itemAt(at(2, dayDelta = 2), id = "too-far"),
        )
        val result = memories(items, windowDays = 1)
        assertEquals(1, result.size)
        assertEquals(setOf("before", "day", "after"), result.first().second.map { (it as GalleryItem.CloudOnly).cloud.linkId }.toSet())
    }

    @Test
    fun `a window of three days reaches three whole days either side`() {
        assertEquals(1, memories(listOf(itemAt(at(1, dayDelta = -3, hour = 0))), windowDays = 3).size)
        assertEquals(1, memories(listOf(itemAt(at(1, dayDelta = 3, hour = 23, minute = 59))), windowDays = 3).size)
        assertTrue(memories(listOf(itemAt(at(1, dayDelta = 4, hour = 0))), windowDays = 3).isEmpty())
    }

    @Test
    fun `a window beyond the maximum is held at three days`() {
        assertTrue(memories(listOf(itemAt(at(1, dayDelta = 4, hour = 0))), windowDays = 9).isEmpty())
        assertEquals(1, memories(listOf(itemAt(at(1, dayDelta = 3))), windowDays = 9).size)
    }

    @Test
    fun `a photo weeks away is excluded at any window`() {
        assertTrue(memories(listOf(itemAt(at(1, dayDelta = 30))), windowDays = 3).isEmpty())
    }

    @Test
    fun `a non-milestone anniversary (3 years) is skipped`() {
        assertTrue(memories(listOf(itemAt(at(3)))).isEmpty())
    }

    @Test
    fun `multiple milestones are returned most-recent-first`() {
        val items = listOf(
            itemAt(at(5), id = "five"),
            itemAt(at(1), id = "one"),
            itemAt(at(10), id = "ten"),
        )
        val result = memories(items)
        assertEquals(listOf(2025, 2021, 2016), result.map { it.first })
    }

    @Test
    fun `all photos on the day in one milestone year land in the same group`() {
        val items = listOf(
            itemAt(at(2, hour = 8), id = "a"),
            itemAt(at(2, hour = 13), id = "b"),
            itemAt(at(2, hour = 21), id = "c"),
        )
        val result = memories(items)
        assertEquals(1, result.size)
        assertEquals(3, result.first().second.size)
    }
}
