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

/** One file of an album or device folder in the contents sheet. A [sizeBytes] of 0 or less is unknown. */
internal data class AlbumContentsRow(val name: String, val sizeBytes: Long)

internal data class AlbumContentsSummary(val totalBytes: Long, val knownCount: Int, val unknownCount: Int)

/** Adds up the known sizes. A row without a known size is counted apart and left out of the total. */
internal fun albumContentsSummary(rows: List<AlbumContentsRow>): AlbumContentsSummary {
    var total = 0L
    var known = 0
    for (row in rows) {
        if (row.sizeBytes > 0) {
            total += row.sizeBytes
            known++
        }
    }
    return AlbumContentsSummary(totalBytes = total, knownCount = known, unknownCount = rows.size - known)
}

/** A cloud album's rows, in the album's own order, sized by what Drive stores for each photo. */
internal fun albumContentsRowsOfCloud(photos: List<CloudPhoto>): List<AlbumContentsRow> =
    photos.map { AlbumContentsRow(it.displayName, it.sizeBytes) }

/** A device folder's rows, in the folder's own order, sized by the file on the device. */
internal fun albumContentsRowsOfDevice(items: List<GalleryItem>): List<AlbumContentsRow> =
    items.mapNotNull { item ->
        val local = when (item) {
            is GalleryItem.LocalOnly -> item.local
            is GalleryItem.Synced -> item.local
            is GalleryItem.CloudOnly -> return@mapNotNull null
        }
        AlbumContentsRow(local.displayName, local.sizeBytes)
    }
