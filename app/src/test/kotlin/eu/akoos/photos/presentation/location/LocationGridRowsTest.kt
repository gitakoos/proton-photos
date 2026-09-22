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

package eu.akoos.photos.presentation.location

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pins how the place page lays its photos out under month headers. The rows decide both what the
 * grid draws and which photo a tap opens: a header that shifted the index mapping would open the
 * viewer on the wrong photo. Plain JVM, and UTC unless a test says otherwise, so a month edge sits
 * exactly where the test puts it.
 */
class LocationGridRowsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun ms(at: String): Long = Instant.parse(at).toEpochMilli()

    private fun media(uri: String, at: String) = LocalMediaItem(
        uri = uri,
        dateTaken = ms(at),
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_000L,
        bucketName = "Camera",
    )

    private fun cloud(linkId: String, at: String) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = ms(at) / 1000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1_000L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(id: String, at: String) =
        GalleryItem.LocalOnly(media("content://media/external/images/media/$id", at))

    /** The rows as the screen builds them: a "MMMM yyyy" label read in the same [zone]. */
    private fun rows(items: List<GalleryItem>, zone: TimeZone = utc): List<LocationGridRow> {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.US).apply { timeZone = zone }
        return locationGridRows(items, zone) { fmt.format(Date(it)) }
    }

    /** The rows in short: "# label" for a header, the list index for a photo. */
    private fun shape(rows: List<LocationGridRow>): List<String> = rows.map { row ->
        when (row) {
            is LocationGridRow.Header -> "# " + row.label
            is LocationGridRow.Photo -> row.index.toString()
        }
    }

    @Test
    fun `an empty place has no rows`() {
        assertTrue(rows(emptyList()).isEmpty())
    }

    @Test
    fun `one month is one header over all of its photos`() {
        val items = listOf(
            local("a", "2026-09-20T10:00:00Z"),
            local("b", "2026-09-12T08:30:00Z"),
            local("c", "2026-09-01T00:00:00Z"),
        )

        assertEquals(listOf("# September 2026", "0", "1", "2"), shape(rows(items)))
    }

    @Test
    fun `the first instant of a month opens a new header`() {
        val items = listOf(
            local("march", "2026-03-01T00:00:00Z"),
            local("february", "2026-02-28T23:59:59.999Z"),
        )

        assertEquals(listOf("# March 2026", "0", "# February 2026", "1"), shape(rows(items)))
    }

    @Test
    fun `a year change opens a new header, the same month name included`() {
        val items = listOf(
            local("new-year", "2026-01-01T00:00:00Z"),
            local("eve", "2025-12-31T23:59:59Z"),
            local("a-year-before", "2025-01-15T12:00:00Z"),
        )
        val result = rows(items)

        assertEquals(
            listOf("# January 2026", "0", "# December 2025", "1", "# January 2025", "2"),
            shape(result),
        )
        assertEquals(
            listOf("hdr_2026-1", "hdr_2025-12", "hdr_2025-1"),
            result.filterIsInstance<LocationGridRow.Header>().map { it.key },
        )
    }

    @Test
    fun `the month is read in the given time zone`() {
        // 23:30 UTC on 31 January is already 1 February one hour east.
        val items = listOf(local("edge", "2026-01-31T23:30:00Z"))
        val oneHourEast = TimeZone.getTimeZone("GMT+01:00")

        assertEquals(listOf("# January 2026", "0"), shape(rows(items, utc)))
        assertEquals(listOf("# February 2026", "0"), shape(rows(items, oneHourEast)))
        assertEquals("hdr_2026-2", rows(items, oneHourEast).first().key)
    }

    @Test
    fun `every photo row points back at its own place in the list`() {
        val items = listOf(
            local("a", "2026-09-20T10:00:00Z"),
            GalleryItem.CloudOnly(cloud("link-b", "2026-09-02T10:00:00Z")),
            GalleryItem.Synced(
                cloud("link-c", "2026-08-30T10:00:00Z"),
                media("content://m/c", "2026-08-30T10:00:00Z"),
            ),
            local("d", "2025-12-24T18:00:00Z"),
        )
        val photos = rows(items).filterIsInstance<LocationGridRow.Photo>()

        // A tap hands the viewer the list and this index, so the photo found there must be the cell's.
        assertEquals(items.indices.toList(), photos.map { it.index })
        photos.forEach { assertSame(it.item, items[it.index]) }
    }

    @Test
    fun `photos keep the keys the drag-select resolves and no key repeats`() {
        // A device photo and a cloud photo with the same raw id still get distinct keys.
        val items = listOf(
            GalleryItem.LocalOnly(media("x", "2026-09-20T10:00:00Z")),
            GalleryItem.CloudOnly(cloud("x", "2026-09-10T10:00:00Z")),
            GalleryItem.Synced(cloud("link-y", "2026-08-30T10:00:00Z"), media("y", "2026-08-30T10:00:00Z")),
        )
        val result = rows(items)

        assertEquals(listOf("hdr_2026-9", "L:x", "C:x", "hdr_2026-8", "S:y"), result.map { it.key })
        result.filterIsInstance<LocationGridRow.Photo>().forEach {
            assertEquals(locationCellKey(it.item), it.key)
        }
    }

    @Test
    fun `a month the list comes back to gets a header with a key of its own`() {
        // Only an unsorted list can return to a month, and the grid must still see distinct keys.
        val items = listOf(
            local("a", "2026-09-20T10:00:00Z"),
            local("b", "2026-08-20T10:00:00Z"),
            local("c", "2026-09-10T10:00:00Z"),
        )
        val result = rows(items)

        assertEquals(
            listOf("# September 2026", "0", "# August 2026", "1", "# September 2026", "2"),
            shape(result),
        )
        val keys = result.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `header keys hold across a change of label language`() {
        val items = listOf(local("a", "2026-09-20T10:00:00Z"), local("b", "2026-08-20T10:00:00Z"))
        val english = locationGridRows(items, utc) { "en $it" }
        val hungarian = locationGridRows(items, utc) { "hu $it" }

        assertNotEquals(
            english.filterIsInstance<LocationGridRow.Header>().map { it.label },
            hungarian.filterIsInstance<LocationGridRow.Header>().map { it.label },
        )
        assertEquals(english.map { it.key }, hungarian.map { it.key })
    }
}
