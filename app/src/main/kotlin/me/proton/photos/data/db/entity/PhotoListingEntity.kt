package me.proton.photos.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.proton.photos.domain.entity.CloudPhoto

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
