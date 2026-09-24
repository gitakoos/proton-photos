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

/** Distinct values of one kind a [ListingValueInterner] keeps. An own stream holds one or two share
 *  and volume ids and a few dozen mime types and tag combinations. */
private const val LISTING_INTERNER_MAX_ENTRIES = 128

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
 * table-level invalidation signals a re-read on every such write regardless. Dropping the column makes
 * the re-read rows equal to the last read, so the shared timeline copy does not publish them again and
 * the merge/group rebuild never runs for them; carrying it would make each write a distinct emission
 * that forces a whole-timeline rebuild on every thumbnail landing during a sustained scroll (the churn
 * behind the large-library OOM). The decrypted URL reaches the cell through the in-memory
 * ThumbnailUrlStore instead, so [toDomain] leaves it null.
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
    fun toDomain(): CloudPhoto = toDomain(interner = null)

    /** The row as a [CloudPhoto], taking each repeated value from [interner] when one is given. */
    internal fun toDomain(interner: ListingValueInterner?): CloudPhoto = CloudPhoto(
        linkId = linkId,
        shareId = interner?.string(shareId) ?: shareId,
        volumeId = interner?.string(volumeId) ?: volumeId,
        captureTime = captureTime,
        displayName = displayName,
        mimeType = interner?.string(mimeType) ?: mimeType,
        sizeBytes = sizeBytes,
        // Supplied by ThumbnailUrlStore at the cell, not this row (see the class doc).
        thumbnailUrl = null,
        revisionId = revisionId,
        contentHash = contentHash,
        tags = interner?.tags(tagsCsv) ?: parseTagsCsv(tagsCsv),
        durationMs = durationMs,
    )
}

/**
 * [PhotoListingLite.toDomain] over a whole listing, in order, holding each repeated value once and
 * each link once.
 *
 * The cursor hands every row its own copy of the share id, the volume id and the mime type, and
 * every tagged row its own tag set, though a library has only a handful of each. The mapped list
 * outlives the rows, so the copies are dropped here and all rows share one instance of each value.
 *
 * A link the rows carry twice keeps its first row. Every reader keys the listing by link and the grid
 * throws on a repeated key, and a read the table changed under can hand a row back twice.
 */
fun List<PhotoListingLite>.toCloudPhotos(): List<CloudPhoto> {
    val interner = ListingValueInterner()
    val seen = HashSet<String>(size * 2)
    val photos = ArrayList<CloudPhoto>(size)
    for (row in this) if (seen.add(row.linkId)) photos += row.toDomain(interner)
    return photos
}

/**
 * One instance per distinct value of the listing columns that repeat from row to row, for a single
 * mapping pass. Not thread-safe: a pass owns its interner.
 *
 * Bounded, so a column whose values turn out not to repeat can never grow it with the library: once
 * [maxEntries] values of a kind are held, a new one is handed back as it came, unshared.
 */
internal class ListingValueInterner(private val maxEntries: Int = LISTING_INTERNER_MAX_ENTRIES) {
    private val strings = HashMap<String, String>()
    private val tagSets = HashMap<String, Set<Int>>()

    fun string(value: String): String {
        strings[value]?.let { return it }
        if (strings.size < maxEntries) strings[value] = value
        return value
    }

    /** The tag set [csv] names, parsed once per distinct CSV. */
    fun tags(csv: String): Set<Int> {
        if (csv.isEmpty()) return emptySet()
        tagSets[csv]?.let { return it }
        val parsed = parseTagsCsv(csv)
        if (tagSets.size < maxEntries) tagSets[csv] = parsed
        return parsed
    }
}

/** The server tag ids a stored CSV names; an entry that is not a number is skipped. */
private fun parseTagsCsv(csv: String): Set<Int> =
    if (csv.isEmpty()) emptySet() else csv.split(',').mapNotNull { it.toIntOrNull() }.toSet()

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
