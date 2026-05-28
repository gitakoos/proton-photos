package eu.akoos.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import eu.akoos.photos.domain.entity.CloudPhoto

@Entity(tableName = "photo_listing")
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
    // ── Lazy-thumbnail-decrypt material (v1.3, schema v5) ──────────────────
    //
    // When the sync path runs in "metadata-only" mode we DO NOT decrypt the thumbnail
    // blob upfront — instead we persist the cipher inputs needed to do it later, on
    // demand, when the cell actually scrolls into view. All five fields below must
    // be present for an on-demand decrypt to succeed; any null means we fall back
    // to the placeholder until the next full refresh fills them in.
    //
    // Storing only ENCRYPTED material is non-negotiable: a plaintext nodeKey on
    // disk would defeat the whole point of Drive's hierarchical encryption (the
    // encrypted node key bytes are useless without the parent key, which lives
    // in the in-memory share-key cache).
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
