package me.proton.photos.domain.entity

data class ShareMember(
    val memberId: String,
    val email: String,
    val permissions: Int,
)
