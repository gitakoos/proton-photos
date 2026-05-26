package me.proton.photos.domain.entity

data class CloudPhoto(
    val linkId: String,
    val shareId: String,
    val volumeId: String,
    val captureTime: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val thumbnailUrl: String?,
    val revisionId: String,
    /** SHA-256 of the plaintext content (hex) — used for hash-based local↔cloud matching. */
    val contentHash: String? = null,
    /** PhotoTag ids assigned to this photo by Drive (0 = Favorite). */
    val tags: Set<Int> = emptySet(),
) {
    val isFavoriteOnCloud: Boolean get() = 0 in tags
}
