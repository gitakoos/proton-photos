package eu.akoos.photos.domain.entity

/**
 * A photo that currently sits in the Proton Drive server-side trash.
 * It was deleted from the Photos stream (via the app or Drive web) but not yet
 * permanently removed — the server keeps it for 30 days before auto-purging.
 */
data class CloudTrashItem(
    val linkId: String,
    val captureTime: Long?,       // epoch seconds, null if unknown
    val thumbnailUrl: String?,    // pre-fetched CDN bare-URL, null if unavailable
    val thumbnailToken: String?,  // CDN token for the thumbnail URL
)
