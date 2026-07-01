/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://photos.akoos.eu
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
 * Coverage for [computeOnThisDay] — the milestone-anniversary "memories" selector behind the
 * gallery carousel and the search memories row. The function reads the system clock via
 * [Calendar.getInstance], so the fixtures are built RELATIVE to now (anniversary anchors derived
 * the same way the production code does) rather than from hardcoded epochs.
 *
 * Milestones surfaced: 1, 2, 5, 10 years ago. Window: ±3 days around each anniversary. Non-milestone
 * years (3, 4, 6 …) are intentionally skipped.
 */
class OnThisDayHelperTest {

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

    /** Epoch-ms for [yearsAgo] years before now, shifted by [dayDelta] days. */
    private fun anniversaryMs(yearsAgo: Int, dayDelta: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.YEAR, -yearsAgo)
            add(Calendar.DAY_OF_YEAR, dayDelta)
        }.timeInMillis

    @Test
    fun `empty input yields no memories`() {
        assertEquals(emptyList<Pair<Int, List<GalleryItem>>>(), computeOnThisDay(emptyList()))
    }

    @Test
    fun `a photo exactly one year ago today is surfaced as a 1-year memory`() {
        val todayYear = Calendar.getInstance().get(Calendar.YEAR)
        val result = computeOnThisDay(listOf(itemAt(anniversaryMs(1))))

        assertEquals(1, result.size)
        // The pair's year label is (todayYear - yearsAgo).
        assertEquals(todayYear - 1, result.first().first)
        assertEquals(1, result.first().second.size)
    }

    @Test
    fun `a photo within the plus-or-minus 3 day window still counts`() {
        // 2 days before the 2-year anniversary is inside the ±3-day window.
        val result = computeOnThisDay(listOf(itemAt(anniversaryMs(2, dayDelta = -2))))
        assertEquals(1, result.size)
    }

    @Test
    fun `a photo well outside the window is excluded`() {
        // 30 days off the 1-year anniversary is far outside ±3 days → no memory.
        val result = computeOnThisDay(listOf(itemAt(anniversaryMs(1, dayDelta = 30))))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a non-milestone anniversary (3 years) is skipped`() {
        // 3 years is NOT a milestone (only 1, 2, 5, 10 are), so an exact 3-year-ago photo is ignored.
        val result = computeOnThisDay(listOf(itemAt(anniversaryMs(3))))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple milestones are returned most-recent-first`() {
        val items = listOf(
            itemAt(anniversaryMs(5), id = "five"),
            itemAt(anniversaryMs(1), id = "one"),
            itemAt(anniversaryMs(10), id = "ten"),
        )
        val todayYear = Calendar.getInstance().get(Calendar.YEAR)
        val result = computeOnThisDay(items)

        // 1, 5 and 10 years all have a photo → three groups.
        assertEquals(3, result.size)
        // MEMORY_MILESTONE_YEARS iterates 1, 2, 5, 10, so the year labels descend: y-1, y-5, y-10.
        assertEquals(listOf(todayYear - 1, todayYear - 5, todayYear - 10), result.map { it.first })
    }

    @Test
    fun `all photos in one milestone window land in the same group`() {
        val items = listOf(
            itemAt(anniversaryMs(2, dayDelta = -1), id = "a"),
            itemAt(anniversaryMs(2, dayDelta = 0), id = "b"),
            itemAt(anniversaryMs(2, dayDelta = 1), id = "c"),
        )
        val result = computeOnThisDay(items)
        assertEquals(1, result.size)
        assertEquals(3, result.first().second.size)
    }
}
