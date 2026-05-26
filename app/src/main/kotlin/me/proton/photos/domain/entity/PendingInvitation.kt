package me.proton.photos.domain.entity

data class PendingInvitation(
    val invitationId: String,
    val shareId: String,
    val inviterEmail: String,
)
