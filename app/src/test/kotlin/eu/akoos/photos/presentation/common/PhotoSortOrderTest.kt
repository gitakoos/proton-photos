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

import eu.akoos.photos.domain.entity.CloudPhoto
import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.domain.entity.LocalMediaItem
import eu.akoos.photos.domain.entity.TimestampSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the order a device folder lists its photos in, and the one property that makes its month
 * headings trustworthy: the key the list is sorted on is the key each heading is built from.
 *
 * A folder holds device-only and synced photos. A synced photo's device DATE_TAKEN and its Drive
 * capture time are two different numbers whenever the file reached the device after the upload, so
 * these assertions hold the folder to the second one — the value [GalleryItem.captureTimeMs]
 * resolves and every heading, scrubber and scroll pill reads. Plain JVM assertions, no Android.
 */
class PhotoSortOrderTest {

    /** When the downloaded photo was actually taken (November 2023). */
    private val capturedMs = 1_700_000_000_000L

    /** When a photo shot after it was taken (July 2024). */
    private val shotLaterMs = 1_720_000_000_000L

    /** When the downloaded photo landed on the device, which is the date MediaStore reports for it
     *  (October 2025) — a different month from [capturedMs], so a heading can tell them apart. */
    private val downloadedAtMs = 1_760_000_000_000L

    private fun cloud(linkId: String, captureTimeMs: Long) = CloudPhoto(
        linkId = linkId,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = captureTimeMs / 1000L,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 0L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(uri: String, dateTaken: Long) = LocalMediaItem(
        uri = uri,
        dateTaken = dateTaken,
        displayName = "IMG_0001.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2_481_365L,
        bucketName = "Camera",
    )

    private fun localOnly(uri: String, dateTaken: Long) = GalleryItem.LocalOnly(local(uri, dateTaken))

    private fun synced(name: String, cloudMs: Long, deviceMs: Long) =
        GalleryItem.Synced(cloud(name, cloudMs), local("content://$name", deviceMs))

    /** The label a month heading carries for [ms]. */
    private fun monthOf(ms: Long): String =
        java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.UK).format(java.util.Date(ms))

    @Test
    fun `a folder is ordered on the capture time its month headings are built from`() {
        // The synced photo was taken before the device-only one but reached the device after it, so
        // its two timestamps point at opposite ends of the list. Ordering on the device date would
        // put it first while its heading said November 2023.
        val downloaded = synced("downloaded", cloudMs = capturedMs, deviceMs = downloadedAtMs)
        val shotLater = localOnly("content://shot-later", shotLaterMs)
        assertNotEquals(downloaded.captureTimeMs, downloaded.local.dateTaken)

        val ordered = PhotoSortOrder.ordered(listOf(downloaded, shotLater), newestFirst = true)

        assertEquals(listOf("content://shot-later", "downloaded"), ordered.map { it.stableId })
        // Every heading boundary falls where the order says it does: the times only ever descend.
        assertTrue(ordered.zipWithNext().all { (a, b) -> a.captureTimeMs >= b.captureTimeMs })
        assertEquals(monthOf(capturedMs), monthOf(downloaded.captureTimeMs))
        assertNotEquals(monthOf(downloadedAtMs), monthOf(downloaded.captureTimeMs))
    }

    @Test
    fun `oldest first turns the same folder around`() {
        val downloaded = synced("downloaded", cloudMs = capturedMs, deviceMs = downloadedAtMs)
        val shotLater = localOnly("content://shot-later", shotLaterMs)

        val ordered = PhotoSortOrder.ordered(listOf(shotLater, downloaded), newestFirst = false)

        assertEquals(listOf("downloaded", "content://shot-later"), ordered.map { it.stableId })
        assertTrue(ordered.zipWithNext().all { (a, b) -> a.captureTimeMs <= b.captureTimeMs })
    }

    @Test
    fun `a synced photo with no Drive capture time falls back to its device date`() {
        // A stripped upload reaches Drive with no capture time. Raw, it files the photo under
        // January 1970; the device DATE_TAKEN is the date the folder's heading shows for it.
        val stripped = synced("stripped", cloudMs = 0L, deviceMs = downloadedAtMs)
        val older = localOnly("content://older", capturedMs)

        val ordered = PhotoSortOrder.ordered(listOf(older, stripped), newestFirst = true)

        assertEquals(downloadedAtMs, stripped.captureTimeMs)
        assertEquals(listOf("stripped", "content://older"), ordered.map { it.stableId })
    }

    @Test
    fun `the stable-id tie-break runs the same way in both directions`() {
        // Every capture time equal, so only the tie-break decides. It keeps the order total, and
        // turning it around too would reshuffle a burst for no reason the user could read.
        val burst = listOf(
            localOnly("content://ccc", capturedMs),
            localOnly("content://aaa", capturedMs),
            localOnly("content://bbb", capturedMs),
        )
        val expected = listOf("content://aaa", "content://bbb", "content://ccc")

        assertEquals(expected, PhotoSortOrder.ordered(burst, newestFirst = true).map { it.stableId })
        assertEquals(expected, PhotoSortOrder.ordered(burst, newestFirst = false).map { it.stableId })
    }

    @Test
    fun `a burst holds its block while the dates around it turn around`() {
        val items = listOf(
            localOnly("content://bbb", capturedMs),
            localOnly("content://newest", shotLaterMs),
            localOnly("content://aaa", capturedMs),
            localOnly("content://oldest", TimestampSanity.FLOOR_MS + 1_000L),
        )

        assertEquals(
            listOf("content://newest", "content://aaa", "content://bbb", "content://oldest"),
            PhotoSortOrder.ordered(items, newestFirst = true).map { it.stableId },
        )
        assertEquals(
            listOf("content://oldest", "content://aaa", "content://bbb", "content://newest"),
            PhotoSortOrder.ordered(items, newestFirst = false).map { it.stableId },
        )
    }

    @Test
    fun `a folder with nothing to reorder is returned untouched`() {
        val empty = emptyList<GalleryItem>()
        val single = listOf(localOnly("content://only", capturedMs))

        assertSame(empty, PhotoSortOrder.ordered(empty, newestFirst = true))
        assertSame(single, PhotoSortOrder.ordered(single, newestFirst = false))
    }

    @Test
    fun `the rule reads each key once per item`() {
        // The lists this backs reach library size and both keys are computed getters, so a
        // comparator that re-derived them would pay O(n log n) resolves instead of n.
        val items = (1..8).map { "item$it" }
        var timeReads = 0
        var idReads = 0

        val ordered = PhotoSortOrder.ordered(
            items = items,
            newestFirst = true,
            timeOf = { timeReads++; it.removePrefix("item").toLong() },
            idOf = { idReads++; it },
        )

        assertEquals(items.size, timeReads)
        assertEquals(items.size, idReads)
        assertEquals("item8", ordered.first())
    }
}
