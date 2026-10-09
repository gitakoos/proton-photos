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
import eu.akoos.photos.presentation.common.isUnfiled
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Pins the photo picker's prepared grid against a plain reference: chained filters and a fresh date
 * format for every photo. Any drift in the photos, their order, the date sections, the labels or the
 * keys shows up as a diff. Plain JVM, no Android.
 */
class AlbumPickerContentTest {

    // region fixture

    private fun cloud(id: String, ms: Long, mime: String = "image/jpeg") = CloudPhoto(
        linkId = id,
        shareId = "share1",
        volumeId = "vol1",
        captureTime = ms / 1000L,
        displayName = "$id.jpg",
        mimeType = mime,
        sizeBytes = 0L,
        thumbnailUrl = null,
        revisionId = "rev1",
    )

    private fun local(uri: String, ms: Long, mime: String = "image/jpeg") = LocalMediaItem(
        uri = uri,
        dateTaken = ms,
        displayName = "photo.jpg",
        mimeType = mime,
        sizeBytes = 0L,
        bucketName = "Camera",
    )

    private fun epochMs(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    /** Instants that sit on local-day, month, year and daylight-saving edges in several zones, plus a
     *  seeded spread, so a label cached per local day is checked against a fresh format of each. */
    private fun edgeInstants(): List<Long> {
        val out = ArrayList<Long>()
        val zones = listOf("Europe/Budapest", "America/New_York", "Asia/Kolkata", "Australia/Lord_Howe", "Pacific/Chatham")
        for (zone in zones) {
            for (year in listOf(2019, 2024, 2025)) {
                for ((mo, d) in listOf(1 to 1, 3 to 10, 3 to 31, 10 to 27, 11 to 3, 12 to 31)) {
                    out += epochMs(zone, year, mo, d, 0, 0)
                    out += epochMs(zone, year, mo, d, 23, 59, 59)
                    out += epochMs(zone, year, mo, d, 2, 30)
                    out += epochMs(zone, year, mo, d, 12, 0)
                }
            }
        }
        val rnd = Random(42)
        val from = epochMs("UTC", 2015, 1, 1, 0, 0)
        val to = epochMs("UTC", 2026, 1, 1, 0, 0)
        repeat(2_000) { out += from + rnd.nextLong(to - from) / 1000L * 1000L }
        return out.map { it / 1000L * 1000L }
    }

    /** A mixed library: every state, images and videos, one sub-floor cloud time with a device twin. */
    private fun mixedLibrary(instants: List<Long>): List<GalleryItem> = instants.mapIndexed { i, ms ->
        val mime = if (i % 7 == 0) "video/mp4" else "image/jpeg"
        when (i % 3) {
            0 -> GalleryItem.CloudOnly(cloud("c$i", ms, mime))
            1 -> GalleryItem.LocalOnly(local("content://media/$i", ms, mime))
            else -> if (i == 2) GalleryItem.Synced(cloud("s$i", 0L, mime), local("content://media/s$i", ms, mime))
                else GalleryItem.Synced(cloud("s$i", ms, mime), local("content://media/s$i", ms + 5_000L, mime))
        }
    }

    // endregion

    // region reference: chained filters and a per-photo format

    private fun referenceFilter(
        allItems: List<GalleryItem>,
        excludeLinkIds: Set<String>,
        excludeKeys: Set<String>,
        hiddenCloudLinkIds: Set<String>,
        deviceOnly: Boolean,
        pickerFilter: PickerFilter,
        unfiledOnly: Boolean,
        inAnyAlbumLinkIds: Set<String>,
        videosOnly: Boolean,
    ): List<GalleryItem> {
        val filtered =
            if (excludeLinkIds.isEmpty() && excludeKeys.isEmpty() && hiddenCloudLinkIds.isEmpty()) allItems
            else allItems.filter { item ->
                val cloudId = when (item) {
                    is GalleryItem.CloudOnly -> item.cloud.linkId
                    is GalleryItem.Synced    -> item.cloud.linkId
                    is GalleryItem.LocalOnly -> null
                }
                val cloudOk = cloudId == null || (cloudId !in excludeLinkIds && cloudId !in hiddenCloudLinkIds)
                cloudOk && AlbumPhotoPickerViewModel.stableKeyOf(item) !in excludeKeys
            }
        val basePhotos =
            if (deviceOnly) filtered.filter { it is GalleryItem.LocalOnly || it is GalleryItem.Synced } else filtered
        val bySource = when (pickerFilter) {
            PickerFilter.All -> basePhotos
            PickerFilter.Cloud -> basePhotos.filter { it is GalleryItem.CloudOnly || it is GalleryItem.Synced }
            PickerFilter.Device -> basePhotos.filter { it is GalleryItem.LocalOnly }
        }
        val mimeOf = { item: GalleryItem ->
            when (item) {
                is GalleryItem.LocalOnly -> item.local.mimeType
                is GalleryItem.Synced -> item.local.mimeType
                is GalleryItem.CloudOnly -> item.cloud.mimeType
            }
        }
        val byType = if (videosOnly) bySource.filter { mimeOf(it).startsWith("video/") } else bySource
        return if (unfiledOnly) byType.filter { isUnfiled(it, inAnyAlbumLinkIds) } else byType
    }

    private fun referenceGroup(
        photos: List<GalleryItem>,
        grouping: TimelineGrouping,
        locale: Locale,
        timeZone: TimeZone,
    ): List<Pair<String, List<GalleryItem>>> {
        val pattern = when (grouping) {
            TimelineGrouping.None -> "yyyy"
            TimelineGrouping.Day -> "d MMMM yyyy"
            TimelineGrouping.Month -> "MMMM yyyy"
            TimelineGrouping.Year -> "yyyy"
        }
        val fmt = SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }
        return photos.groupBy { fmt.format(Date(it.captureTimeMs)) }.entries.map { it.key to it.value }
    }

