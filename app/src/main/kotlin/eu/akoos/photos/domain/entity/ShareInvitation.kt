package eu.akoos.photos.domain.entity

data class ShareInvitation(
    val invitationId: String,
    val email: String,
    val permissions: Int,
)
