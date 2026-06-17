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
