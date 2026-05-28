package eu.akoos.photos.domain.entity

data class LocalMediaItem(
    val uri: String,
    val dateTaken: Long,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bucketName: String?,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Long = 0,
)
