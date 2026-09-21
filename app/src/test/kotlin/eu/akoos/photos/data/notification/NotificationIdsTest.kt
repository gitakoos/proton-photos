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

package eu.akoos.photos.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Pins the one property the notification layer cannot enforce by review: no two posters share an
 * id. A shared id means whichever posts last replaces the other in the shade, and either one's
 * cancel takes down the other, up to and including a running foreground service's notification.
 *
 * The ids are read reflectively rather than listed here, so an id added to [NotificationIds]
 * without a thought for the ones already there fails this suite instead of shipping. Pure Kotlin,
 * no Android types, so it runs as a plain JVM test.
 */
class NotificationIdsTest {

    /** Every public `const val Int` on the ledger, by name. The range bounds are private ints and
     *  the range itself is not an int, so neither reaches this map. */
    private val fixedIds: Map<String, Int> =
        NotificationIds::class.java.declaredFields
            .filter { !it.isSynthetic }
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    @Test
    fun `the reflective sweep sees the declared ids`() {
        // Without this the checks below would pass on an empty map if the sweep ever stopped
        // matching, and the suite would go green while enforcing nothing.
        val expected = setOf(
            "SYNC_WORKER",
            "BACKGROUND_SYNC_SERVICE",
            "DELETE_CONSENT",
            "UPDATE_AVAILABLE",
            "SCREENSHOT_OVERLAY",
        )
        assertTrue(
            "Ledger constants missing from the sweep: ${expected - fixedIds.keys}",
            fixedIds.keys.containsAll(expected),
        )
    }

    @Test
    fun `no two notifications share an id`() {
        val shared = fixedIds.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size > 1 }
        assertEquals("Ids claimed by more than one poster: $shared", emptyMap<Int, List<String>>(), shared)
    }

    @Test
    fun `no fixed id falls inside the album download range`() {
        val range = NotificationIds.ALBUM_DOWNLOAD_RANGE
        val intruders = fixedIds.filterValues { it in range }
        assertEquals(
            "Fixed ids inside the per-run album-download range $range: $intruders",
            emptyMap<String, Int>(),
            intruders,
        )
    }

    @Test
    fun `an album download run posts inside its reserved range`() {
        val range = NotificationIds.ALBUM_DOWNLOAD_RANGE
        // Int.MIN_VALUE is the one that matters: its absolute value is still negative, so an
        // abs-based derivation would put the run below the range and onto a fixed id.
        val seeds = listOf(Int.MIN_VALUE, Int.MAX_VALUE, 0, -1, 1) +
            List(1_000) { UUID.randomUUID().hashCode() }
        seeds.forEach { seed ->
            val id = NotificationIds.albumDownload(seed)
            assertTrue("Seed $seed produced $id, outside $range", id in range)
        }
    }

    @Test
    fun `an album download id is stable for a given run`() {
        // The worker recomputes the id on every progress refresh, so an unstable derivation would
        // leave a trail of orphaned progress notifications behind one download.
        val seed = UUID.randomUUID().hashCode()
        assertEquals(NotificationIds.albumDownload(seed), NotificationIds.albumDownload(seed))
    }
}
