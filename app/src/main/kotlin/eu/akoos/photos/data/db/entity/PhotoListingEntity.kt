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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import eu.akoos.photos.domain.entity.CloudPhoto

@Entity(
    tableName = "photo_listing",
    // Cover the timeline's (userId, captureTime) sort and per-album parentLinkId lookups —
    // both full-scan a large library otherwise.
    indices = [
        Index(value = ["userId", "captureTime"]),
        Index(value = ["parentLinkId"]),
    ],
)
data class PhotoListingEntity(
    @PrimaryKey val linkId: String,
    val shareId: String,
    val volumeId: String,
    val userId: String,
    val captureTime: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val revisionId: String,
    val thumbnailUrl: String? = null,
    /** SHA-256 of the plaintext file (hex). Read from Photo.ContentHash. Null for legacy entries. */
    val contentHash: String? = null,
    /** Comma-separated PhotoTag ids (Drive enum: 0=Favorite, 1=Screenshot, …). */
    val tagsCsv: String = "",
    // ── Lazy-thumbnail-decrypt material ──────────────────────────────────
    // Cipher inputs for the on-demand thumbnail decrypt; all five must be present or the cell
    // shows a placeholder until the next refresh. Only ENCRYPTED material is stored — a plaintext
    // nodeKey at rest would defeat Drive's hierarchical encryption (the parent key stays in memory).
    /** URL where the encrypted thumbnail blob lives on the Drive CDN. */
    val serverThumbnailUrl: String? = null,
    /** Optional pm-storage-token paired with [serverThumbnailUrl] (CDN auth). */
    val serverThumbnailToken: String? = null,
    /** Base64-encoded PKESK packet — when decrypted with this photo's nodeKey, yields the session key for the thumbnail SEIPD block. */
    val contentKeyPacket: String? = null,
    /** Armored encrypted nodeKey for this photo — decrypted with parent's nodeKey. */
    val encNodeKey: String? = null,
    /** Armored encrypted node passphrase. Together with [encNodeKey] reproduces the unlocked node key. */
    val encNodePassphrase: String? = null,
    /** linkId of this photo's parent (root or album). Needed to look up the parent's decrypted nodeKey at thumbnail-decrypt time. */
    val parentLinkId: String? = null,
    /**
     * True when this row exists ONLY as a direct child of an album, never in the user's own photo
     * stream. That is what a photo someone else contributed to a shared album looks like: the copy
     * lands on the album owner's volume parented to the album, so the volume's photo listing never
     * returns it.
     *
     * A photo the user uploaded and then added to one of their own albums stays false: adding to an
     * album rewraps the passphrase but leaves the photo parented to the photos root, so it is still
     * a stream photo and still belongs on the timeline.
     *
     * Held per row rather than derived from `album_photo_membership`, so the answer does not depend
     * on whether an album's edges happen to be cached, and so the timeline query stays subquery-free.
     */
    val isChildOfAlbum: Boolean = false,
    /** Armored encrypted XAttr blob for this photo's active revision. Decrypted later (off the read
     *  path) to recover the GPS Location block for the map; null for rows captured before this column. */
    val encXAttr: String? = null,
    /** How far the GPS backfill has got with this row, one of the [GpsCheck] states. */
    val gpsChecked: Int = GpsCheck.UNCHECKED,
    /** Video length in MILLISECONDS, recovered from the xAttr Media.Duration block (stored there in
     *  seconds, converted on parse). Populated for our own uploads at pairing time and for every other
     *  video by the bounded background duration backfill. Null for images and for rows not yet walked. */
    val durationMs: Long? = null,
    /**
     * Change-detection digest of the ENCRYPTED name this row's [displayName] was decrypted from.
     * A rename on another client changes that ciphertext, so a refresh can spot one by comparing
     * digests instead of decrypting every name on every pass, which is what keeps PGP off the sync
     * walk. Null on a row written without one, and null is read as "recheck", so the first pass
     * after that repairs any name that already drifted.
     */
    val nameFingerprint: String? = null,
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
        durationMs = durationMs,
    )
}

/** The states of [PhotoListingEntity.gpsChecked]; the DAO's queries spell the same numbers out. */
object GpsCheck {
    const val UNCHECKED = 0

    /** The XAttr was read; a Location in it is stored in `photo_location`. */
    const val XATTR_READ = 1

    /** The XAttr held no Location and the file's own first and last blocks were read for one. */
    const val CONTENT_READ = 2

    /** Passed over while the photo was hidden; set back to [UNCHECKED] once it no longer is. */
    const val SKIPPED_HIDDEN = 3

    /** The XAttr held no Location and the file was not read: its uploader read the EXIF, or its format is not parsed. */
    const val CONTENT_SKIPPED = 4

    /** As [XATTR_READ], with one failed file read counted. */
    const val CONTENT_FAILED_ONCE = 5

    /** As [XATTR_READ], with two failed file reads counted. */
    const val CONTENT_FAILED_TWICE = 6

    /** The file read failed three times; not tried again until a new revision rebuilds the row. */
    const val CONTENT_GAVE_UP = 7

    /** The XAttr was read but the photo's key could not be decrypted; set back to [UNCHECKED] by the first walk after the app starts. */
    const val KEY_PENDING = 8
}

/** How many of a user's cloud photos the map can plot, for the diagnostics report. */
data class CloudPlaceCounts(val total: Int, val located: Int, val checkedWithout: Int) {
    fun report(): String = "cloud items: $total\nwith a place: $located\nchecked without one: $checkedWithout"
}

/** This rebuilt row with [existing]'s GPS walk state kept, so long as both describe the same revision. */
fun PhotoListingEntity.keepingGpsCheckOf(existing: PhotoListingEntity?): PhotoListingEntity =
    if (existing != null && existing.revisionId == revisionId && existing.gpsChecked != gpsChecked) {
        copy(gpsChecked = existing.gpsChecked)
    } else {
        this
    }
