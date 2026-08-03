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

package eu.akoos.photos.presentation.albums

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.TimestampSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the album's side of the shared capture-time rule and the item it hands the viewer.
 *
 * A Drive photo can carry no capture time at all (0, or any value under the sanity floor), and the
 * album is the one surface that reads that value raw. These assertions hold it to what every other
 * list does with the same photo, and hold the viewer's item to the device file's own facts. Plain JVM
 * assertions, no Android.
 */
class AlbumPhotoItemsTest {

    private val realMs = TimestampSanity.FLOOR_MS + 400_000_000L
    private val deviceMs = TimestampSanity.FLOOR_MS + 900_000_000L

    private fun cloud(
        linkId: String = "link1",
        captureTimeSec: Long = realMs / 1000L,
        displayName: String = "IMG_0001.jpg",
    ) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTimeSec,
        displayName = displayName,
        mimeType = "image/jpeg",
        sizeBytes = 0L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(
        uri: String = "content://media/external/images/media/42",
        dateTaken: Long = deviceMs,
        displayName: String = "IMG_0001.jpg",
    ) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = displayName,
        mimeType = "image/jpeg",
        sizeBytes = 2_481_365L,
        bucketName = "Camera",
        width = 4032,
        height = 3024,
    )

    // region the capture time an album groups and sorts by

    @Test
    fun `a sub-floor member with a device twin reports the device date`() {
        // A stripped upload reaches Drive with no capture time. Raw, it files the photo under
        // January 1970; the twin's DATE_TAKEN is the date every other list shows for it.
        assertEquals(deviceMs, AlbumPhotoItems.captureTimeMs(cloud(captureTimeSec = 0L), local()))
        assertEquals(
            deviceMs,
            AlbumPhotoItems.captureTimeMs(cloud(captureTimeSec = TimestampSanity.FLOOR_MS / 1000L), local()),
        )
    }

    @Test
    fun `a sub-floor member with no twin keeps the raw cloud value`() {
        // Documented CloudOnly behaviour: nothing better is in hand, and the album has to put the
        // photo exactly where the timeline puts it rather than invent a date for it.
        val photo = cloud(captureTimeSec = 0L)

        assertEquals(GalleryItem.CloudOnly(photo).captureTimeMs, AlbumPhotoItems.captureTimeMs(photo, null))
        assertEquals(0L, AlbumPhotoItems.captureTimeMs(photo, null))
    }

    @Test
    fun `a normal member reads its Drive capture time, twin or no twin`() {
        val photo = cloud()

        assertEquals(realMs, AlbumPhotoItems.captureTimeMs(photo, null))
        assertEquals(realMs, AlbumPhotoItems.captureTimeMs(photo, local()))
    }

    @Test
    fun `a twin that is itself dateless leaves the sub-floor value alone`() {
        val photo = cloud(captureTimeSec = 0L)

        assertEquals(0L, AlbumPhotoItems.captureTimeMs(photo, local(dateTaken = 0L)))
    }

    // endregion
    // region the order those times imply

    @Test
    fun `a dateless member sorts on its twin's date, not at the tail`() {
        val stripped = cloud(linkId = "stripped", captureTimeSec = 0L)
        val older = cloud(linkId = "older", captureTimeSec = (TimestampSanity.FLOOR_MS + 1_000L) / 1000L)
        val newest = cloud(linkId = "newest", captureTimeSec = (deviceMs + 60_000L) / 1000L)

        val ordered = AlbumPhotoItems.ordered(
            listOf(older, stripped, newest),
            mapOf("stripped" to local()),
        )

        assertEquals(listOf("newest", "stripped", "older"), ordered.map { it.linkId })
    }

    @Test
    fun `equal capture times break on linkId so the order is total`() {
        val a = cloud(linkId = "aaa")
        val b = cloud(linkId = "bbb")

        assertEquals(listOf("aaa", "bbb"), AlbumPhotoItems.ordered(listOf(b, a), emptyMap()).map { it.linkId })
    }

    @Test
    fun `ordering an already ordered list is a no-op`() {
        val newer = cloud(linkId = "newer", captureTimeSec = realMs / 1000L)
        val older = cloud(linkId = "older", captureTimeSec = (TimestampSanity.FLOOR_MS + 1_000L) / 1000L)
        val once = AlbumPhotoItems.ordered(listOf(newer, older), emptyMap())

        assertEquals(once, AlbumPhotoItems.ordered(once, emptyMap()))
    }

    @Test
    fun `a single member is returned untouched`() {
        val photos = listOf(cloud())

        assertSame(photos, AlbumPhotoItems.ordered(photos, emptyMap()))
    }

    // endregion
    // region the direction the album is listed in (#85)

    /** Three distinct dates, handed in shuffled so a passing assertion cannot be the input order. */
    private fun datedTrio() = listOf(
        cloud(linkId = "b", captureTimeSec = realMs / 1000L),
        cloud(linkId = "a", captureTimeSec = (TimestampSanity.FLOOR_MS + 1_000L) / 1000L),
        cloud(linkId = "c", captureTimeSec = (realMs + 120_000L) / 1000L),
    )

    @Test
    fun `the direction an album opens on is newest first`() {
        val shuffled = datedTrio()

        val default = AlbumPhotoItems.ordered(shuffled, emptyMap())

        assertEquals(listOf("c", "b", "a"), default.map { it.linkId })
        assertEquals(AlbumPhotoSortMode.NewestFirst, AlbumPhotoSortMode.Default)
        assertEquals(default, AlbumPhotoItems.ordered(shuffled, emptyMap(), AlbumPhotoSortMode.NewestFirst))
    }

    @Test
    fun `oldest first turns the same album around`() {
        val shuffled = datedTrio()

        val ordered = AlbumPhotoItems.ordered(shuffled, emptyMap(), AlbumPhotoSortMode.OldestFirst)

        assertEquals(listOf("a", "b", "c"), ordered.map { it.linkId })
    }

    @Test
    fun `the linkId tie-break runs the same way in both directions`() {
        // Every capture time equal, so only the tie-break decides. It exists to make the order total,
        // and turning it around too would reshuffle a burst for no reason the user could read.
        val shuffled = listOf(cloud(linkId = "ccc"), cloud(linkId = "aaa"), cloud(linkId = "bbb"))

        assertEquals(
            listOf("aaa", "bbb", "ccc"),
            AlbumPhotoItems.ordered(shuffled, emptyMap(), AlbumPhotoSortMode.NewestFirst).map { it.linkId },
        )
        assertEquals(
            listOf("aaa", "bbb", "ccc"),
            AlbumPhotoItems.ordered(shuffled, emptyMap(), AlbumPhotoSortMode.OldestFirst).map { it.linkId },
        )
    }

    @Test
    fun `a burst of equal times holds its block while the dates around it turn around`() {
        val shuffled = listOf(
            cloud(linkId = "bbb"),
            cloud(linkId = "newest", captureTimeSec = (realMs + 120_000L) / 1000L),
            cloud(linkId = "aaa"),
            cloud(linkId = "oldest", captureTimeSec = (TimestampSanity.FLOOR_MS + 1_000L) / 1000L),
        )

        assertEquals(
            listOf("newest", "aaa", "bbb", "oldest"),
            AlbumPhotoItems.ordered(shuffled, emptyMap(), AlbumPhotoSortMode.NewestFirst).map { it.linkId },
        )
        assertEquals(
            listOf("oldest", "aaa", "bbb", "newest"),
            AlbumPhotoItems.ordered(shuffled, emptyMap(), AlbumPhotoSortMode.OldestFirst).map { it.linkId },
        )
    }

    @Test
    fun `a dateless member sorts on its twin's date in the turned-around album too`() {
        val stripped = cloud(linkId = "stripped", captureTimeSec = 0L)
        val older = cloud(linkId = "older", captureTimeSec = (TimestampSanity.FLOOR_MS + 1_000L) / 1000L)
        val newest = cloud(linkId = "newest", captureTimeSec = (deviceMs + 60_000L) / 1000L)

        val ordered = AlbumPhotoItems.ordered(
            listOf(older, stripped, newest),
            mapOf("stripped" to local()),
            AlbumPhotoSortMode.OldestFirst,
        )

        assertEquals(listOf("older", "stripped", "newest"), ordered.map { it.linkId })
    }

    @Test
    fun `an empty album is returned untouched in either direction`() {
        val empty = emptyList<CloudPhoto>()

        assertSame(empty, AlbumPhotoItems.ordered(empty, emptyMap(), AlbumPhotoSortMode.NewestFirst))
        assertSame(empty, AlbumPhotoItems.ordered(empty, emptyMap(), AlbumPhotoSortMode.OldestFirst))
    }

    @Test
    fun `a persisted direction survives an absent or damaged value`() {
        assertEquals(AlbumPhotoSortMode.Default, AlbumPhotoSortMode.fromOrdinal(null))
        assertEquals(AlbumPhotoSortMode.Default, AlbumPhotoSortMode.fromOrdinal(99))
        assertEquals(AlbumPhotoSortMode.Default, AlbumPhotoSortMode.fromOrdinal(-1))
        assertEquals(
            AlbumPhotoSortMode.OldestFirst,
            AlbumPhotoSortMode.fromOrdinal(AlbumPhotoSortMode.OldestFirst.ordinal),
        )
    }

    // endregion
    // region the twins an album takes out of the merged library

    @Test
    fun `only this album's paired members end up in the map`() {
        val mine = cloud(linkId = "mine")
        val elsewhere = cloud(linkId = "elsewhere")
        val library = listOf(
            GalleryItem.Synced(mine, local()),
            GalleryItem.Synced(elsewhere, local(uri = "content://media/external/images/media/99")),
            GalleryItem.CloudOnly(cloud(linkId = "cloudy")),
        )

        val twins = AlbumPhotoItems.twinsFor(listOf(mine, cloud(linkId = "cloudy")), library)

        assertEquals(setOf("mine"), twins.keys)
        assertEquals(local(), twins["mine"])
    }

    @Test
    fun `an empty album asks nothing of the library`() {
        assertTrue(AlbumPhotoItems.twinsFor(emptyList(), listOf(GalleryItem.Synced(cloud(), local()))).isEmpty())
    }

    // endregion
    // region the item the viewer, the details sheet and the categoriser receive

    @Test
    fun `a paired member hands over the device file itself`() {
        val photo = cloud()
        val twin = local()

        val item = AlbumPhotoItems.galleryItem(photo, twin, twin.uri)

        assertEquals(GalleryItem.Synced(photo, twin), item)
        // The rows a hollow stand-in used to blank out.
        assertEquals("IMG_0001.jpg", (item as GalleryItem.Synced).local.displayName)
        assertEquals(2_481_365L, item.local.sizeBytes)
        assertEquals("Camera", item.local.bucketName)
        assertEquals(4032, item.local.width)
    }

    @Test
    fun `a member known only by uri stays Synced but claims nothing it cannot know`() {
        val photo = cloud()

        val item = AlbumPhotoItems.galleryItem(photo, twin = null, localUri = "content://media/external/images/media/7")

        assertTrue(item is GalleryItem.Synced)
        val stand = (item as GalleryItem.Synced).local
        assertEquals("content://media/external/images/media/7", stand.uri)
        // Named and typed after the photo it is a copy of; size, folder and dimensions stay absent so
        // the sheet dashes them and the categoriser skips the heuristics that need them.
        assertEquals("IMG_0001.jpg", stand.displayName)
        assertEquals("image/jpeg", stand.mimeType)
        assertEquals(0L, stand.sizeBytes)
        assertNull(stand.bucketName)
        assertEquals(0, stand.width)
    }

    @Test
    fun `a uri-only stand-in never passes the cloud time off as a device date`() {
        val photo = cloud()

        val stand = (AlbumPhotoItems.galleryItem(photo, null, "content://media/1") as GalleryItem.Synced).local

        assertEquals(0L, stand.dateTaken)
        assertNotEquals(photo.captureTimeMs, stand.dateTaken)
        // The real Drive time still wins, so the item lands on the same date the grid shows.
        assertEquals(realMs, AlbumPhotoItems.galleryItem(photo, null, "content://media/1").captureTimeMs)
    }

    @Test
    fun `a member with no device copy at all is CloudOnly`() {
        val photo = cloud()

        assertEquals(GalleryItem.CloudOnly(photo), AlbumPhotoItems.galleryItem(photo, null, null))
    }

    @Test
    fun `every wrapping reports the cloud linkId as its stable id`() {
        // The grid keys its cells by linkId, and the scrubber maps back through the same id.
        val photo = cloud(linkId = "link42")

        assertEquals("link42", AlbumPhotoItems.galleryItem(photo, local(), null).stableId)
        assertEquals("link42", AlbumPhotoItems.galleryItem(photo, null, "content://media/1").stableId)
        assertEquals("link42", AlbumPhotoItems.galleryItem(photo, null, null).stableId)
    }

    // endregion
}
