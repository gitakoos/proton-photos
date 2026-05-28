package eu.akoos.photos.domain.entity

data class Album(
    val linkId: String,
    val name: String,
    val photoCount: Int,
    val coverLinkId: String?,
    val lastActivityTimeMs: Long?,
    val coverThumbnailUrl: String? = null,
    /** Non-null when the album has been shared (contains the child share ID). */
    val sharingShareId: String? = null,
    /** Non-null when a public share URL exists for this album. */
    val sharingShareUrlId: String? = null,
    /** Non-null for shared-with-me albums — the email of the user who shared it. */
    val sharedByEmail: String? = null,
    /** The volume this album lives in; may differ from the current user's own volume for shared-with-me albums. */
    val volumeId: String? = null,
) {
    val isShared: Boolean get() = sharingShareId != null
    val isSharedWithMe: Boolean get() = sharedByEmail != null
}