    // endregion

    @Test
    fun `sections and labels match a fresh format of every photo`() {
        val library = mixedLibrary(edgeInstants())
        val cases = listOf(
            Locale.forLanguageTag("hu-HU") to "Europe/Budapest",
            Locale.US to "America/New_York",
            Locale.GERMANY to "Asia/Kolkata",
            Locale.JAPAN to "Australia/Lord_Howe",
            Locale.UK to "Pacific/Chatham",
            Locale.FRANCE to "UTC",
        )
        for ((locale, zone) in cases) {
            val tz = TimeZone.getTimeZone(zone)
            for (grouping in TimelineGrouping.entries) {
                assertEquals(
                    "$locale $zone $grouping",
                    referenceGroup(library, grouping, locale, tz),
                    AlbumPickerContent.group(library, grouping, locale, tz),
                )
            }
        }
    }

    @Test
    fun `filtering matches the chained filters for every query`() {
        val library = mixedLibrary(edgeInstants().take(300))
        val cloudIds = library.mapNotNull {
            when (it) {
                is GalleryItem.CloudOnly -> it.cloud.linkId
                is GalleryItem.Synced -> it.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
        }
        val allKeys = library.map(AlbumPhotoPickerViewModel::stableKeyOf)
        val hidden = cloudIds.filterIndexed { i, _ -> i % 5 == 0 }.toSet()
        val filed = cloudIds.filterIndexed { i, _ -> i % 4 == 1 }.toSet()
        val none = emptySet<String>()
        val excludeSets = listOf(
            Triple(none, none, none),
            Triple(none, none, hidden),
            Triple(cloudIds.filterIndexed { i, _ -> i % 6 == 2 }.toSet(), none, none),
            Triple(none, allKeys.filterIndexed { i, _ -> i % 9 == 4 }.toSet(), hidden),
        )
        for ((excludeLinkIds, excludeKeys, hiddenIds) in excludeSets) {
            for (deviceOnly in listOf(false, true)) for (videosOnly in listOf(false, true))
                for (unfiledOnly in listOf(false, true)) for (source in PickerFilter.entries) {
                    val query = PickerQuery(excludeLinkIds, excludeKeys, deviceOnly, videosOnly, source, unfiledOnly)
                    assertEquals(
                        query.toString(),
                        referenceFilter(
                            library, excludeLinkIds, excludeKeys, hiddenIds,
                            deviceOnly, source, unfiledOnly, filed, videosOnly,
                        ),
                        AlbumPickerContent.filter(library, query, hiddenIds, filed),
                    )
                }
        }
    }

    @Test
    fun `photos keep library order and the keys index into it`() {
        val library = mixedLibrary(edgeInstants()).sortedByDescending { it.captureTimeMs }
        val content = AlbumPickerContent.build(
            library,
            PickerQuery(grouping = TimelineGrouping.Month),
            hiddenCloudLinkIds = emptySet(),
            inAnyAlbumLinkIds = emptySet(),
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("Europe/Budapest"),
        )
        assertSame(library, content.photos)
        assertEquals(library.map(AlbumPhotoPickerViewModel::stableKeyOf), content.photoKeys)
        content.photoKeys.forEachIndexed { i, key -> assertEquals(i, content.indexByKey[key]) }
        assertEquals(content.photoKeys.size, content.indexByKey.size)
        // A newest-first library splits into contiguous runs, so the sections read back in grid order.
        assertEquals(library, content.sections.flatMap { it.second })
    }

    @Test
    fun `excluded keys drop out and the rest keep their order`() {
        val library = mixedLibrary(edgeInstants().take(60))
        val held = library.filterIndexed { i, _ -> i % 4 == 0 }.map(AlbumPhotoPickerViewModel::stableKeyOf).toSet()
        val content = AlbumPickerContent.build(library, PickerQuery(excludeKeys = held), emptySet(), emptySet())
        assertEquals(library.filter { AlbumPhotoPickerViewModel.stableKeyOf(it) !in held }, content.photos)
        assertTrue(content.photoKeys.none { it in held })
    }

    @Test
    fun `an open cloud album keeps only its own photos in library order`() {
        val library = listOf<GalleryItem>(
            GalleryItem.CloudOnly(cloud("a", epochMs("UTC", 2024, 5, 3, 10, 0))),
            GalleryItem.CloudOnly(cloud("b", epochMs("UTC", 2024, 5, 2, 10, 0))),
            GalleryItem.LocalOnly(local("content://media/c", epochMs("UTC", 2024, 5, 1, 10, 0))),
        )
        val content = AlbumPickerContent.build(
            library, PickerQuery(scopeLinkIds = setOf("b", "a")), emptySet(), emptySet(),
        )
        assertEquals(listOf("a", "b"), content.photoKeys)
    }

    @Test
    fun `an open device folder keeps only the photos on the device in that folder`() {
        val library = listOf<GalleryItem>(
            GalleryItem.CloudOnly(cloud("a", epochMs("UTC", 2024, 5, 3, 10, 0))),
            GalleryItem.LocalOnly(local("content://media/b", epochMs("UTC", 2024, 5, 2, 10, 0))),
            GalleryItem.LocalOnly(local("content://media/c", epochMs("UTC", 2024, 5, 1, 10, 0)).copy(bucketName = "Other")),
        )
        val content = AlbumPickerContent.build(
            library, PickerQuery(scopeFolder = "Camera"), emptySet(), emptySet(),
        )
        assertEquals(listOf("content://media/b"), content.photoKeys)
    }

    @Test
    fun `an empty library or a query that matches nothing yields empty content`() {
        val empty = AlbumPickerContent.build(emptyList(), PickerQuery(), emptySet(), emptySet())
        assertTrue(empty.photos.isEmpty())
        assertTrue(empty.photoKeys.isEmpty())
        assertTrue(empty.indexByKey.isEmpty())
        assertTrue(empty.sections.isEmpty())

        val imagesOnly = listOf<GalleryItem>(
            GalleryItem.CloudOnly(cloud("a", epochMs("UTC", 2024, 5, 1, 10, 0))),
            GalleryItem.LocalOnly(local("content://media/b", epochMs("UTC", 2024, 5, 2, 10, 0))),
        )
        val none = AlbumPickerContent.build(imagesOnly, PickerQuery(videosOnly = true), emptySet(), emptySet())
        assertTrue(none.photos.isEmpty())
        assertTrue(none.sections.isEmpty())
    }

    @Test
    fun `a large library is prepared quickly and still matches the reference`() {
        val rnd = Random(7)
        val newest = epochMs("UTC", 2026, 1, 1, 0, 0)
        val instants = (0 until 20_000).map { newest - it * 9_000_000L - rnd.nextLong(8_000_000L) }
        val library = mixedLibrary(instants)
        val tz = TimeZone.getTimeZone("Europe/Budapest")
        val query = PickerQuery(excludeKeys = setOf("c3", "content://media/4"), grouping = TimelineGrouping.Day)
        AlbumPickerContent.build(library.take(500), query, emptySet(), emptySet(), Locale.US, tz)

        val start = System.nanoTime()
        val content = AlbumPickerContent.build(library, query, emptySet(), emptySet(), Locale.US, tz)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        assertTrue("took $elapsedMs ms", elapsedMs < 2_000L)
        assertEquals(19_998, content.photos.size)
        assertEquals(referenceGroup(content.photos, TimelineGrouping.Day, Locale.US, tz), content.sections)
    }
}
