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

import eu.akoos.photos.domain.entity.GalleryItem
import eu.akoos.photos.presentation.common.isUnfiled
import eu.akoos.photos.presentation.gallery.TimelineGrouping
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Source filter for the photo picker: everything, cloud-backed only, or on-device only. */
enum class PickerFilter { All, Cloud, Device }

/** Everything the picker grid is narrowed and sectioned by, set by the screen. */
data class PickerQuery(
    val excludeLinkIds: Set<String> = emptySet(),
    val excludeKeys: Set<String> = emptySet(),
    val deviceOnly: Boolean = false,
    val videosOnly: Boolean = false,
    val filter: PickerFilter = PickerFilter.All,
    val unfiledOnly: Boolean = false,
    /** Keeps only the photos of one cloud album, by cloud id, while that album is open in the picker. */
    val scopeLinkIds: Set<String>? = null,
    /** Keeps only the photos of one device folder, by its name, while that folder is open in the picker. */
    val scopeFolder: String? = null,
    val grouping: TimelineGrouping = TimelineGrouping.Day,
)

/**
 * The picker grid, prepared off the main thread. [photos] is the flat library order the drag-select and
 * scrubber index into, [photoKeys] and [indexByKey] its keys, and [sections] the same photos split under
 * date headers without reordering them. Plain identity equality, so comparing two never walks the lists.
 */
class PickerContent(
    val photos: List<GalleryItem>,
    val photoKeys: List<String>,
    val indexByKey: Map<String, Int>,
    val sections: List<Pair<String, List<GalleryItem>>>,
)

object AlbumPickerContent {

    private const val DAY_MS = 86_400_000L

    fun build(
        items: List<GalleryItem>,
        query: PickerQuery,
        hiddenCloudLinkIds: Set<String>,
        inAnyAlbumLinkIds: Set<String>,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): PickerContent {
        val photos = filter(items, query, hiddenCloudLinkIds, inAnyAlbumLinkIds)
        val keys = ArrayList<String>(photos.size)
        val indexByKey = HashMap<String, Int>(photos.size * 4 / 3 + 1)
        photos.forEachIndexed { i, p ->
            val key = AlbumPhotoPickerViewModel.stableKeyOf(p)
            keys.add(key)
            indexByKey[key] = i
        }
        return PickerContent(photos, keys, indexByKey, group(photos, query.grouping, locale, timeZone))
    }

    /**
     * Drops photos the target already holds ([PickerQuery.excludeLinkIds] by cloud id,
     * [PickerQuery.excludeKeys] by item key) and photos hidden on this device, since re-adding a hidden
     * photo to an album would un-hide it. The source, type and unfiled filters then narrow the rest.
     * Library order is kept.
     */
    fun filter(
        items: List<GalleryItem>,
        query: PickerQuery,
        hiddenCloudLinkIds: Set<String>,
        inAnyAlbumLinkIds: Set<String>,
    ): List<GalleryItem> {
        val excludes = query.excludeLinkIds.isNotEmpty() || query.excludeKeys.isNotEmpty() ||
            hiddenCloudLinkIds.isNotEmpty()
        val narrows = query.deviceOnly || query.filter != PickerFilter.All || query.videosOnly || query.unfiledOnly ||
            query.scopeLinkIds != null || query.scopeFolder != null
        if (!excludes && !narrows) return items
        return items.filter { item ->
            val cloudId = when (item) {
                is GalleryItem.CloudOnly -> item.cloud.linkId
                is GalleryItem.Synced -> item.cloud.linkId
                is GalleryItem.LocalOnly -> null
            }
            if (excludes) {
                if (cloudId != null && (cloudId in query.excludeLinkIds || cloudId in hiddenCloudLinkIds)) return@filter false
                if (AlbumPhotoPickerViewModel.stableKeyOf(item) in query.excludeKeys) return@filter false
            }
            if (query.scopeLinkIds != null && (cloudId == null || cloudId !in query.scopeLinkIds)) return@filter false
            if (query.scopeFolder != null && deviceBucketOf(item) != query.scopeFolder) return@filter false
            // A folder move relocates a device file, so device-only mode keeps photos with a device original.
            if (query.deviceOnly && item is GalleryItem.CloudOnly) return@filter false
            val sourceOk = when (query.filter) {
                PickerFilter.All -> true
                PickerFilter.Cloud -> cloudId != null
                PickerFilter.Device -> item is GalleryItem.LocalOnly
            }
            if (!sourceOk) return@filter false
            if (query.videosOnly && mimeTypeOf(item)?.startsWith("video/") != true) return@filter false
            !query.unfiledOnly || isUnfiled(item, inAnyAlbumLinkIds)
        }
    }

    /**
     * Splits [photos] under one header per [grouping] span, in first-seen order. One formatter serves
     * the whole pass, and each local calendar day is formatted once: every pattern here reads only the
     * day, month and year, so any instant of that day yields the same label.
     */
    fun group(
        photos: List<GalleryItem>,
        grouping: TimelineGrouping,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<Pair<String, List<GalleryItem>>> {
        if (photos.isEmpty()) return emptyList()
        val fmt = SimpleDateFormat(patternFor(grouping), locale).apply { this.timeZone = timeZone }
        val labelByDay = HashMap<Long, String>()
        val sections = LinkedHashMap<String, MutableList<GalleryItem>>()
        for (item in photos) {
            val ms = item.captureTimeMs
            val localDay = Math.floorDiv(ms + timeZone.getOffset(ms), DAY_MS)
            val label = labelByDay.getOrPut(localDay) { fmt.format(Date(ms)) }
            sections.getOrPut(label) { ArrayList() }.add(item)
        }
        return sections.map { (label, members) -> label to members }
    }

    fun patternFor(grouping: TimelineGrouping): String = when (grouping) {
        TimelineGrouping.None -> "yyyy"
        TimelineGrouping.Day -> "d MMMM yyyy"
        TimelineGrouping.Month -> "MMMM yyyy"
        TimelineGrouping.Year -> "yyyy"
    }

    /** Prefers the device original's mime type when present, else the cloud record's. */
    private fun mimeTypeOf(item: GalleryItem): String? = when (item) {
        is GalleryItem.LocalOnly -> item.local.mimeType
        is GalleryItem.Synced -> item.local.mimeType
        is GalleryItem.CloudOnly -> item.cloud.mimeType
    }
}

/** The device folder (bucket) [item] sits in, or null for a photo with no device original. */
internal fun deviceBucketOf(item: GalleryItem): String? = when (item) {
    is GalleryItem.LocalOnly -> item.local.bucketName
    is GalleryItem.Synced -> item.local.bucketName
    is GalleryItem.CloudOnly -> null
}
