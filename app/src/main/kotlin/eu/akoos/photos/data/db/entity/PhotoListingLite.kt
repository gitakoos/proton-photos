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
 *
 * thumbnailUrl is NOT part of this projection. A decrypt-completion writes it per-linkId, and Room's
 * table-level invalidation re-runs this query on every such write regardless. Dropping the column makes
 * the re-queried rows byte-identical to the last emission, so the distinctUntilChanged in
 * GetGalleryItemsUseCase drops it before the merge/group rebuild; carrying it would make each write a
 * distinct emission that forces a whole-timeline rebuild on every thumbnail landing during a sustained
 * scroll (the churn behind the large-library OOM). The decrypted URL reaches the cell through the
 * in-memory ThumbnailUrlStore instead, so [toDomain] leaves it null.
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
    val contentHash: String?,
    val tagsCsv: String,
    // A tiny nullable Long, unlike the kilobyte-scale crypto blobs, so it is safe to carry on the OOM-lite
    // feed so the timeline's video cells can show a duration pill without a per-cell lookup.
    val durationMs: Long?,
) {
    fun toDomain() = CloudPhoto(
        linkId = linkId,
        shareId = shareId,
        volumeId = volumeId,
        captureTime = captureTime,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        // Supplied by ThumbnailUrlStore at the cell, not this row (see the class doc).
        thumbnailUrl = null,
        revisionId = revisionId,
        contentHash = contentHash,
        tags = if (tagsCsv.isEmpty()) emptySet() else tagsCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet(),
        durationMs = durationMs,
    )
}

/** Two-column projection for [eu.akoos.photos.data.db.dao.PhotoListingDao.getThumbnailUrlSeed]: the
 *  startup prime of [eu.akoos.photos.data.repository.drive.ThumbnailUrlStore]. Kept separate from
 *  [PhotoListingLite] so the seed read never pulls the display columns it doesn't need. */
data class ThumbnailUrlSeed(
    val linkId: String,
    val thumbnailUrl: String?,
)

/**
 * Two-column projection for [eu.akoos.photos.data.db.dao.PhotoListingDao.observeOwnStreamPickerRows]:
 * one cell of the home-screen widget's photo picker, which keys on the link id and binds the already
 * decrypted thumbnail, and reads nothing else off the row.
 *
 * Separate from [PhotoListingLite] because that projection deliberately omits thumbnailUrl for the
 * timeline's re-emission behaviour, and the picker has no [eu.akoos.photos.data.repository.drive.ThumbnailUrlStore]
 * primed to supply it from — the config screen is its own entry point and can open without the gallery
 * ever having run. Separate from [ThumbnailUrlSeed] because that one answers which cached URLs are
 * worth priming, not what a grid renders. A cell whose thumbnail is still null asks for a decrypt by
 * link id, which is what keeps the per-row crypto material off this read entirely.
 */
data class PhotoPickerRow(
    val linkId: String,
    val thumbnailUrl: String?,
)
