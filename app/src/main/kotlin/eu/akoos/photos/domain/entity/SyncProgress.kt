package eu.akoos.photos.domain.entity

data class SyncProgress(
    val total: Int,
    val done: Int,
    val running: Boolean,
)
