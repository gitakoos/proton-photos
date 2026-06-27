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

package eu.akoos.photos.data.db.entity

import eu.akoos.photos.domain.entity.CloudPhoto

/**
 * Display-only projection of [PhotoListingEntity] for the timeline feed.
 *
 * The full entity carries the lazy-thumbnail crypto material (encNodeKey, encNodePassphrase,
 * contentKeyPacket, encXAttr) — armored PGP blocks that are kilobytes each. The timeline only needs
 * the display columns, and [toDomain] drops the crypto fields anyway, so selecting `*` made the feed
 * materialise the whole library's crypto blobs on every Room re-emit just to discard them. On a large
 * library that transient was hundreds of MB per write and pinned the heap at its ceiling. This
 * projection selects only what the grid binds; the crypto material stays in the table and is read
 * per-linkId by the thumbnail decrypt scheduler when a cell actually needs it.
 */
data class PhotoListingLite(
    val linkId: String,
    val shareId: String,
    val volumeId: String,
    val captureTime: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val revisionId: String,
    val thumbnailUrl: String?,
    val contentHash: String?,
    val tagsCsv: String,
) {
    fun toDomain() = CloudPhoto(
        linkId = linkId,
        shareId = shareId,
        volumeId = volumeId,
        captureTime = captureTime,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        thumbnailUrl = thumbnailUrl,
        revisionId = revisionId,
        contentHash = contentHash,
        tags = if (tagsCsv.isEmpty()) emptySet() else tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet(),
    )
}
